#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

ELASTICSEARCH_VERSION=8.18.8
CREDENTIAL_HEADROOM_SECONDS=3600
CREDENTIAL_SHUTDOWN_HEADROOM_SECONDS=300
LEASE_DEADLINE_GRACE_SECONDS=900
LEASE_HEARTBEAT_INTERVAL_SECONDS=60
LEASE_HEARTBEAT_TTL_SECONDS=180
ELASTICSEARCH_CONNECT_TIMEOUT_SECONDS=5
ELASTICSEARCH_REQUEST_TIMEOUT_SECONDS=60
ELASTICSEARCH_CLEANUP_RESERVE_SECONDS=300
LEASE_TABLE=airbob-performance-lab-orchestration-lease
SOURCE_INDEX=accommodations
S3_CLIENT=airbob_dataset_producer
WRITER_REPOSITORY=airbob-dataset-producer
READER_REPOSITORY=airbob-dataset-readonly

usage() {
  printf 'usage: %s ETL_RELEASE_DIR ATTESTATION IMAGE_RELEASE DATASET_RELEASE SNAPSHOT_REF_OUT RECEIPT_OUT\n' \
    "${0##*/}" >&2
  exit 64
}

fail() {
  printf 'Elasticsearch snapshot production failed: %s\n' "$1" >&2
  exit 1
}

[[ -z "${AIRBOB_NOW_EPOCH+x}" ]] \
  || fail 'AIRBOB_NOW_EPOCH is a test-only clock override and is forbidden for snapshot production'

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    fail 'a SHA-256 implementation is required'
  fi
}

resolve_output_path() {
  local candidate=$1
  local parent name
  parent=${candidate%/*}
  name=${candidate##*/}
  [[ "$parent" != "$candidate" ]] || parent=.
  [[ -n "$name" && "$name" != . && "$name" != .. ]] \
    || fail 'snapshot output path is invalid'
  [[ -d "$parent" && ! -L "$parent" ]] \
    || fail 'snapshot output directory is missing or unsafe'
  parent=$(CDPATH= cd -P -- "$parent" && pwd -P)
  printf '%s/%s\n' "$parent" "$name"
}

curl_json() {
  local method=$1
  local path=$2
  local body=${3:-}
  local max_time=${4:-$ELASTICSEARCH_REQUEST_TIMEOUT_SECONDS}
  if [[ -n "$body" ]]; then
    curl --fail --silent --show-error \
      --connect-timeout "$ELASTICSEARCH_CONNECT_TIMEOUT_SECONDS" \
      --max-time "$max_time" \
      --request "$method" \
      --header 'Content-Type: application/json' \
      --data-binary "$body" \
      "$AIRBOB_DATASET_ES_URL$path"
  else
    curl --fail --silent --show-error \
      --connect-timeout "$ELASTICSEARCH_CONNECT_TIMEOUT_SECONDS" \
      --max-time "$max_time" \
      --request "$method" "$AIRBOB_DATASET_ES_URL$path"
  fi
}

remaining_elasticsearch_request_budget() {
  local current_epoch remaining
  current_epoch=$(date -u '+%s')
  remaining=$((
    expiration_epoch - current_epoch -
    CREDENTIAL_SHUTDOWN_HEADROOM_SECONDS - ELASTICSEARCH_CLEANUP_RESERVE_SECONDS
  ))
  [[ "$remaining" -ge "$ELASTICSEARCH_REQUEST_TIMEOUT_SECONDS" ]] || return 1
  printf '%s\n' "$remaining"
}

reload_secure_settings() {
  local response
  response=$(curl_json POST /_nodes/reload_secure_settings '{}') || return 1
  jq -e '
    (.nodes | type == "object" and length > 0) and
    all(.nodes[]; (.reload_exception // null) == null)
  ' <<<"$response" >/dev/null
}

set_source_write_block() {
  local value=$1
  local response
  response=$(curl_json PUT "/$SOURCE_INDEX/_settings" \
    "{\"index\":{\"blocks.write\":$value}}") || return 1
  jq -e '.acknowledged == true' <<<"$response" >/dev/null
}

remove_keystore_credentials() {
  local failed=0
  local setting
  for setting in access_key secret_key session_token; do
    docker exec "$AIRBOB_DATASET_ES_CONTAINER" \
      /usr/share/elasticsearch/bin/elasticsearch-keystore remove \
      "s3.client.$S3_CLIENT.$setting" >/dev/null 2>&1 || failed=1
  done
  reload_secure_settings >/dev/null 2>&1 || failed=1
  return "$failed"
}

lease_command() {
  AWS_REGION="$AIRBOB_REGION" "$lease_script" "$@"
}

assert_snapshot_lease() {
  [[ -z "$lease_guard_failure_file" || ! -e "$lease_guard_failure_file" ]] || return 1
  lease_command assert \
    "$LEASE_TABLE" "$lease_lock_id" "$lease_owner" "$lease_token" \
    "$lease_run_id" dataset-snapshot >/dev/null
}

stop_lease_guard() {
  local process_id=$1
  [[ -n "$process_id" ]] || return 0
  kill "$process_id" >/dev/null 2>&1 || true
  wait "$process_id" >/dev/null 2>&1 || true
}

start_lease_guards() {
  local producer_pid=$$
  (
    while sleep "$LEASE_HEARTBEAT_INTERVAL_SECONDS"; do
      if ! lease_command heartbeat \
        "$LEASE_TABLE" "$lease_lock_id" "$lease_owner" "$lease_token" \
        "$lease_run_id" dataset-snapshot "$LEASE_HEARTBEAT_TTL_SECONDS" \
        >/dev/null 2>&1; then
        : > "$lease_guard_failure_file"
        kill -TERM "$producer_pid" >/dev/null 2>&1 || true
        exit 1
      fi
    done
  ) &
  lease_heartbeat_pid=$!
  (
    sleep "$credential_watchdog_seconds"
    : > "$lease_guard_failure_file"
    kill -TERM "$producer_pid" >/dev/null 2>&1 || true
  ) &
  credential_watchdog_pid=$!
}

cleanup_runtime() {
  local failed=0
  if [[ -n "$active_scroll_id" ]]; then
    local clear_body
    clear_body=$(jq -cn --arg scrollId "$active_scroll_id" '{scroll_id:[$scrollId]}')
    if curl_json DELETE '/_search/scroll' "$clear_body" >/dev/null 2>&1; then
      active_scroll_id=''
    else
      failed=1
    fi
  fi
  if [[ "$temporary_index_created" == true ]]; then
    if curl_json DELETE "/$temporary_index" >/dev/null 2>&1; then
      temporary_index_created=false
    else
      failed=1
    fi
  fi
  if [[ "$reader_registered" == true ]]; then
    if curl_json DELETE "/_snapshot/$READER_REPOSITORY" >/dev/null 2>&1; then
      reader_registered=false
    else
      failed=1
    fi
  fi
  if [[ "$writer_registered" == true ]]; then
    if curl_json DELETE "/_snapshot/$WRITER_REPOSITORY" >/dev/null 2>&1; then
      writer_registered=false
    else
      failed=1
    fi
  fi
  if [[ "$source_frozen" == true ]]; then
    if set_source_write_block false >/dev/null 2>&1; then
      source_frozen=false
    else
      failed=1
    fi
  fi
  if [[ "$credentials_installed" == true ]]; then
    if remove_keystore_credentials; then
      credentials_installed=false
    else
      failed=1
    fi
  fi
  [[ "$failed" -eq 0 ]]
}

on_exit() {
  local status=$?
  local cleanup_confirmed=false
  trap - EXIT HUP INT TERM
  unset MYSQL_PWD database_password access_key secret_key session_token credentials_json
  stop_lease_guard "$lease_heartbeat_pid"
  stop_lease_guard "$credential_watchdog_pid"
  if [[ "$runtime_cleaned" == true ]]; then
    cleanup_confirmed=true
  elif cleanup_runtime >/dev/null 2>&1; then
    runtime_cleaned=true
    cleanup_confirmed=true
  else
    printf '%s\n' 'warning: local Elasticsearch cleanup was incomplete; the dataset snapshot lease will not be released' >&2
    [[ "$status" -ne 0 ]] || status=1
  fi
  if [[ "$lease_acquired" == true && "$cleanup_confirmed" == true ]]; then
    if assert_snapshot_lease >/dev/null 2>&1 && lease_command release \
      "$LEASE_TABLE" "$lease_lock_id" "$lease_owner" "$lease_token" \
      "$lease_run_id" dataset-snapshot >/dev/null 2>&1; then
      lease_acquired=false
    else
      printf '%s\n' 'warning: dataset snapshot lease release failed; wait for its deadline before another attempt' >&2
      [[ "$status" -ne 0 ]] || status=1
    fi
  fi
  if [[ "$status" -ne 0 && "$outputs_validated" != true ]]; then
    [[ "$reference_linked" != true ]] || rm -f "$snapshot_reference_output" >/dev/null 2>&1 || true
    [[ "$receipt_linked" != true ]] || rm -f "$receipt_output" >/dev/null 2>&1 || true
  fi
  [[ -z "$reference_temp" ]] || rm -f "$reference_temp" >/dev/null 2>&1 || true
  [[ -z "$receipt_temp" ]] || rm -f "$receipt_temp" >/dev/null 2>&1 || true
  [[ -z "$work_dir" ]] || rm -rf "$work_dir" >/dev/null 2>&1 || true
  exit "$status"
}

[[ "$#" -eq 6 ]] || usage
etl_release_dir=$1
attestation_file=$2
image_release_file=$3
dataset_release=$4
snapshot_reference_output=$5
receipt_output=$6
script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
lease_script="$script_dir/orchestration-lease.sh"
lineage_verifier="$script_dir/verify-etl-release-database.sh"
[[ -x "$lease_script" && ! -L "$lease_script" ]] \
  || fail 'the shared orchestration lease helper is missing or unsafe'
[[ -x "$lineage_verifier" && ! -L "$lineage_verifier" ]] \
  || fail 'the ETL release database verifier is missing or unsafe'

for required_command in aws jq curl docker mysql sort awk mktemp date find basename dirname cmp wc tr \
  chmod ln rm gzip mv cat sleep; do
  command -v "$required_command" >/dev/null 2>&1 \
    || fail "required local command is unavailable: $required_command"
done

required_environment=(
  AIRBOB_REGION
  AIRBOB_AWS_ACCOUNT_ID
  AIRBOB_DATASET_ETL_REPOSITORY
  AIRBOB_DATASET_DB_HOST
  AIRBOB_DATASET_DB_PORT
  AIRBOB_DATASET_DB_USER
  AIRBOB_DATASET_DB_PASSWORD
  AIRBOB_DATASET_DB_NAME
  AIRBOB_DATASET_ES_URL
  AIRBOB_DATASET_ES_CONTAINER
)
for environment_name in "${required_environment[@]}"; do
  [[ -n "${!environment_name:-}" ]] \
    || fail "missing required producer environment: $environment_name"
done
database_password=$AIRBOB_DATASET_DB_PASSWORD
unset AIRBOB_DATASET_DB_PASSWORD

[[ "$AIRBOB_REGION" == ap-northeast-2 ]] \
  || fail 'snapshot production is pinned to ap-northeast-2'
[[ "$AIRBOB_AWS_ACCOUNT_ID" == 942632789808 ]] \
  || fail 'active account must match the Airbob foundation account'
[[ "$AIRBOB_DATASET_DB_NAME" == airbobdb ]] || fail 'database name must be airbobdb'
[[ "$AIRBOB_DATASET_DB_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}$ ]] \
  || fail 'database host is invalid'
[[ "$AIRBOB_DATASET_DB_PORT" =~ ^[0-9]{1,5}$ ]] \
  || fail 'database port is invalid'
((10#$AIRBOB_DATASET_DB_PORT >= 1 && 10#$AIRBOB_DATASET_DB_PORT <= 65535)) \
  || fail 'database port is invalid'
[[ "$AIRBOB_DATASET_DB_USER" =~ ^[a-zA-Z][a-zA-Z0-9_]{0,31}$ ]] \
  || fail 'database user is invalid'
[[ "${AIRBOB_DATASET_DB_QUIESCED:-}" == true ]] \
  || fail 'AIRBOB_DATASET_DB_QUIESCED=true is required for the writer-free restored database'
[[ "${AIRBOB_DATASET_ES_QUIESCED:-}" == true ]] \
  || fail 'AIRBOB_DATASET_ES_QUIESCED=true is required after all index writers are stopped'
[[ "$AIRBOB_DATASET_ES_URL" =~ ^http://(127\.0\.0\.1|localhost):[0-9]{1,5}$ ]] \
  || fail 'Elasticsearch producer URL must be an explicit local HTTP endpoint'
[[ "$AIRBOB_DATASET_ES_CONTAINER" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$ ]] \
  || fail 'Elasticsearch container name is invalid'
[[ "$dataset_release" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ ]] \
  || fail 'dataset release must be a lowercase safe name'

[[ -d "$etl_release_dir" && ! -L "$etl_release_dir" ]] \
  || fail 'ETL release directory is missing or unsafe'
[[ -f "$attestation_file" && ! -L "$attestation_file" ]] \
  || fail 'dataset attestation is missing or unsafe'
[[ -f "$image_release_file" && ! -L "$image_release_file" ]] \
  || fail 'infrastructure image release is missing or unsafe'
etl_release_dir=$(CDPATH= cd -P -- "$etl_release_dir" && pwd -P)
attestation_file=$(CDPATH= cd -P -- "$(dirname -- "$attestation_file")" && pwd -P)/$(basename -- "$attestation_file")
image_release_file=$(CDPATH= cd -P -- "$(dirname -- "$image_release_file")" && pwd -P)/$(basename -- "$image_release_file")
snapshot_reference_output=$(resolve_output_path "$snapshot_reference_output")
receipt_output=$(resolve_output_path "$receipt_output")
[[ "$snapshot_reference_output" != "$receipt_output" ]] \
  || fail 'snapshot reference and receipt outputs must be different files'
[[ ! -e "$snapshot_reference_output" && ! -L "$snapshot_reference_output" ]] \
  || fail 'snapshot reference output already exists'
[[ ! -e "$receipt_output" && ! -L "$receipt_output" ]] \
  || fail 'snapshot receipt output already exists'
case "$snapshot_reference_output" in "$etl_release_dir"/*) fail 'snapshot output must be outside the ETL release' ;; esac
case "$receipt_output" in "$etl_release_dir"/*) fail 'snapshot output must be outside the ETL release' ;; esac

source_files=(
  PROVENANCE.txt
  SHA256SUMS
  airbob-production-seed.sql.gz
  backend-migrations.sha256
  benchmark-fixture.json
  database-fingerprint.tsv
  etl-code.sha256
  release-metadata.txt
  source.sha256
  traffic-v1.json
)
checksummed_files=(
  PROVENANCE.txt
  airbob-production-seed.sql.gz
  backend-migrations.sha256
  benchmark-fixture.json
  database-fingerprint.tsv
  etl-code.sha256
  release-metadata.txt
  source.sha256
  traffic-v1.json
)

verify_source_release() {
  local expected_inventory actual_inventory checksum_line file_name expected_sha actual_sha
  expected_inventory=$(printf '%s\n' "${source_files[@]}")
  actual_inventory=$(find "$etl_release_dir" -mindepth 1 -maxdepth 1 -exec basename {} \; | sort)
  [[ "$actual_inventory" == "$expected_inventory" ]] \
    || fail 'ETL source release inventory does not match the exact contract'
  for file_name in "${source_files[@]}"; do
    [[ -f "$etl_release_dir/$file_name" && ! -L "$etl_release_dir/$file_name" ]] \
      || fail 'ETL source release contains a missing or unsafe file'
  done
  gzip -t "$etl_release_dir/airbob-production-seed.sql.gz" >/dev/null 2>&1 \
    || fail 'ETL database dump is not a valid gzip stream'
  [[ "$(wc -l < "$etl_release_dir/SHA256SUMS" | tr -d '[:space:]')" == 9 ]] \
    || fail 'ETL checksum inventory must contain exactly nine entries'
  local line_number=0
  while IFS= read -r checksum_line; do
    ((line_number += 1))
    [[ "$checksum_line" =~ ^([0-9a-f]{64})[\ ][\ ]([a-zA-Z0-9._-]+)$ ]] \
      || fail 'ETL checksum inventory contains a malformed entry'
    file_name=${BASH_REMATCH[2]}
    [[ "$file_name" == "${checksummed_files[$((line_number - 1))]}" ]] \
      || fail 'ETL checksum entries are missing, duplicated, or out of order'
    expected_sha=${BASH_REMATCH[1]}
    actual_sha=$(sha256_file "$etl_release_dir/$file_name")
    [[ "$actual_sha" == "$expected_sha" ]] || fail 'ETL source artifact checksum mismatch'
  done < "$etl_release_dir/SHA256SUMS"
}

verify_source_release
source_payload_sha=$(sha256_file "$etl_release_dir/SHA256SUMS")
source_dump_sha=$(awk '$2 == "airbob-production-seed.sql.gz" { print $1 }' "$etl_release_dir/SHA256SUMS")
source_database_fingerprint_sha=$(awk '$2 == "database-fingerprint.tsv" { print $1 }' "$etl_release_dir/SHA256SUMS")
attestation_sha=$(sha256_file "$attestation_file")
image_release_sha=$(sha256_file "$image_release_file")

expected_metadata_keys=$(printf '%s\n' \
  format release_id dump manifest traffic_manifest traffic_manifest_sha256 \
  traffic_dataset_version traffic_dataset_run_id traffic_flyway_version \
  traffic_migration_digest fingerprint required_rows recovery)
actual_metadata_keys=$(awk -F= 'NF >= 2 { print $1 }' "$etl_release_dir/release-metadata.txt")
[[ "$actual_metadata_keys" == "$expected_metadata_keys" ]] \
  || fail 'ETL release metadata keys or ordering are invalid'
dataset_run_id=$(awk -F= '$1 == "traffic_dataset_run_id" { print substr($0, index($0, "=") + 1) }' \
  "$etl_release_dir/release-metadata.txt")
[[ "$dataset_run_id" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$ ]] \
  || fail 'ETL traffic dataset run id is not canonical'
jq -se --arg runId "$dataset_run_id" '
  length == 1 and
  (.[0] |
    .datasetVersion == "traffic-v1" and
    .datasetRunId == $runId
  )
' "$etl_release_dir/traffic-v1.json" >/dev/null \
  || fail 'ETL traffic manifest does not bind the dataset run id'

jq -se \
  --arg sourcePayload "$source_payload_sha" \
  --arg sourceDump "$source_dump_sha" \
  --arg sourceFingerprint "$source_database_fingerprint_sha" '
  def sha256: type == "string" and test("^[0-9a-f]{64}$");
  length == 1 and
  (.[0] |
    (keys | sort) == ([
      "capturedAt", "expectedTableRows", "flywayHistoryRows", "flywayVersion",
      "migrationChecksumSha256", "outboxState", "schemaFingerprintSha256",
      "schemaVersion", "sourceDatabaseFingerprintSha256", "sourceDumpSha256",
      "restoredDumpSha256", "databaseRestoreMethod",
      "sourceReleasePayloadSha256", "sourceEtlCommit", "databaseServerUuid",
      "verifierContractInventorySha256", "databaseFingerprintSubsetSha256"
    ] | sort) and
    .schemaVersion == 3 and
    .databaseRestoreMethod == "gzip-to-empty-airbobdb-v1" and
    .sourceReleasePayloadSha256 == $sourcePayload and
    .sourceDumpSha256 == $sourceDump and
    .restoredDumpSha256 == $sourceDump and
    .restoredDumpSha256 == .sourceDumpSha256 and
    .sourceDatabaseFingerprintSha256 == $sourceFingerprint and
    (.sourceEtlCommit | type == "string" and test("^[0-9a-f]{40}$")) and
    (.databaseServerUuid | type == "string" and
      test("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) and
    (.verifierContractInventorySha256 | sha256) and
    (.databaseFingerprintSubsetSha256 | sha256) and
    .flywayVersion == "17" and
    .flywayHistoryRows == 17 and
    (.migrationChecksumSha256 | sha256) and
    (.schemaFingerprintSha256 | sha256) and
    .outboxState == "empty" and
    (.expectedTableRows | type == "object" and length > 0) and
    .expectedTableRows.flyway_schema_history == 17 and
    .expectedTableRows.outbox == 0 and
    (.expectedTableRows.accommodation | type == "number" and floor == . and . >= 0) and
    all(.expectedTableRows | to_entries[];
      (.key | test("^[a-z][a-z0-9_]{0,63}$")) and
      (.value | type == "number" and floor == . and . >= 0)
    ) and
    (.capturedAt | fromdateiso8601 | type == "number")
  )
' "$attestation_file" >/dev/null || fail 'dataset attestation does not bind the exact V17 ETL release'

expected_image_keys='["DEBEZIUM_IMAGE","ELASTICSEARCH_EXPORTER_IMAGE","ELASTICSEARCH_IMAGE","GRAFANA_IMAGE","KAFKA_IMAGE","NODE_EXPORTER_IMAGE","PROMETHEUS_IMAGE","REDIS_EXPORTER_IMAGE","REDIS_IMAGE"]'
jq -se \
  --arg account "$AIRBOB_AWS_ACCOUNT_ID" \
  --arg region "$AIRBOB_REGION" \
  --argjson expectedKeys "$expected_image_keys" '
  def image($repository):
    type == "string" and
    test("^" + $account + "\\.dkr\\.ecr\\." + $region + "\\.amazonaws\\.com/" + $repository + "@sha256:[0-9a-f]{64}$");
  length == 1 and
  (.[0] |
    (keys | sort) == (["gitCommit", "images", "kind", "schemaVersion"] | sort) and
    .schemaVersion == 1 and .kind == "infra" and
    (.gitCommit | test("^[0-9a-f]{40}$")) and
    (.images | keys | sort) == ($expectedKeys | sort) and
    (.images.DEBEZIUM_IMAGE | image("airbob-infra/debezium")) and
    (.images.ELASTICSEARCH_EXPORTER_IMAGE | image("airbob-infra/elasticsearch-exporter")) and
    (.images.ELASTICSEARCH_IMAGE | image("airbob-infra/elasticsearch")) and
    (.images.GRAFANA_IMAGE | image("airbob-infra/grafana")) and
    (.images.KAFKA_IMAGE | image("airbob-infra/kafka")) and
    (.images.NODE_EXPORTER_IMAGE | image("airbob-infra/node-exporter")) and
    (.images.PROMETHEUS_IMAGE | image("airbob-infra/prometheus")) and
    (.images.REDIS_EXPORTER_IMAGE | image("airbob-infra/redis-exporter")) and
    (.images.REDIS_IMAGE | image("airbob-infra/redis"))
  )
' "$image_release_file" >/dev/null || fail 'infrastructure image release violates the exact producer contract'
elasticsearch_image_ref=$(jq -r '.images.ELASTICSEARCH_IMAGE' "$image_release_file")
image_digest=${elasticsearch_image_ref##*@}

caller_identity=$(aws sts get-caller-identity --output json --region "$AIRBOB_REGION") \
  || fail 'cannot resolve the active AWS caller'
jq -e \
  --arg account "$AIRBOB_AWS_ACCOUNT_ID" \
  --arg arn "arn:aws:sts::$AIRBOB_AWS_ACCOUNT_ID:assumed-role/airbob-dataset-publisher/" '
  .Account == $account and (.Arn | startswith($arn))
' <<<"$caller_identity" >/dev/null \
  || fail 'snapshot production requires assumed-role/airbob-dataset-publisher credentials'
caller_arn=$(jq -r '.Arn' <<<"$caller_identity")

credentials_json=$(aws configure export-credentials --format process) \
  || fail 'cannot export the active temporary AWS credentials'
jq -e '
  .Version == 1 and
  (.AccessKeyId | type == "string" and test("^ASIA[A-Z0-9]{16}$")) and
  (.SecretAccessKey | type == "string" and length > 0) and
  (.SessionToken | type == "string" and length > 0) and
  (.Expiration | type == "string")
' <<<"$credentials_json" >/dev/null \
  || fail 'active AWS credentials are not temporary session credentials'
expiration_epoch=$(jq -er '.Expiration | sub("\\+00:00$"; "Z") | fromdateiso8601' <<<"$credentials_json") \
  || fail 'temporary AWS credential expiration is invalid'
current_epoch=$(date -u '+%s')
[[ "$expiration_epoch" =~ ^[0-9]+$ && "$expiration_epoch" -ge $((current_epoch + CREDENTIAL_HEADROOM_SECONDS)) ]] \
  || fail 'temporary AWS credentials do not have one hour of expiry headroom'
credential_remaining_seconds=$((expiration_epoch - current_epoch))
credential_watchdog_seconds=$((credential_remaining_seconds - CREDENTIAL_SHUTDOWN_HEADROOM_SECONDS))
lease_deadline_seconds=$((credential_remaining_seconds + LEASE_DEADLINE_GRACE_SECONDS))
[[ "$credential_watchdog_seconds" -gt 0 && "$lease_deadline_seconds" -le 9000 ]] \
  || fail 'temporary AWS credential lifetime is outside the snapshot lease boundary'
access_key=$(jq -r '.AccessKeyId' <<<"$credentials_json")
secret_key=$(jq -r '.SecretAccessKey' <<<"$credentials_json")
session_token=$(jq -r '.SessionToken' <<<"$credentials_json")
credentials_json=''

caller_session=${caller_arn##*/}
lease_owner="dataset-publisher/$caller_session"
lease_run_id="snapshot-${dataset_run_id:0:8}-${dataset_run_id##*-}"
lease_lock_id="airbob-dataset-snapshot/$dataset_release"
[[ "$lease_owner" =~ ^[A-Za-z0-9._:@/-]{3,128}$ ]] \
  || fail 'derived dataset snapshot lease owner is invalid'
[[ "$lease_run_id" =~ ^snapshot-[0-9]{8}-[0-9a-f]{8}$ ]] \
  || fail 'derived dataset snapshot lease run id is invalid'

dataset_bucket="airbob-performance-lab-dataset-$AIRBOB_AWS_ACCOUNT_ID"
base_path="elasticsearch/releases/$dataset_release"
seal_key="elasticsearch/seals/$dataset_release.json"
snapshot_name="airbob-$dataset_release"
temporary_index="airbob-verify-$dataset_release"
[[ "$snapshot_name" =~ ^[a-z0-9._-]+$ && "$temporary_index" =~ ^[a-z0-9._-]+$ ]] \
  || fail 'derived snapshot names are unsafe'

work_dir=''
reference_temp=''
receipt_temp=''
reference_linked=false
receipt_linked=false
credentials_installed=false
source_frozen=false
writer_registered=false
reader_registered=false
temporary_index_created=false
active_scroll_id=''
runtime_cleaned=false
lease_acquired=false
lease_token=''
lease_heartbeat_pid=''
credential_watchdog_pid=''
lease_guard_failure_file=''
outputs_validated=false
trap on_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-es-snapshot-producer.XXXXXX") \
  || fail 'cannot create a private snapshot workspace'
lease_guard_failure_file="$work_dir/lease-guard.failed"

collect_inventory() {
  local output=$1
  local raw="$output.raw"
  local page="$output.page"
  local key_marker=''
  local version_marker=''
  local truncated next_key next_version
  : > "$raw"
  while :; do
    local -a arguments=(
      s3api list-object-versions
      --bucket "$dataset_bucket"
      --prefix "$base_path/"
      --output json
      --no-paginate
      --no-cli-pager
      --region "$AIRBOB_REGION"
    )
    if [[ -n "$key_marker" ]]; then
      arguments+=(--key-marker "$key_marker")
      [[ -z "$version_marker" ]] || arguments+=(--version-id-marker "$version_marker")
    fi
    aws "${arguments[@]}" > "$page" || fail 'cannot inventory the snapshot repository prefix'
    jq -e --arg prefix "$base_path/" '
      (.IsTruncated | type == "boolean") and
      ((.Versions // []) | type == "array") and
      ((.DeleteMarkers // []) | type == "array") and
      all((.Versions // [])[];
        (.Key | type == "string" and startswith($prefix)) and
        (.VersionId | type == "string" and length > 0) and
        (.IsLatest | type == "boolean") and
        (.Size | type == "number" and floor == . and . >= 0) and
        (.ETag | type == "string") and
        ((.ChecksumAlgorithm // []) | type == "array" and all(.[]; type == "string"))
      ) and
      all((.DeleteMarkers // [])[];
        (.Key | type == "string" and startswith($prefix)) and
        (.VersionId | type == "string" and length > 0) and
        (.IsLatest | type == "boolean")
      )
    ' "$page" >/dev/null || fail 'S3 returned an unsafe snapshot inventory page'
    jq -c '
      (.Versions // [])[] |
      {
        kind: "version",
        key: .Key,
        versionId: .VersionId,
        isLatest: .IsLatest,
        size: .Size,
        eTag: .ETag,
        checksumAlgorithms: ((.ChecksumAlgorithm // []) | sort)
      }
    ' "$page" >> "$raw"
    jq -c '
      (.DeleteMarkers // [])[] |
      {
        kind: "delete-marker",
        key: .Key,
        versionId: .VersionId,
        isLatest: .IsLatest
      }
    ' "$page" >> "$raw"
    truncated=$(jq -r '.IsTruncated' "$page")
    [[ "$truncated" == true ]] || break
    next_key=$(jq -er '.NextKeyMarker | select(type == "string" and length > 0)' "$page") \
      || fail 'truncated S3 inventory omitted the next key marker'
    next_version=$(jq -r '.NextVersionIdMarker // ""' "$page")
    [[ -z "$next_version" || "$next_version" =~ ^[^[:cntrl:]]+$ ]] \
      || fail 'truncated S3 inventory returned an invalid next version marker'
    [[ "$next_key|$next_version" != "$key_marker|$version_marker" ]] \
      || fail 'S3 snapshot inventory pagination did not advance'
    key_marker=$next_key
    version_marker=$next_version
  done
  jq -cS -s 'sort_by(.key, .kind, .versionId)[]' "$raw" > "$output"
  rm -f "$raw" "$page"
}

lease_output=$(lease_command acquire \
  "$LEASE_TABLE" "$lease_lock_id" "$lease_owner" "$lease_run_id" \
  dataset-snapshot "$LEASE_HEARTBEAT_TTL_SECONDS" "$lease_deadline_seconds") \
  || fail 'another producer owns the dataset snapshot release lease'
[[ "$lease_output" =~ ^fencing_token=([1-9][0-9]*)$ ]] \
  || fail 'dataset snapshot lease returned an invalid fencing token'
lease_token=${BASH_REMATCH[1]}
lease_acquired=true
start_lease_guards
assert_snapshot_lease || fail 'dataset snapshot lease was lost before repository inspection'

lineage_receipt=$(AIRBOB_DATASET_DB_PASSWORD="$database_password" \
  "$lineage_verifier" "$etl_release_dir") \
  || fail 'live database does not match the attested ETL release'
jq -se --slurpfile attestation "$attestation_file" '
  length == 1 and
  (.[0] | keys | sort) == ([
    "schemaVersion", "sourceEtlCommit", "databaseServerUuid",
    "verifierContractInventorySha256", "databaseFingerprintSubsetSha256"
  ] | sort) and
  .[0].schemaVersion == 1 and
  .[0].sourceEtlCommit == $attestation[0].sourceEtlCommit and
  .[0].databaseServerUuid == $attestation[0].databaseServerUuid and
  .[0].verifierContractInventorySha256 == $attestation[0].verifierContractInventorySha256 and
  .[0].databaseFingerprintSubsetSha256 == $attestation[0].databaseFingerprintSubsetSha256
' <<<"$lineage_receipt" >/dev/null \
  || fail 'live database lineage differs from the dataset attestation'
database_server_uuid=$(jq -r '.databaseServerUuid' <<<"$lineage_receipt")
lineage_receipt=''

pre_inventory="$work_dir/pre-inventory.jsonl"
collect_inventory "$pre_inventory"
[[ ! -s "$pre_inventory" ]] \
  || fail 'Elasticsearch snapshot repository prefix is not entirely empty'

container_contract=$(docker inspect --format '{{.State.Running}}|{{.Config.Image}}' \
  "$AIRBOB_DATASET_ES_CONTAINER") || fail 'cannot inspect the local Elasticsearch container'
[[ "$container_contract" == "true|$elasticsearch_image_ref" ]] \
  || fail 'local Elasticsearch container is not running the selected immutable image'
container_environment=$(docker inspect --format '{{json .Config.Env}}' \
  "$AIRBOB_DATASET_ES_CONTAINER") || fail 'cannot inspect the local Elasticsearch environment'
jq -e --arg expected "s3.client.$S3_CLIENT.region=$AIRBOB_REGION" '
  type == "array" and index($expected) != null
' <<<"$container_environment" >/dev/null \
  || fail 'local Elasticsearch does not pin the dataset producer S3 client region'
repo_digests=$(docker image inspect --format '{{json .RepoDigests}}' "$elasticsearch_image_ref") \
  || fail 'cannot inspect the local Elasticsearch image digest'
jq -e --arg reference "$elasticsearch_image_ref" 'index($reference) != null' <<<"$repo_digests" >/dev/null \
  || fail 'local Elasticsearch image does not retain the selected repository digest'

elasticsearch_info=$(curl_json GET /) || fail 'local Elasticsearch is unavailable'
[[ "$(jq -r '.version.number // empty' <<<"$elasticsearch_info")" == "$ELASTICSEARCH_VERSION" ]] \
  || fail 'local Elasticsearch version must be 8.18.8'
plugin_info=$(curl_json GET '/_nodes/plugins?filter_path=nodes.*.modules.name,nodes.*.plugins.name') \
  || fail 'cannot inspect local Elasticsearch plugins'
jq -e '
  (.nodes | type == "object" and length > 0) and
  all(.nodes[];
    ([((.modules // [])[] | .name), ((.plugins // [])[] | .name)] | index("repository-s3") != null) and
    ([((.modules // [])[] | .name), ((.plugins // [])[] | .name)] | index("analysis-nori") != null)
  )
' <<<"$plugin_info" >/dev/null || fail 'local Elasticsearch is missing repository-s3 or analysis-nori'
cluster_health=$(curl_json GET '/_cluster/health?wait_for_no_relocating_shards=true&wait_for_no_initializing_shards=true&timeout=30s') \
  || fail 'local Elasticsearch did not reach a stable cluster state'
jq -e '.timed_out == false and .relocating_shards == 0 and .initializing_shards == 0' \
  <<<"$cluster_health" >/dev/null || fail 'local Elasticsearch cluster is not quiesced'

source_settings=$(curl_json GET "/$SOURCE_INDEX/_settings?flat_settings=true&filter_path=*.settings.index.blocks.write") \
  || fail 'cannot inspect the source index write block'
jq -e --arg index "$SOURCE_INDEX" \
  '((.[$index].settings["index.blocks.write"] // "false") == "false")' \
  <<<"$source_settings" >/dev/null \
  || fail 'source index already has a caller-owned write block'
for repository_name in "$WRITER_REPOSITORY" "$READER_REPOSITORY"; do
  repository_status=$(curl --silent \
    --connect-timeout "$ELASTICSEARCH_CONNECT_TIMEOUT_SECONDS" \
    --max-time "$ELASTICSEARCH_REQUEST_TIMEOUT_SECONDS" \
    --output /dev/null --write-out '%{http_code}' \
    "$AIRBOB_DATASET_ES_URL/_snapshot/$repository_name") \
    || fail 'cannot inspect existing Elasticsearch snapshot repositories'
  [[ "$repository_status" == 404 ]] \
    || fail 'an Elasticsearch snapshot repository name is already caller-owned'
done
keystore_entries=$(docker exec "$AIRBOB_DATASET_ES_CONTAINER" \
  /usr/share/elasticsearch/bin/elasticsearch-keystore list) \
  || fail 'cannot inspect the local Elasticsearch keystore'
for setting in access_key secret_key session_token; do
  setting_name="s3.client.$S3_CLIENT.$setting"
  case $'\n'"$keystore_entries"$'\n' in
    *$'\n'"$setting_name"$'\n'*) fail 'temporary S3 client keystore setting is already caller-owned' ;;
  esac
done

mysql_exec() {
  MYSQL_PWD="$database_password" mysql \
    --protocol=TCP \
    --host="$AIRBOB_DATASET_DB_HOST" \
    --port="$AIRBOB_DATASET_DB_PORT" \
    --user="$AIRBOB_DATASET_DB_USER" \
    --batch --raw --skip-column-names \
    "$AIRBOB_DATASET_DB_NAME" "$@"
}

verify_database_state() {
  local expected_rows=$1
  local server_state actual_uuid read_only super_read_only extra_field actual_rows outbox_rows
  server_state=$(mysql_exec \
    --execute='SELECT LOWER(@@server_uuid), @@GLOBAL.read_only, @@GLOBAL.super_read_only') \
    || fail 'cannot inspect local MySQL identity and read-only state'
  IFS=$'\t' read -r actual_uuid read_only super_read_only extra_field <<<"$server_state"
  [[ -z "${extra_field:-}" \
    && "$actual_uuid" == "$database_server_uuid" \
    && "$read_only" == 1 \
    && "$super_read_only" == 1 ]] \
    || fail 'local MySQL identity or read-only state drifted from the attestation'
  actual_rows=$(mysql_exec --execute='SELECT COUNT(*) FROM accommodation') \
    || fail 'cannot inspect local accommodation row count'
  [[ "$actual_rows" == "$expected_rows" ]] \
    || fail 'local accommodation row count drifted from the attestation'
  outbox_rows=$(mysql_exec --execute='SELECT COUNT(*) FROM outbox') \
    || fail 'cannot inspect the local outbox'
  [[ "$outbox_rows" == 0 ]] || fail 'local outbox must remain empty during snapshot production'
}

capture_database_ids() {
  local output=$1
  mysql_exec --execute="SELECT id FROM accommodation WHERE status = 'PUBLISHED' ORDER BY id" > "$output" \
    || fail 'cannot read published accommodation ids from local MySQL'
  awk 'NF != 1 || $1 !~ /^[1-9][0-9]*$/ { exit 1 }' "$output" \
    || fail 'database accommodation id stream is invalid'
  sort -n "$output" -o "$output"
}

capture_search_fingerprint() {
  local index=$1
  local label=$2
  local count_response mapping_response page_hits scroll_id scroll_body clear_body
  local count_file="$work_dir/$label.count"
  local mapping_file="$work_dir/$label.mapping.json"
  local ids_file="$work_dir/$label.ids.txt"
  local content_file="$work_dir/$label.content.jsonl"
  local page_file="$work_dir/$label.page.json"

  count_response=$(curl_json GET "/$index/_count") || fail 'cannot count Elasticsearch snapshot documents'
  jq -e '.count | type == "number" and floor == . and . >= 0' <<<"$count_response" >/dev/null \
    || fail 'Elasticsearch returned an invalid document count'
  jq -e '((._shards.failed // 0) == 0)' <<<"$count_response" >/dev/null \
    || fail 'Elasticsearch count request had shard failures'
  jq -r '.count' <<<"$count_response" > "$count_file"

  mapping_response=$(curl_json GET "/$index/_mapping") || fail 'cannot read Elasticsearch mapping'
  jq -S --arg index "$index" '
    (.[$index].mappings // null) as $mappings |
    if ($mappings | type) == "object" then $mappings else error("missing mapping") end
  ' <<<"$mapping_response" > "$mapping_file" || fail 'Elasticsearch mapping response is invalid'

  : > "$ids_file"
  : > "$content_file"
  curl_json POST "/$index/_search?scroll=2m" \
    '{"size":1000,"sort":["_doc"],"_source":true}' > "$page_file" \
    || fail 'cannot start Elasticsearch fingerprint scroll'
  while :; do
    active_scroll_id=$(jq -r '._scroll_id // empty' "$page_file")
    jq -e '
      .timed_out == false and ._shards.failed == 0 and
      (._scroll_id | type == "string" and length > 0) and
      (.hits.hits | type == "array") and
      all(.hits.hits[];
        (._source | type == "object") and
        (._source.accommodationId | type == "number" and floor == . and . > 0)
      )
    ' "$page_file" >/dev/null || fail 'Elasticsearch scroll page is invalid'
    page_hits=$(jq -r '.hits.hits | length' "$page_file")
    scroll_id=$(jq -r '._scroll_id' "$page_file")
    active_scroll_id=$scroll_id
    [[ "$page_hits" -gt 0 ]] || break
    jq -r '.hits.hits[]._source.accommodationId' "$page_file" >> "$ids_file"
    jq -S -c '.hits.hits[]._source' "$page_file" >> "$content_file"
    scroll_body=$(jq -cn --arg scrollId "$scroll_id" '{scroll:"2m",scroll_id:$scrollId}')
    curl_json POST '/_search/scroll' "$scroll_body" > "$page_file.next" \
      || fail 'cannot continue Elasticsearch fingerprint scroll'
    mv "$page_file.next" "$page_file"
  done
  clear_body=$(jq -cn --arg scrollId "$scroll_id" '{scroll_id:[$scrollId]}')
  curl_json DELETE '/_search/scroll' "$clear_body" >/dev/null \
    || fail 'cannot clear Elasticsearch fingerprint scroll'
  active_scroll_id=''

  awk 'NF != 1 || $1 !~ /^[1-9][0-9]*$/ { exit 1 }' "$ids_file" \
    || fail 'Elasticsearch accommodation id stream is invalid'
  sort -n "$ids_file" -o "$ids_file"
  sort "$content_file" -o "$content_file"
  [[ "$(wc -l < "$ids_file" | tr -d '[:space:]')" == "$(cat "$count_file")" ]] \
    || fail 'Elasticsearch id count does not match the index count'

  printf '%s\n' "$(sha256_file "$mapping_file")" > "$work_dir/$label.mapping.sha256"
  printf '%s\n' "$(sha256_file "$ids_file")" > "$work_dir/$label.ids.sha256"
  printf '%s\n' "$(sha256_file "$content_file")" > "$work_dir/$label.content.sha256"
}

expected_accommodation_rows=$(jq -r '.expectedTableRows.accommodation' "$attestation_file")
verify_database_state "$expected_accommodation_rows"
database_ids_pre="$work_dir/database-ids.pre.txt"
capture_database_ids "$database_ids_pre"
database_ids_pre_sha=$(sha256_file "$database_ids_pre")

source_frozen=true
set_source_write_block true || fail 'cannot freeze the source Elasticsearch index'
capture_search_fingerprint "$SOURCE_INDEX" source-pre
source_document_count=$(cat "$work_dir/source-pre.count")
source_mapping_sha=$(cat "$work_dir/source-pre.mapping.sha256")
source_es_ids_sha=$(cat "$work_dir/source-pre.ids.sha256")
source_content_sha=$(cat "$work_dir/source-pre.content.sha256")
[[ "$database_ids_pre_sha" == "$source_es_ids_sha" ]] \
  || fail 'local database and Elasticsearch accommodation ids do not match'
[[ "$(wc -l < "$database_ids_pre" | tr -d '[:space:]')" == "$source_document_count" ]] \
  || fail 'local database and Elasticsearch published accommodation counts do not match'

add_keystore_value() {
  local setting=$1
  local value=$2
  printf '%s' "$value" | docker exec -i "$AIRBOB_DATASET_ES_CONTAINER" \
    /usr/share/elasticsearch/bin/elasticsearch-keystore add --stdin --force \
    "s3.client.$S3_CLIENT.$setting" >/dev/null
}
credentials_installed=true
add_keystore_value access_key "$access_key" || fail 'cannot install the temporary S3 access key'
add_keystore_value secret_key "$secret_key" || fail 'cannot install the temporary S3 secret key'
add_keystore_value session_token "$session_token" || fail 'cannot install the temporary S3 session token'
unset access_key secret_key session_token
reload_secure_settings || fail 'Elasticsearch rejected the temporary S3 credentials'

writer_body=$(jq -cn \
  --arg client "$S3_CLIENT" \
  --arg bucket "$dataset_bucket" \
  --arg basePath "$base_path" '
  {
    type:"s3",
    settings:{
      client:$client,
      bucket:$bucket,
      base_path:$basePath,
      compress:true,
      canned_acl:"bucket-owner-full-control",
      server_side_encryption:true,
      readonly:false
    }
  }
')
assert_snapshot_lease || fail 'dataset snapshot lease was lost before writer registration'
writer_registered=true
writer_response=$(curl_json PUT "/_snapshot/$WRITER_REPOSITORY" "$writer_body") \
  || fail 'cannot register the Elasticsearch snapshot writer repository'
jq -e '.acknowledged == true' <<<"$writer_response" >/dev/null \
  || fail 'Elasticsearch did not acknowledge the writer repository'
writer_verification=$(curl_json POST "/_snapshot/$WRITER_REPOSITORY/_verify") \
  || fail 'Elasticsearch could not verify the writer repository'
writer_node_count=$(jq -er '.nodes | length | select(. > 0)' <<<"$writer_verification") \
  || fail 'writer repository verification returned no nodes'

snapshot_body=$(jq -cn \
  --arg index "$SOURCE_INDEX" \
  --arg release "$dataset_release" \
  --arg runId "$dataset_run_id" \
  --arg sourcePayloadSha256 "$source_payload_sha" \
  --arg imageDigest "$image_digest" '
  {
    indices:$index,
    ignore_unavailable:false,
    include_global_state:false,
    feature_states:["none"],
    partial:false,
    metadata:{
      datasetRelease:$release,
      datasetRunId:$runId,
      sourceReleasePayloadSha256:$sourcePayloadSha256,
      imageDigest:$imageDigest
    }
  }
')
assert_snapshot_lease || fail 'dataset snapshot lease was lost before snapshot creation'
snapshot_request_timeout=$(remaining_elasticsearch_request_budget) \
  || fail 'temporary credential budget is too small for snapshot creation and cleanup'
snapshot_create=$(curl_json PUT \
  "/_snapshot/$WRITER_REPOSITORY/$snapshot_name?wait_for_completion=true" \
  "$snapshot_body" "$snapshot_request_timeout") || fail 'Elasticsearch snapshot creation failed'
jq -e \
  --arg snapshot "$snapshot_name" \
  --arg index "$SOURCE_INDEX" \
  --arg version "$ELASTICSEARCH_VERSION" \
  --arg release "$dataset_release" \
  --arg runId "$dataset_run_id" \
  --arg sourcePayloadSha256 "$source_payload_sha" \
  --arg imageDigest "$image_digest" '
  .snapshot.snapshot == $snapshot and
  (.snapshot.uuid | type == "string" and length > 0) and
  .snapshot.state == "SUCCESS" and
  .snapshot.version == $version and
  .snapshot.indices == [$index] and
  .snapshot.include_global_state == false and
  (.snapshot.feature_states // []) == [] and
  .snapshot.metadata == {
    datasetRelease:$release,
    datasetRunId:$runId,
    sourceReleasePayloadSha256:$sourcePayloadSha256,
    imageDigest:$imageDigest
  } and
  .snapshot.shards.total > 0 and
  .snapshot.shards.successful == .snapshot.shards.total and
  .snapshot.shards.failed == 0
' <<<"$snapshot_create" >/dev/null || fail 'Elasticsearch snapshot did not complete exactly and successfully'

curl_json DELETE "/_snapshot/$WRITER_REPOSITORY" >/dev/null \
  || fail 'cannot unregister the snapshot writer repository'
writer_registered=false

reader_body=$(jq -cn \
  --arg client "$S3_CLIENT" \
  --arg bucket "$dataset_bucket" \
  --arg basePath "$base_path" '
  {
    type:"s3",
    settings:{
      client:$client,
      bucket:$bucket,
      base_path:$basePath,
      readonly:true
    }
  }
')
reader_registered=true
reader_response=$(curl_json PUT "/_snapshot/$READER_REPOSITORY" "$reader_body") \
  || fail 'cannot register the read-only snapshot repository'
jq -e '.acknowledged == true' <<<"$reader_response" >/dev/null \
  || fail 'Elasticsearch did not acknowledge the read-only repository'
reader_verification=$(curl_json POST "/_snapshot/$READER_REPOSITORY/_verify") \
  || fail 'Elasticsearch could not verify the read-only repository'
verification_node_count=$(jq -er '.nodes | length | select(. > 0)' <<<"$reader_verification") \
  || fail 'read-only repository verification returned no nodes'
[[ "$verification_node_count" == "$writer_node_count" ]] \
  || fail 'writer and read-only repository verification node sets differ'

snapshot_metadata_response=$(curl_json GET "/_snapshot/$READER_REPOSITORY/$snapshot_name") \
  || fail 'cannot read the completed snapshot metadata through the read-only repository'
jq -e \
  --arg snapshot "$snapshot_name" \
  --arg index "$SOURCE_INDEX" \
  --arg version "$ELASTICSEARCH_VERSION" \
  --arg release "$dataset_release" \
  --arg runId "$dataset_run_id" \
  --arg sourcePayloadSha256 "$source_payload_sha" \
  --arg imageDigest "$image_digest" '
  .total == 1 and .remaining == 0 and (.snapshots | length) == 1 and
  .snapshots[0].snapshot == $snapshot and
  (.snapshots[0].uuid | type == "string" and length > 0) and
  .snapshots[0].state == "SUCCESS" and
  .snapshots[0].version == $version and
  .snapshots[0].indices == [$index] and
  .snapshots[0].include_global_state == false and
  (.snapshots[0].feature_states // []) == [] and
  .snapshots[0].metadata == {
    datasetRelease:$release,
    datasetRunId:$runId,
    sourceReleasePayloadSha256:$sourcePayloadSha256,
    imageDigest:$imageDigest
  } and
  .snapshots[0].shards.total > 0 and
  .snapshots[0].shards.successful == .snapshots[0].shards.total and
  .snapshots[0].shards.failed == 0
' <<<"$snapshot_metadata_response" >/dev/null \
  || fail 'read-only snapshot metadata violates the exact contract'
snapshot_metadata_file="$work_dir/snapshot-metadata.json"
jq -cS '.snapshots[0]' <<<"$snapshot_metadata_response" > "$snapshot_metadata_file"
snapshot_metadata_sha=$(sha256_file "$snapshot_metadata_file")
snapshot_uuid=$(jq -r '.snapshots[0].uuid' <<<"$snapshot_metadata_response")
snapshot_version=$(jq -r '.snapshots[0].version' <<<"$snapshot_metadata_response")
snapshot_total_shards=$(jq -r '.snapshots[0].shards.total' <<<"$snapshot_metadata_response")
snapshot_successful_shards=$(jq -r '.snapshots[0].shards.successful' <<<"$snapshot_metadata_response")
snapshot_failed_shards=$(jq -r '.snapshots[0].shards.failed' <<<"$snapshot_metadata_response")

temporary_index_status=$(curl --silent \
  --connect-timeout "$ELASTICSEARCH_CONNECT_TIMEOUT_SECONDS" \
  --max-time "$ELASTICSEARCH_REQUEST_TIMEOUT_SECONDS" \
  --output /dev/null --write-out '%{http_code}' \
  "$AIRBOB_DATASET_ES_URL/$temporary_index") || fail 'cannot probe the temporary restore index'
[[ "$temporary_index_status" == 404 ]] \
  || fail 'temporary snapshot verification index already exists'
restore_body=$(jq -cn \
  --arg source "$SOURCE_INDEX" \
  --arg target "$temporary_index" '
  {
    indices:$source,
    include_global_state:false,
    include_aliases:false,
    feature_states:["none"],
    rename_pattern:("^" + $source + "$"),
    rename_replacement:$target,
    index_settings:{"index.number_of_replicas":0,"index.blocks.write":false}
  }
')
temporary_index_created=true
restore_request_timeout=$(remaining_elasticsearch_request_budget) \
  || fail 'temporary credential budget is too small for snapshot restore and cleanup'
restore_response=$(curl_json POST \
  "/_snapshot/$READER_REPOSITORY/$snapshot_name/_restore?wait_for_completion=true" \
  "$restore_body" "$restore_request_timeout") \
  || fail 'cannot restore the completed snapshot for verification'
jq -e --arg index "$temporary_index" '
  .snapshot.indices == [$index] and
  .snapshot.shards.total > 0 and
  .snapshot.shards.successful == .snapshot.shards.total and
  .snapshot.shards.failed == 0
' <<<"$restore_response" >/dev/null || fail 'temporary snapshot restore did not complete exactly'

capture_search_fingerprint "$temporary_index" restored
[[ "$(cat "$work_dir/restored.count")" == "$source_document_count" ]] \
  || fail 'restored snapshot document count differs from the frozen source'
[[ "$(cat "$work_dir/restored.mapping.sha256")" == "$source_mapping_sha" ]] \
  || fail 'restored snapshot mapping differs from the frozen source'
[[ "$(cat "$work_dir/restored.ids.sha256")" == "$source_es_ids_sha" ]] \
  || fail 'restored snapshot ids differ from the frozen source'
[[ "$(cat "$work_dir/restored.content.sha256")" == "$source_content_sha" ]] \
  || fail 'restored snapshot content differs from the frozen source'

verify_database_state "$expected_accommodation_rows"
database_ids_post="$work_dir/database-ids.post.txt"
capture_database_ids "$database_ids_post"
cmp -s "$database_ids_pre" "$database_ids_post" \
  || fail 'database accommodation ids changed during snapshot production'
capture_search_fingerprint "$SOURCE_INDEX" source-post
for fingerprint_part in count mapping.sha256 ids.sha256 content.sha256; do
  cmp -s "$work_dir/source-pre.$fingerprint_part" "$work_dir/source-post.$fingerprint_part" \
    || fail 'source Elasticsearch index changed during snapshot production'
done

snapshot_metadata_second=$(curl_json GET "/_snapshot/$READER_REPOSITORY/$snapshot_name") \
  || fail 'cannot repeat the completed snapshot metadata read'
jq -cS '.snapshots[0]' <<<"$snapshot_metadata_second" > "$work_dir/snapshot-metadata.second.json"
cmp -s "$snapshot_metadata_file" "$work_dir/snapshot-metadata.second.json" \
  || fail 'completed snapshot metadata changed during verification'

post_inventory="$work_dir/post-inventory.jsonl"
post_inventory_second="$work_dir/post-inventory.second.jsonl"
collect_inventory "$post_inventory"
[[ -s "$post_inventory" ]] || fail 'completed snapshot repository has an empty S3 inventory'
collect_inventory "$post_inventory_second"
cmp -s "$post_inventory" "$post_inventory_second" \
  || fail 'snapshot S3 version inventory changed during verification'
assert_snapshot_lease || fail 'dataset snapshot lease was lost during repository verification'
inventory_sha=$(sha256_file "$post_inventory")
inventory_entry_count=$(wc -l < "$post_inventory" | tr -d '[:space:]')
inventory_total_version_bytes=$(jq -s '[.[] | select(.kind == "version") | .size] | add // 0' \
  "$post_inventory")
[[ "$inventory_entry_count" =~ ^[1-9][0-9]*$ && "$inventory_total_version_bytes" =~ ^[0-9]+$ ]] \
  || fail 'snapshot S3 version inventory summary is invalid'

verify_source_release
[[ "$(sha256_file "$etl_release_dir/SHA256SUMS")" == "$source_payload_sha" ]] \
  || fail 'ETL source release changed during snapshot production'
[[ "$(sha256_file "$attestation_file")" == "$attestation_sha" ]] \
  || fail 'dataset attestation changed during snapshot production'
[[ "$(sha256_file "$image_release_file")" == "$image_release_sha" ]] \
  || fail 'infrastructure image release changed during snapshot production'

cleanup_runtime || fail 'local Elasticsearch snapshot resources or credentials could not be cleaned up'
runtime_cleaned=true

created_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
reference_parent=${snapshot_reference_output%/*}
reference_name=${snapshot_reference_output##*/}
receipt_parent=${receipt_output%/*}
receipt_name=${receipt_output##*/}
reference_temp=$(mktemp "$reference_parent/.${reference_name}.XXXXXX") \
  || fail 'cannot create temporary snapshot reference output'
receipt_temp=$(mktemp "$receipt_parent/.${receipt_name}.XXXXXX") \
  || fail 'cannot create temporary snapshot receipt output'

jq -nS \
  --arg repository "$READER_REPOSITORY" \
  --arg bucket "$dataset_bucket" \
  --arg basePath "$base_path" \
  --arg snapshot "$snapshot_name" \
  --arg index "$SOURCE_INDEX" \
  --arg elasticsearchVersion "$ELASTICSEARCH_VERSION" \
  --arg imageDigest "$image_digest" \
  --argjson documentCount "$source_document_count" \
  --arg mappingSha256 "$source_mapping_sha" \
  --arg dbIdsSha256 "$database_ids_pre_sha" \
  --arg esIdsSha256 "$source_es_ids_sha" \
  --arg contentFingerprintSha256 "$source_content_sha" '
  {
    schemaVersion:1,
    repository:$repository,
    bucket:$bucket,
    basePath:$basePath,
    snapshot:$snapshot,
    index:$index,
    elasticsearchVersion:$elasticsearchVersion,
    imageDigest:$imageDigest,
    documentCount:$documentCount,
    mappingSha256:$mappingSha256,
    dbIdsSha256:$dbIdsSha256,
    esIdsSha256:$esIdsSha256,
    contentFingerprintSha256:$contentFingerprintSha256
  }
' > "$reference_temp"
chmod 600 "$reference_temp"
snapshot_reference_sha=$(sha256_file "$reference_temp")

jq -nS \
  --arg datasetRelease "$dataset_release" \
  --arg datasetRunId "$dataset_run_id" \
  --arg sourceReleasePayloadSha256 "$source_payload_sha" \
  --arg createdAt "$created_at" \
  --arg elasticsearchVersion "$ELASTICSEARCH_VERSION" \
  --arg imageDigest "$image_digest" \
  --arg client "$S3_CLIENT" \
  --arg bucket "$dataset_bucket" \
  --arg basePath "$base_path" \
  --arg writerName "$WRITER_REPOSITORY" \
  --arg readerName "$READER_REPOSITORY" \
  --argjson verificationNodeCount "$verification_node_count" \
  --arg inventorySha256 "$inventory_sha" \
  --argjson inventoryEntryCount "$inventory_entry_count" \
  --argjson inventoryTotalVersionBytes "$inventory_total_version_bytes" \
  --arg snapshotName "$snapshot_name" \
  --arg snapshotUuid "$snapshot_uuid" \
  --arg snapshotVersion "$snapshot_version" \
  --argjson snapshotTotalShards "$snapshot_total_shards" \
  --argjson snapshotSuccessfulShards "$snapshot_successful_shards" \
  --argjson snapshotFailedShards "$snapshot_failed_shards" \
  --arg snapshotMetadataSha256 "$snapshot_metadata_sha" \
  --arg snapshotReferenceSha256 "$snapshot_reference_sha" \
  --argjson documentCount "$source_document_count" \
  --arg mappingSha256 "$source_mapping_sha" \
  --arg dbIdsSha256 "$database_ids_pre_sha" \
  --arg esIdsSha256 "$source_es_ids_sha" \
  --arg contentFingerprintSha256 "$source_content_sha" '
  {
    schemaVersion:1,
    datasetRelease:$datasetRelease,
    datasetRunId:$datasetRunId,
    sourceReleasePayloadSha256:$sourceReleasePayloadSha256,
    createdAt:$createdAt,
    producer:{
      elasticsearchVersion:$elasticsearchVersion,
      imageDigest:$imageDigest,
      client:$client
    },
    repository:{
      bucket:$bucket,
      basePath:$basePath,
      writerName:$writerName,
      readerName:$readerName,
      verificationNodeCount:$verificationNodeCount,
      inventory:{
        algorithm:"s3-list-object-versions-v1",
        sha256:$inventorySha256,
        entryCount:$inventoryEntryCount,
        totalVersionBytes:$inventoryTotalVersionBytes
      }
    },
    snapshot:{
      name:$snapshotName,
      uuid:$snapshotUuid,
      state:"SUCCESS",
      version:$snapshotVersion,
      indices:["accommodations"],
      includeGlobalState:false,
      totalShards:$snapshotTotalShards,
      successfulShards:$snapshotSuccessfulShards,
      failedShards:$snapshotFailedShards,
      metadataSha256:$snapshotMetadataSha256
    },
    validation:{
      snapshotReferenceSha256:$snapshotReferenceSha256,
      documentCount:$documentCount,
      mappingSha256:$mappingSha256,
      dbIdsSha256:$dbIdsSha256,
      esIdsSha256:$esIdsSha256,
      contentFingerprintSha256:$contentFingerprintSha256
    }
  }
' > "$receipt_temp"
chmod 600 "$receipt_temp"

jq -e --arg release "$dataset_release" '
  (keys | sort) == (["basePath", "bucket", "contentFingerprintSha256", "dbIdsSha256", "documentCount", "elasticsearchVersion", "esIdsSha256", "imageDigest", "index", "mappingSha256", "repository", "schemaVersion", "snapshot"] | sort) and
  .schemaVersion == 1 and .basePath == ("elasticsearch/releases/" + $release) and
  .dbIdsSha256 == .esIdsSha256
' "$reference_temp" >/dev/null || fail 'generated snapshot reference violates its exact schema'
jq -e --arg referenceSha "$snapshot_reference_sha" '
  (keys | sort) == ([
    "createdAt", "datasetRelease", "datasetRunId", "producer", "repository", "schemaVersion",
    "snapshot", "sourceReleasePayloadSha256", "validation"
  ] | sort) and
  (.datasetRunId | type == "string" and test("^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")) and
  (.sourceReleasePayloadSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
  (.producer | keys | sort) == (["client", "elasticsearchVersion", "imageDigest"] | sort) and
  (.repository | keys | sort) == (["basePath", "bucket", "inventory", "readerName", "verificationNodeCount", "writerName"] | sort) and
  (.repository.inventory | keys | sort) == (["algorithm", "entryCount", "sha256", "totalVersionBytes"] | sort) and
  (.snapshot | keys | sort) == (["failedShards", "includeGlobalState", "indices", "metadataSha256", "name", "state", "successfulShards", "totalShards", "uuid", "version"] | sort) and
  (.validation | keys | sort) == (["contentFingerprintSha256", "dbIdsSha256", "documentCount", "esIdsSha256", "mappingSha256", "snapshotReferenceSha256"] | sort) and
  .validation.snapshotReferenceSha256 == $referenceSha and
  .validation.dbIdsSha256 == .validation.esIdsSha256
' "$receipt_temp" >/dev/null || fail 'generated snapshot receipt violates its exact schema'
snapshot_receipt_sha=$(sha256_file "$receipt_temp")

assert_snapshot_lease || fail 'dataset snapshot lease was lost before publishing local outputs'
ln "$reference_temp" "$snapshot_reference_output" 2>/dev/null \
  || fail 'snapshot reference output already exists or cannot be created'
reference_linked=true
ln "$receipt_temp" "$receipt_output" 2>/dev/null \
  || fail 'snapshot receipt output already exists or cannot be created'
receipt_linked=true
assert_snapshot_lease || fail 'dataset snapshot lease was lost after publishing local outputs'
[[ "$(sha256_file "$snapshot_reference_output")" == "$snapshot_reference_sha" \
  && "$(sha256_file "$receipt_output")" == "$snapshot_receipt_sha" ]] \
  || fail 'linked snapshot outputs changed before release sealing'

seal_file="$work_dir/snapshot-seal.json"
jq -nS \
  --arg datasetRelease "$dataset_release" \
  --arg snapshot "$snapshot_name" \
  --arg snapshotReferenceSha256 "$snapshot_reference_sha" \
  --arg snapshotReceiptSha256 "$snapshot_receipt_sha" \
  --arg createdAt "$created_at" '
  {
    schemaVersion:1,
    datasetRelease:$datasetRelease,
    snapshot:$snapshot,
    snapshotReferenceSha256:$snapshotReferenceSha256,
    snapshotReceiptSha256:$snapshotReceiptSha256,
    createdAt:$createdAt
  }
' > "$seal_file"
chmod 600 "$seal_file"
jq -se \
  --arg datasetRelease "$dataset_release" \
  --arg snapshot "$snapshot_name" \
  --arg snapshotReferenceSha256 "$snapshot_reference_sha" \
  --arg snapshotReceiptSha256 "$snapshot_receipt_sha" \
  --arg createdAt "$created_at" '
  length == 1 and
  (.[0] |
    (keys | sort) == ([
      "createdAt", "datasetRelease", "snapshot",
      "snapshotReceiptSha256", "snapshotReferenceSha256", "schemaVersion"
    ] | sort) and
    .schemaVersion == 1 and
    .datasetRelease == $datasetRelease and
    .snapshot == $snapshot and
    .snapshotReferenceSha256 == $snapshotReferenceSha256 and
    .snapshotReceiptSha256 == $snapshotReceiptSha256 and
    .createdAt == $createdAt and
    (.createdAt | fromdateiso8601 | type == "number"))
' "$seal_file" >/dev/null || fail 'generated snapshot seal violates its exact schema'

assert_snapshot_lease || fail 'dataset snapshot lease was lost before creating the immutable seal'
seal_put_response="$work_dir/snapshot-seal-put.json"
seal_put_confirmed=false
if aws s3api put-object \
  --bucket "$dataset_bucket" \
  --key "$seal_key" \
  --body "$seal_file" \
  --if-none-match '*' \
  --server-side-encryption AES256 \
  --content-type application/json \
  --region "$AIRBOB_REGION" \
  --output json \
  --no-cli-pager > "$seal_put_response" 2>/dev/null; then
  if seal_version_id=$(jq -er '
    .VersionId |
    select(type == "string" and length > 0 and test("^[^[:cntrl:]]+$"))
  ' "$seal_put_response") && jq -e '
    .ServerSideEncryption == "AES256"
  ' "$seal_put_response" >/dev/null; then
    seal_put_confirmed=true
  fi
fi

seal_readback="$work_dir/snapshot-seal.readback.json"
seal_get_response="$work_dir/snapshot-seal-get.json"
verify_snapshot_seal() {
  local expected_version_id=$1
  local actual_version_id
  local -a get_arguments=(
    s3api get-object
    --bucket "$dataset_bucket"
    --key "$seal_key"
  )
  [[ -z "$expected_version_id" ]] || get_arguments+=(--version-id "$expected_version_id")
  get_arguments+=(
    --region "$AIRBOB_REGION"
    --output json
    --no-cli-pager
    "$seal_readback"
  )

  rm -f "$seal_readback" "$seal_get_response"
  aws "${get_arguments[@]}" > "$seal_get_response" 2>/dev/null || return 1
  actual_version_id=$(jq -er '
    .VersionId |
    select(type == "string" and length > 0 and test("^[^[:cntrl:]]+$"))
  ' "$seal_get_response") || return 1
  [[ -z "$expected_version_id" || "$actual_version_id" == "$expected_version_id" ]] || return 1
  jq -e '
    .ServerSideEncryption == "AES256" and
    .ContentType == "application/json"
  ' "$seal_get_response" >/dev/null || return 1
  cmp -s "$seal_file" "$seal_readback"
}

if [[ "$seal_put_confirmed" == true ]]; then
  verify_snapshot_seal "$seal_version_id" \
    || fail 'immutable snapshot seal could not be verified by its created version'
else
  verify_snapshot_seal '' \
    || fail 'ambiguous snapshot seal upload could not be recovered from the exact latest object'
fi
[[ "$(sha256_file "$snapshot_reference_output")" == "$snapshot_reference_sha" \
  && "$(sha256_file "$receipt_output")" == "$snapshot_receipt_sha" ]] \
  || fail 'linked snapshot outputs changed during release sealing'

outputs_validated=true
rm -f "$reference_temp" "$receipt_temp"
reference_temp=''
receipt_temp=''

printf '%s\n' 'Elasticsearch snapshot produced and verified'
