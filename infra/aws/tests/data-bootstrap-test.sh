#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
lab_root="$repo_root/infra/aws/lab"
bootstrap="$repo_root/infra/aws/scripts/bootstrap-data.sh"
validator="$repo_root/infra/aws/scripts/verify-dataset-release.sh"
dataset_readme="$repo_root/infra/aws/datasets/README.md"

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
  "$validator"
  "$dataset_readme"
  "$repo_root/infra/aws/scripts/promote-rds-snapshot.sh"
  "$lab_root/rds.tf"
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
assert_contains "$lab_root/modules/rds/main.tf" 'manage_master_user_password = true'
assert_contains "$lab_root/modules/rds/main.tf" 'snapshot_identifier'
assert_contains "$lab_root/modules/rds/main.tf" 'backup_retention_period'
assert_contains "$lab_root/modules/rds/main.tf" 'binlog_format'
assert_contains "$lab_root/modules/rds/main.tf" 'binlog_row_image'
assert_contains "$lab_root/modules/rds/main.tf" 'performance_schema'
assert_contains "$lab_root/ssm.tf" 'resource "aws_ssm_association" "data_bootstrap"'
assert_contains "$lab_root/checks.tf" 'resource "terraform_data" "data_bootstrap_gate"'
assert_contains "$lab_root/iam.tf" 'ManageEphemeralDebeziumCredentialValue'
assert_contains "$lab_root/iam.tf" 'elasticsearch/releases/${var.dataset_release}/*'
assert_contains "$repo_root/infra/aws/foundation/lab-compute.tf" 'BootstrapSecrets'
assert_contains "$repo_root/infra/aws/foundation/lab-compute.tf" 'WriteBootstrapEvidence'
assert_contains "$repo_root/docker/elasticsearch/Dockerfile" 'repository-s3/plugin-descriptor.properties'
assert_contains "$repo_root/infra/aws/bundles/debezium/connector.aws.json.tmpl" '"snapshot.mode": "no_data"'

mysql_line=$(grep -n 'outbox_policy=' "$bootstrap" | head -1 | cut -d: -f1)
es_line=$(grep -n 'if \[\[ "$search_enabled" == true \]\]' "$bootstrap" | tail -1 | cut -d: -f1)
redis_line=$(grep -n 'redis_cli 6379 FLUSHDB' "$bootstrap" | cut -d: -f1)
kafka_line=$(grep -n 'kafka_exec=' "$bootstrap" | cut -d: -f1)
connector_line=$(grep -n 'connector_template=' "$bootstrap" | cut -d: -f1)
[[ "$mysql_line" -lt "$es_line" && "$es_line" -lt "$redis_line" && "$redis_line" -lt "$kafka_line" && "$kafka_line" -lt "$connector_line" ]] \
  || fail "Phase 3 bootstrap order must remain MySQL, Elasticsearch, Redis, Kafka, then Debezium"

if grep -En 'set -x|--password=|printf.*(master_password|debezium_password)|echo.*(master_password|debezium_password)' "$bootstrap" >/dev/null; then
  fail "Phase 3 bootstrap must not expose credentials in argv or logs"
fi
assert_contains "$bootstrap" '--secret-string "file://$debezium_secret_file"'
assert_contains "$bootstrap" 'redis_cli 6379 FLUSHDB'
assert_contains "$bootstrap" 'redis_cli 6380 FLUSHDB'
assert_contains "$bootstrap" 'index.number_of_replicas'
assert_contains "$bootstrap" 'include_global_state'
assert_contains "$bootstrap" '/_search?scroll=2m'
assert_contains "$bootstrap" 'dbIdsSha256'
assert_contains "$bootstrap" 'esIdsSha256'
assert_contains "$bootstrap" 'contentFingerprintSha256'
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
assert_contains "$bootstrap" '.tasks'
assert_contains "$bootstrap" 'datasetManifestSha256'
assert_contains "$bootstrap" '"$dataset_uri/benchmark/manifest.json" "$benchmark_manifest"'
assert_contains "$bootstrap" 'Retention=summary'
assert_contains "$bootstrap" 'trap cleanup EXIT'
assert_contains "$bootstrap" '<<AIRBOB_DEBEZIUM_SQL'
assert_contains "$bootstrap" 'MYSQL_PWD="$master_password" mysql'
if grep -Fq 'export MYSQL_PWD' "$bootstrap"; then
  fail "Phase 3 bootstrap must scope MYSQL_PWD to MySQL client processes"
fi
if grep -Fq 'sql_log_bin=0' "$bootstrap"; then
  fail "Phase 3 bootstrap must not depend on the unavailable RDS sql_log_bin privilege"
fi
if grep -F -- "--execute" "$bootstrap" | grep -Fq 'debezium_password'; then
  fail "Phase 3 bootstrap must not place the Debezium password in process arguments"
fi

checks="$lab_root/checks.tf"
aws_lab="$repo_root/infra/aws/scripts/aws-lab.sh"
discovery="$repo_root/load-test/k6/traffic/run-aws-discovery.sh"
aggregator="$repo_root/load-test/k6/traffic/aggregate-traffic-results.mjs"
assert_contains "$validator" ".mysql.flywayVersion == \"$latest_flyway_version\""
assert_contains "$validator" ".mysql.expectedTableRows.flyway_schema_history == $flyway_history_rows"
assert_contains "$checks" "local.dataset_manifest.mysql.flywayVersion == \"$latest_flyway_version\""
assert_contains "$checks" "local.dataset_expected_table_rows.flyway_schema_history == $flyway_history_rows"
assert_contains "$checks" "local.data_bootstrap_receipt.flywayVersion == \"$latest_flyway_version\""
assert_contains "$aws_lab" ".mysql.flywayVersion == \"$latest_flyway_version\""
assert_contains "$aws_lab" ".mysql.expectedTableRows.flyway_schema_history == $flyway_history_rows"
assert_contains "$discovery" ".flywayVersion == \"$latest_flyway_version\""
assert_contains "$discovery" '== "$expected_flyway_version" ]]'
assert_contains "$discovery" '--arg flywayVersion "$expected_flyway_version"'
assert_contains "$aggregator" "const CURRENT_FLYWAY_VERSION = '$latest_flyway_version';"
assert_contains "$aggregator" 'metadata.flywayVersion === CURRENT_FLYWAY_VERSION'

embedded_document_bytes=$((
  $(wc -c < "$bootstrap") +
  $(wc -c < "$validator") +
  $(wc -c < "$repo_root/src/main/resources/lua/coupon_prepare.lua")
))
[[ "$embedded_document_bytes" -lt 55000 ]] || fail "Phase 3 bootstrap exceeds the bounded SSM document payload budget"

"$repo_root/infra/aws/tests/dataset-release-test.sh" >/dev/null
"$repo_root/infra/aws/tests/rds-snapshot-promotion-test.sh" >/dev/null

printf '%s\n' 'data bootstrap contract tests passed'
