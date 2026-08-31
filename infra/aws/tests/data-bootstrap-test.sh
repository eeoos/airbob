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

assert_contains "$lab_root/variables.tf" 'contains(["network", "probe-cleared", "services", "data-ready"], var.deployment_phase)'
assert_contains "$lab_root/modules/rds/main.tf" 'instance_class = "db.t3.micro"'
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
assert_contains "$lab_root/iam.tf" 'elasticsearch/releases/${var.dataset_release}/*'
assert_contains "$repo_root/infra/aws/foundation/lab-compute.tf" 'BootstrapSecrets'
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
attest_line=$(grep -n 'second live fingerprint attestation failed' "$bootstrap" | cut -d: -f1)
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
assert_contains "$bootstrap" 'zstd --decompress --stdout "$dump" | mysql_exec airbobdb'
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
assert_contains "$bootstrap" 'second live fingerprint attestation failed'
assert_contains "$bootstrap" 'dataset release requires verified production fingerprints'
assert_contains "$bootstrap" '$a[0].distributionAssertionSha256'
assert_contains "$bootstrap" '$a[0].distributionSpecSha256'
assert_contains "$bootstrap" '.capsuleId=="read-model-v2" or .capsuleId=="index-query-v1"'
assert_contains "$bootstrap" 'JOIN address addr ON addr.id=a.address_id'
assert_contains "$bootstrap" 'JOIN occupancy_policy op ON op.id=a.occupancy_policy_id'
assert_contains "$bootstrap" 'final live target attestation failed'
assert_contains "$bootstrap" 'live targets drifted after the semantic gate'
assert_contains "$bootstrap" 'Retention=summary'
assert_contains "$bootstrap" 'trap cleanup EXIT'
assert_contains "$bootstrap" '<<AIRBOB_DEBEZIUM_SQL'
assert_contains "$bootstrap" 'MYSQL_PWD="$master_password" mysql'
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
if grep -Fq 'export MYSQL_PWD' "$bootstrap"; then
  fail "Phase 3 bootstrap must scope MYSQL_PWD to MySQL client processes"
fi
if grep -Fq 'sql_log_bin=0' "$bootstrap"; then
  fail "Phase 3 bootstrap must not depend on the unavailable RDS sql_log_bin privilege"
fi
if grep -F -- "--execute" "$bootstrap" | grep -Fq 'debezium_password'; then
  fail "Phase 3 bootstrap must not place the Debezium password in process arguments"
fi
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
