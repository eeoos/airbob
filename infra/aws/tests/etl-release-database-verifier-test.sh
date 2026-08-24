#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
verifier="$repo_root/infra/aws/scripts/verify-etl-release-database.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-etl-release-database-verifier-test.XXXXXX")
fixture_password='verifier-test-password-do-not-log'

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  rm -rf "$temp_dir"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  printf 'ETL release database verifier test failed: %s\n' "$1" >&2
  exit 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

write_fingerprint() {
  local output=$1
  local mismatch_value=${2:-0}
  cat > "$output" <<EOF
foreign_key_checks_global	1
foreign_key_checks_session	1
orphan_total	0
traffic_admin_account_count	3
traffic_admin_role_mismatch_count	0
traffic_cohort_account_count	27
traffic_cohort_distinct_email_count	27
traffic_cohort_mismatch_count	$mismatch_value
traffic_manifest_dataset_version_match	1
traffic_payment_graph_invalid_count	0
traffic_public_detail_anchor_mismatch_count	0
traffic_public_detail_last_window_mismatch_count	0
traffic_public_detail_overlap_count	0
traffic_public_review_image_mismatch_count	0
traffic_public_review_mismatch_count	0
traffic_review_summary_mismatch_count	0
traffic_settlement_target_mismatch_count	0
traffic_viewer_state_mismatch_count	0
traffic_wishlist_denormalization_mismatch_count	0
EOF
}

write_etl_repository() {
  local repository=$1
  mkdir -p "$repository/scripts"
  git -C "$repository" init -q
  git -C "$repository" config user.name 'Airbob Test'
  git -C "$repository" config user.email 'airbob-test@example.invalid'
  cat > "$repository/scripts/verify-production-seed.sql" <<'EOF'
SELECT 'production-contract-marker';
EOF
  cat > "$repository/scripts/verify-traffic-v1.sql" <<'EOF'
SELECT 'traffic-contract-marker';
EOF
  git -C "$repository" add scripts/verify-production-seed.sql scripts/verify-traffic-v1.sql
  git -C "$repository" commit -qm 'fixture verifier contracts'
}

write_release() {
  local release=$1
  local repository=$2
  local commit=$3
  local contract_path

  mkdir -p "$release"
  printf '%s\n' '{"datasetVersion":"nplus1-v1"}' > "$release/benchmark-fixture.json"
  jq -nS '
    {
      datasetVersion: "traffic-v1",
      cohorts: [range(0; 27) as $index |
        {accounts: [{email: ("traffic-" + ($index | tostring) + "@airbob.cloud")}]}]
    }
  ' > "$release/traffic-v1.json"
  write_fingerprint "$release/database-fingerprint.tsv"
  printf 'format=airbob-production-seed-provenance-v1\netl_head=%s\n' "$commit" \
    > "$release/PROVENANCE.txt"
  : > "$release/etl-code.sha256"
  for contract_path in scripts/verify-production-seed.sql scripts/verify-traffic-v1.sql; do
    git -C "$repository" show "$commit:$contract_path" > "$temp_dir/contract-file"
    printf '%s  %s\n' "$(sha256_file "$temp_dir/contract-file")" "$contract_path" \
      >> "$release/etl-code.sha256"
  done
  printf '%s  %s\n' \
    'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee' \
    'src/main/java/Unrelated.java' >> "$release/etl-code.sha256"
}

write_fake_mysql() {
  mkdir -p "$temp_dir/bin"
  cat > "$temp_dir/bin/mysql" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${MYSQL_PWD:-}" == 'verifier-test-password-do-not-log' ]] || exit 91
[[ -z "${AIRBOB_DATASET_DB_PASSWORD:-}" ]] || exit 92
query=$(cat)
case "$query" in
  *'@@server_uuid'*)
    count=0
    [[ ! -f "$AIRBOB_FAKE_UUID_COUNTER" ]] || count=$(cat "$AIRBOB_FAKE_UUID_COUNTER")
    count=$((count + 1))
    printf '%s\n' "$count" > "$AIRBOB_FAKE_UUID_COUNTER"
    if [[ "${AIRBOB_FAKE_UUID_DRIFT:-false}" == true && "$count" -gt 1 ]]; then
      printf '%s\t%s\t%s\n' 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee' '1' '1'
    else
      printf '%s\t%s\t%s\n' "${AIRBOB_FAKE_SERVER_UUID:-00112233-4455-6677-8899-aabbccddeeff}" "${AIRBOB_FAKE_READ_ONLY:-1}" "${AIRBOB_FAKE_SUPER_READ_ONLY:-1}"
    fi
    ;;
  *'production-contract-marker'*'traffic-contract-marker'*)
    printf '%s' "$query" > "$AIRBOB_FAKE_SQL_LOG"
    cat "$AIRBOB_FAKE_VERIFICATION_OUTPUT"
    ;;
  *) exit 93 ;;
esac
EOF
  chmod 700 "$temp_dir/bin/mysql"
}

run_verifier() {
  local release=$1
  local output=$2
  shift 2
  : > "$temp_dir/uuid-counter"
  env \
    PATH="$temp_dir/bin:$PATH" \
    AIRBOB_DATASET_ETL_REPOSITORY="$temp_dir/etl" \
    AIRBOB_DATASET_DB_HOST=127.0.0.1 \
    AIRBOB_DATASET_DB_PORT=3307 \
    AIRBOB_DATASET_DB_USER=airbob_verify \
    AIRBOB_DATASET_DB_PASSWORD="$fixture_password" \
    AIRBOB_DATASET_DB_NAME=airbobdb \
    AIRBOB_DATASET_DB_QUIESCED=true \
    AIRBOB_FAKE_UUID_COUNTER="$temp_dir/uuid-counter" \
    AIRBOB_FAKE_SQL_LOG="$temp_dir/mysql.sql" \
    AIRBOB_FAKE_VERIFICATION_OUTPUT="${AIRBOB_FAKE_VERIFICATION_OUTPUT:-$release/database-fingerprint.tsv}" \
    "$@" "$verifier" "$release" > "$output"
}

expect_failure() {
  local label=$1
  shift
  if "$@" > "$temp_dir/$label.stdout" 2> "$temp_dir/$label.stderr"; then
    fail "expected rejection: $label"
  fi
  if grep -Fq -- "$fixture_password" "$temp_dir/$label.stdout" "$temp_dir/$label.stderr"; then
    fail "secret leaked for rejection: $label"
  fi
}

[[ -x "$verifier" ]] || fail 'verifier script is missing or not executable'
/bin/bash -n "$verifier"
command -v git >/dev/null 2>&1 || fail 'git is required'
command -v jq >/dev/null 2>&1 || fail 'jq is required'

write_fake_mysql
write_etl_repository "$temp_dir/etl"
source_commit=$(git -C "$temp_dir/etl" rev-parse HEAD)
write_release "$temp_dir/release" "$temp_dir/etl" "$source_commit"

# The verifier must read the committed blobs, never mutable working-tree files.
printf '%s\n' "SELECT 'uncommitted-production-contract';" \
  > "$temp_dir/etl/scripts/verify-production-seed.sql"
printf '%s\n' "SELECT 'uncommitted-traffic-contract';" \
  > "$temp_dir/etl/scripts/verify-traffic-v1.sql"

run_verifier "$temp_dir/release" "$temp_dir/result.json"
contract_inventory="$temp_dir/expected-contract-inventory.sha256"
awk '$2 == "scripts/verify-production-seed.sql" || $2 == "scripts/verify-traffic-v1.sql"' \
  "$temp_dir/release/etl-code.sha256" > "$contract_inventory"
expected_contract_inventory_sha=$(sha256_file "$contract_inventory")
expected_subset_sha=$(sha256_file "$temp_dir/release/database-fingerprint.tsv")
jq -e \
  --arg sourceEtlCommit "$source_commit" \
  --arg verifierContractInventorySha256 "$expected_contract_inventory_sha" \
  --arg databaseFingerprintSubsetSha256 "$expected_subset_sha" '
  (keys | sort) == ([
    "schemaVersion", "sourceEtlCommit", "databaseServerUuid",
    "verifierContractInventorySha256", "databaseFingerprintSubsetSha256"
  ] | sort) and
  .schemaVersion == 1 and
  .sourceEtlCommit == $sourceEtlCommit and
  .databaseServerUuid == "00112233-4455-6677-8899-aabbccddeeff" and
  .verifierContractInventorySha256 == $verifierContractInventorySha256 and
  .databaseFingerprintSubsetSha256 == $databaseFingerprintSubsetSha256
' "$temp_dir/result.json" >/dev/null || fail 'verifier receipt does not match the exact contract'
grep -Fq "SELECT 'production-contract-marker';" "$temp_dir/mysql.sql" \
  || fail 'committed production verifier was not executed'
grep -Fq "SELECT 'traffic-contract-marker';" "$temp_dir/mysql.sql" \
  || fail 'committed traffic verifier was not executed'
if grep -Fq 'uncommitted-' "$temp_dir/mysql.sql"; then
  fail 'verifier executed a mutable working-tree contract'
fi
benchmark_hex=$(od -An -v -tx1 "$temp_dir/release/benchmark-fixture.json" | tr -d ' \n')
traffic_hex=$(od -An -v -tx1 "$temp_dir/release/traffic-v1.json" | tr -d ' \n')
{
  printf "SET @manifest_json = CONVERT(UNHEX('%s') USING utf8mb4);\n" "$benchmark_hex"
  printf "SET @traffic_manifest_json = CONVERT(UNHEX('%s') USING utf8mb4);\n" "$traffic_hex"
} > "$temp_dir/expected-manifest-prefix.sql"
sed -n '1,2p' "$temp_dir/mysql.sql" > "$temp_dir/actual-manifest-prefix.sql"
cmp -s "$temp_dir/expected-manifest-prefix.sql" "$temp_dir/actual-manifest-prefix.sql" \
  || fail 'manifest bytes were not bound to verifier SQL'
if grep -Fq -- "$fixture_password" "$temp_dir/result.json" "$temp_dir/mysql.sql"; then
  fail 'successful verification leaked the database password'
fi

bad_inventory="$temp_dir/release-bad-inventory"
cp -R "$temp_dir/release" "$bad_inventory"
sed '1s/^[0-9a-f]/f/' "$bad_inventory/etl-code.sha256" > "$bad_inventory/etl-code.next"
mv "$bad_inventory/etl-code.next" "$bad_inventory/etl-code.sha256"
expect_failure contract-digest-mismatch run_verifier \
  "$bad_inventory" "$temp_dir/bad-inventory.json"

bad_actual="$temp_dir/actual-mismatch.tsv"
write_fingerprint "$bad_actual" 1
AIRBOB_FAKE_VERIFICATION_OUTPUT="$bad_actual" expect_failure subset-mismatch \
  run_verifier "$temp_dir/release" "$temp_dir/subset-mismatch.json"

bad_gate="$temp_dir/release-bad-gate"
cp -R "$temp_dir/release" "$bad_gate"
write_fingerprint "$bad_gate/database-fingerprint.tsv" 1
expect_failure nonzero-mismatch run_verifier "$bad_gate" "$temp_dir/nonzero-mismatch.json"

duplicate_metric="$temp_dir/release-duplicate-metric"
cp -R "$temp_dir/release" "$duplicate_metric"
printf '%s\n' $'orphan_total\t0' >> "$duplicate_metric/database-fingerprint.tsv"
expect_failure duplicate-required-metric run_verifier \
  "$duplicate_metric" "$temp_dir/duplicate-metric.json"

AIRBOB_FAKE_READ_ONLY=0 expect_failure writable-database \
  run_verifier "$temp_dir/release" "$temp_dir/writable.json"
AIRBOB_FAKE_SUPER_READ_ONLY=0 expect_failure super-writable-database \
  run_verifier "$temp_dir/release" "$temp_dir/super-writable.json"
AIRBOB_FAKE_UUID_DRIFT=true expect_failure server-uuid-drift \
  run_verifier "$temp_dir/release" "$temp_dir/uuid-drift.json"

printf '%s\n' 'ETL release database verifier test passed'
