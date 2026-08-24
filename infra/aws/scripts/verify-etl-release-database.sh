#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
umask 077

usage() {
  printf 'usage: %s <etl-release-dir>\n' "${0##*/}" >&2
  exit 64
}

fail() {
  printf 'ETL release database verification failed: %s\n' "$1" >&2
  exit 1
}

[[ "$#" -eq 1 ]] || usage
release_dir=$1

required_environment=(
  AIRBOB_DATASET_ETL_REPOSITORY
  AIRBOB_DATASET_DB_HOST
  AIRBOB_DATASET_DB_PORT
  AIRBOB_DATASET_DB_USER
  AIRBOB_DATASET_DB_PASSWORD
  AIRBOB_DATASET_DB_NAME
)
for environment_name in "${required_environment[@]}"; do
  [[ -n "${!environment_name:-}" ]] \
    || fail "missing required environment: $environment_name"
done
database_password=$AIRBOB_DATASET_DB_PASSWORD
unset AIRBOB_DATASET_DB_PASSWORD

[[ "${AIRBOB_DATASET_DB_QUIESCED:-}" == true ]] \
  || fail 'AIRBOB_DATASET_DB_QUIESCED=true is required'
[[ "$AIRBOB_DATASET_DB_NAME" == airbobdb ]] \
  || fail 'database name must be airbobdb'
[[ "$AIRBOB_DATASET_DB_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}$ ]] \
  || fail 'database host is invalid'
[[ "$AIRBOB_DATASET_DB_PORT" =~ ^[0-9]{1,5}$ ]] \
  || fail 'database port is invalid'
((10#$AIRBOB_DATASET_DB_PORT >= 1 && 10#$AIRBOB_DATASET_DB_PORT <= 65535)) \
  || fail 'database port is invalid'
[[ "$AIRBOB_DATASET_DB_USER" =~ ^[a-zA-Z][a-zA-Z0-9_]{0,31}$ ]] \
  || fail 'database user is invalid'

for required_command in git jq mysql awk grep sort cmp od tr tail wc mktemp rm cat; do
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
release_dir=$(CDPATH= cd -P -- "$release_dir" && pwd -P)

etl_repository=$AIRBOB_DATASET_ETL_REPOSITORY
[[ -d "$etl_repository" && ! -L "$etl_repository" ]] \
  || fail 'ETL repository is missing or unsafe'
etl_repository=$(CDPATH= cd -P -- "$etl_repository" && pwd -P)
[[ "$(git -C "$etl_repository" rev-parse --is-inside-work-tree 2>/dev/null)" == true ]] \
  || fail 'ETL repository is not a Git working tree'

required_release_files=(
  PROVENANCE.txt
  benchmark-fixture.json
  database-fingerprint.tsv
  etl-code.sha256
  traffic-v1.json
)
for file_name in "${required_release_files[@]}"; do
  [[ -f "$release_dir/$file_name" && ! -L "$release_dir/$file_name" ]] \
    || fail 'required ETL release artifact is missing or unsafe'
done

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-etl-release-database.XXXXXX") \
  || fail 'cannot create verification workspace'
cleanup() {
  unset MYSQL_PWD database_password
  rm -rf "$work_dir"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

provenance_file="$release_dir/PROVENANCE.txt"
has_final_newline "$provenance_file" \
  || fail 'ETL provenance must end with one newline'
source_etl_commit=$(awk -F= '
  $1 == "etl_head" {
    count += 1
    value = substr($0, index($0, "=") + 1)
  }
  END {
    if (count == 1) print value
  }
' "$provenance_file")
[[ "$source_etl_commit" =~ ^[0-9a-f]{40}$ ]] \
  || fail 'ETL provenance commit is missing or invalid'
resolved_etl_commit=$(git -C "$etl_repository" rev-parse --verify "$source_etl_commit^{commit}" 2>/dev/null) \
  || fail 'ETL provenance commit is unavailable'
[[ "$resolved_etl_commit" == "$source_etl_commit" ]] \
  || fail 'ETL provenance commit is not canonical'

code_inventory="$release_dir/etl-code.sha256"
has_final_newline "$code_inventory" \
  || fail 'ETL code inventory must end with one newline'

contract_paths=(
  scripts/verify-production-seed.sql
  scripts/verify-traffic-v1.sql
)
contract_inventory="$work_dir/verifier-contract-inventory.sha256"
: > "$contract_inventory"
for contract_path in "${contract_paths[@]}"; do
  inventory_entry="$work_dir/inventory-entry.tsv"
  awk -v target="$contract_path" '
    substr($0, 67) == target {
      count += 1
      if (substr($0, 65, 2) != "  ") invalid = 1
      digest = substr($0, 1, 64)
    }
    END {
      if (count == 1 && invalid != 1) print digest
    }
  ' "$code_inventory" > "$inventory_entry"
  [[ "$(wc -l < "$inventory_entry" | tr -d '[:space:]')" == 1 ]] \
    || fail 'ETL verifier contract is missing or duplicated in the code inventory'
  expected_contract_sha=$(awk 'NR == 1 { print $1 }' "$inventory_entry")
  [[ "$expected_contract_sha" =~ ^[0-9a-f]{64}$ ]] \
    || fail 'ETL verifier contract inventory digest is invalid'

  extracted_contract="$work_dir/${contract_path##*/}"
  git -C "$etl_repository" show "$source_etl_commit:$contract_path" \
    > "$extracted_contract" 2>/dev/null \
    || fail 'ETL verifier contract cannot be extracted from the provenance commit'
  [[ -s "$extracted_contract" && ! -L "$extracted_contract" ]] \
    || fail 'ETL verifier contract blob is empty or unsafe'
  has_final_newline "$extracted_contract" \
    || fail 'ETL verifier contract blob must end with one newline'
  [[ "$(sha256_file "$extracted_contract")" == "$expected_contract_sha" ]] \
    || fail 'ETL verifier contract blob does not match the release code inventory'
  printf '%s  %s\n' "$expected_contract_sha" "$contract_path" \
    >> "$contract_inventory"
done
verifier_contract_inventory_sha256=$(sha256_file "$contract_inventory")

benchmark_hex=$(od -An -v -tx1 "$release_dir/benchmark-fixture.json" | tr -d ' \n')
traffic_hex=$(od -An -v -tx1 "$release_dir/traffic-v1.json" | tr -d ' \n')
[[ -n "$benchmark_hex" && "$benchmark_hex" =~ ^[0-9a-f]+$ ]] \
  || fail 'benchmark manifest cannot be encoded for verification'
[[ -n "$traffic_hex" && "$traffic_hex" =~ ^[0-9a-f]+$ ]] \
  || fail 'traffic manifest cannot be encoded for verification'

verification_sql="$work_dir/verification.sql"
{
  printf "SET @manifest_json = CONVERT(UNHEX('%s') USING utf8mb4);\n" "$benchmark_hex"
  printf "SET @traffic_manifest_json = CONVERT(UNHEX('%s') USING utf8mb4);\n" "$traffic_hex"
  cat "$work_dir/verify-production-seed.sql"
  cat "$work_dir/verify-traffic-v1.sql"
} > "$verification_sql"

mysql_file() {
  local label=$1
  local input_file=$2
  local output_file=$3
  if ! MYSQL_PWD="$database_password" mysql \
    --protocol=TCP \
    --host="$AIRBOB_DATASET_DB_HOST" \
    --port="$AIRBOB_DATASET_DB_PORT" \
    --user="$AIRBOB_DATASET_DB_USER" \
    --batch \
    --raw \
    --skip-column-names \
    "$AIRBOB_DATASET_DB_NAME" < "$input_file" > "$output_file" 2>/dev/null; then
    fail "database query failed: $label"
  fi
}

server_query="$work_dir/server-query.sql"
printf '%s\n' \
  'SELECT LOWER(@@server_uuid), @@GLOBAL.read_only, @@GLOBAL.super_read_only;' \
  > "$server_query"
server_before="$work_dir/server-before.tsv"
mysql_file server-identity-before "$server_query" "$server_before"
[[ "$(wc -l < "$server_before" | tr -d '[:space:]')" == 1 ]] \
  || fail 'database server identity is malformed'
IFS=$'\t' read -r database_server_uuid database_read_only database_super_read_only extra_field \
  < "$server_before"
[[ -z "${extra_field:-}" \
   && "$database_server_uuid" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ \
   && "$database_read_only" == 1 \
   && "$database_super_read_only" == 1 ]] \
  || fail 'database server identity or read-only state is invalid'

verification_output="$work_dir/restore-verification.tsv"
mysql_file release-contract "$verification_sql" "$verification_output"
has_final_newline "$verification_output" \
  || fail 'database verifier output must end with one newline'

source_subset="$work_dir/source-fingerprint-subset.tsv"
actual_subset="$work_dir/actual-fingerprint-subset.tsv"
if ! grep -E '^(traffic_|orphan_total[[:space:]]|foreign_key_checks_)' \
  "$release_dir/database-fingerprint.tsv" > "$source_subset"; then
  fail 'source database fingerprint does not contain the required verification subset'
fi
if ! grep -E '^(traffic_|orphan_total[[:space:]]|foreign_key_checks_)' \
  "$verification_output" > "$actual_subset"; then
  fail 'live database verification does not contain the required fingerprint subset'
fi
has_final_newline "$source_subset" && has_final_newline "$actual_subset" \
  || fail 'database fingerprint subset is not newline-terminated'

validate_metric_subset() {
  local subset_file=$1
  awk -F $'\t' '
    NF != 2 { exit 1 }
    $1 !~ /^[a-z][a-z0-9_]*$/ { exit 1 }
    $2 !~ /^[0-9]+$/ { exit 1 }
    seen[$1]++ { if (seen[$1] != 1) exit 1 }
    END { if (NR == 0) exit 1 }
  ' "$subset_file" || fail 'database fingerprint subset is malformed or duplicated'
}
validate_metric_subset "$source_subset"
validate_metric_subset "$actual_subset"

cmp -s "$source_subset" "$actual_subset" \
  || fail 'live database fingerprint subset does not match the shipped release'

required_metrics=(
  foreign_key_checks_global
  foreign_key_checks_session
  orphan_total
  traffic_admin_account_count
  traffic_admin_role_mismatch_count
  traffic_cohort_account_count
  traffic_cohort_distinct_email_count
  traffic_cohort_mismatch_count
  traffic_manifest_dataset_version_match
  traffic_payment_graph_invalid_count
  traffic_public_detail_anchor_mismatch_count
  traffic_public_detail_last_window_mismatch_count
  traffic_public_detail_overlap_count
  traffic_public_review_image_mismatch_count
  traffic_public_review_mismatch_count
  traffic_review_summary_mismatch_count
  traffic_settlement_target_mismatch_count
  traffic_viewer_state_mismatch_count
  traffic_wishlist_denormalization_mismatch_count
)
metric_value() {
  local metric=$1
  awk -F $'\t' -v target="$metric" '$1 == target { print $2 }' "$actual_subset"
}
for required_metric in "${required_metrics[@]}"; do
  [[ "$(awk -F $'\t' -v target="$required_metric" '$1 == target { count += 1 } END { print count + 0 }' "$actual_subset")" == 1 ]] \
    || fail 'database fingerprint subset is missing a required metric'
done

[[ "$(metric_value foreign_key_checks_global)" == 1 \
   && "$(metric_value foreign_key_checks_session)" == 1 ]] \
  || fail 'database foreign-key enforcement is not enabled'
[[ "$(metric_value orphan_total)" == 0 ]] \
  || fail 'database orphan gate failed'
[[ "$(metric_value traffic_manifest_dataset_version_match)" == 1 ]] \
  || fail 'traffic dataset version gate failed'
[[ "$(metric_value traffic_admin_account_count)" == 3 ]] \
  || fail 'traffic administrator account gate failed'

expected_cohort_count=$(jq -er '[.cohorts[].accounts[]] | length' \
  "$release_dir/traffic-v1.json" 2>/dev/null) \
  || fail 'traffic manifest cohort capacity is invalid'
expected_distinct_cohort_count=$(jq -er '[.cohorts[].accounts[].email] | unique | length' \
  "$release_dir/traffic-v1.json" 2>/dev/null) \
  || fail 'traffic manifest cohort identity set is invalid'
[[ "$expected_cohort_count" =~ ^[1-9][0-9]*$ \
   && "$expected_distinct_cohort_count" == "$expected_cohort_count" ]] \
  || fail 'traffic manifest cohort identities are empty or duplicated'
[[ "$(metric_value traffic_cohort_account_count)" == "$expected_cohort_count" \
   && "$(metric_value traffic_cohort_distinct_email_count)" == "$expected_distinct_cohort_count" ]] \
  || fail 'database traffic cohort identity gate failed'

awk -F $'\t' '
  $1 ~ /^foreign_key_checks_/ && $2 != "1" { exit 1 }
  $1 == "orphan_total" && $2 != "0" { exit 1 }
  $1 == "traffic_public_detail_overlap_count" && $2 != "0" { exit 1 }
  $1 ~ /^traffic_.*_(mismatch|invalid)_count$/ && $2 != "0" { exit 1 }
' "$actual_subset" || fail 'database fingerprint integrity gate failed'

server_after="$work_dir/server-after.tsv"
mysql_file server-identity-after "$server_query" "$server_after"
cmp -s "$server_before" "$server_after" \
  || fail 'database server identity or read-only state changed during verification'

database_fingerprint_subset_sha256=$(sha256_file "$actual_subset")
jq -nS \
  --arg sourceEtlCommit "$source_etl_commit" \
  --arg databaseServerUuid "$database_server_uuid" \
  --arg verifierContractInventorySha256 "$verifier_contract_inventory_sha256" \
  --arg databaseFingerprintSubsetSha256 "$database_fingerprint_subset_sha256" '
  {
    schemaVersion: 1,
    sourceEtlCommit: $sourceEtlCommit,
    databaseServerUuid: $databaseServerUuid,
    verifierContractInventorySha256: $verifierContractInventorySha256,
    databaseFingerprintSubsetSha256: $databaseFingerprintSubsetSha256
  }
'
