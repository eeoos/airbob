#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
lab_root="$repo_root/infra/aws/lab"
bootstrap="$repo_root/infra/aws/scripts/bootstrap-data.sh"
restore_verifier="$repo_root/infra/aws/scripts/verify-etl-release-database.sh"
validator="$repo_root/infra/aws/scripts/verify-dataset-release.sh"
dataset_readme="$repo_root/infra/aws/datasets/README.md"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-data-bootstrap-test.XXXXXX")

cleanup() {
  rm -rf "$temp_dir"
}
trap cleanup EXIT HUP INT TERM

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

assert_contains() {
  grep -Fq -- "$2" "$1" || fail "$1 does not contain the required Phase 3 contract: $2"
}

latest_flyway_version=0
flyway_history_rows=0
for migration_path in "$repo_root"/src/main/resources/db/migration/V*.sql; do
  migration_file=${migration_path##*/}
  migration_version=${migration_file#V}
  migration_version=${migration_version%%__*}
  [[ "$migration_version" =~ ^[0-9]+$ ]] || fail "invalid Flyway migration filename: $migration_file"
  ((flyway_history_rows += 1))
  if ((migration_version > latest_flyway_version)); then
    latest_flyway_version=$migration_version
  fi
done
[[ "$latest_flyway_version" -gt 0 && "$flyway_history_rows" -eq "$latest_flyway_version" ]] \
  || fail "Flyway versions must remain contiguous for the release row-count contract"

required_files=(
  "$bootstrap"
  "$restore_verifier"
  "$validator"
  "$dataset_readme"
  "$repo_root/infra/aws/scripts/promote-rds-snapshot.sh"
  "$lab_root/rds.tf"
  "$lab_root/data.tf"
  "$lab_root/checks.tf"
  "$lab_root/modules/rds/main.tf"
  "$lab_root/modules/rds/variables.tf"
  "$lab_root/modules/rds/outputs.tf"
  "$lab_root/tests/phase3.tftest.hcl"
  "$lab_root/tests/fixtures/dataset-manifest.json"
)
for required_file in "${required_files[@]}"; do
  [[ -f "$required_file" && ! -L "$required_file" ]] || fail "Phase 3 file is missing or unsafe: $required_file"
done
[[ -x "$bootstrap" && -x "$validator" ]] || fail "Phase 3 scripts must be executable"

/bin/bash -n "$bootstrap"
/bin/bash -n "$validator"
if grep -En '\$\(result_field[[:space:]]+\\"' "$bootstrap" >/dev/null; then
  fail "bootstrap SQL must precompute result_field expressions that require double quotes"
fi

assert_contains "$lab_root/variables.tf" 'contains(["network", "probe-cleared", "services", "data-ready"], var.deployment_phase)'
assert_contains "$lab_root/modules/rds/main.tf" 'instance_class = "db.t3.small"'
assert_contains "$lab_root/modules/rds/main.tf" 'var.bootstrap_mode == "dump" ? var.dump_storage_gib : null'
assert_contains "$lab_root/modules/rds/main.tf" 'manage_master_user_password = true'
assert_contains "$lab_root/modules/rds/main.tf" 'snapshot_identifier'
assert_contains "$lab_root/modules/rds/main.tf" 'backup_retention_period'
assert_contains "$lab_root/modules/rds/main.tf" 'binlog_format'
assert_contains "$lab_root/modules/rds/main.tf" 'binlog_row_image'
assert_contains "$lab_root/modules/rds/main.tf" 'performance_schema'
assert_contains "$lab_root/modules/rds/variables.tf" 'contains([20, 100], var.dump_storage_gib)'
assert_contains "$lab_root/modules/rds/outputs.tf" 'configured_storage_gib'
assert_contains "$lab_root/rds.tf" 'dump_storage_gib    = local.dataset_dump_storage_gib'
assert_contains "$lab_root/outputs.tf" 'rds_configured_storage_gib'
assert_contains "$lab_root/data.tf" 'data "aws_s3_object" "dataset_production_spec"'
assert_contains "$lab_root/data.tf" '"benchmark/unsupported-profile.json"'
assert_contains "$lab_root/checks.tf" '"production-skew-v1" = {'
assert_contains "$lab_root/checks.tf" '"production-skew-large-v1" = {'
assert_contains "$lab_root/checks.tf" 'production_spec_key = "benchmark/production-skew-v1.json"'
assert_contains "$lab_root/checks.tf" 'production_spec_key = "benchmark/production-skew-large-v1.json"'
assert_contains "$lab_root/checks.tf" 'sha256(nonsensitive(data.aws_s3_object.dataset_production_spec[0].body))'
assert_contains "$lab_root/checks.tf" 'local.dataset_production_spec_budgets == local.dataset_profile_contract.budgets'
assert_contains "$lab_root/tests/phase3.tftest.hcl" 'run "accept_large_profile_with_large_spec_and_budgets"'
assert_contains "$lab_root/tests/phase3.tftest.hcl" 'run "reject_unsupported_third_profile"'
assert_contains "$lab_root/tests/phase3.tftest.hcl" 'run "reject_large_profile_with_canonical_spec_key"'
assert_contains "$lab_root/tests/phase3.tftest.hcl" 'run "reject_large_profile_with_canonical_budget"'
assert_contains "$bootstrap" '.aggregate.counts.uniqueListings >= $budgets.accommodations'
assert_contains "$bootstrap" 'activeWishlists: .wishlist.rowCount'
assert_contains "$bootstrap" 'wishlistLinks: .["wishlist-accommodation"].rowCount'
assert_contains "$bootstrap" '.value >= $budgets[.key]'
assert_contains "$lab_root/ssm.tf" 'resource "aws_ssm_association" "data_bootstrap"'
assert_contains "$lab_root/checks.tf" 'resource "terraform_data" "data_bootstrap_gate"'
assert_contains "$lab_root/iam.tf" 'ManageEphemeralDebeziumCredentialValue'
assert_contains "$lab_root/iam.tf" 'ReadDataBootstrapReceipt'
assert_contains "$lab_root/iam.tf" '["s3:GetObject", "s3:GetObjectVersion"]'
assert_contains "$lab_root/iam.tf" 'elasticsearch/releases/${var.dataset_release}/*'
assert_contains "$repo_root/infra/aws/foundation/lab-compute.tf" 'ReadLabDebeziumSecret'
assert_contains "$repo_root/infra/aws/foundation/lab-compute.tf" 'ReadLabRdsManagedMasterSecret'
assert_contains "$repo_root/infra/aws/foundation/lab-compute.tf" 'WriteLabDebeziumSecret'
assert_contains "$repo_root/infra/aws/foundation/lab-compute.tf" 'WriteBootstrapEvidence'
assert_contains "$repo_root/docker/elasticsearch/Dockerfile" 'repository-s3/plugin-descriptor.properties'
assert_contains "$repo_root/infra/aws/bundles/debezium/connector.aws.json.tmpl" '"snapshot.mode": "no_data"'

mysql_line=$(grep -n 'outbox_count=' "$bootstrap" | head -1 | cut -d: -f1)
es_line=$(grep -n 'if \[\[ "$search_enabled" == true \]\]' "$bootstrap" | tail -1 | cut -d: -f1)
redis_line=$(grep -n 'redis_cli 6379 FLUSHDB' "$bootstrap" | cut -d: -f1)
kafka_line=$(grep -n 'kafka_exec=' "$bootstrap" | cut -d: -f1)
connector_line=$(grep -n 'connector_template=' "$bootstrap" | cut -d: -f1)
[[ "$mysql_line" -lt "$es_line" && "$es_line" -lt "$redis_line" && "$redis_line" -lt "$kafka_line" && "$kafka_line" -lt "$connector_line" ]] \
  || fail "Phase 3 bootstrap order must remain MySQL, Elasticsearch, Redis, Kafka, then Debezium"

wrapper_line=$(grep -n 'aws_cp "$dataset_uri/manifest.json"' "$bootstrap" | cut -d: -f1)
validator_line=$(grep -n 'download_sha benchmark/validate-benchmark-dataset-v2.jq' "$bootstrap" | cut -d: -f1)
semantic_line=$(grep -n 'jq -e -f "$semantic_validator"' "$bootstrap" | cut -d: -f1)
secret_line=$(grep -n 'secretsmanager get-secret-value' "$bootstrap" | head -1 | cut -d: -f1)
restore_line=$(grep -n 'zstd --decompress --stdout' "$bootstrap" | cut -d: -f1)
attest_line=$(grep -n '^semantic_attestation_sha256=' "$bootstrap" | cut -d: -f1)
mutation_line=$(grep -n "CALL mysql.rds_set_configuration" "$bootstrap" | cut -d: -f1)
[[ "$wrapper_line" -lt "$validator_line" && "$validator_line" -lt "$semantic_line" \
  && "$semantic_line" -lt "$secret_line" && "$secret_line" -lt "$restore_line" \
  && "$restore_line" -lt "$attest_line" && "$attest_line" -lt "$mutation_line" ]] \
  || fail "bootstrap trust order must be wrapper SHA, artifact hashes, semantics, secrets, restore, attestation, then mutation"

# Execute the exact calibration guard extracted from bootstrap so argument
# placement cannot silently turn the large listing threshold into dead text.
calibration_function="$temp_dir/validate-source-calibration.sh"
awk '
  /^validate_source_calibration\(\) \{/ { capture = 1 }
  capture { print }
  capture && /^}/ { exit }
' "$bootstrap" > "$calibration_function"
[[ -s "$calibration_function" ]] || fail "bootstrap source calibration validator is missing"
# shellcheck source=/dev/null
source "$calibration_function"
large_budgets='{"accommodations":200000,"activeWishlists":1600000,"members":800000,"reservations":10000000,"reviews":4000000,"wishlistLinks":6000000}'
large_calibration="$temp_dir/large-calibration.json"
jq -nS --argjson uniqueListings 200000 '
  {
    calibrationVersion:"source-calibration-v1", catalogVersion:"source-catalog-v1",
    inventorySha256:("a"*64),
    sourceInventory:[{canonicalPath:"inside-airbnb/listings.csv",byteSize:1,sha256:("9"*64),role:"LISTINGS"}],
    cohorts:[{id:"seoul"}], aggregate:{counts:{uniqueListings:$uniqueListings}},
    syntheticReviewTemplatePolicy:{
      reviewerIdentityPolicy:"EXCLUDED",reviewProsePolicy:"EXCLUDED",
      templatePolicy:"VERSIONED_COHORT_TEMPLATE"
    }
  }
' > "$large_calibration"
validate_source_calibration "$large_calibration" "$large_budgets" \
  || fail "large calibration rejected the exact 200000-listing boundary"
jq '.aggregate.counts.uniqueListings=199999' "$large_calibration" > "$large_calibration.next"
mv "$large_calibration.next" "$large_calibration"
if validate_source_calibration "$large_calibration" "$large_budgets"; then
  fail "large calibration accepted 199999 unique listings"
fi

if grep -En 'set -x|--password=|printf.*(master_password|debezium_password)|echo.*(master_password|debezium_password)' "$bootstrap" >/dev/null; then
  fail "Phase 3 bootstrap must not expose credentials in argv or logs"
fi
assert_contains "$bootstrap" '--secret-string "file://$debezium_secret_file"'
assert_contains "$bootstrap" 'redis_cli 6379 FLUSHDB'
assert_contains "$bootstrap" 'redis_cli 6380 FLUSHDB'
assert_contains "$bootstrap" 'index.number_of_replicas'
assert_contains "$bootstrap" '"index.blocks.write":false'
assert_contains "$bootstrap" 'include_global_state'
assert_contains "$bootstrap" 'include_aliases:false'
assert_contains "$bootstrap" 'feature_states:["none"]'
assert_contains "$bootstrap" '/_search?scroll=2m'
assert_contains "$bootstrap" 'dbIdsSha256'
assert_contains "$bootstrap" 'esIdsSha256'
assert_contains "$bootstrap" 'dbDocumentIdentityPairsSha256'
assert_contains "$bootstrap" 'esDocumentIdentityPairsSha256'
assert_contains "$bootstrap" 'BIN_TO_UUID(accommodation_uid)'
assert_contains "$bootstrap" '[._id, (._source.accommodationId | tostring)] | @tsv'
assert_contains "$bootstrap" 'contentFingerprintSha256'
assert_contains "$bootstrap" 'validate_snapshot_reference'
assert_contains "$bootstrap" 'Elasticsearch snapshot reference contradicts the trusted wrapper'
assert_contains "$bootstrap" '.dbIdsSha256 == $wrapper[0].search.databaseAccommodationIdsSha256'
assert_contains "$bootstrap" '.esDocumentIdentityPairsSha256 == $wrapper[0].search.elasticsearchDocumentIdentityPairsSha256'
assert_contains "$bootstrap" 'logical_alias=$(jq -r '\''.logicalAlias'\'' "$snapshot_reference")'
assert_contains "$bootstrap" 'snapshot_index=$(jq -r '\''.snapshotIndex'\'' "$snapshot_reference")'
assert_contains "$bootstrap" 'restored_index="${logical_alias}-vdataset-${AIRBOB_DATASET_RELEASE}"'
assert_contains "$bootstrap" '--arg sourceIndex "$snapshot_index"'
assert_contains "$bootstrap" 'rename_pattern:'
assert_contains "$bootstrap" 'rename_replacement:'
assert_contains "$bootstrap" 'is_write_index: true'
assert_contains "$bootstrap" '/_aliases'
assert_contains "$bootstrap" '(keys == [$restoredIndex])'
assert_contains "$bootstrap" 'migrationChecksumSha256'
assert_contains "$bootstrap" 'mysql_connect_timeout_seconds=10'
assert_contains "$bootstrap" 'mysql_readiness_timeout_seconds=30'
assert_contains "$bootstrap" 'mysql_general_timeout_seconds=900'
assert_contains "$bootstrap" 'mysql_import_timeout_seconds=7200'
assert_contains "$bootstrap" 'mysql_kill_after_seconds=30'
assert_contains "$bootstrap" 'MYSQL_PWD="$master_password" timeout'
assert_contains "$bootstrap" '--foreground --signal=TERM --kill-after="${mysql_kill_after_seconds}s" "${deadline_seconds}s"'
assert_contains "$bootstrap" '--connect-timeout="$mysql_connect_timeout_seconds" --skip-reconnect'
assert_contains "$bootstrap" 'mysql_with_deadline "$mysql_readiness_timeout_seconds"'
assert_contains "$bootstrap" 'mysql_with_deadline "$mysql_general_timeout_seconds"'
assert_contains "$bootstrap" 'mysql_with_deadline "$mysql_import_timeout_seconds" airbobdb'
assert_contains "$bootstrap" 'actual_rows=$(mysql_attestation_exec airbobdb --execute="SELECT COUNT(*) FROM \`$table_name\`")'
assert_contains "$bootstrap" 'mysql_attestation_exec airbobdb <<'"'"'AIRBOB_SEMANTIC_SQL'"'"''
assert_contains "$bootstrap" 'digest=$(mysql_attestation_exec airbobdb --execute="$1"'
assert_contains "$bootstrap" 'rows=$(mysql_attestation_exec airbobdb --execute="SELECT COUNT(*) FROM $table WHERE $predicate")'
assert_contains "$bootstrap" 'mysql_attestation_exec airbobdb --execute="SELECT id FROM accommodation WHERE status = '"'"'PUBLISHED'"'"' ORDER BY id"'
assert_contains "$bootstrap" 'semantic restore pass failed or exceeded its wall deadline: pass=%s status=%s'
assert_contains "$bootstrap" 'GNU timeout is required'
assert_contains "$bootstrap" 'information_schema.COLUMNS'
assert_contains "$bootstrap" 'information_schema.STATISTICS'
assert_contains "$bootstrap" 'information_schema.TABLE_CONSTRAINTS'
assert_contains "$bootstrap" 'information_schema.KEY_COLUMN_USAGE'
assert_contains "$bootstrap" 'information_schema.REFERENTIAL_CONSTRAINTS'
assert_contains "$bootstrap" 'information_schema.CHECK_CONSTRAINTS'
assert_contains "$bootstrap" 'UNION ALL'
assert_contains "$bootstrap" 'LC_ALL=C sort'
for schema_contract in \
  information_schema.COLUMNS \
  information_schema.STATISTICS \
  information_schema.TABLE_CONSTRAINTS \
  information_schema.KEY_COLUMN_USAGE \
  information_schema.REFERENTIAL_CONSTRAINTS \
  information_schema.CHECK_CONSTRAINTS; do
  assert_contains "$dataset_readme" "$schema_contract"
done
assert_contains "$dataset_readme" '`LC_ALL=C sort`'
assert_contains "$bootstrap" 'kafka-topics.sh'
assert_contains "$bootstrap" 'kafka-configs.sh'
for canonical_topic in \
  PAYMENT_OPERATION.events PAYMENT_OPERATION.events.RETRY PAYMENT_OPERATION.events.DLT \
  ACCOMMODATION_INDEX.events ACCOMMODATION_INDEX.events.RETRY ACCOMMODATION_INDEX.events.DLT \
  ACCOMMODATION_CACHE.events ACCOMMODATION_CACHE.events.RETRY ACCOMMODATION_CACHE.events.DLT \
  OPERATOR_ALERT.events OPERATOR_ALERT.events.RETRY OPERATOR_ALERT.events.DLT; do
  assert_contains "$bootstrap" "$canonical_topic"
done
assert_contains "$bootstrap" '.tasks'
assert_contains "$bootstrap" 'verify_connector_runtime_config "$connector_payload" "$connector_runtime_config"'
assert_contains "$bootstrap" 'Debezium runtime connector config differs from the approved no-data contract'
assert_contains "$bootstrap" 'Kafka topic changed while starting Debezium:'
assert_contains "$bootstrap" 'dataset outbox changed while starting Debezium'
assert_contains "$bootstrap" 'datasetManifestSha256'
assert_contains "$bootstrap" 'download_sha benchmark/manifest.json "$benchmark_manifest"'
assert_contains "$bootstrap" 'download_sha benchmark/dataset-manifest.json "$benchmark_dataset_manifest"'
assert_contains "$bootstrap" 'download_sha benchmark/validate-benchmark-dataset-v2.jq'
assert_contains "$bootstrap" '.releaseKind=="pipeline-rehearsal"'
assert_contains "$bootstrap" 'wrapper contains secret-like keys, credentials, or unapproved identity material'
assert_contains "$bootstrap" '--cli-connect-timeout 10 --cli-read-timeout 60'
assert_contains "$bootstrap" 'command curl --connect-timeout 10 --max-time 60 "$@"'
assert_contains "$bootstrap" 'command curl --connect-timeout 10 --max-time 900 "$@"'
assert_contains "$bootstrap" '$a[0].sourceDumpSha256==$a[0].restoredDumpSha256'
if awk '/^[[:space:]]*curl[[:space:]]/ || /\$\(curl[[:space:]]/' "$bootstrap" | grep -q .; then
  fail "bootstrap HTTP calls must use a bounded curl helper"
fi
if grep -Fq '$a[0].sourceDumpSha256==$w[0].releaseTuple.dumpSha256' "$bootstrap"; then
  fail "bootstrap must compare the source gzip digest to the restored gzip digest, not the zstd payload digest"
fi
assert_contains "$bootstrap" '"$benchmark_dataset_manifest"'
assert_contains "$bootstrap" 'semanticAttestationSha256'
assert_contains "$bootstrap" 'schemaVersion: 2'
assert_contains "$bootstrap" 'final_world_fingerprint=$(combine_fingerprints final-)'
assert_contains "$bootstrap" 'base_world_fingerprint=$(combine_fingerprints base-)'
assert_contains "$bootstrap" 'inventory_fingerprint=$(awk'
assert_contains "$bootstrap" 'target_fingerprint=$(recompute_target_fingerprint)'
assert_contains "$bootstrap" 'manifest fingerprint component is missing or malformed:'
assert_contains "$bootstrap" 'fingerprint component differs from restored canonical rows:'
assert_contains "$restore_verifier" 'manifest fingerprint component is missing or malformed:'
assert_contains "$restore_verifier" 'fingerprint component differs from restored canonical rows:'
assert_contains "$bootstrap" 'then "<null>"'
assert_contains "$bootstrap" 'cat "$live_fingerprint_receipt"'
assert_contains "$bootstrap" 'dataset release requires verified production fingerprints'
assert_contains "$bootstrap" '$a[0].distributionAssertionSha256'
assert_contains "$bootstrap" '$a[0].distributionSpecSha256'
assert_contains "$bootstrap" '.capsuleId=="read-model-v2" or .capsuleId=="index-query-v1"'
assert_contains "$bootstrap" 'JOIN address addr ON addr.id=a.address_id'
assert_contains "$bootstrap" 'JOIN occupancy_policy op ON op.id=a.occupancy_policy_id'
assert_contains "$bootstrap" 'Retention=summary'
assert_contains "$bootstrap" 'publish_immutable_receipt'
assert_contains "$bootstrap" "--if-none-match '*'"
assert_contains "$bootstrap" '--server-side-encryption AES256'
assert_contains "$bootstrap" 'immutable data bootstrap receipt publication could not be verified'
assert_contains "$bootstrap" 'trap cleanup EXIT'
assert_contains "$bootstrap" '<<AIRBOB_DEBEZIUM_SQL'
for component_id in \
  final-accommodation final-address final-occupancy-policy \
  final-accommodation-image final-accommodation-amenity final-member \
  final-reservation final-review final-review-image final-wishlist \
  final-wishlist-accommodation final-payment final-payment-transaction \
  final-review-summary final-daily-revenue final-inventory \
  base-accommodation base-member base-reservation base-review base-wishlist \
  base-wishlist-accommodation base-payment base-payment-transaction
do
  assert_contains "$bootstrap" "$component_id"
  assert_contains "$restore_verifier" "$component_id"
done
for semantic_field in \
  a.address_id a.occupancy_policy_id m.thumbnail_image_url \
  a.latitude a.longitude a.postal_code a.city a.country a.detail a.district a.street a.state \
  o.infant_occupancy o.max_occupancy o.pet_occupancy \
  ai.accommodation_id ai.image_url aa.accommodation_id aa.amenity_code aa.count \
  ri.review_id ri.image_url
do
  assert_contains "$bootstrap" "$semantic_field"
  assert_contains "$restore_verifier" "$semantic_field"
done
extract_fingerprint_components() {
  awk '
    /^[[:space:]]*fingerprint_table final-/ {print $2}
    /^[[:space:]]*base_fingerprint [^[:space:]]+ base-/ {print $3}
  ' "$1"
}
restore_components=$(extract_fingerprint_components "$restore_verifier")
bootstrap_components=$(extract_fingerprint_components "$bootstrap")
[[ "$restore_components" == "$bootstrap_components" ]] \
  || fail "restore and bootstrap fingerprint component order differs"
[[ "$(printf '%s\n' "$restore_components" | wc -l | tr -d '[:space:]')" == 24 ]] \
  || fail "restore and bootstrap must enumerate exactly 24 component fingerprints"
guarded_fingerprint_calls=0
while IFS= read -r guarded_fingerprint_call; do
  ((guarded_fingerprint_calls += 1))
  [[ "$guarded_fingerprint_call" == *'|| return 1' ]] \
    || fail "live restore fingerprint call does not propagate failure: $guarded_fingerprint_call"
done < <(awk '
  /^live_restore_fingerprints\(\) \{/ { capture = 1; next }
  capture && /^}/ { exit }
  capture && /^[[:space:]]*(fingerprint_table|base_fingerprint) / { print }
' "$bootstrap")
[[ "$guarded_fingerprint_calls" == 24 ]] \
  || fail "live restore must guard all 24 component fingerprint calls"
assert_contains "$bootstrap" 'final_world_fingerprint=$(combine_fingerprints final-) || return 1'
assert_contains "$bootstrap" 'base_world_fingerprint=$(combine_fingerprints base-) || return 1'
assert_contains "$bootstrap" 'inventory_fingerprint=$(awk -F '\''\t'\'' '\''$1=="final-inventory"{print $2}'\'' "$live_fingerprint_rows") || return 1'
if grep -Fq 'export MYSQL_PWD' "$bootstrap"; then
  fail "Phase 3 bootstrap must scope MYSQL_PWD to MySQL client processes"
fi
if grep -Fq 'sql_log_bin=0' "$bootstrap"; then
  fail "Phase 3 bootstrap must not depend on the unavailable RDS sql_log_bin privilege"
fi
if grep -F -- "--execute" "$bootstrap" | grep -Fq 'debezium_password'; then
  fail "Phase 3 bootstrap must not place the Debezium password in process arguments"
fi

mysql_deadline_functions="$temp_dir/mysql-deadline-functions.sh"
awk '
  /^mysql_with_deadline\(\) \{/ { capture = 1 }
  capture { print }
  /^mysql_import_dump\(\) \{/ { import_function = 1 }
  capture && import_function && /^}/ { exit }
' "$bootstrap" > "$mysql_deadline_functions"
[[ -s "$mysql_deadline_functions" ]] || fail "bootstrap MySQL deadline helpers are missing"
# shellcheck source=/dev/null
source "$mysql_deadline_functions"

mysql_deadline_bin="$temp_dir/mysql-deadline-bin"
timeout_log="$temp_dir/mysql-deadline-timeout.log"
mysql_log="$temp_dir/mysql-deadline-client.log"
install -d -m 700 "$mysql_deadline_bin"
cat > "$mysql_deadline_bin/timeout" <<'AIRBOB_TEST_TIMEOUT'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" >> "$AIRBOB_TEST_TIMEOUT_LOG"
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --foreground|--signal=TERM|--kill-after=*) shift ;;
    *s) shift; break ;;
    *) exit 64 ;;
  esac
done
if [[ "${AIRBOB_TEST_TIMEOUT_EXIT:-0}" != 0 ]]; then
  exit "$AIRBOB_TEST_TIMEOUT_EXIT"
fi
exec "$@"
AIRBOB_TEST_TIMEOUT
cat > "$mysql_deadline_bin/mysql" <<'AIRBOB_TEST_MYSQL'
#!/usr/bin/env bash
set -euo pipefail
{
  printf 'MYSQL_PWD=%s\n' "${MYSQL_PWD:+set}"
  printf 'arg=%s\n' "$@"
} >> "$AIRBOB_TEST_MYSQL_LOG"
cat >/dev/null
exit "${AIRBOB_TEST_MYSQL_EXIT:-0}"
AIRBOB_TEST_MYSQL
chmod 700 "$mysql_deadline_bin/timeout" "$mysql_deadline_bin/mysql"

export AIRBOB_TEST_TIMEOUT_LOG="$timeout_log"
export AIRBOB_TEST_MYSQL_LOG="$mysql_log"
mysql_deadline_original_path=$PATH
PATH="$mysql_deadline_bin:$PATH"
master_password=deadline-test-password
master_username=airbob_admin
AIRBOB_RDS_ENDPOINT=airbob-rds.internal
mysql_connect_timeout_seconds=10
mysql_readiness_timeout_seconds=30
mysql_general_timeout_seconds=900
mysql_attestation_timeout_seconds=3600
mysql_import_timeout_seconds=7200
mysql_kill_after_seconds=30
dump="$temp_dir/mysql-deadline-dump.zst"
: > "$dump"
zstd() {
  [[ "$*" == "--decompress --stdout $dump" ]] || return 91
  printf '%s\n' 'SELECT 1;'
}

mysql_exec --execute='SELECT 1' >/dev/null \
  || fail "bounded general MySQL helper rejected a successful client"
mysql_readiness_exec --execute='SELECT 1' >/dev/null \
  || fail "bounded readiness MySQL helper rejected a successful client"
# A healthy attestation must reach MySQL even when an independent timeout
# wrapper would terminate it. The outer operator/SSM lifetime owns its budget.
export AIRBOB_TEST_TIMEOUT_EXIT=124
mysql_attestation_exec --execute='SELECT 1' >/dev/null \
  || fail "attestation was interrupted by a speculative per-query deadline"
unset AIRBOB_TEST_TIMEOUT_EXIT
mysql_import_dump \
  || fail "bounded dump import helper rejected a successful pipeline"
for expected_timeout_argument in --foreground --signal=TERM --kill-after=30s 30s 900s 7200s; do
  grep -Fxq -- "$expected_timeout_argument" "$timeout_log" \
    || fail "MySQL deadline helper omitted timeout argument: $expected_timeout_argument"
done
[[ "$(grep -Fxc -- 'arg=--connect-timeout=10' "$mysql_log")" == 4 ]] \
  || fail "every MySQL path must set the initial connect timeout"
[[ "$(grep -Fxc -- 'arg=--skip-reconnect' "$mysql_log")" == 4 ]] \
  || fail "every MySQL path must disable automatic reconnect"
[[ "$(grep -Fxc -- 'MYSQL_PWD=set' "$mysql_log")" == 4 ]] \
  || fail "every MySQL path must scope the password through MYSQL_PWD"
if grep -Fq -- "$master_password" "$timeout_log" "$mysql_log"; then
  fail "MySQL deadline helper exposed the password in process arguments or logs"
fi

export AIRBOB_TEST_MYSQL_EXIT=23
if mysql_attestation_exec --execute='SELECT 1' >/dev/null; then
  fail "attestation hid a MySQL client failure"
else
  attestation_status=$?
fi
[[ "$attestation_status" == 23 ]] || fail "attestation did not preserve the MySQL failure status"
if mysql_import_dump; then
  fail "dump import pipeline hid a MySQL client failure"
else
  import_status=$?
fi
[[ "$import_status" == 23 ]] || fail "dump import pipeline did not preserve the MySQL failure status"
unset AIRBOB_TEST_MYSQL_EXIT
export AIRBOB_TEST_TIMEOUT_EXIT=124
if mysql_import_dump; then
  fail "dump import pipeline hid a timeout"
else
  import_status=$?
fi
[[ "$import_status" == 124 ]] || fail "dump import pipeline did not preserve timeout status 124"
unset AIRBOB_TEST_TIMEOUT_EXIT
PATH=$mysql_deadline_original_path
unset -f zstd
unset AIRBOB_TEST_TIMEOUT_LOG AIRBOB_TEST_MYSQL_LOG

semantic_deadline_functions="$temp_dir/semantic-deadline-functions.sh"
awk '
  /^semantic_restore_pass\(\) \{/ { capture = 1 }
  capture { print }
  /^run_semantic_restore_pass\(\) \{/ { wrapper = 1 }
  capture && wrapper && /^}/ { exit }
' "$bootstrap" > "$semantic_deadline_functions"
[[ -s "$semantic_deadline_functions" ]] || fail "semantic restore deadline wrapper is missing"
# shellcheck source=/dev/null
source "$semantic_deadline_functions"
semantic_restore_pass() {
  printf '%s\n' semantic-result
  return "${AIRBOB_TEST_SEMANTIC_STATUS:-0}"
}
semantic_output="$temp_dir/semantic-output.tsv"
semantic_error="$temp_dir/semantic-error.log"
export AIRBOB_TEST_SEMANTIC_STATUS=124
if run_semantic_restore_pass 1 "$semantic_output" 2>"$semantic_error"; then
  fail "semantic restore wrapper accepted a timed-out pass"
else
  semantic_status=$?
fi
[[ "$semantic_status" == 124 ]] || fail "semantic restore wrapper did not preserve timeout status 124"
grep -Fq 'semantic restore pass started: pass=1' "$semantic_error" \
  || fail "semantic restore timeout omitted its pass start diagnostic"
grep -Fq 'semantic restore pass failed or exceeded its wall deadline: pass=1 status=124' "$semantic_error" \
  || fail "semantic restore timeout omitted its exact failure diagnostic"
if grep -Fq 'semantic restore pass completed' "$semantic_error"; then
  fail "timed-out semantic restore pass emitted a completion diagnostic"
fi
unset AIRBOB_TEST_SEMANTIC_STATUS
run_semantic_restore_pass 2 "$semantic_output" 2>"$semantic_error" \
  || fail "semantic restore wrapper rejected a successful pass"
[[ "$(cat "$semantic_output")" == semantic-result ]] \
  || fail "semantic restore wrapper did not preserve the successful result"
grep -Fq 'semantic restore pass completed: pass=2' "$semantic_error" \
  || fail "successful semantic restore pass omitted its completion diagnostic"

attestation_functions="$temp_dir/bootstrap-attestation-functions.sh"
awk '
  /^result_field\(\)/ { capture = 1 }
  capture { print }
  /^verify_targets\(\) \{/ { verify_targets_function = 1 }
  capture && verify_targets_function && /^}/ { exit }
' "$bootstrap" > "$attestation_functions"
[[ -s "$attestation_functions" ]] || fail "bootstrap attestation helpers are missing"
# shellcheck source=/dev/null
source "$attestation_functions"

attestation_work_root="$temp_dir/attestation-work"
attestation_manifest="$attestation_work_root/dataset-manifest.json"
attestation_hash=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
install -d -m 700 "$attestation_work_root"
jq -n --arg hash "$attestation_hash" '
  {
    world:{fingerprints:{"final-accommodation":$hash}},
    capsules:[{
      capsuleId:"read-model-v2",
      targets:[{
        id:"review-target",resourceIds:[1],
        query:{kind:"REVIEW_SUMMARY_V1",accommodationId:1},
        expectedRows:1,expectedResultHash:$hash
      }]
    }]
  }
' > "$attestation_manifest"

benchmark_dataset_manifest=$attestation_manifest
live_fingerprint_rows="$attestation_work_root/live-fingerprint-rows.tsv"
: > "$live_fingerprint_rows"
mysql_test_count_status=0
mysql_test_hash_status=0
mysql_attestation_exec() {
  if [[ "$*" == *'--execute=SELECT COUNT(*) FROM'* ]]; then
    printf '%s\n' 1
    return "$mysql_test_count_status"
  fi
  printf '%s\n' 616263
  return "$mysql_test_hash_status"
}
mysql_exec() { mysql_attestation_exec "$@"; }

# Both fake results are byte-for-byte valid, but status 124 must still fail.
mysql_test_hash_status=124
if mysql_result_hash 'SELECT exact_hash' >/dev/null; then
  fail "result hash accepted exact output followed by status 124"
else
  attestation_status=$?
fi
[[ "$attestation_status" == 124 ]] || fail "result hash did not preserve status 124"

for fingerprint_failure in count hash; do
  mysql_test_count_status=0
  mysql_test_hash_status=0
  [[ "$fingerprint_failure" == count ]] && mysql_test_count_status=124 || mysql_test_hash_status=124
  : > "$live_fingerprint_rows"
  if fingerprint_table final-accommodation 'accommodation a' '1=1' a.id 1 a.id text 2>/dev/null; then
    fail "fingerprint table accepted exact $fingerprint_failure output followed by status 124"
  fi
  [[ ! -s "$live_fingerprint_rows" ]] || fail "failed fingerprint table wrote a receipt row"
done

for target_failure in count hash; do
  mysql_test_count_status=0
  mysql_test_hash_status=0
  [[ "$target_failure" == count ]] && mysql_test_count_status=124 || mysql_test_hash_status=124
  target_receipt="$attestation_work_root/target-$target_failure.tsv"
  if verify_targets "$target_receipt" 2>/dev/null; then
    fail "target verifier accepted exact $target_failure output followed by status 124"
  fi
  [[ ! -s "$target_receipt" ]] || fail "failed target verifier wrote a receipt row"
done

# The review aggregation is portable SQL: exercise its actual CTE on a tiny
# in-memory fixture, without connecting to or changing the restored database.
review_truth_sql=$(awk '
  /^WITH review_expected AS \(/ { p=1 }
  /^\), review_counts AS \(/ { print ")"; exit }
  p { print }
' "$bootstrap")
python3 - "$review_truth_sql" <<'AIRBOB_REVIEW_TRUTH_TEST'
import sqlite3
import sys

sql = sys.argv[1]
assert sql.startswith("WITH review_expected AS ("), "review truth CTE is missing"
fixture = """WITH accommodation(id) AS (VALUES (1),(2),(3)),
review(id,accommodation_id,rating,status) AS (
  VALUES (1,1,4,'PUBLISHED'),(2,1,5,'PUBLISHED'),(3,2,1,'DELETED')
), """
rows = sqlite3.connect(":memory:").execute(
    fixture + sql.removeprefix("WITH ")
    + " SELECT * FROM review_expected ORDER BY accommodation_id"
).fetchall()
assert rows == [(1, 2, 9, 4.5)], (
    "review summaries must exist only for accommodations with published reviews", rows
)
AIRBOB_REVIEW_TRUTH_TEST

# Execute the production orchestration, replacing only the expensive DB reads.
# Catch duplicate scans, skipped gates, changed receipt bytes and hidden errors.
single_semantic="$temp_dir/single-semantic.sh"
single_fingerprints="$temp_dir/single-fingerprints.sh"
final_targets="$temp_dir/final-targets.sh"
awk '/^semantic_one=/{p=1} /^result_field\(\)/{exit} p{print}' "$bootstrap" > "$single_semantic"
awk '/^targets_one=/{p=1} /^# Operational mutations/{exit} p{print}' "$bootstrap" > "$single_fingerprints"
awk '/^targets_final=/{p=1} /^cleanup$/{if(p)exit} p{print}' "$bootstrap" > "$final_targets"
[[ -s "$single_semantic" && -s "$single_fingerprints" ]] || fail "attestation orchestration is missing"
run_single_attestation_fixture() (
  set -euo pipefail
  local scenario=$1
  work_root="$temp_dir/single-$scenario"
  mkdir -p "$work_root"
  manifest="$work_root/manifest.json"
  printf '%s\n' '{"releaseTuple":{"targetFingerprintSha256":"target-hash"}}' > "$manifest"
  calls="$work_root/calls"
  : > "$calls"
  run_semantic_restore_pass() {
    printf '%s\n' semantic >> "$calls"
    [[ "$scenario" != semantic-error ]] || return 23
    printf '%s\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\n' \
      "$([[ "$scenario" == semantic-mismatch ]] && printf 1 || printf 0)" > "$2"
  }
  verify_targets() {
    printf '%s\n' targets >> "$calls"
    [[ "$scenario" != target-error ]] || return 23
    printf 'review-target\t1\tabc\n' > "$1"
  }
  recompute_target_fingerprint() {
    [[ "$scenario" != target-drift ]] || { printf wrong-hash; return; }
    printf target-hash
  }
  live_restore_fingerprints() {
    printf '%s\n' fingerprints >> "$calls"
    [[ "$scenario" != fingerprint-error ]] || return 23
    final_world_fingerprint=final-hash
    base_world_fingerprint=base-hash
    inventory_fingerprint=inventory-hash
    live_fingerprint_rows="$work_root/rows.tsv"
    printf 'component\tdigest\n' > "$live_fingerprint_rows"
  }
  source "$single_semantic"
  source "$single_fingerprints"
  source "$final_targets"
  printf 'COMPLETE %s\n' "$semantic_attestation_sha256"
)
single_result=$(run_single_attestation_fixture valid) || fail "single attestation rejected valid data"
[[ "$single_result" == 'COMPLETE b9195554bec8204bfae037ad4a0e1405a2b9fc20f2c60992b61736bfcfb748a2' ]] \
  || fail "single attestation changed the canonical receipt digest"
[[ "$(cat "$temp_dir/single-valid/calls")" == $'semantic\ntargets\nfingerprints' ]] \
  || fail "bootstrap must run each full attestation exactly once"
for single_failure in semantic-error semantic-mismatch target-error target-drift fingerprint-error; do
  if run_single_attestation_fixture "$single_failure" > "$temp_dir/single-result" 2>/dev/null; then
    fail "single attestation accepted $single_failure"
  fi
  if grep -Fq COMPLETE "$temp_dir/single-result"; then
    fail "failed attestation produced a completion digest"
  fi
done

fingerprint_line=$(grep -n 'Elasticsearch content fingerprint does not match the release' "$bootstrap" | cut -d: -f1)
document_identity_line=$(grep -n 'document identity pair fingerprint does not match the release' "$bootstrap" | cut -d: -f1)
alias_cutover_line=$(grep -n 'alias_update=$(curl_http' "$bootstrap" | cut -d: -f1)
[[ -n "$fingerprint_line" && -n "$document_identity_line" && -n "$alias_cutover_line" \
  && "$fingerprint_line" -lt "$alias_cutover_line" \
  && "$document_identity_line" -lt "$alias_cutover_line" ]] \
  || fail "Elasticsearch write alias must be cut over only after restored content verification"

identity_function="$temp_dir/validate-document-identity-pairs.sh"
awk '
  /^validate_document_identity_pairs\(\) \{/ { capture = 1 }
  capture { print }
  capture && /^}/ { exit }
' "$bootstrap" > "$identity_function"
[[ -s "$identity_function" ]] || fail "bootstrap document identity validator is missing"
# shellcheck source=/dev/null
source "$identity_function"
database_pairs="$temp_dir/database-pairs.tsv"
elasticsearch_pairs="$temp_dir/elasticsearch-pairs.tsv"
wrong_elasticsearch_pairs="$temp_dir/wrong-elasticsearch-pairs.tsv"
printf '%s\t%s\n' \
  '11111111-1111-1111-1111-111111111111' 1 \
  '22222222-2222-2222-2222-222222222222' 2 > "$database_pairs"
cp "$database_pairs" "$elasticsearch_pairs"
printf '%s\t%s\n' \
  '99999999-9999-9999-9999-999999999999' 1 \
  '22222222-2222-2222-2222-222222222222' 2 > "$wrong_elasticsearch_pairs"
document_identity_sha=$(sha256sum "$database_pairs" | awk '{print $1}')
validate_document_identity_pairs \
  "$database_pairs" "$elasticsearch_pairs" \
  "$document_identity_sha" "$document_identity_sha" 2 \
  || fail "matching canonical document identity pairs were rejected"
if validate_document_identity_pairs \
  "$database_pairs" "$wrong_elasticsearch_pairs" \
  "$document_identity_sha" "$document_identity_sha" 2; then
  fail "bootstrap accepted an Elasticsearch _id mapped to the wrong accommodation"
fi

snapshot_reference_function="$temp_dir/validate-snapshot-reference.sh"
awk '
  /^validate_snapshot_reference\(\) \{/ { capture = 1 }
  capture { print }
  capture && /^}/ { exit }
' "$bootstrap" > "$snapshot_reference_function"
[[ -s "$snapshot_reference_function" ]] || fail "bootstrap snapshot reference validator is missing"
# shellcheck source=/dev/null
source "$snapshot_reference_function"
snapshot_wrapper="$temp_dir/search-wrapper.json"
snapshot_reference="$temp_dir/snapshot-reference.json"
jq -nS '
  {
    datasetRelease:"rehearsal-search-v20",
    search:{
      enabled:true,repository:"airbob-dataset-readonly",logicalAlias:"accommodations",
      snapshotIndex:"accommodations-vfixture",elasticsearchVersion:"8.18.8",
      imageDigest:("sha256:" + ("5" * 64)),documentCount:200201,
      mappingSha256:("1" * 64),databaseAccommodationIdsSha256:("2" * 64),
      elasticsearchAccommodationIdsSha256:("2" * 64),
      databaseDocumentIdentityPairsSha256:("3" * 64),
      elasticsearchDocumentIdentityPairsSha256:("3" * 64),
      contentFingerprintSha256:("4" * 64)
    }
  }
' > "$snapshot_wrapper"
jq -nS '
  {
    schemaVersion:2,repository:"airbob-dataset-readonly",
    bucket:"airbob-performance-lab-dataset-942632789808",
    basePath:"elasticsearch/releases/rehearsal-search-v20",
    snapshot:"airbob-rehearsal-search-v20",logicalAlias:"accommodations",
    snapshotIndex:"accommodations-vfixture",elasticsearchVersion:"8.18.8",
    imageDigest:("sha256:" + ("5" * 64)),documentCount:200201,
    mappingSha256:("1" * 64),dbIdsSha256:("2" * 64),esIdsSha256:("2" * 64),
    dbDocumentIdentityPairsSha256:("3" * 64),
    esDocumentIdentityPairsSha256:("3" * 64),contentFingerprintSha256:("4" * 64)
  }
' > "$snapshot_reference"
validate_snapshot_reference \
  "$snapshot_reference" "$snapshot_wrapper" \
  airbob-performance-lab-dataset-942632789808 rehearsal-search-v20 \
  || fail "bootstrap rejected the exact wrapper-bound snapshot reference"
for reference_drift in database-id component-swap bucket extra-field; do
  case "$reference_drift" in
    database-id) jq '.dbIdsSha256=("9"*64)' "$snapshot_reference" > "$snapshot_reference.next" ;;
    component-swap)
      jq '.dbIdsSha256=.dbDocumentIdentityPairsSha256 | .esIdsSha256=.esDocumentIdentityPairsSha256' \
        "$snapshot_reference" > "$snapshot_reference.next"
      ;;
    bucket) jq '.bucket="attacker-controlled-bucket"' "$snapshot_reference" > "$snapshot_reference.next" ;;
    extra-field) jq '.unbound=true' "$snapshot_reference" > "$snapshot_reference.next" ;;
  esac
  if validate_snapshot_reference \
    "$snapshot_reference.next" "$snapshot_wrapper" \
    airbob-performance-lab-dataset-942632789808 rehearsal-search-v20; then
    fail "bootstrap accepted snapshot reference drift: $reference_drift"
  fi
done

receipt_publication_function="$temp_dir/publish-immutable-receipt.sh"
awk '
  /^publish_immutable_receipt\(\) \{/ { capture = 1 }
  capture { print }
  capture && /^}/ { exit }
' "$bootstrap" > "$receipt_publication_function"
[[ -s "$receipt_publication_function" ]] || fail "bootstrap immutable receipt publisher is missing"
receipt_fixture="$temp_dir/bootstrap-receipt.json"
receipt_readback="$temp_dir/bootstrap-receipt.readback.json"
receipt_store="$temp_dir/bootstrap-receipt.remote.json"
receipt_log="$temp_dir/bootstrap-receipt-aws.log"
printf '%s\n' '{"schemaVersion":2,"verified":true}' > "$receipt_fixture"
(
  # shellcheck source=/dev/null
  source "$receipt_publication_function"
  aws() {
    local argument body='' destination=''
    printf '%s\n' "$*" >> "$receipt_log"
    case "$*" in
      *'s3api put-object'*)
        while [[ "$#" -gt 0 ]]; do
          argument=$1
          shift
          if [[ "$argument" == --body ]]; then body=$1; shift; fi
        done
        if [[ -e "$receipt_store" ]]; then return 1; fi
        cp "$body" "$receipt_store"
        ;;
      *'s3api get-object'*)
        destination=${!#}
        cp "$receipt_store" "$destination"
        ;;
      *) return 1 ;;
    esac
  }
  export AIRBOB_REGION=ap-northeast-2
  publish_immutable_receipt \
    "$receipt_fixture" airbob-performance-lab-evidence-942632789808 \
    data-bootstrap/phase3-test/rehearsal-v20.json "$receipt_readback"
) || fail "bootstrap could not publish and read back a new immutable receipt"
cmp -s "$receipt_fixture" "$receipt_store" || fail "immutable receipt store differs from the producer bytes"
grep -Fq -- '--if-none-match *' "$receipt_log" || fail "receipt publication is not create-only"
grep -Fq -- '--server-side-encryption AES256' "$receipt_log" || fail "receipt publication is not encrypted"
grep -Fq -- 's3api get-object' "$receipt_log" || fail "receipt publication did not perform readback"

printf '%s\n' '{"schemaVersion":2,"verified":false}' > "$receipt_fixture"
if (
  # shellcheck source=/dev/null
  source "$receipt_publication_function"
  aws() {
    local destination=''
    case "$*" in
      *'s3api put-object'*) return 1 ;;
      *'s3api get-object'*) destination=${!#}; cp "$receipt_store" "$destination" ;;
      *) return 1 ;;
    esac
  }
  export AIRBOB_REGION=ap-northeast-2
  publish_immutable_receipt \
    "$receipt_fixture" airbob-performance-lab-evidence-942632789808 \
    data-bootstrap/phase3-test/rehearsal-v20.json "$receipt_readback"
); then
  fail "bootstrap silently overwrote or accepted a different receipt at the immutable key"
fi

rm -f "$receipt_store"
printf '%s\n' '{"schemaVersion":2,"verified":true}' > "$receipt_fixture"
(
  # shellcheck source=/dev/null
  source "$receipt_publication_function"
  aws() {
    local argument body='' destination=''
    case "$*" in
      *'s3api put-object'*)
        while [[ "$#" -gt 0 ]]; do
          argument=$1
          shift
          if [[ "$argument" == --body ]]; then body=$1; shift; fi
        done
        cp "$body" "$receipt_store"
        return 1
        ;;
      *'s3api get-object'*) destination=${!#}; cp "$receipt_store" "$destination" ;;
      *) return 1 ;;
    esac
  }
  export AIRBOB_REGION=ap-northeast-2
  publish_immutable_receipt \
    "$receipt_fixture" airbob-performance-lab-evidence-942632789808 \
    data-bootstrap/phase3-test/rehearsal-v20.json "$receipt_readback"
) || fail "bootstrap did not recover an ambiguous successful receipt write by exact readback"

connector_config_function="$temp_dir/verify-connector-runtime-config.sh"
awk '
  /^verify_connector_runtime_config\(\) \{/ { capture = 1 }
  capture { print }
  capture && /^}/ { exit }
' "$bootstrap" > "$connector_config_function"
[[ -s "$connector_config_function" ]] || fail "bootstrap runtime connector config validator is missing"
# shellcheck source=/dev/null
source "$connector_config_function"
expected_connector_config="$temp_dir/expected-connector.json"
runtime_connector_config="$temp_dir/runtime-connector.json"
jq '
  .["database.hostname"] = "rds.example.internal" |
  .["database.user"] = "airbob_debezium" |
  .["database.password"] = "expected-secret"
' "$repo_root/infra/aws/bundles/debezium/connector.aws.json.tmpl" > "$expected_connector_config"
jq '.["database.password"] = "masked-or-different-runtime-value"' \
  "$expected_connector_config" > "$runtime_connector_config"
verify_connector_runtime_config "$expected_connector_config" "$runtime_connector_config" \
  || fail "runtime connector config rejected an exact non-secret contract"
for connector_drift_case in snapshot-mode outbox-table predicate extra-field missing-password; do
  case "$connector_drift_case" in
    snapshot-mode) jq '.["snapshot.mode"] = "initial"' "$expected_connector_config" ;;
    outbox-table) jq '.["table.include.list"] = "airbobdb.reservation"' "$expected_connector_config" ;;
    predicate) jq '.["transforms.outbox.predicate"] = "OtherPredicate"' "$expected_connector_config" ;;
    extra-field) jq '.["consumer.override.auto.offset.reset"] = "earliest"' "$expected_connector_config" ;;
    missing-password) jq 'del(.["database.password"])' "$expected_connector_config" ;;
  esac > "$runtime_connector_config"
  if verify_connector_runtime_config "$expected_connector_config" "$runtime_connector_config"; then
    fail "runtime connector config accepted drift: $connector_drift_case"
  fi
done

topic_offset_function="$temp_dir/validate-empty-topic-offsets.sh"
awk '
  /^validate_empty_topic_offsets\(\) \{/ { capture = 1 }
  capture { print }
  capture && /^}/ { exit }
' "$bootstrap" > "$topic_offset_function"
[[ -s "$topic_offset_function" ]] || fail "bootstrap Kafka offset validator is missing"
# shellcheck source=/dev/null
source "$topic_offset_function"
validate_empty_topic_offsets PAYMENT_OPERATION.events 3 $'PAYMENT_OPERATION.events:0:0\nPAYMENT_OPERATION.events:1:0\nPAYMENT_OPERATION.events:2:0' \
  || fail "complete empty Kafka offsets were rejected"
if validate_empty_topic_offsets PAYMENT_OPERATION.events 3 $'PAYMENT_OPERATION.events:0:0\nPAYMENT_OPERATION.events:1:1\nPAYMENT_OPERATION.events:2:0'; then
  fail "Kafka offset validator accepted a non-empty partition"
fi
if validate_empty_topic_offsets PAYMENT_OPERATION.events 3 $'PAYMENT_OPERATION.events:0:0\nPAYMENT_OPERATION.events:1:0'; then
  fail "Kafka offset validator accepted an incomplete partition set"
fi
if validate_empty_topic_offsets PAYMENT_OPERATION.events 3 $'PAYMENT_OPERATION.events:0:0\nPAYMENT_OPERATION.events:0:0\nPAYMENT_OPERATION.events:2:0'; then
  fail "Kafka offset validator accepted a duplicate partition"
fi
if validate_empty_topic_offsets PAYMENT_OPERATION.events 3 $'OTHER.events:0:0\nPAYMENT_OPERATION.events:1:0\nPAYMENT_OPERATION.events:2:0'; then
  fail "Kafka offset validator accepted another topic"
fi
if validate_empty_topic_offsets PAYMENT_OPERATION.events 3 ''; then
  fail "Kafka offset validator accepted a missing offset stream"
fi

runtime_connector_line=$(grep -n 'verify_connector_runtime_config "$connector_payload"' "$bootstrap" | cut -d: -f1)
final_topic_line=$(grep -n 'Kafka topic changed while starting Debezium:' "$bootstrap" | cut -d: -f1)
final_outbox_line=$(grep -n 'final_outbox_count=' "$bootstrap" | cut -d: -f1)
receipt_line=$(grep -n 'receipt="$work_root/data-bootstrap-receipt.json"' "$bootstrap" | cut -d: -f1)
[[ "$runtime_connector_line" -lt "$final_topic_line" && "$final_topic_line" -lt "$final_outbox_line" \
  && "$final_outbox_line" -lt "$receipt_line" ]] \
  || fail "Debezium runtime config, final Kafka/outbox checks, and receipt are out of order"

checks="$lab_root/checks.tf"
aws_lab="$repo_root/infra/aws/scripts/aws-lab.sh"
discovery="$repo_root/load-test/k6/traffic/run-aws-discovery.sh"
aggregator="$repo_root/load-test/k6/traffic/aggregate-traffic-results.mjs"
assert_contains "$validator" ".mysql.flywayVersion == \"$latest_flyway_version\""
assert_contains "$validator" ".mysql.expectedTableRows.flyway_schema_history == $flyway_history_rows"
assert_contains "$validator" 'has("accommodation_inventory_day")'
assert_contains "$validator" 'all($dataset[0].world.tableRows | to_entries[]'
assert_contains "$checks" "local.dataset_manifest.mysql.flywayVersion == \"$latest_flyway_version\""
assert_contains "$checks" 'local.dataset_release_kind == "pipeline-rehearsal"'
assert_contains "$checks" "local.dataset_expected_table_rows.flyway_schema_history == $flyway_history_rows"
assert_contains "$checks" 'contains(keys(local.dataset_expected_table_rows), "accommodation_inventory_day")'
assert_contains "$checks" "local.data_bootstrap_receipt.flywayVersion == \"$latest_flyway_version\""
assert_contains "$aws_lab" ".mysql.flywayVersion == \"$latest_flyway_version\""
assert_contains "$aws_lab" ".mysql.expectedTableRows.flyway_schema_history == $flyway_history_rows"
assert_contains "$discovery" ".flywayVersion == \"$latest_flyway_version\""
assert_contains "$discovery" '== "$expected_flyway_version" ]]'
assert_contains "$discovery" '--arg flywayVersion "$expected_flyway_version"'
assert_contains "$aggregator" "const CURRENT_FLYWAY_VERSION = '$latest_flyway_version';"
assert_contains "$aggregator" 'metadata.flywayVersion === CURRENT_FLYWAY_VERSION'
assert_contains "$bootstrap" 'published accommodation timezone contract failed'
assert_contains "$bootstrap" "time_zone_id NOT REGEXP '^[A-Za-z][A-Za-z0-9._+-]*(/[A-Za-z0-9._+-]+)*$'"

assert_contains "$lab_root/ssm.tf" 'base64gzip(file("${path.module}/../scripts/bootstrap-data.sh"))'
assert_contains "$lab_root/ssm.tf" 'filesha256("${path.module}/../scripts/bootstrap-data.sh")'
if grep -Fq 'file("${path.module}/../scripts/verify-dataset-release.sh")' "$lab_root/ssm.tf"; then
  fail "aggregate release verifier must not be embedded in the SSM document"
fi
compressed_bootstrap_bytes=$(gzip -c "$bootstrap" | base64 | wc -c | tr -d '[:space:]')
embedded_document_bytes=$((compressed_bootstrap_bytes + $(wc -c < "$repo_root/src/main/resources/lua/coupon_prepare.lua") + 6000))
[[ "$embedded_document_bytes" -le 45000 ]] || fail "Phase 3 bootstrap exceeds the 45KB SSM document payload budget"

"$repo_root/infra/aws/tests/dataset-release-test.sh" >/dev/null
"$repo_root/infra/aws/tests/rds-snapshot-promotion-test.sh" >/dev/null

printf '%s\n' 'data bootstrap contract tests passed'
