#!/usr/bin/env bash
set -euo pipefail
umask 077

# This imports the verified dump into an empty target, locks that exact server
# read-only, and captures a writer-free database attestation. The verifier SQL
# from the source ETL commit must reproduce the shipped database fingerprint
# subset before any live database facts are recorded.

usage() {
  printf 'usage: %s <etl-release-dir> <output-json>\n' "${0##*/}" >&2
  exit 64
}

fail() {
  printf 'dataset attestation capture failed: %s\n' "$1" >&2
  exit 1
}

[[ "$#" -eq 2 ]] || usage
release_dir=$1
output_json=$2

required_environment=(
  AIRBOB_DATASET_ETL_REPOSITORY
  AIRBOB_DATASET_DB_HOST
  AIRBOB_DATASET_DB_PORT
  AIRBOB_DATASET_DB_USER
  AIRBOB_DATASET_DB_PASSWORD
  AIRBOB_DATASET_DB_RESTORE_USER
  AIRBOB_DATASET_DB_RESTORE_PASSWORD
  AIRBOB_DATASET_DB_NAME
)
for environment_name in "${required_environment[@]}"; do
  [[ -n "${!environment_name:-}" ]] || fail "missing required database environment: $environment_name"
done
database_password=$AIRBOB_DATASET_DB_PASSWORD
restore_user=$AIRBOB_DATASET_DB_RESTORE_USER
restore_password=$AIRBOB_DATASET_DB_RESTORE_PASSWORD
unset AIRBOB_DATASET_DB_PASSWORD AIRBOB_DATASET_DB_RESTORE_PASSWORD

[[ "$AIRBOB_DATASET_DB_NAME" == airbobdb ]] || fail 'database name must be airbobdb'
[[ "$AIRBOB_DATASET_DB_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}$ ]] \
  || fail 'database host is invalid'
[[ "$AIRBOB_DATASET_DB_PORT" =~ ^[0-9]{1,5}$ ]] \
  || fail 'database port is invalid'
((10#$AIRBOB_DATASET_DB_PORT >= 1 && 10#$AIRBOB_DATASET_DB_PORT <= 65535)) \
  || fail 'database port is invalid'
[[ "$AIRBOB_DATASET_DB_USER" =~ ^[a-zA-Z][a-zA-Z0-9_]{0,31}$ ]] \
  || fail 'database user is invalid'
[[ "$restore_user" =~ ^[a-zA-Z][a-zA-Z0-9_]{0,31}$ ]] \
  || fail 'database restore user is invalid'
[[ "$restore_user" != "$AIRBOB_DATASET_DB_USER" \
  && "$restore_password" != "$database_password" ]] \
  || fail 'database restore and attestation credentials must be distinct'
[[ "${AIRBOB_DATASET_DB_QUIESCED:-}" == true ]] \
  || fail 'AIRBOB_DATASET_DB_QUIESCED=true is required for an isolated restore target'

for required_command in jq mysql sort find basename awk sed tail od tr mktemp chmod ln rm wc cmp gzip date; do
  command -v "$required_command" >/dev/null 2>&1 \
    || fail "required command is unavailable: $required_command"
done

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    fail 'a SHA-256 implementation is required'
  fi
}

has_final_newline() {
  local last_byte
  [[ -s "$1" ]] || return 1
  last_byte=$(tail -c 1 "$1" | od -An -t x1 | tr -d '[:space:]')
  [[ "$last_byte" == 0a ]]
}

[[ -d "$release_dir" && ! -L "$release_dir" ]] \
  || fail 'ETL release directory is missing or unsafe'
release_dir=$(cd "$release_dir" && pwd -P)

output_parent=${output_json%/*}
output_name=${output_json##*/}
[[ "$output_parent" != "$output_json" ]] || output_parent=.
[[ -n "$output_name" && "$output_name" != . && "$output_name" != .. ]] \
  || fail 'attestation output path is invalid'
[[ -d "$output_parent" && ! -L "$output_parent" ]] \
  || fail 'attestation output directory is missing or unsafe'
[[ ! -e "$output_json" && ! -L "$output_json" ]] \
  || fail 'attestation output already exists'
output_parent=$(cd "$output_parent" && pwd -P)
case "$output_parent/" in
  "$release_dir/"*) fail 'attestation output must be outside the ETL release directory' ;;
esac
output_json="$output_parent/$output_name"

release_files=(
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
expected_inventory=$(printf '%s\n' "${release_files[@]}")
actual_inventory=$(
  find "$release_dir" -mindepth 1 -maxdepth 1 -exec basename {} \; | LC_ALL=C sort
)
[[ "$actual_inventory" == "$expected_inventory" ]] \
  || fail 'ETL release inventory does not match the exact V17 contract'

for file_name in "${release_files[@]}"; do
  [[ -f "$release_dir/$file_name" && ! -L "$release_dir/$file_name" ]] \
    || fail 'ETL release artifact is missing or unsafe'
done
gzip -t "$release_dir/airbob-production-seed.sql.gz" >/dev/null 2>&1 \
  || fail 'ETL database dump is not a valid gzip stream'

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-dataset-attestation.XXXXXX") \
  || fail 'cannot create attestation workspace'
output_temp=''
cleanup() {
  unset MYSQL_PWD database_password restore_password restore_user
  [[ -z "$output_temp" ]] || rm -f "$output_temp"
  rm -rf "$work_dir"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
lineage_verifier="$script_dir/verify-etl-release-database.sh"
[[ -f "$lineage_verifier" && ! -L "$lineage_verifier" && -x "$lineage_verifier" ]] \
  || fail 'ETL release database verifier is missing or unsafe'

checksum_file="$release_dir/SHA256SUMS"
has_final_newline "$checksum_file" || fail 'SHA256SUMS must end with one newline'
[[ "$(wc -l < "$checksum_file" | tr -d '[:space:]')" == 9 ]] \
  || fail 'SHA256SUMS must contain exactly nine entries'

checksum_entries="$work_dir/checksum-entries.tsv"
: > "$checksum_entries"
while IFS= read -r checksum_line; do
  [[ "$checksum_line" =~ ^([0-9a-f]{64})[\ ][\ ]([a-zA-Z0-9._-]+)$ ]] \
    || fail 'SHA256SUMS contains a malformed entry'
  printf '%s\t%s\n' "${BASH_REMATCH[2]}" "${BASH_REMATCH[1]}" >> "$checksum_entries"
done < "$checksum_file"

expected_checksum_names=$(printf '%s\n' "${checksummed_files[@]}")
actual_checksum_names=$(awk -F $'\t' '{print $1}' "$checksum_entries")
[[ "$actual_checksum_names" == "$expected_checksum_names" ]] \
  || fail 'SHA256SUMS entries are missing, duplicated, or not filename-sorted'

checksum_digest_for() {
  awk -F $'\t' -v target="$1" '$1 == target { print $2 }' "$checksum_entries"
}

for file_name in "${checksummed_files[@]}"; do
  expected_digest=$(checksum_digest_for "$file_name")
  actual_digest=$(sha256_file "$release_dir/$file_name")
  [[ "$actual_digest" == "$expected_digest" ]] \
    || fail 'ETL release artifact checksum mismatch'
done

source_release_payload_sha256=$(sha256_file "$checksum_file")
source_dump_sha256=$(checksum_digest_for airbob-production-seed.sql.gz)
source_database_fingerprint_sha256=$(checksum_digest_for database-fingerprint.tsv)

metadata_file="$release_dir/release-metadata.txt"
has_final_newline "$metadata_file" || fail 'release metadata must end with one newline'
[[ "$(wc -l < "$metadata_file" | tr -d '[:space:]')" == 13 ]] \
  || fail 'release metadata must contain exactly thirteen entries'
metadata_entries="$work_dir/release-metadata.tsv"
: > "$metadata_entries"
while IFS= read -r metadata_line; do
  [[ "$metadata_line" =~ ^([a-z][a-z0-9_]*)=([^=[:cntrl:]]+)$ ]] \
    || fail 'release metadata contains a malformed entry'
  printf '%s\t%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" >> "$metadata_entries"
done < "$metadata_file"

expected_metadata_keys=$'format\nrelease_id\ndump\nmanifest\ntraffic_manifest\ntraffic_manifest_sha256\ntraffic_dataset_version\ntraffic_dataset_run_id\ntraffic_flyway_version\ntraffic_migration_digest\nfingerprint\nrequired_rows\nrecovery'
actual_metadata_keys=$(awk -F $'\t' '{print $1}' "$metadata_entries")
[[ "$actual_metadata_keys" == "$expected_metadata_keys" ]] \
  || fail 'release metadata keys are missing, duplicated, reordered, or unsupported'

metadata_value() {
  awk -F $'\t' -v target="$1" '$1 == target { print $2 }' "$metadata_entries"
}

[[ "$(metadata_value format)" == airbob-production-seed-release-v1 ]] \
  || fail 'release metadata format is unsupported'
release_id=$(metadata_value release_id)
[[ "$release_id" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$ ]] \
  || fail 'release metadata id is invalid'
[[ "$(metadata_value dump)" == airbob-production-seed.sql.gz ]] \
  || fail 'release metadata dump is unsupported'
[[ "$(metadata_value manifest)" == benchmark-fixture.json ]] \
  || fail 'release metadata benchmark manifest is unsupported'
[[ "$(metadata_value traffic_manifest)" == traffic-v1.json ]] \
  || fail 'release metadata traffic manifest is unsupported'
[[ "$(metadata_value fingerprint)" == database-fingerprint.tsv ]] \
  || fail 'release metadata fingerprint is unsupported'
[[ "$(metadata_value traffic_dataset_version)" == traffic-v1 ]] \
  || fail 'release metadata dataset version is unsupported'
traffic_dataset_run_id=$(metadata_value traffic_dataset_run_id)
[[ "$traffic_dataset_run_id" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$ ]] \
  || fail 'release metadata traffic run identity is invalid'
[[ "$(metadata_value traffic_flyway_version)" == 17 ]] \
  || fail 'release metadata Flyway version is stale'
required_rows=$(metadata_value required_rows)
[[ "$required_rows" == 201 ]] \
  || fail 'release metadata row capacity is unsupported'
[[ "$(metadata_value recovery)" == reset-flyway-v1-v17-etl-reseed-before-traffic ]] \
  || fail 'release metadata recovery contract is unsupported'

traffic_manifest_sha256=$(metadata_value traffic_manifest_sha256)
[[ "$traffic_manifest_sha256" =~ ^[0-9a-f]{64}$ ]] \
  || fail 'release metadata traffic manifest digest is invalid'
[[ "$traffic_manifest_sha256" == "$(checksum_digest_for traffic-v1.json)" ]] \
  || fail 'release metadata traffic manifest digest does not match the release'

migration_digest=$(metadata_value traffic_migration_digest)
[[ "$migration_digest" =~ ^sha256:[0-9a-f]{64}$ ]] \
  || fail 'release metadata migration digest is invalid'
migration_digest_file="$release_dir/backend-migrations.sha256"
has_final_newline "$migration_digest_file" \
  || fail 'backend migration digest must end with one newline'
[[ "$(wc -l < "$migration_digest_file" | tr -d '[:space:]')" == 17 ]] \
  || fail 'backend migration inventory must contain exactly V1 through V17'
migration_inventory_paths="$work_dir/backend-migration-paths.txt"
migration_inventory_versions="$work_dir/backend-migration-versions.txt"
: > "$migration_inventory_paths"
: > "$migration_inventory_versions"
while IFS= read -r migration_inventory_line; do
  [[ "$migration_inventory_line" =~ ^([0-9a-f]{64})[\ ][\ ](\./V([1-9][0-9]*)__([a-zA-Z0-9][a-zA-Z0-9._-]*)\.sql)$ ]] \
    || fail 'backend migration inventory contains a malformed entry'
  printf '%s\n' "${BASH_REMATCH[2]}" >> "$migration_inventory_paths"
  printf '%s\n' "${BASH_REMATCH[3]}" >> "$migration_inventory_versions"
done < "$migration_digest_file"
LC_ALL=C sort "$migration_inventory_paths" > "$work_dir/backend-migration-paths.sorted.txt"
cmp -s "$migration_inventory_paths" "$work_dir/backend-migration-paths.sorted.txt" \
  || fail 'backend migration inventory paths are not bytewise sorted'
[[ "$(LC_ALL=C sort -u "$migration_inventory_paths" | wc -l | tr -d '[:space:]')" == 17 ]] \
  || fail 'backend migration inventory contains duplicate paths'
LC_ALL=C sort -n "$migration_inventory_versions" > "$work_dir/backend-migration-versions.sorted.txt"
awk 'BEGIN { for (version = 1; version <= 17; version += 1) print version }' \
  > "$work_dir/backend-migration-versions.expected.txt"
cmp -s \
  "$work_dir/backend-migration-versions.sorted.txt" \
  "$work_dir/backend-migration-versions.expected.txt" \
  || fail 'backend migration inventory does not contain each version V1 through V17 exactly once'

jq -se \
  --arg runId "$traffic_dataset_run_id" \
  --arg migrationDigest "$migration_digest" '
  length == 1 and
  (.[0] |
    .datasetVersion == "traffic-v1" and
    .datasetRunId == $runId and
    .schema.flywayVersion == "17" and
    .schema.migrationDigest == $migrationDigest and
    ([.. | objects | keys[]] |
      all(test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not)) and
    ([.. | strings] |
      all(test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not)))
' "$release_dir/traffic-v1.json" >/dev/null \
  || fail 'traffic manifest does not match the V17 release metadata contract'
jq -se '
  length == 1 and
  (.[0] | [.. | objects | keys[]] |
    all(test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not)) and
  (.[0] | [.. | strings] |
    all(test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not))
' "$release_dir/benchmark-fixture.json" >/dev/null \
  || fail 'benchmark manifest contains unsupported or secret-bearing keys'

restore_mysql_query() {
  local label=$1
  local query=$2
  local destination=$3
  if ! printf '%s\n' "$query" | MYSQL_PWD="$restore_password" mysql \
    --protocol=TCP \
    --host="$AIRBOB_DATASET_DB_HOST" \
    --port="$AIRBOB_DATASET_DB_PORT" \
    --user="$restore_user" \
    --batch \
    --raw \
    --skip-column-names \
    "$AIRBOB_DATASET_DB_NAME" > "$destination" 2>/dev/null; then
    fail "database restore precondition failed: $label"
  fi
}

restore_target_query="
  SELECT
    LOWER(@@server_uuid),
    (SELECT COUNT(*) FROM information_schema.SCHEMATA
     WHERE SCHEMA_NAME = 'airbobdb'),
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = 'airbobdb' AND TABLE_TYPE = 'BASE TABLE'),
    (SELECT COUNT(*) FROM information_schema.VIEWS
     WHERE TABLE_SCHEMA = 'airbobdb'),
    (SELECT COUNT(*) FROM information_schema.ROUTINES
     WHERE ROUTINE_SCHEMA = 'airbobdb'),
    (SELECT COUNT(*) FROM information_schema.EVENTS
     WHERE EVENT_SCHEMA = 'airbobdb'),
    (SELECT COUNT(*) FROM information_schema.TRIGGERS
     WHERE TRIGGER_SCHEMA = 'airbobdb');
"
restore_target_file="$work_dir/restore-target.tsv"
restore_mysql_query empty-target "$restore_target_query" "$restore_target_file"
[[ "$(wc -l < "$restore_target_file" | tr -d '[:space:]')" == 1 ]] \
  || fail 'database restore target inventory is malformed'
IFS=$'\t' read -r restored_database_server_uuid schema_count table_count view_count \
  routine_count event_count trigger_count extra_field < "$restore_target_file"
[[ -z "${extra_field:-}" \
  && "$restored_database_server_uuid" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ \
  && "$schema_count" == 1 \
  && "$table_count" == 0 \
  && "$view_count" == 0 \
  && "$routine_count" == 0 \
  && "$event_count" == 0 \
  && "$trigger_count" == 0 ]] \
  || fail 'database restore target must be the existing empty airbobdb schema'

restored_dump="$work_dir/airbob-production-seed.sql.gz"
cp "$release_dir/airbob-production-seed.sql.gz" "$restored_dump" \
  || fail 'cannot copy the verified database dump into the private workspace'
chmod 600 "$restored_dump"
[[ -f "$restored_dump" && ! -L "$restored_dump" \
  && "$(sha256_file "$restored_dump")" == "$source_dump_sha256" ]] \
  || fail 'private database dump copy does not match the verified source dump'
restored_dump_sha256=$(sha256_file "$restored_dump")

dump_import_succeeded=true
if ! gzip -dc "$restored_dump" 2>/dev/null | MYSQL_PWD="$restore_password" mysql \
  --protocol=TCP \
  --host="$AIRBOB_DATASET_DB_HOST" \
  --port="$AIRBOB_DATASET_DB_PORT" \
  --user="$restore_user" \
  --batch \
  --raw \
  --skip-column-names \
  "$AIRBOB_DATASET_DB_NAME" >/dev/null 2>/dev/null; then
  dump_import_succeeded=false
fi

read_only_result="$work_dir/restore-read-only.txt"
restore_mysql_query enable-super-read-only \
  'SET GLOBAL super_read_only = ON;' "$read_only_result"
[[ ! -s "$read_only_result" ]] \
  || fail 'database read-only transition returned unexpected output'
[[ "$(sha256_file "$restored_dump")" == "$restored_dump_sha256" \
  && "$restored_dump_sha256" == "$source_dump_sha256" ]] \
  || fail 'private database dump copy changed during restore'

unset MYSQL_PWD restore_password restore_user \
  AIRBOB_DATASET_DB_RESTORE_PASSWORD AIRBOB_DATASET_DB_RESTORE_USER
[[ "$dump_import_succeeded" == true ]] \
  || fail 'exact database dump import failed'

captured_at=${AIRBOB_DATASET_CAPTURED_AT:-$(date -u '+%Y-%m-%dT%H:%M:%SZ')}
[[ "$captured_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
  || fail 'capture timestamp is not RFC3339 UTC'

lineage_receipt="$work_dir/lineage-receipt.json"
if ! AIRBOB_DATASET_DB_PASSWORD="$database_password" \
  "$lineage_verifier" "$release_dir" > "$lineage_receipt"; then
  fail 'ETL release database lineage verification failed'
fi
jq -se '
  def sha256: type == "string" and test("^[0-9a-f]{64}$");
  length == 1 and
  (.[0] |
    (keys | sort) == ([
      "schemaVersion", "sourceEtlCommit", "databaseServerUuid",
      "verifierContractInventorySha256", "databaseFingerprintSubsetSha256"
    ] | sort) and
    .schemaVersion == 1 and
    (.sourceEtlCommit | type == "string" and test("^[0-9a-f]{40}$")) and
    (.databaseServerUuid | type == "string" and
      test("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) and
    (.verifierContractInventorySha256 | sha256) and
    (.databaseFingerprintSubsetSha256 | sha256))
' "$lineage_receipt" >/dev/null \
  || fail 'ETL release database lineage receipt is invalid'
source_etl_commit=$(jq -r '.sourceEtlCommit' "$lineage_receipt")
database_server_uuid=$(jq -r '.databaseServerUuid' "$lineage_receipt")
[[ "$database_server_uuid" == "$restored_database_server_uuid" ]] \
  || fail 'attested database server differs from the exact dump restore target'
verifier_contract_inventory_sha256=$(jq -r '.verifierContractInventorySha256' "$lineage_receipt")
database_fingerprint_subset_sha256=$(jq -r '.databaseFingerprintSubsetSha256' "$lineage_receipt")
jq -en --arg capturedAt "$captured_at" '$capturedAt | fromdateiso8601' >/dev/null \
  || fail 'capture timestamp is not RFC3339 UTC'

mysql_query() {
  local label=$1
  local query=$2
  local destination=$3
  if ! printf '%s\n' "$query" | MYSQL_PWD="$database_password" mysql \
    --protocol=TCP \
    --host="$AIRBOB_DATASET_DB_HOST" \
    --port="$AIRBOB_DATASET_DB_PORT" \
    --user="$AIRBOB_DATASET_DB_USER" \
    --batch \
    --raw \
    --skip-column-names \
    "$AIRBOB_DATASET_DB_NAME" > "$destination" 2>/dev/null; then
    fail "database query failed: $label"
  fi
}

server_before_file="$work_dir/server-before.tsv"
mysql_query server-before \
  'SELECT LOWER(@@server_uuid), @@GLOBAL.read_only, @@GLOBAL.super_read_only;' \
  "$server_before_file"
[[ "$(wc -l < "$server_before_file" | tr -d '[:space:]')" == 1 ]] \
  || fail 'database server identity is malformed before capture'
IFS=$'\t' read -r live_server_uuid read_only_before super_read_only_before extra_field \
  < "$server_before_file"
[[ -z "${extra_field:-}" \
   && "$live_server_uuid" == "$database_server_uuid" \
   && "$read_only_before" == 1 \
   && "$super_read_only_before" == 1 ]] \
  || fail 'database server identity or read-only state is invalid before capture'

flyway_summary_query="
  SELECT
    COALESCE(
      (SELECT version
       FROM flyway_schema_history
       WHERE success = 1 AND version IS NOT NULL
       ORDER BY installed_rank DESC
       LIMIT 1),
      '<NULL>'
    ) AS current_version,
    COUNT(*) AS history_rows,
    SUM(CASE WHEN success = 1 AND version IS NOT NULL THEN 1 ELSE 0 END) AS successful_versioned_rows,
    SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failed_rows
  FROM flyway_schema_history;
"
flyway_summary_file="$work_dir/flyway-summary.tsv"
mysql_query flyway-lineage "$flyway_summary_query" "$flyway_summary_file"
[[ "$(wc -l < "$flyway_summary_file" | tr -d '[:space:]')" == 1 ]] \
  || fail 'database returned a malformed Flyway lineage'
IFS=$'\t' read -r flyway_version flyway_history_rows successful_versioned_rows failed_rows extra_field \
  < "$flyway_summary_file"
[[ -z "${extra_field:-}" && "$flyway_version" == 17 ]] \
  || fail 'database Flyway version is not V17'
[[ "$flyway_history_rows" == 17 && "$successful_versioned_rows" == 17 && "$failed_rows" == 0 ]] \
  || fail 'database Flyway history is not exactly seventeen successful versioned rows'

outbox_file="$work_dir/outbox-count.txt"
mysql_query outbox-state 'SELECT COUNT(*) FROM outbox;' "$outbox_file"
outbox_count=$(sed -n '1p' "$outbox_file")
[[ "$(wc -l < "$outbox_file" | tr -d '[:space:]')" == 1 && "$outbox_count" == 0 ]] \
  || fail 'database outbox is not empty'

migration_query="
  SELECT installed_rank, COALESCE(version, '<NULL>'), description, type, script,
         COALESCE(checksum, '<NULL>'), success
  FROM flyway_schema_history
  ORDER BY installed_rank;
"
migration_file="$work_dir/flyway-migrations.tsv"
mysql_query migration-checksum "$migration_query" "$migration_file"
has_final_newline "$migration_file" \
  || fail 'database returned a malformed Flyway history'
[[ "$(wc -l < "$migration_file" | tr -d '[:space:]')" == 17 ]] \
  || fail 'database Flyway history does not contain exactly seventeen records'
awk -F $'\t' 'NF != 7 || $7 !~ /^[01]$/ { exit 1 }' "$migration_file" \
  || fail 'database returned a malformed Flyway history'
migration_checksum_sha256=$(sha256_file "$migration_file")
traffic_migration_stream="$work_dir/traffic-migration-canonical.txt"
awk -F $'\t' '
  BEGIN { expected_version = 1 }
  NF != 7 || $2 !~ /^[0-9]+$/ || ($2 + 0) != expected_version { exit 1 }
  $5 !~ /^V[1-9][0-9]*__[a-zA-Z0-9][a-zA-Z0-9._-]*\.sql$/ { exit 1 }
  $6 !~ /^-?[0-9]+$/ || $7 != "1" { exit 1 }
  { printf "%s|%s|%s\n", $2, $5, $6; expected_version += 1 }
  END { if (expected_version != 18) exit 1 }
' "$migration_file" > "$traffic_migration_stream" \
  || fail 'database Flyway rows cannot form the traffic migration digest'
live_traffic_migration_digest="sha256:$(sha256_file "$traffic_migration_stream")"
[[ "$live_traffic_migration_digest" == "$migration_digest" ]] \
  || fail 'database traffic migration digest does not match the source release'

schema_query="
  SELECT 'COLUMN', HEX(TABLE_NAME), HEX(COLUMN_NAME), HEX(CAST(ORDINAL_POSITION AS CHAR)),
         HEX(COLUMN_NAME), HEX(COLUMN_TYPE), HEX(IS_NULLABLE),
         COALESCE(HEX(CAST(COLUMN_DEFAULT AS CHAR)), '<NULL>'), HEX(EXTRA),
         COALESCE(HEX(COLLATION_NAME), '<NULL>'),
         COALESCE(HEX(CHARACTER_SET_NAME), '<NULL>'),
         COALESCE(HEX(GENERATION_EXPRESSION), '<NULL>')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'airbobdb'
  UNION ALL
  SELECT 'INDEX', HEX(TABLE_NAME), HEX(INDEX_NAME), HEX(CAST(SEQ_IN_INDEX AS CHAR)),
         COALESCE(HEX(COLUMN_NAME), '<NULL>'), HEX(CAST(NON_UNIQUE AS CHAR)),
         COALESCE(HEX(COLLATION), '<NULL>'), COALESCE(HEX(CAST(SUB_PART AS CHAR)), '<NULL>'),
         HEX(NULLABLE), HEX(INDEX_TYPE), HEX(IS_VISIBLE), COALESCE(HEX(EXPRESSION), '<NULL>')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'airbobdb'
  UNION ALL
  SELECT 'CONSTRAINT', HEX(tc.TABLE_NAME), HEX(tc.CONSTRAINT_NAME),
         COALESCE(HEX(CAST(kcu.ORDINAL_POSITION AS CHAR)), '<NULL>'),
         COALESCE(HEX(kcu.COLUMN_NAME), '<NULL>'), HEX(tc.CONSTRAINT_TYPE),
         COALESCE(HEX(CAST(kcu.POSITION_IN_UNIQUE_CONSTRAINT AS CHAR)), '<NULL>'),
         COALESCE(HEX(kcu.REFERENCED_TABLE_SCHEMA), '<NULL>'),
         COALESCE(HEX(kcu.REFERENCED_TABLE_NAME), '<NULL>'),
         COALESCE(HEX(kcu.REFERENCED_COLUMN_NAME), '<NULL>'), HEX(tc.ENFORCED), '<NULL>'
  FROM information_schema.TABLE_CONSTRAINTS AS tc
  LEFT JOIN information_schema.KEY_COLUMN_USAGE AS kcu
    ON kcu.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
   AND kcu.TABLE_NAME = tc.TABLE_NAME
   AND kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
  WHERE tc.CONSTRAINT_SCHEMA = 'airbobdb'
  UNION ALL
  SELECT 'REFERENTIAL', HEX(TABLE_NAME), HEX(CONSTRAINT_NAME), '<NULL>', '<NULL>',
         HEX(UNIQUE_CONSTRAINT_SCHEMA), HEX(UNIQUE_CONSTRAINT_NAME), HEX(MATCH_OPTION),
         HEX(UPDATE_RULE), HEX(DELETE_RULE), HEX(REFERENCED_TABLE_NAME), '<NULL>'
  FROM information_schema.REFERENTIAL_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA = 'airbobdb'
  UNION ALL
  SELECT 'CHECK', HEX(tc.TABLE_NAME), HEX(cc.CONSTRAINT_NAME), '<NULL>', '<NULL>',
         HEX(cc.CHECK_CLAUSE), HEX(tc.ENFORCED), '<NULL>', '<NULL>', '<NULL>', '<NULL>', '<NULL>'
  FROM information_schema.CHECK_CONSTRAINTS AS cc
  INNER JOIN information_schema.TABLE_CONSTRAINTS AS tc
    ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
   AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
   AND tc.CONSTRAINT_TYPE = 'CHECK'
  WHERE cc.CONSTRAINT_SCHEMA = 'airbobdb';
"
schema_unsorted_file="$work_dir/schema-fingerprint.unsorted.tsv"
schema_file="$work_dir/schema-fingerprint.tsv"
mysql_query schema-fingerprint "$schema_query" "$schema_unsorted_file"
has_final_newline "$schema_unsorted_file" \
  || fail 'database returned a malformed schema fingerprint'
awk -F $'\t' '
  NF != 12 { exit 1 }
  $1 !~ /^(COLUMN|INDEX|CONSTRAINT|REFERENTIAL|CHECK)$/ { exit 1 }
  {
    for (field = 2; field <= 12; field += 1) {
      if ($field != "<NULL>" && $field !~ /^[0-9A-F]*$/) exit 1
    }
  }
' "$schema_unsorted_file" || fail 'database returned a malformed schema fingerprint'
LC_ALL=C sort "$schema_unsorted_file" > "$schema_file"
schema_fingerprint_sha256=$(sha256_file "$schema_file")

table_inventory_query="
  SELECT TABLE_NAME
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = 'airbobdb'
    AND TABLE_TYPE = 'BASE TABLE'
  ORDER BY TABLE_NAME;
"
table_inventory_file="$work_dir/base-tables.txt"
mysql_query table-inventory "$table_inventory_query" "$table_inventory_file"
has_final_newline "$table_inventory_file" || fail 'database has no valid base-table inventory'

sorted_table_inventory="$work_dir/base-tables.sorted.txt"
LC_ALL=C sort "$table_inventory_file" > "$sorted_table_inventory"
[[ "$(wc -l < "$sorted_table_inventory" | tr -d '[:space:]')" \
   == "$(LC_ALL=C sort -u "$sorted_table_inventory" | wc -l | tr -d '[:space:]')" ]] \
  || fail 'database returned duplicate base-table names'

expected_rows_tsv="$work_dir/expected-table-rows.tsv"
: > "$expected_rows_tsv"
while IFS= read -r table_name; do
  [[ "$table_name" =~ ^[a-z][a-z0-9_]{0,63}$ ]] \
    || fail 'database returned an unsafe base-table name'
  row_count_file="$work_dir/row-count-$table_name.txt"
  mysql_query "row-count-$table_name" "SELECT COUNT(*) FROM \`$table_name\`;" "$row_count_file"
  row_count=$(sed -n '1p' "$row_count_file")
  [[ "$(wc -l < "$row_count_file" | tr -d '[:space:]')" == 1 && "$row_count" =~ ^[0-9]+$ ]] \
    || fail 'database returned an invalid table row count'
  printf '%s\t%s\n' "$table_name" "$row_count" >> "$expected_rows_tsv"
done < "$sorted_table_inventory"

second_migration_file="$work_dir/flyway-migrations.second.tsv"
mysql_query migration-checksum-second "$migration_query" "$second_migration_file"
has_final_newline "$second_migration_file" \
  || fail 'database returned a malformed Flyway history during stability verification'

second_schema_unsorted_file="$work_dir/schema-fingerprint.second.unsorted.tsv"
second_schema_file="$work_dir/schema-fingerprint.second.tsv"
mysql_query schema-fingerprint-second "$schema_query" "$second_schema_unsorted_file"
has_final_newline "$second_schema_unsorted_file" \
  || fail 'database returned a malformed schema fingerprint during stability verification'
LC_ALL=C sort "$second_schema_unsorted_file" > "$second_schema_file"

second_table_inventory_file="$work_dir/base-tables.second.txt"
second_sorted_table_inventory="$work_dir/base-tables.second.sorted.txt"
mysql_query table-inventory-second "$table_inventory_query" "$second_table_inventory_file"
has_final_newline "$second_table_inventory_file" \
  || fail 'database returned a malformed base-table inventory during stability verification'
LC_ALL=C sort "$second_table_inventory_file" > "$second_sorted_table_inventory"
second_expected_rows_tsv="$work_dir/expected-table-rows.second.tsv"
: > "$second_expected_rows_tsv"
while IFS= read -r table_name; do
  [[ "$table_name" =~ ^[a-z][a-z0-9_]{0,63}$ ]] \
    || fail 'database returned an unsafe base-table name during stability verification'
  row_count_file="$work_dir/row-count-second-$table_name.txt"
  mysql_query "row-count-second-$table_name" "SELECT COUNT(*) FROM \`$table_name\`;" "$row_count_file"
  row_count=$(sed -n '1p' "$row_count_file")
  [[ "$(wc -l < "$row_count_file" | tr -d '[:space:]')" == 1 && "$row_count" =~ ^[0-9]+$ ]] \
    || fail 'database returned an invalid table row count during stability verification'
  printf '%s\t%s\n' "$table_name" "$row_count" >> "$second_expected_rows_tsv"
done < "$second_sorted_table_inventory"

server_after_file="$work_dir/server-after.tsv"
mysql_query server-after \
  'SELECT LOWER(@@server_uuid), @@GLOBAL.read_only, @@GLOBAL.super_read_only;' \
  "$server_after_file"
cmp -s "$server_before_file" "$server_after_file" \
  || fail 'database server identity or read-only state changed during capture'

cmp -s "$migration_file" "$second_migration_file" \
  || fail 'Flyway history changed during capture'
cmp -s "$schema_file" "$second_schema_file" \
  || fail 'database schema changed during capture'
cmp -s "$sorted_table_inventory" "$second_sorted_table_inventory" \
  || fail 'base-table inventory changed during capture'
cmp -s "$expected_rows_tsv" "$second_expected_rows_tsv" \
  || fail 'table row counts changed during capture'

[[ "$(sha256_file "$checksum_file")" == "$source_release_payload_sha256" ]] \
  || fail 'ETL release checksum inventory changed during capture'
for file_name in "${checksummed_files[@]}"; do
  [[ "$(sha256_file "$release_dir/$file_name")" == "$(checksum_digest_for "$file_name")" ]] \
    || fail 'ETL release artifact changed during capture'
done

lineage_receipt_after="$work_dir/lineage-receipt.after.json"
if ! AIRBOB_DATASET_DB_PASSWORD="$database_password" \
  "$lineage_verifier" "$release_dir" > "$lineage_receipt_after"; then
  fail 'ETL release database lineage changed during capture'
fi
cmp -s "$lineage_receipt" "$lineage_receipt_after" \
  || fail 'ETL release database lineage changed during capture'

for required_table in accommodation flyway_schema_history outbox; do
  awk -F $'\t' -v target="$required_table" '$1 == target { found = 1 } END { exit !found }' \
    "$expected_rows_tsv" || fail 'database is missing a required base table'
done
accommodation_rows=$(awk -F $'\t' '$1 == "accommodation" { print $2 }' "$expected_rows_tsv")
[[ "$accommodation_rows" =~ ^[0-9]+$ && "$accommodation_rows" -ge "$required_rows" ]] \
  || fail 'database accommodation capacity is below the release contract'

expected_table_rows_json=$(jq -Rn '
  reduce inputs as $line (
    {};
    ($line | split("\t")) as $fields |
    . + {($fields[0]): ($fields[1] | tonumber)}
  )
' < "$expected_rows_tsv")

output_temp=$(mktemp "$output_parent/.${output_name}.XXXXXX") \
  || fail 'cannot create temporary attestation output'
jq -n \
  --arg sourceReleasePayloadSha256 "$source_release_payload_sha256" \
  --arg sourceDumpSha256 "$source_dump_sha256" \
  --arg restoredDumpSha256 "$restored_dump_sha256" \
  --arg sourceDatabaseFingerprintSha256 "$source_database_fingerprint_sha256" \
  --arg sourceEtlCommit "$source_etl_commit" \
  --arg databaseServerUuid "$database_server_uuid" \
  --arg verifierContractInventorySha256 "$verifier_contract_inventory_sha256" \
  --arg databaseFingerprintSubsetSha256 "$database_fingerprint_subset_sha256" \
  --arg flywayVersion "$flyway_version" \
  --argjson flywayHistoryRows "$flyway_history_rows" \
  --arg migrationChecksumSha256 "$migration_checksum_sha256" \
  --arg schemaFingerprintSha256 "$schema_fingerprint_sha256" \
  --argjson expectedTableRows "$expected_table_rows_json" \
  --arg capturedAt "$captured_at" '
  {
    schemaVersion: 3,
    databaseRestoreMethod: "gzip-to-empty-airbobdb-v1",
    sourceReleasePayloadSha256: $sourceReleasePayloadSha256,
    sourceDumpSha256: $sourceDumpSha256,
    restoredDumpSha256: $restoredDumpSha256,
    sourceDatabaseFingerprintSha256: $sourceDatabaseFingerprintSha256,
    sourceEtlCommit: $sourceEtlCommit,
    databaseServerUuid: $databaseServerUuid,
    verifierContractInventorySha256: $verifierContractInventorySha256,
    databaseFingerprintSubsetSha256: $databaseFingerprintSubsetSha256,
    flywayVersion: $flywayVersion,
    flywayHistoryRows: $flywayHistoryRows,
    migrationChecksumSha256: $migrationChecksumSha256,
    schemaFingerprintSha256: $schemaFingerprintSha256,
    outboxState: "empty",
    expectedTableRows: $expectedTableRows,
    capturedAt: $capturedAt
  }
' > "$output_temp"

jq -e '
  def sha256: type == "string" and test("^[0-9a-f]{64}$");
  (keys | sort) == ([
    "capturedAt", "expectedTableRows", "flywayHistoryRows", "flywayVersion",
    "migrationChecksumSha256", "outboxState", "schemaFingerprintSha256",
    "schemaVersion", "sourceDatabaseFingerprintSha256", "sourceDumpSha256",
    "sourceReleasePayloadSha256", "sourceEtlCommit", "databaseServerUuid",
    "verifierContractInventorySha256", "databaseFingerprintSubsetSha256",
    "databaseRestoreMethod", "restoredDumpSha256"
  ] | sort) and
  .schemaVersion == 3 and
  .databaseRestoreMethod == "gzip-to-empty-airbobdb-v1" and
  (.sourceReleasePayloadSha256 | sha256) and
  (.sourceDumpSha256 | sha256) and
  (.restoredDumpSha256 | sha256) and
  .restoredDumpSha256 == .sourceDumpSha256 and
  (.sourceDatabaseFingerprintSha256 | sha256) and
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
  all(.expectedTableRows | to_entries[];
    (.key | test("^[a-z][a-z0-9_]{0,63}$")) and
    (.key | test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not) and
    (.value | type == "number" and floor == . and . >= 0)
  ) and
  (.capturedAt | fromdateiso8601 | type == "number")
' "$output_temp" >/dev/null || fail 'generated attestation violates the exact output contract'

chmod 600 "$output_temp"
ln "$output_temp" "$output_json" 2>/dev/null \
  || fail 'attestation output already exists or cannot be created'
rm -f "$output_temp"
output_temp=''
printf '%s\n' 'dataset attestation captured'
