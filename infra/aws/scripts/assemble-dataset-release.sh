#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

usage() {
  printf 'usage: %s ETL_RELEASE_DIR ATTESTATION_JSON OUTPUT_ROOT DATASET_RELEASE EVALUATION_TIME VALID_UNTIL\n' \
    "${0##*/}" >&2
  exit 64
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    fail 'a SHA-256 implementation is required'
  fi
}

sha256_stream() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 | awk '{print $1}'
  else
    fail 'a SHA-256 implementation is required'
  fi
}

stat_uid() {
  if stat -f '%u' "$1" >/dev/null 2>&1; then
    stat -f '%u' "$1"
  else
    stat -c '%u' "$1"
  fi
}

stat_mode() {
  if stat -f '%Lp' "$1" >/dev/null 2>&1; then
    stat -f '%Lp' "$1"
  else
    stat -c '%a' "$1"
  fi
}

contains_secret_marker() {
  printf '%s\n' "$1" \
    | grep -Eqi 'password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account'
}

local_timestamp_to_utc() {
  local local_timestamp=$1
  local timezone=$2
  local epoch
  local round_trip

  if date -u -d '@0' '+%s' >/dev/null 2>&1; then
    epoch=$(TZ="$timezone" date -d "$local_timestamp" '+%s') || return 1
    round_trip=$(TZ="$timezone" date -d "@$epoch" '+%Y-%m-%dT%H:%M:%S') || return 1
    [[ "$round_trip" == "$local_timestamp" ]] || return 1
    date -u -d "@$epoch" '+%Y-%m-%dT%H:%M:%SZ'
  else
    epoch=$(TZ="$timezone" date -j -f '%Y-%m-%dT%H:%M:%S' "$local_timestamp" '+%s') || return 1
    round_trip=$(TZ="$timezone" date -r "$epoch" '+%Y-%m-%dT%H:%M:%S') || return 1
    [[ "$round_trip" == "$local_timestamp" ]] || return 1
    date -u -r "$epoch" '+%Y-%m-%dT%H:%M:%SZ'
  fi
}

require_small_text_file() {
  local file=$1
  local maximum=$2
  local size
  size=$(wc -c < "$file" | tr -d '[:space:]')
  [[ "$size" =~ ^[0-9]+$ && "$size" -le "$maximum" ]] \
    || fail 'source metadata exceeds its size limit'
  cmp -s "$file" <(tr -d '\000' < "$file") \
    || fail 'source metadata contains a NUL byte'
}

cleanup_staging() {
  local staged_name
  [[ ${incomplete_created:-false} == true ]] || return 0
  [[ -n ${staging_dir:-} && -d ${staging_dir:-} && ! -L ${staging_dir:-} ]] || return 0
  for staged_name in "${source_files[@]}" attestation.json; do
    rm -f "$staging_dir/$staged_name" >/dev/null 2>&1 || true
  done
  rmdir "$staging_dir" >/dev/null 2>&1 || true
}

on_exit() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ $status -ne 0 ]]; then
    cleanup_staging
    if [[ ${incomplete_created:-false} == true \
      && -n ${incomplete_dir:-} \
      && -d ${incomplete_dir:-} \
      && ! -L ${incomplete_dir:-} ]]; then
      rm -f "$incomplete_dir/.manifest.json.tmp" >/dev/null 2>&1 || true
      printf '%s\n' 'dataset assembly failed; incomplete release was not published' >&2
    fi
  fi
  if [[ ${lock_acquired:-false} == true \
    && -n ${release_lock_dir:-} \
    && -d ${release_lock_dir:-} \
    && ! -L ${release_lock_dir:-} ]]; then
    rmdir "$release_lock_dir" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
incomplete_created=false
lock_acquired=false
trap on_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

[[ $# -eq 6 ]] || usage
etl_release_dir=$1
attestation_file=$2
output_root=$3
dataset_release=$4
evaluation_time=$5
valid_until=$6

for required_command in jq gzip zstd stat id cp cmp tr grep find sort awk wc date; do
  command -v "$required_command" >/dev/null 2>&1 \
    || fail "required local command is unavailable: $required_command"
done

[[ "$dataset_release" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ ]] \
  || fail 'dataset release must be a lowercase safe name'
[[ "$evaluation_time" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
  || fail 'evaluation time must be an RFC3339 UTC timestamp'
[[ "$valid_until" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
  || fail 'valid-until must be an RFC3339 UTC timestamp'
jq -en \
  --arg evaluationTime "$evaluation_time" \
  --arg validUntil "$valid_until" '
    ($evaluationTime | fromdateiso8601) < ($validUntil | fromdateiso8601) and
    ($validUntil | fromdateiso8601) > now
  ' >/dev/null || fail 'dataset evaluation window is invalid or expired'

[[ -d "$etl_release_dir" && ! -L "$etl_release_dir" ]] \
  || fail 'ETL source release directory is missing or unsafe'
[[ -f "$attestation_file" && ! -L "$attestation_file" ]] \
  || fail 'dataset attestation is missing or unsafe'
[[ -d "$output_root" && ! -L "$output_root" ]] \
  || fail 'dataset output root is missing or unsafe'

etl_release_dir=$(CDPATH= cd -P -- "$etl_release_dir" && pwd -P)
attestation_parent=$(CDPATH= cd -P -- "$(dirname -- "$attestation_file")" && pwd -P)
attestation_file="$attestation_parent/$(basename -- "$attestation_file")"
output_root=$(CDPATH= cd -P -- "$output_root" && pwd -P)

[[ "$output_root" != / && "$output_root" != "$etl_release_dir" ]] \
  || fail 'dataset output root is unsafe'
[[ "$(stat_uid "$output_root")" == "$(id -u)" ]] \
  || fail 'dataset output root must be owned by the caller'
[[ "$(stat_mode "$output_root")" == 700 ]] \
  || fail 'dataset output root must have mode 0700'

source_files=(
  PROVENANCE.txt
  SHA256SUMS
  airbob-production-seed.sql.gz
  backend-migrations.sha256
  benchmark-fixture.json
  traffic-v1.json
  database-fingerprint.tsv
  etl-code.sha256
  release-metadata.txt
  source.sha256
)
shopt -s nullglob dotglob
source_entries=("$etl_release_dir"/*)
[[ ${#source_entries[@]} -eq ${#source_files[@]} ]] \
  || fail 'ETL source release must contain exactly ten files'
for source_name in "${source_files[@]}"; do
  [[ -f "$etl_release_dir/$source_name" && ! -L "$etl_release_dir/$source_name" ]] \
    || fail 'ETL source release contains a missing or unsafe file'
done

final_dir="$output_root/$dataset_release"
incomplete_dir="$output_root/$dataset_release.incomplete"
release_lock_dir="$output_root/.$dataset_release.assemble.lock"
if ! mkdir -m 700 "$release_lock_dir"; then
  fail 'another assembler owns the dataset release lock'
fi
lock_acquired=true
[[ ! -e "$final_dir" && ! -L "$final_dir" ]] \
  || fail 'final dataset release already exists'
[[ ! -e "$incomplete_dir" && ! -L "$incomplete_dir" ]] \
  || fail 'incomplete dataset release already exists'
mkdir -m 700 "$incomplete_dir" || fail 'unable to create the incomplete dataset release'
incomplete_created=true
[[ "$(stat_uid "$incomplete_dir")" == "$(id -u)" && "$(stat_mode "$incomplete_dir")" == 700 ]] \
  || fail 'incomplete dataset release ownership or mode is unsafe'
staging_dir="$incomplete_dir/.staging"
mkdir -m 700 "$staging_dir"

for source_name in "${source_files[@]}"; do
  cp "$etl_release_dir/$source_name" "$staging_dir/$source_name"
  chmod 600 "$staging_dir/$source_name"
  [[ -f "$staging_dir/$source_name" && ! -L "$staging_dir/$source_name" ]] \
    || fail 'unable to stage an ETL source release file safely'
done
cp "$attestation_file" "$staging_dir/attestation.json"
chmod 600 "$staging_dir/attestation.json"
[[ -f "$staging_dir/attestation.json" && ! -L "$staging_dir/attestation.json" ]] \
  || fail 'unable to stage the dataset attestation safely'

expected_checksum_files=(
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
source_dump_sha=''
source_database_fingerprint_sha=''
traffic_manifest_sha=''
exec 3< "$staging_dir/SHA256SUMS"
for source_name in "${expected_checksum_files[@]}"; do
  checksum_line=''
  IFS= read -r checksum_line <&3 \
    || fail 'ETL SHA256SUMS must contain nine canonical newline-terminated entries'
  [[ "$checksum_line" =~ ^([0-9a-f]{64})\ \ ([A-Za-z0-9._-]+)$ ]] \
    || fail 'ETL SHA256SUMS contains a malformed entry'
  checksum_digest=${BASH_REMATCH[1]}
  checksum_name=${BASH_REMATCH[2]}
  [[ "$checksum_name" == "$source_name" ]] \
    || fail 'ETL SHA256SUMS entries are not canonical or complete'
  [[ "$(sha256_file "$staging_dir/$source_name")" == "$checksum_digest" ]] \
    || fail 'ETL source release checksum verification failed'
  case "$source_name" in
    airbob-production-seed.sql.gz) source_dump_sha=$checksum_digest ;;
    database-fingerprint.tsv) source_database_fingerprint_sha=$checksum_digest ;;
    traffic-v1.json) traffic_manifest_sha=$checksum_digest ;;
  esac
done
extra_checksum_line=''
if IFS= read -r extra_checksum_line <&3; then
  fail 'ETL SHA256SUMS contains an unexpected extra entry'
fi
exec 3<&-
canonical_payload_sha=$(sha256_file "$staging_dir/SHA256SUMS")

require_small_text_file "$staging_dir/backend-migrations.sha256" 1048576
migration_count=0
previous_migration_path=''
seen_migration_versions=' '
while IFS= read -r migration_line; do
  [[ "$migration_line" =~ ^[0-9a-f]{64}\ \ (\./V([0-9]+)__[A-Za-z0-9][A-Za-z0-9._-]*\.sql)$ ]] \
    || fail 'backend migration inventory contains a malformed entry'
  migration_path=${BASH_REMATCH[1]}
  migration_version=${BASH_REMATCH[2]}
  ((migration_count += 1))
  [[ -z "$previous_migration_path" || "$migration_path" > "$previous_migration_path" ]] \
    || fail 'backend migration inventory is not bytewise path-sorted'
  [[ "$migration_version" -ge 1 && "$migration_version" -le 20 ]] \
    || fail 'backend migration inventory is outside V1-V20'
  [[ "$seen_migration_versions" != *" $migration_version "* ]] \
    || fail 'backend migration inventory contains a duplicate version'
  seen_migration_versions="${seen_migration_versions}${migration_version} "
  previous_migration_path=$migration_path
done < "$staging_dir/backend-migrations.sha256"
[[ $migration_count -eq 20 ]] \
  || fail 'backend migration inventory must cover exactly V1-V20'
for migration_version in {1..20}; do
  [[ "$seen_migration_versions" == *" $migration_version "* ]] \
    || fail 'backend migration inventory is missing a V1-V20 migration'
done

require_small_text_file "$staging_dir/release-metadata.txt" 65536
metadata_keys=(
  format
  release_id
  dump
  manifest
  traffic_manifest
  traffic_manifest_sha256
  traffic_dataset_version
  traffic_dataset_run_id
  traffic_flyway_version
  traffic_migration_digest
  fingerprint
  required_rows
  recovery
)
metadata_lines=()
while IFS= read -r metadata_line; do
  metadata_lines+=("$metadata_line")
done < "$staging_dir/release-metadata.txt"
[[ ${#metadata_lines[@]} -eq ${#metadata_keys[@]} ]] \
  || fail 'ETL release metadata must contain exactly thirteen canonical lines'
for metadata_index in "${!metadata_keys[@]}"; do
  metadata_key=${metadata_keys[$metadata_index]}
  metadata_line=${metadata_lines[$metadata_index]}
  [[ "$metadata_line" == "$metadata_key="* ]] \
    || fail 'ETL release metadata keys or ordering are invalid'
  metadata_value=${metadata_line#*=}
  [[ -n "$metadata_value" ]] || fail 'ETL release metadata contains a blank value'
  contains_secret_marker "$metadata_key" && fail 'ETL release metadata contains a secret-like key'
  contains_secret_marker "$metadata_value" && fail 'ETL release metadata contains a secret-like value'
  case "$metadata_key" in
    format) metadata_format=$metadata_value ;;
    release_id) source_release_id=$metadata_value ;;
    dump) metadata_dump=$metadata_value ;;
    manifest) metadata_manifest=$metadata_value ;;
    traffic_manifest) metadata_traffic_manifest=$metadata_value ;;
    traffic_manifest_sha256) metadata_traffic_sha=$metadata_value ;;
    traffic_dataset_version) metadata_traffic_version=$metadata_value ;;
    traffic_dataset_run_id) metadata_traffic_run_id=$metadata_value ;;
    traffic_flyway_version) metadata_traffic_flyway=$metadata_value ;;
    traffic_migration_digest) metadata_traffic_migration_digest=$metadata_value ;;
    fingerprint) metadata_fingerprint=$metadata_value ;;
    required_rows) metadata_required_rows=$metadata_value ;;
    recovery) metadata_recovery=$metadata_value ;;
  esac
done
[[ "$metadata_format" == airbob-production-seed-release-v1 ]] \
  || fail 'ETL release metadata format is unsupported'
[[ "$source_release_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] \
  || fail 'ETL source release id is unsafe'
[[ "$metadata_dump" == airbob-production-seed.sql.gz \
  && "$metadata_manifest" == benchmark-fixture.json \
  && "$metadata_traffic_manifest" == traffic-v1.json \
  && "$metadata_fingerprint" == database-fingerprint.tsv ]] \
  || fail 'ETL release metadata names an unexpected artifact'
[[ "$metadata_traffic_sha" == "$traffic_manifest_sha" ]] \
  || fail 'ETL traffic manifest checksum binding failed'
[[ "$metadata_traffic_run_id" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$ ]] \
  || fail 'ETL traffic dataset run id is not canonical'
[[ "$metadata_traffic_version" == traffic-v1 \
  && "$metadata_traffic_flyway" == 20 \
  && "$metadata_required_rows" == 201 \
  && "$metadata_recovery" == reset-flyway-v1-v20-etl-reseed-before-traffic ]] \
  || fail 'ETL release metadata does not satisfy the V20 traffic contract'
[[ "$metadata_traffic_migration_digest" =~ ^sha256:[0-9a-f]{64}$ ]] \
  || fail 'ETL traffic migration digest is invalid'

require_small_text_file "$staging_dir/PROVENANCE.txt" 1048576
provenance_lines=()
while IFS= read -r provenance_line; do
  provenance_lines+=("$provenance_line")
done < "$staging_dir/PROVENANCE.txt"
[[ ${#provenance_lines[@]} -ge 16 ]] \
  || fail 'ETL provenance envelope is incomplete'
[[ "${provenance_lines[0]}" == format=airbob-production-seed-provenance-v1 ]] \
  || fail 'ETL provenance format is unsupported'
[[ "${provenance_lines[1]}" =~ ^etl_head=([0-9a-f]{40})$ ]] \
  || fail 'ETL provenance commit is invalid'
etl_commit=${BASH_REMATCH[1]}
[[ "${provenance_lines[2]}" =~ ^backend_head=[0-9a-f]{40}$ ]] \
  || fail 'ETL provenance backend commit is invalid'
for provenance_index in 3 4 5 6; do
  provenance_line=${provenance_lines[$provenance_index]}
  case "$provenance_index" in
    3) provenance_key=java ;;
    4) provenance_key=gradle ;;
    5) provenance_key=mysql ;;
    6) provenance_key=mysqldump ;;
  esac
  [[ "$provenance_line" == "$provenance_key="* && "$provenance_line" != "$provenance_key=" ]] \
    || fail 'ETL provenance toolchain envelope is invalid'
  contains_secret_marker "${provenance_line#*=}" \
    && fail 'ETL provenance contains a secret-like value'
done
[[ "${provenance_lines[7]}" == porcelain_status_begin \
  && -z "${provenance_lines[8]}" \
  && "${provenance_lines[9]}" == porcelain_status_end \
  && "${provenance_lines[10]}" == backend_porcelain_status_begin \
  && -z "${provenance_lines[11]}" \
  && "${provenance_lines[12]}" == backend_porcelain_status_end \
  && "${provenance_lines[13]}" == options_begin \
  && "${provenance_lines[${#provenance_lines[@]}-1]}" == options_end ]] \
  || fail 'ETL provenance block envelope is invalid or dirty'
seen_provenance_keys=$'format\netl_head\nbackend_head\njava\ngradle\nmysql\nmysqldump\n'
profile=''
service_schema=''
benchmark_fixtures=''
traffic_fixtures=''
provenance_traffic_seed=''
provenance_traffic_anchor=''
provenance_traffic_valid_until=''
provenance_traffic_timezone=''
for ((provenance_index = 14; provenance_index < ${#provenance_lines[@]} - 1; provenance_index += 1)); do
  provenance_line=${provenance_lines[$provenance_index]}
  [[ "$provenance_line" =~ ^([a-z][a-z0-9_]*)=(.+)$ ]] \
    || fail 'ETL provenance contains a malformed option'
  provenance_key=${BASH_REMATCH[1]}
  provenance_value=${BASH_REMATCH[2]}
  if printf '%s' "$seen_provenance_keys" | grep -Fxq "$provenance_key"; then
    fail 'ETL provenance contains a duplicate key'
  fi
  seen_provenance_keys="${seen_provenance_keys}${provenance_key}"$'\n'
  contains_secret_marker "$provenance_key" && fail 'ETL provenance contains a secret-like key'
  contains_secret_marker "$provenance_value" && fail 'ETL provenance contains a secret-like value'
  case "$provenance_key" in
    profile) profile=$provenance_value ;;
    service_schema) service_schema=$provenance_value ;;
    benchmark_fixtures) benchmark_fixtures=$provenance_value ;;
    traffic_fixtures) traffic_fixtures=$provenance_value ;;
    traffic_seed) provenance_traffic_seed=$provenance_value ;;
    traffic_anchor_time) provenance_traffic_anchor=$provenance_value ;;
    traffic_valid_until) provenance_traffic_valid_until=$provenance_value ;;
    traffic_timezone) provenance_traffic_timezone=$provenance_value ;;
  esac
done
[[ "$profile" == large \
  && "$service_schema" == airbobdb \
  && "$benchmark_fixtures" == true \
  && "$traffic_fixtures" == true ]] \
  || fail 'ETL provenance does not enable the required V20 fixture contract'

require_small_text_file "$staging_dir/benchmark-fixture.json" 1048576
jq -se '
  length == 1 and
  (.[0] |
    .datasetVersion == "nplus1-v1" and
    ([.. | objects | keys[]] | all(
      test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not
    )) and
    ([.. | strings] | all(
      test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not
    ))
  )
' "$staging_dir/benchmark-fixture.json" >/dev/null \
  || fail 'benchmark fixture does not satisfy the nplus1-v1 contract'

require_small_text_file "$staging_dir/traffic-v1.json" 8388608
jq -se \
  --arg runId "$metadata_traffic_run_id" \
  --arg migrationDigest "$metadata_traffic_migration_digest" '
  length == 1 and
  (.[0] |
    .datasetVersion == "traffic-v1" and
    .datasetRunId == $runId and
    (.seed | type == "number" and floor == . and . > 0) and
    (.anchorTime | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}$")) and
    .timezone == "Asia/Seoul" and
    (.validUntil | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}$")) and
    ((has("anchorInstant") | not) or
      (.anchorInstant | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$") and (fromdateiso8601 | type == "number"))) and
    ((has("validUntilInstant") | not) or
      (.validUntilInstant | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$") and (fromdateiso8601 | type == "number"))) and
    .schema.flywayVersion == "20" and
    .schema.migrationDigest == $migrationDigest and
    ([.. | objects | keys[]] | all(
      test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not
    )) and
    ([.. | strings] | all(
      test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not
    ))
  )
' "$staging_dir/traffic-v1.json" >/dev/null \
  || fail 'traffic manifest does not satisfy the V20 release metadata contract'
traffic_anchor_local=$(jq -r '.anchorTime' "$staging_dir/traffic-v1.json")
traffic_timezone=$(jq -r '.timezone' "$staging_dir/traffic-v1.json")
traffic_valid_until_local=$(jq -r '.validUntil' "$staging_dir/traffic-v1.json")
traffic_seed=$(jq -r '.seed' "$staging_dir/traffic-v1.json")
[[ "$provenance_traffic_seed" == "$traffic_seed" \
  && "$provenance_traffic_anchor" == "$traffic_anchor_local" \
  && "$provenance_traffic_valid_until" == "$traffic_valid_until_local" \
  && "$provenance_traffic_timezone" == "$traffic_timezone" ]] \
  || fail 'ETL provenance contradicts the traffic manifest contract'
converted_traffic_anchor=$(local_timestamp_to_utc "$traffic_anchor_local" "$traffic_timezone") \
  || fail 'traffic manifest anchor time or timezone is invalid'
converted_traffic_valid_until=$(local_timestamp_to_utc "$traffic_valid_until_local" "$traffic_timezone") \
  || fail 'traffic manifest valid-until time or timezone is invalid'
declared_traffic_anchor=$(jq -r '.anchorInstant // empty' "$staging_dir/traffic-v1.json")
declared_traffic_valid_until=$(jq -r '.validUntilInstant // empty' "$staging_dir/traffic-v1.json")
[[ -z "$declared_traffic_anchor" || "$declared_traffic_anchor" == "$converted_traffic_anchor" ]] \
  || fail 'traffic local timestamps do not match their canonical UTC instants'
[[ -z "$declared_traffic_valid_until" \
  || "$declared_traffic_valid_until" == "$converted_traffic_valid_until" ]] \
  || fail 'traffic local timestamps do not match their canonical UTC instants'

require_small_text_file "$staging_dir/attestation.json" 8388608
jq -se \
  --arg sourceReleasePayloadSha256 "$canonical_payload_sha" \
  --arg sourceDumpSha256 "$source_dump_sha" \
  --arg sourceDatabaseFingerprintSha256 "$source_database_fingerprint_sha" \
  --argjson requiredRows "$metadata_required_rows" '
  def exact_keys($wanted): (keys | sort) == ($wanted | sort);
  def sha256: type == "string" and test("^[0-9a-f]{64}$");
  def utc: type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$");
  length == 1 and
  (.[0] |
    exact_keys([
      "schemaVersion", "sourceReleasePayloadSha256", "sourceDumpSha256",
      "sourceDatabaseFingerprintSha256", "flywayVersion", "flywayHistoryRows",
      "migrationChecksumSha256", "schemaFingerprintSha256", "outboxState",
      "expectedTableRows", "capturedAt"
    ]) and
    .schemaVersion == 1 and
    .sourceReleasePayloadSha256 == $sourceReleasePayloadSha256 and
    .sourceDumpSha256 == $sourceDumpSha256 and
    .sourceDatabaseFingerprintSha256 == $sourceDatabaseFingerprintSha256 and
    .flywayVersion == "20" and
    .flywayHistoryRows == 20 and
    (.migrationChecksumSha256 | sha256) and
    (.schemaFingerprintSha256 | sha256) and
    .outboxState == "empty" and
    (.expectedTableRows | type == "object" and length > 0) and
    (.expectedTableRows | has("flyway_schema_history") and has("outbox") and has("accommodation")) and
    .expectedTableRows.flyway_schema_history == 20 and
    .expectedTableRows.outbox == 0 and
    .expectedTableRows.accommodation >= $requiredRows and
    all(.expectedTableRows | to_entries[];
      (.key | test("^[a-z][a-z0-9_]{0,63}$")) and
      (.value | type == "number" and floor == . and . >= 0)
    ) and
    (.capturedAt | utc) and
    (.capturedAt | fromdateiso8601 | type == "number") and
    ([.. | objects | keys[]] | all(
      test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not
    ))
  )
' "$staging_dir/attestation.json" >/dev/null \
  || fail 'dataset attestation does not satisfy or bind the source release contract'

migration_checksum_sha=$(jq -r '.migrationChecksumSha256' "$staging_dir/attestation.json")
schema_fingerprint_sha=$(jq -r '.schemaFingerprintSha256' "$staging_dir/attestation.json")
expected_table_rows=$(jq -cS '.expectedTableRows' "$staging_dir/attestation.json")
attestation_captured_at=$(jq -r '.capturedAt' "$staging_dir/attestation.json")
jq -en \
  --arg trafficAnchor "$converted_traffic_anchor" \
  --arg sourceValidUntil "$converted_traffic_valid_until" \
  --arg attestationCapturedAt "$attestation_captured_at" \
  --arg evaluationTime "$evaluation_time" \
  --arg suppliedValidUntil "$valid_until" '
    $suppliedValidUntil == $sourceValidUntil and
    ($trafficAnchor | fromdateiso8601) <= ($attestationCapturedAt | fromdateiso8601) and
    ($attestationCapturedAt | fromdateiso8601) <= ($evaluationTime | fromdateiso8601) and
    ($evaluationTime | fromdateiso8601) < ($sourceValidUntil | fromdateiso8601) and
    ($sourceValidUntil | fromdateiso8601) > now
  ' >/dev/null || fail 'supplied timestamps do not match the attested traffic validity window'

mkdir -m 700 "$incomplete_dir/benchmark" "$incomplete_dir/mysql"
cp "$staging_dir/benchmark-fixture.json" "$incomplete_dir/benchmark/manifest.json"
chmod 600 "$incomplete_dir/benchmark/manifest.json"
benchmark_manifest_sha=$(sha256_file "$incomplete_dir/benchmark/manifest.json")
[[ "$benchmark_manifest_sha" == "$(sha256_file "$staging_dir/benchmark-fixture.json")" ]] \
  || fail 'benchmark fixture byte copy failed'

source_sql_sha=$(gzip -dc "$staging_dir/airbob-production-seed.sql.gz" | sha256_stream) \
  || fail 'ETL source database dump is not valid gzip'
gzip -dc "$staging_dir/airbob-production-seed.sql.gz" \
  | zstd --threads=1 --no-progress --quiet --stdout \
    > "$incomplete_dir/mysql/airbob.sql.zst" \
  || fail 'unable to convert the ETL dump to zstd'
chmod 600 "$incomplete_dir/mysql/airbob.sql.zst"
[[ -s "$incomplete_dir/mysql/airbob.sql.zst" ]] \
  || fail 'assembled zstd database dump is empty'
released_sql_sha=$(zstd --quiet --decompress --stdout \
  "$incomplete_dir/mysql/airbob.sql.zst" | sha256_stream) \
  || fail 'assembled database dump is not valid zstd'
[[ "$source_sql_sha" =~ ^[0-9a-f]{64}$ \
  && "$source_sql_sha" != e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 \
  && "$source_sql_sha" == "$released_sql_sha" ]] \
  || fail 'gzip to zstd conversion did not preserve the SQL bytes'

dump_sha=$(sha256_file "$incomplete_dir/mysql/airbob.sql.zst")
printf '%s  airbob.sql.zst\n' "$dump_sha" > "$incomplete_dir/mysql/sha256.txt"
chmod 600 "$incomplete_dir/mysql/sha256.txt"

cleanup_staging
staging_dir=''

jq -nS \
  --arg datasetRelease "$dataset_release" \
  --arg datasetRunId "$metadata_traffic_run_id" \
  --arg etlCommit "$etl_commit" \
  --arg profile "$profile" \
  --arg canonicalPayloadSha256 "$canonical_payload_sha" \
  --arg benchmarkManifestSha256 "$benchmark_manifest_sha" \
  --arg dumpSha256 "$dump_sha" \
  --arg migrationChecksumSha256 "$migration_checksum_sha" \
  --arg schemaFingerprintSha256 "$schema_fingerprint_sha" \
  --arg evaluationTime "$evaluation_time" \
  --arg validUntil "$valid_until" \
  --argjson expectedTableRows "$expected_table_rows" '
  {
    schemaVersion: 1,
    releaseKind: "pipeline-rehearsal",
    datasetRelease: $datasetRelease,
    datasetRunId: $datasetRunId,
    source: {
      datasetVersion: "nplus1-v1",
      etlCommit: $etlCommit,
      seed: "airbob-production-seed-v1",
      profile: $profile,
      manifestVersion: "benchmark-fixture-v1",
      canonicalPayloadSha256: $canonicalPayloadSha256,
      benchmarkManifestKey: "benchmark/manifest.json",
      benchmarkManifestSha256: $benchmarkManifestSha256
    },
    mysql: {
      dumpKey: "mysql/airbob.sql.zst",
      dumpSha256: $dumpSha256,
      flywayVersion: "20",
      migrationChecksumSha256: $migrationChecksumSha256,
      schemaFingerprintSha256: $schemaFingerprintSha256,
      timezone: "UTC",
      evaluationTime: $evaluationTime,
      validUntil: $validUntil,
      outboxPolicy: "absent",
      expectedTableRows: $expectedTableRows
    },
    couponPreparation: [],
    kafka: {
      topics: [
        {name: "PAYMENT_OPERATION.events", partitions: 3, retentionMs: 86400000},
        {name: "PAYMENT_OPERATION.events.RETRY", partitions: 3, retentionMs: 86400000},
        {name: "PAYMENT_OPERATION.events.DLT", partitions: 3, retentionMs: 86400000},
        {name: "ACCOMMODATION_INDEX.events", partitions: 3, retentionMs: 86400000},
        {name: "ACCOMMODATION_INDEX.events.RETRY", partitions: 3, retentionMs: 86400000},
        {name: "ACCOMMODATION_INDEX.events.DLT", partitions: 3, retentionMs: 86400000},
        {name: "ACCOMMODATION_CACHE.events", partitions: 3, retentionMs: 86400000},
        {name: "ACCOMMODATION_CACHE.events.RETRY", partitions: 3, retentionMs: 86400000},
        {name: "ACCOMMODATION_CACHE.events.DLT", partitions: 3, retentionMs: 86400000},
        {name: "OPERATOR_ALERT.events", partitions: 3, retentionMs: 86400000},
        {name: "OPERATOR_ALERT.events.RETRY", partitions: 3, retentionMs: 86400000},
        {name: "OPERATOR_ALERT.events.DLT", partitions: 3, retentionMs: 86400000}
      ]
    },
    search: {enabled: false}
  }
' > "$incomplete_dir/.manifest.json.tmp"
chmod 600 "$incomplete_dir/.manifest.json.tmp"
mv "$incomplete_dir/.manifest.json.tmp" "$incomplete_dir/manifest.json"

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
validator="$script_dir/verify-dataset-release.sh"
[[ -x "$validator" && ! -L "$validator" ]] \
  || fail 'dataset release validator is missing or unsafe'
"$validator" "$incomplete_dir" "$dataset_release" pipeline-rehearsal >/dev/null \
  || fail 'assembled dataset release failed local verification'

actual_inventory=$(CDPATH= cd -P -- "$incomplete_dir" && find . -mindepth 1 -print | sort)
expected_inventory=$(printf '%s\n' \
  './benchmark' \
  './benchmark/manifest.json' \
  './manifest.json' \
  './mysql' \
  './mysql/airbob.sql.zst' \
  './mysql/sha256.txt')
[[ "$actual_inventory" == "$expected_inventory" ]] \
  || fail 'assembled dataset release contains an unexpected artifact'
for output_file in \
  "$incomplete_dir/manifest.json" \
  "$incomplete_dir/benchmark/manifest.json" \
  "$incomplete_dir/mysql/airbob.sql.zst" \
  "$incomplete_dir/mysql/sha256.txt"
do
  [[ -f "$output_file" && ! -L "$output_file" ]] \
    || fail 'assembled dataset release contains an unsafe artifact'
done

[[ ! -e "$final_dir" && ! -L "$final_dir" ]] \
  || fail 'final dataset release appeared before atomic promotion'
mv "$incomplete_dir" "$final_dir"
incomplete_dir=''
incomplete_created=false
rmdir "$release_lock_dir" || fail 'unable to release the dataset assembly lock'
lock_acquired=false
trap - EXIT HUP INT TERM
printf '%s\n' 'dataset release assembled and verified'
