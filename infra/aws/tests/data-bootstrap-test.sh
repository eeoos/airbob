#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
lab_root="$repo_root/infra/aws/lab"
bootstrap="$repo_root/infra/aws/scripts/bootstrap-data.sh"
validator="$repo_root/infra/aws/scripts/verify-dataset-release.sh"

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

assert_contains() {
  grep -Fq -- "$2" "$1" || fail "$1 does not contain the required Phase 3 contract: $2"
}

required_files=(
  "$bootstrap"
  "$validator"
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
assert_contains "$bootstrap" 'LC_ALL=C sort'
assert_contains "$bootstrap" 'migrationChecksumSha256'
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

embedded_document_bytes=$((
  $(wc -c < "$bootstrap") +
  $(wc -c < "$validator") +
  $(wc -c < "$repo_root/src/main/resources/lua/coupon_prepare.lua")
))
[[ "$embedded_document_bytes" -lt 55000 ]] || fail "Phase 3 bootstrap exceeds the bounded SSM document payload budget"

"$repo_root/infra/aws/tests/dataset-release-test.sh" >/dev/null
"$repo_root/infra/aws/tests/rds-snapshot-promotion-test.sh" >/dev/null

printf '%s\n' 'data bootstrap contract tests passed'
