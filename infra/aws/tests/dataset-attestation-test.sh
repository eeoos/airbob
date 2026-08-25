#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../../.." && pwd)
capture="$repo_root/infra/aws/scripts/capture-dataset-attestation.sh"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-dataset-attestation-test.XXXXXX")
fixture_password='attestation-test-password-do-not-log'
restore_fixture_password='restore-test-password-do-not-log'

cleanup() {
  [[ "${AIRBOB_KEEP_TEST_TMP:-false}" == true ]] || rm -rf "$tmp_dir"
}
trap cleanup EXIT HUP INT TERM

fail() {
  printf 'dataset attestation test failed: %s\n' "$1" >&2
  exit 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

write_checksums() {
  local root=$1
  local file_name

  : > "$root/SHA256SUMS"
  for file_name in \
    PROVENANCE.txt \
    airbob-production-seed.sql.gz \
    backend-migrations.sha256 \
    benchmark-fixture.json \
    database-fingerprint.tsv \
    etl-code.sha256 \
    release-metadata.txt \
    source.sha256 \
    traffic-v1.json; do
    printf '%s  %s\n' "$(sha256_file "$root/$file_name")" "$file_name" >> "$root/SHA256SUMS"
  done
}

refresh_traffic_manifest_binding() {
  local root=$1
  local traffic_sha

  traffic_sha=$(sha256_file "$root/traffic-v1.json")
  sed "s/^traffic_manifest_sha256=.*/traffic_manifest_sha256=$traffic_sha/" \
    "$root/release-metadata.txt" > "$root/release-metadata.next"
  mv "$root/release-metadata.next" "$root/release-metadata.txt"
  write_checksums "$root"
}

write_fingerprint() {
  local output=$1
  cat > "$output" <<'EOF'
accommodation	201	bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
foreign_key_checks_global	1
foreign_key_checks_session	1
orphan_total	0
traffic_admin_account_count	3
traffic_admin_role_mismatch_count	0
traffic_cohort_account_count	27
traffic_cohort_distinct_email_count	27
traffic_cohort_mismatch_count	0
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
  printf '%s\n' "SELECT 'production-contract-marker';" \
    > "$repository/scripts/verify-production-seed.sql"
  printf '%s\n' "SELECT 'traffic-contract-marker';" \
    > "$repository/scripts/verify-traffic-v1.sql"
  git -C "$repository" add scripts/verify-production-seed.sql scripts/verify-traffic-v1.sql
  git -C "$repository" commit -qm 'fixture verifier contracts'
}

write_release() {
  local root=$1
  local repository=$2
  local source_commit=$3
  local contract_path
  local traffic_sha
  local migration_digest
  local migration_stream

  mkdir -p "$root"
  printf 'format=airbob-production-seed-provenance-v1\netl_head=%s\n' "$source_commit" \
    > "$root/PROVENANCE.txt"
  printf '%s\n' 'CREATE TABLE exact_dump_binding (id BIGINT PRIMARY KEY);' \
    | gzip -n > "$root/airbob-production-seed.sql.gz"
  : > "$root/backend-migrations.sha256"
  for migration_version in 10 11 12 13 14 15 16 17 18 19 1 20 21 22 23 24 25 26 27 2 3 4 5 6 7 8 9; do
    printf '%s  ./V%s__migration_%s.sql\n' \
      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
      "$migration_version" \
      "$migration_version" \
      >> "$root/backend-migrations.sha256"
  done
  migration_stream="$root/.traffic-migration-canonical"
  : > "$migration_stream"
  for migration_version in $(seq 1 27); do
    printf '%s|V%s__migration_%s.sql|%s\n' \
      "$migration_version" \
      "$migration_version" \
      "$migration_version" \
      "$migration_version" \
      >> "$migration_stream"
  done
  migration_digest="sha256:$(sha256_file "$migration_stream")"
  rm "$migration_stream"
  printf '%s\n' '{"datasetVersion":"nplus1-v1"}' > "$root/benchmark-fixture.json"
  jq -n --arg migrationDigest "$migration_digest" '
    {
      datasetVersion: "traffic-v1",
      datasetRunId: "20260817T000000Z-12345678",
      schema: {flywayVersion: "27", migrationDigest: $migrationDigest},
      cohorts: [range(0; 27) as $index |
        {accounts: [{email: ("traffic-" + ($index | tostring) + "@airbob.cloud")}]}]
    }
  ' > "$root/traffic-v1.json"
  write_fingerprint "$root/database-fingerprint.tsv"
  : > "$root/etl-code.sha256"
  for contract_path in scripts/verify-production-seed.sql scripts/verify-traffic-v1.sql; do
    git -C "$repository" show "$source_commit:$contract_path" > "$tmp_dir/contract-file"
    printf '%s  %s\n' "$(sha256_file "$tmp_dir/contract-file")" "$contract_path" \
      >> "$root/etl-code.sha256"
  done
  printf '%s  %s\n' \
    'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee' \
    'src/main/java/Unrelated.java' >> "$root/etl-code.sha256"
  printf '%s\n' 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd  source.csv' > "$root/source.sha256"
  traffic_sha=$(sha256_file "$root/traffic-v1.json")
  {
    printf '%s\n' 'format=airbob-production-seed-release-v1'
    printf '%s\n' 'release_id=production-seed-20260817T010000Z'
    printf '%s\n' 'dump=airbob-production-seed.sql.gz'
    printf '%s\n' 'manifest=benchmark-fixture.json'
    printf '%s\n' 'traffic_manifest=traffic-v1.json'
    printf 'traffic_manifest_sha256=%s\n' "$traffic_sha"
    printf '%s\n' 'traffic_dataset_version=traffic-v1'
    printf '%s\n' 'traffic_dataset_run_id=20260817T000000Z-12345678'
    printf '%s\n' 'traffic_flyway_version=27'
    printf 'traffic_migration_digest=%s\n' "$migration_digest"
    printf '%s\n' 'fingerprint=database-fingerprint.tsv'
    printf '%s\n' 'required_rows=201'
    printf '%s\n' 'recovery=reset-flyway-v1-v27-etl-reseed-before-traffic'
  } > "$root/release-metadata.txt"

  write_checksums "$root"
}

write_fake_mysql() {
  mkdir -p "$tmp_dir/bin"
  printf '%s\n' '#!/usr/bin/env bash' > "$tmp_dir/bin/mysql"
  printf '%s\n' 'set -euo pipefail' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'user=""' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'for argument in "$@"; do case "$argument" in --user=*) user=${argument#--user=} ;; esac; done' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'case "$user" in' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  airbob_restore) [[ "${MYSQL_PWD:-}" == "restore-test-password-do-not-log" ]] || exit 91 ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  airbob_capture) [[ "${MYSQL_PWD:-}" == "attestation-test-password-do-not-log" ]] || exit 91 ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *) exit 91 ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'esac' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '[[ -z "${AIRBOB_DATASET_DB_PASSWORD:-}" && -z "${AIRBOB_DATASET_DB_RESTORE_PASSWORD:-}" ]] || exit 93' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'input_file="${AIRBOB_FAKE_INPUT_PREFIX:?}.$$"' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'trap '\''rm -f "$input_file"'\'' EXIT' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'cat > "$input_file"' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'query=$(sed -e "s/[[:space:]][[:space:]]*/ /g" -e "s/^ //" -e "s/ $//" "$input_file")' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'if [[ "$user" == airbob_restore && "$query" != *"information_schema.SCHEMATA"* && "$query" != "SET GLOBAL super_read_only = ON;" ]]; then' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  cp "$input_file" "$AIRBOB_FAKE_IMPORT_CAPTURE"' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  printf "IMPORT <airbobdb>\n---\n" >> "$AIRBOB_FAKE_MYSQL_LOG"' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  [[ "${AIRBOB_FAKE_IMPORT_FAILURE:-false}" != true ]] || exit 94' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  cmp -s "$AIRBOB_FAKE_IMPORT_CAPTURE" "$AIRBOB_FAKE_EXPECTED_IMPORT" || exit 95' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  : > "$AIRBOB_FAKE_IMPORT_MARKER"' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  exit 0' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'fi' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '{ printf "ARGS"; for argument in "$@"; do printf " <%s>" "$argument"; done; printf "\nQUERY <%s>\n---\n" "$query"; } >> "$AIRBOB_FAKE_MYSQL_LOG"' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'if [[ "$user" == airbob_capture ]]; then [[ -e "$AIRBOB_FAKE_IMPORT_MARKER" && -e "$AIRBOB_FAKE_READONLY_MARKER" ]] || exit 96; fi' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'case "$query" in' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"information_schema.SCHEMATA"*) printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n" "${AIRBOB_FAKE_RESTORE_SERVER_UUID:-00112233-4455-6677-8899-aabbccddeeff}" 1 "${AIRBOB_FAKE_PREEXISTING_OBJECTS:-0}" 0 0 0 0 ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  "SET GLOBAL super_read_only = ON;") : > "$AIRBOB_FAKE_READONLY_MARKER" ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"LOWER(@@server_uuid)"*) printf "%s\t%s\t%s\n" "${AIRBOB_FAKE_SERVER_UUID:-00112233-4455-6677-8899-aabbccddeeff}" "${AIRBOB_FAKE_READ_ONLY:-1}" "${AIRBOB_FAKE_SUPER_READ_ONLY:-1}" ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"production-contract-marker"*"traffic-contract-marker"*) if [[ -n "${AIRBOB_FAKE_LINEAGE_DRIFT_MARKER:-}" && -e "$AIRBOB_FAKE_LINEAGE_DRIFT_MARKER" ]]; then sed "s/^traffic_public_detail_overlap_count[[:space:]]*0$/traffic_public_detail_overlap_count\t1/" "$AIRBOB_FAKE_VERIFICATION_OUTPUT"; else [[ -z "${AIRBOB_FAKE_LINEAGE_DRIFT_MARKER:-}" ]] || : > "$AIRBOB_FAKE_LINEAGE_DRIFT_MARKER"; cat "$AIRBOB_FAKE_VERIFICATION_OUTPUT"; fi ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"COUNT(*) AS history_rows"*) printf "%b\n" "${AIRBOB_FAKE_FLYWAY_SUMMARY:-27\\t27\\t27\\t0}" ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"SELECT installed_rank, COALESCE(version,"*) for rank in $(seq 1 27); do printf "%s\t%s\tmigration %s\tSQL\tV%s__migration_%s.sql\t%s\t1\n" "$rank" "$rank" "$rank" "$rank" "$rank" "$rank"; done ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"FROM information_schema.COLUMNS"*"UNION ALL"*) printf "INDEX\t62\t69\t31\t3C4E554C4C3E\t30\t41\t3C4E554C4C3E\t\t4254524545\t594553\t3C4E554C4C3E\nCOLUMN\t61\t62\t31\t62\t626967696E74\t4E4F\t3C4E554C4C3E\t\t3C4E554C4C3E\t3C4E554C4C3E\t\n" ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"FROM information_schema.TABLES"*) printf "accommodation\naccommodation_inventory_day\nflyway_schema_history\noutbox\nreservation\n" ;;' >> "$tmp_dir/bin/mysql"
	printf '%s\n' '  *"SELECT COUNT(*) FROM outbox"*) printf "%s\n" "${AIRBOB_FAKE_OUTBOX_COUNT:-0}" ;;' >> "$tmp_dir/bin/mysql"
	printf '%s\n' '  *"time_zone_id NOT REGEXP"*) printf "%s\n" "${AIRBOB_FAKE_INVALID_PUBLISHED_TIMEZONES:-0}" ;;' >> "$tmp_dir/bin/mysql"
	printf '%s\n' '  *"SELECT COUNT(*) FROM "*"accommodation_inventory_day"*) printf "0\n" ;;' >> "$tmp_dir/bin/mysql"
	printf '%s\n' '  *"SELECT COUNT(*) FROM "*"accommodation"*) if [[ -n "${AIRBOB_FAKE_MUTATION_MARKER:-}" ]]; then if [[ -e "$AIRBOB_FAKE_MUTATION_MARKER" ]]; then printf "202\n"; else : > "$AIRBOB_FAKE_MUTATION_MARKER"; printf "201\n"; fi; else printf "%s\n" "${AIRBOB_FAKE_ACCOMMODATION_ROWS:-201}"; fi ;;' >> "$tmp_dir/bin/mysql"
	printf '%s\n' '  *"SELECT COUNT(*) FROM "*"flyway_schema_history"*) printf "27\n" ;;' >> "$tmp_dir/bin/mysql"
	printf '%s\n' '  *"SELECT COUNT(*) FROM "*"outbox"*) printf "%s\n" "${AIRBOB_FAKE_OUTBOX_COUNT:-0}" ;;' >> "$tmp_dir/bin/mysql"
	printf '%s\n' '  *"SELECT COUNT(*) FROM "*"reservation"*) printf "0\n" ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *) exit 92 ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'esac' >> "$tmp_dir/bin/mysql"
  chmod 700 "$tmp_dir/bin/mysql"
}

run_capture() {
  local release=$1
  local output=$2
  shift 2
  rm -f "$output.imported" "$output.readonly" "$output.import.sql"
  gzip -dc "$release/airbob-production-seed.sql.gz" > "$tmp_dir/expected-import.sql"
  env \
    PATH="$tmp_dir/bin:$PATH" \
    AIRBOB_FAKE_MYSQL_LOG="$tmp_dir/mysql.log" \
    AIRBOB_FAKE_VERIFICATION_OUTPUT="$release/database-fingerprint.tsv" \
    AIRBOB_DATASET_ETL_REPOSITORY="$tmp_dir/etl" \
    AIRBOB_DATASET_DB_HOST=127.0.0.1 \
    AIRBOB_DATASET_DB_PORT=3307 \
    AIRBOB_DATASET_DB_USER=airbob_capture \
    AIRBOB_DATASET_DB_PASSWORD="$fixture_password" \
    AIRBOB_DATASET_DB_RESTORE_USER=airbob_restore \
    AIRBOB_DATASET_DB_RESTORE_PASSWORD="$restore_fixture_password" \
    AIRBOB_DATASET_DB_NAME=airbobdb \
    AIRBOB_DATASET_DB_QUIESCED=true \
    AIRBOB_FAKE_EXPECTED_IMPORT="$tmp_dir/expected-import.sql" \
    AIRBOB_FAKE_IMPORT_CAPTURE="$output.import.sql" \
    AIRBOB_FAKE_INPUT_PREFIX="$tmp_dir/mysql-input" \
    AIRBOB_FAKE_IMPORT_MARKER="$output.imported" \
    AIRBOB_FAKE_READONLY_MARKER="$output.readonly" \
    AIRBOB_DATASET_CAPTURED_AT=2026-08-17T03:04:05Z \
    "$@" "$capture" "$release" "$output"
}

expect_failure() {
  local label=$1
  shift
  if "$@" > "$tmp_dir/$label.stdout" 2> "$tmp_dir/$label.stderr"; then
    fail "expected rejection: $label"
  fi
  if grep -Fq -- "$fixture_password" "$tmp_dir/$label.stdout" "$tmp_dir/$label.stderr" >/dev/null 2>&1; then
    fail "secret leaked for rejection: $label"
  fi
  if grep -Fq -- "$restore_fixture_password" "$tmp_dir/$label.stdout" "$tmp_dir/$label.stderr" >/dev/null 2>&1; then
    fail "restore secret leaked for rejection: $label"
  fi
}

[[ -x "$capture" ]] || fail 'capture script is missing or not executable'
/bin/bash -n "$capture"
command -v jq >/dev/null 2>&1 || fail 'jq is required for the test'
command -v git >/dev/null 2>&1 || fail 'git is required for the test'
write_fake_mysql
write_etl_repository "$tmp_dir/etl"
source_commit=$(git -C "$tmp_dir/etl" rev-parse HEAD)
write_release "$tmp_dir/release" "$tmp_dir/etl" "$source_commit"
: > "$tmp_dir/mysql.log"

expect_failure output-inside-release run_capture \
  "$tmp_dir/release" "$tmp_dir/release/attestation.json"
[[ ! -e "$tmp_dir/release/attestation.json" ]] \
  || fail 'rejected in-release output changed the ETL source inventory'

run_capture "$tmp_dir/release" "$tmp_dir/attestation.json"

expected_release_sha=$(sha256_file "$tmp_dir/release/SHA256SUMS")
expected_dump_sha=$(awk '$2 == "airbob-production-seed.sql.gz" { print $1 }' "$tmp_dir/release/SHA256SUMS")
expected_fingerprint_sha=$(awk '$2 == "database-fingerprint.tsv" { print $1 }' "$tmp_dir/release/SHA256SUMS")
jq -e \
  --arg releaseSha "$expected_release_sha" \
  --arg dumpSha "$expected_dump_sha" \
  --arg fingerprintSha "$expected_fingerprint_sha" \
  --arg sourceEtlCommit "$source_commit" \
  --arg databaseFingerprintSubsetSha256 "$(
    grep -E '^(traffic_|orphan_total[[:space:]]|foreign_key_checks_)' \
      "$tmp_dir/release/database-fingerprint.tsv" > "$tmp_dir/expected-subset.tsv"
    sha256_file "$tmp_dir/expected-subset.tsv"
  )" \
  --arg verifierContractInventorySha256 "$(
    awk '$2 == "scripts/verify-production-seed.sql" || $2 == "scripts/verify-traffic-v1.sql"' \
      "$tmp_dir/release/etl-code.sha256" > "$tmp_dir/expected-contract-inventory.sha256"
    sha256_file "$tmp_dir/expected-contract-inventory.sha256"
  )" '
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
  .sourceReleasePayloadSha256 == $releaseSha and
  .sourceDumpSha256 == $dumpSha and
  .restoredDumpSha256 == $dumpSha and
  .sourceDatabaseFingerprintSha256 == $fingerprintSha and
  .sourceEtlCommit == $sourceEtlCommit and
  .databaseServerUuid == "00112233-4455-6677-8899-aabbccddeeff" and
  .verifierContractInventorySha256 == $verifierContractInventorySha256 and
  .databaseFingerprintSubsetSha256 == $databaseFingerprintSubsetSha256 and
  .flywayVersion == "27" and
  .flywayHistoryRows == 27 and
  (.migrationChecksumSha256 | test("^[0-9a-f]{64}$")) and
  (.schemaFingerprintSha256 | test("^[0-9a-f]{64}$")) and
  .outboxState == "empty" and
  .expectedTableRows == {
    accommodation: 201,
    accommodation_inventory_day: 0,
    flyway_schema_history: 27,
    outbox: 0,
    reservation: 0
  } and
  .capturedAt == "2026-08-17T03:04:05Z"
' "$tmp_dir/attestation.json" >/dev/null || fail 'attestation JSON contract mismatch'

if stat -f '%Lp' "$tmp_dir/attestation.json" >/dev/null 2>&1; then
  output_mode=$(stat -f '%Lp' "$tmp_dir/attestation.json")
else
  output_mode=$(stat -c '%a' "$tmp_dir/attestation.json")
fi
[[ "$output_mode" == 600 ]] || fail 'attestation output mode is not 0600'

[[ $(grep -c '^ARGS ' "$tmp_dir/mysql.log") -eq 29 ]] || fail 'unexpected MySQL query count'
[[ $(grep -c '^IMPORT ' "$tmp_dir/mysql.log") -eq 1 ]] || fail 'exactly one dump import is required'
[[ $(grep -c '<airbobdb>' "$tmp_dir/mysql.log") -eq 30 ]] || fail 'every database operation must explicitly select airbobdb'
! grep -En -- '--password|attestation-test-password' "$tmp_dir/mysql.log" >/dev/null \
  || fail 'database password reached MySQL argv/query logs'
! grep -En -- 'restore-test-password' "$tmp_dir/mysql.log" >/dev/null \
  || fail 'restore password reached MySQL argv/query logs'
[[ -e "$tmp_dir/attestation.json.imported" && -e "$tmp_dir/attestation.json.readonly" ]] \
  || fail 'dump import or read-only transition did not complete'
cmp -s "$tmp_dir/attestation.json.import.sql" "$tmp_dir/expected-import.sql" \
  || fail 'import did not receive the exact decompressed private dump copy'
import_line=$(grep -n '^IMPORT ' "$tmp_dir/mysql.log" | cut -d: -f1)
readonly_line=$(grep -n 'QUERY <SET GLOBAL super_read_only = ON;>' "$tmp_dir/mysql.log" | cut -d: -f1)
first_attestor_line=$(grep -n '<--user=airbob_capture>' "$tmp_dir/mysql.log" | head -n 1 | cut -d: -f1)
[[ "$import_line" -lt "$readonly_line" && "$readonly_line" -lt "$first_attestor_line" ]] \
  || fail 'restore, read-only transition, and restricted attestation ordering is invalid'
for required_query in \
  'FROM information_schema.SCHEMATA' \
  'FROM information_schema.VIEWS' \
  'FROM information_schema.ROUTINES' \
  'FROM information_schema.EVENTS' \
  'FROM information_schema.TRIGGERS' \
  'SET GLOBAL super_read_only = ON' \
  'COUNT(*) AS history_rows' \
  'SELECT LOWER(@@server_uuid), @@GLOBAL.read_only, @@GLOBAL.super_read_only' \
  "SELECT installed_rank, COALESCE(version, '<NULL>')" \
  'FROM information_schema.COLUMNS' \
  'FROM information_schema.STATISTICS' \
  'FROM information_schema.TABLE_CONSTRAINTS' \
  'information_schema.KEY_COLUMN_USAGE' \
  'FROM information_schema.REFERENTIAL_CONSTRAINTS' \
  'FROM information_schema.CHECK_CONSTRAINTS' \
  "TABLE_SCHEMA = 'airbobdb'" \
  "TABLE_TYPE = 'BASE TABLE'" \
  'SELECT COUNT(*) FROM `accommodation`' \
	'SELECT COUNT(*) FROM `accommodation_inventory_day`' \
  'SELECT COUNT(*) FROM `flyway_schema_history`' \
	'SELECT COUNT(*) FROM `outbox`' \
	'SELECT COUNT(*) FROM `reservation`' \
		"time_zone_id NOT REGEXP '^[A-Za-z][A-Za-z0-9._+-]*(/[A-Za-z0-9._+-]+)*$'"; do
  grep -Fq -- "$required_query" "$tmp_dir/mysql.log" || fail "missing exact query contract: $required_query"
done

cp -R "$tmp_dir/release" "$tmp_dir/short-release-id"
sed 's/^release_id=.*/release_id=A/' \
  "$tmp_dir/short-release-id/release-metadata.txt" \
  > "$tmp_dir/short-release-id/release-metadata.next"
mv "$tmp_dir/short-release-id/release-metadata.next" \
  "$tmp_dir/short-release-id/release-metadata.txt"
write_checksums "$tmp_dir/short-release-id"
run_capture "$tmp_dir/short-release-id" "$tmp_dir/short-release-id.json"

expect_failure overwrite run_capture "$tmp_dir/release" "$tmp_dir/attestation.json"

expect_failure nonempty-restore-target run_capture \
  "$tmp_dir/release" "$tmp_dir/nonempty-restore-target.json" \
  AIRBOB_FAKE_PREEXISTING_OBJECTS=1
[[ ! -e "$tmp_dir/nonempty-restore-target.json" \
  && ! -e "$tmp_dir/nonempty-restore-target.json.imported" ]] \
  || fail 'nonempty restore target produced an attestation or imported the dump'
grep -Fq 'database restore target must be the existing empty airbobdb schema' \
  "$tmp_dir/nonempty-restore-target.stderr" \
  || fail 'nonempty restore target did not reach the empty-schema gate'

expect_failure dump-import-failure run_capture \
  "$tmp_dir/release" "$tmp_dir/dump-import-failure.json" \
  AIRBOB_FAKE_IMPORT_FAILURE=true
[[ ! -e "$tmp_dir/dump-import-failure.json" ]] \
  || fail 'failed dump import produced an attestation'
[[ -e "$tmp_dir/dump-import-failure.json.readonly" ]] \
  || fail 'failed dump import did not leave the target super-read-only'
grep -Fq 'exact database dump import failed' "$tmp_dir/dump-import-failure.stderr" \
  || fail 'failed dump import did not reach the exact-import gate'

expect_failure restore-server-mismatch run_capture \
  "$tmp_dir/release" "$tmp_dir/restore-server-mismatch.json" \
  AIRBOB_FAKE_RESTORE_SERVER_UUID=11112233-4455-6677-8899-aabbccddeeff
[[ ! -e "$tmp_dir/restore-server-mismatch.json" ]] \
  || fail 'different restore and attestation servers produced an attestation'
grep -Fq 'attested database server differs from the exact dump restore target' \
  "$tmp_dir/restore-server-mismatch.stderr" \
  || fail 'restore server mismatch did not reach the UUID binding gate'

expect_failure shared-database-credentials run_capture \
  "$tmp_dir/release" "$tmp_dir/shared-database-credentials.json" \
  AIRBOB_DATASET_DB_RESTORE_USER=airbob_capture \
  AIRBOB_DATASET_DB_RESTORE_PASSWORD="$fixture_password"
[[ ! -e "$tmp_dir/shared-database-credentials.json" ]] \
  || fail 'shared restore and attestation credentials produced an attestation'

cp -R "$tmp_dir/release" "$tmp_dir/bad-checksums"
printf '%s  %s\n' "$(sha256_file "$tmp_dir/bad-checksums/PROVENANCE.txt")" PROVENANCE.txt >> "$tmp_dir/bad-checksums/SHA256SUMS"
expect_failure duplicate-checksum run_capture "$tmp_dir/bad-checksums" "$tmp_dir/bad-checksums.json"

cp -R "$tmp_dir/release" "$tmp_dir/bad-metadata"
printf '%s\n' 'release_id=duplicate' >> "$tmp_dir/bad-metadata/release-metadata.txt"
write_checksums "$tmp_dir/bad-metadata"
expect_failure duplicate-metadata run_capture "$tmp_dir/bad-metadata" "$tmp_dir/bad-metadata.json"

cp -R "$tmp_dir/release" "$tmp_dir/reordered-metadata"
{
  sed -n '2p' "$tmp_dir/reordered-metadata/release-metadata.txt"
  sed -n '1p' "$tmp_dir/reordered-metadata/release-metadata.txt"
  sed -n '3,13p' "$tmp_dir/reordered-metadata/release-metadata.txt"
} > "$tmp_dir/reordered-metadata/release-metadata.next"
mv "$tmp_dir/reordered-metadata/release-metadata.next" \
  "$tmp_dir/reordered-metadata/release-metadata.txt"
write_checksums "$tmp_dir/reordered-metadata"
expect_failure reordered-metadata run_capture \
  "$tmp_dir/reordered-metadata" "$tmp_dir/reordered-metadata.json"

cp -R "$tmp_dir/release" "$tmp_dir/mismatched-run-id"
sed 's/^traffic_dataset_run_id=.*/traffic_dataset_run_id=20260817T000000Z-87654321/' \
  "$tmp_dir/mismatched-run-id/release-metadata.txt" \
  > "$tmp_dir/mismatched-run-id/release-metadata.next"
mv "$tmp_dir/mismatched-run-id/release-metadata.next" \
  "$tmp_dir/mismatched-run-id/release-metadata.txt"
write_checksums "$tmp_dir/mismatched-run-id"
expect_failure mismatched-run-id run_capture \
  "$tmp_dir/mismatched-run-id" "$tmp_dir/mismatched-run-id.json"

cp -R "$tmp_dir/release" "$tmp_dir/secret-metadata"
sed 's/^recovery=.*/password=do-not-accept/' \
  "$tmp_dir/secret-metadata/release-metadata.txt" > "$tmp_dir/secret-metadata/release-metadata.next"
mv "$tmp_dir/secret-metadata/release-metadata.next" "$tmp_dir/secret-metadata/release-metadata.txt"
write_checksums "$tmp_dir/secret-metadata"
expect_failure secret-metadata run_capture "$tmp_dir/secret-metadata" "$tmp_dir/secret-metadata.json"

cp -R "$tmp_dir/release" "$tmp_dir/secret-benchmark"
jq '.apiToken = "do-not-accept"' \
  "$tmp_dir/secret-benchmark/benchmark-fixture.json" > "$tmp_dir/secret-benchmark/benchmark-fixture.next"
mv "$tmp_dir/secret-benchmark/benchmark-fixture.next" "$tmp_dir/secret-benchmark/benchmark-fixture.json"
write_checksums "$tmp_dir/secret-benchmark"
expect_failure secret-benchmark run_capture "$tmp_dir/secret-benchmark" "$tmp_dir/secret-benchmark.json"

cp -R "$tmp_dir/release" "$tmp_dir/secret-benchmark-value"
jq '.notes = "aws_secret_access_key=hunter2"' \
  "$tmp_dir/secret-benchmark-value/benchmark-fixture.json" \
  > "$tmp_dir/secret-benchmark-value/benchmark-fixture.next"
mv "$tmp_dir/secret-benchmark-value/benchmark-fixture.next" \
  "$tmp_dir/secret-benchmark-value/benchmark-fixture.json"
write_checksums "$tmp_dir/secret-benchmark-value"
expect_failure secret-benchmark-value run_capture \
  "$tmp_dir/secret-benchmark-value" "$tmp_dir/secret-benchmark-value.json"

cp -R "$tmp_dir/release" "$tmp_dir/multi-document-benchmark"
{
  printf '%s\n' '{"apiToken":"hunter2"}'
  cat "$tmp_dir/release/benchmark-fixture.json"
} > "$tmp_dir/multi-document-benchmark/benchmark-fixture.json"
write_checksums "$tmp_dir/multi-document-benchmark"
expect_failure multi-document-benchmark run_capture \
  "$tmp_dir/multi-document-benchmark" "$tmp_dir/multi-document-benchmark.json"

cp -R "$tmp_dir/release" "$tmp_dir/multi-document-traffic"
{
  printf '%s\n' '{"databasePassword":"hunter2"}'
  cat "$tmp_dir/release/traffic-v1.json"
} > "$tmp_dir/multi-document-traffic/traffic-v1.json"
refresh_traffic_manifest_binding "$tmp_dir/multi-document-traffic"
expect_failure multi-document-traffic run_capture \
  "$tmp_dir/multi-document-traffic" "$tmp_dir/multi-document-traffic.json"

cp -R "$tmp_dir/release" "$tmp_dir/bad-migration-inventory"
{
  sed -n '2p' "$tmp_dir/bad-migration-inventory/backend-migrations.sha256"
  sed -n '1p' "$tmp_dir/bad-migration-inventory/backend-migrations.sha256"
  sed -n '3,17p' "$tmp_dir/bad-migration-inventory/backend-migrations.sha256"
} > "$tmp_dir/bad-migration-inventory/backend-migrations.next"
mv "$tmp_dir/bad-migration-inventory/backend-migrations.next" \
  "$tmp_dir/bad-migration-inventory/backend-migrations.sha256"
write_checksums "$tmp_dir/bad-migration-inventory"
expect_failure migration-order run_capture \
  "$tmp_dir/bad-migration-inventory" "$tmp_dir/bad-migration-inventory.json"

cp -R "$tmp_dir/release" "$tmp_dir/mismatched-traffic-migration"
jq '.schema.migrationDigest = "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"' \
  "$tmp_dir/mismatched-traffic-migration/traffic-v1.json" \
  > "$tmp_dir/mismatched-traffic-migration/traffic-v1.next"
mv "$tmp_dir/mismatched-traffic-migration/traffic-v1.next" \
  "$tmp_dir/mismatched-traffic-migration/traffic-v1.json"
sed 's/^traffic_migration_digest=.*/traffic_migration_digest=sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff/' \
  "$tmp_dir/mismatched-traffic-migration/release-metadata.txt" \
  > "$tmp_dir/mismatched-traffic-migration/release-metadata.next"
mv "$tmp_dir/mismatched-traffic-migration/release-metadata.next" \
  "$tmp_dir/mismatched-traffic-migration/release-metadata.txt"
refresh_traffic_manifest_binding "$tmp_dir/mismatched-traffic-migration"
expect_failure mismatched-traffic-migration run_capture \
  "$tmp_dir/mismatched-traffic-migration" "$tmp_dir/mismatched-traffic-migration.json"
grep -Fq -- 'database traffic migration digest does not match the source release' \
  "$tmp_dir/mismatched-traffic-migration.stderr" >/dev/null \
  || fail 'source migration digest mismatch did not reach the live database binding gate'

cp -R "$tmp_dir/release" "$tmp_dir/malformed-checksum"
sed '1s/^[0-9a-f]\{64\}/not-a-sha256/' \
  "$tmp_dir/malformed-checksum/SHA256SUMS" > "$tmp_dir/malformed-checksum/SHA256SUMS.next"
mv "$tmp_dir/malformed-checksum/SHA256SUMS.next" "$tmp_dir/malformed-checksum/SHA256SUMS"
expect_failure malformed-checksum run_capture "$tmp_dir/malformed-checksum" "$tmp_dir/malformed-checksum.json"

cp -R "$tmp_dir/release" "$tmp_dir/symlink-release"
rm "$tmp_dir/symlink-release/database-fingerprint.tsv"
ln -s PROVENANCE.txt "$tmp_dir/symlink-release/database-fingerprint.tsv"
expect_failure symlink-release run_capture "$tmp_dir/symlink-release" "$tmp_dir/symlink-release.json"

cp -R "$tmp_dir/release" "$tmp_dir/extra-release"
printf '%s\n' 'extra' > "$tmp_dir/extra-release/unreviewed.txt"
expect_failure extra-inventory run_capture "$tmp_dir/extra-release" "$tmp_dir/extra-release.json"

expect_failure wrong-database env \
  PATH="$tmp_dir/bin:$PATH" \
  AIRBOB_FAKE_MYSQL_LOG="$tmp_dir/mysql.log" \
  AIRBOB_DATASET_ETL_REPOSITORY="$tmp_dir/etl" \
  AIRBOB_DATASET_DB_HOST=127.0.0.1 \
  AIRBOB_DATASET_DB_PORT=3307 \
  AIRBOB_DATASET_DB_USER=airbob_capture \
  AIRBOB_DATASET_DB_PASSWORD="$fixture_password" \
  AIRBOB_DATASET_DB_RESTORE_USER=airbob_restore \
  AIRBOB_DATASET_DB_RESTORE_PASSWORD="$restore_fixture_password" \
  AIRBOB_DATASET_DB_NAME=otherdb \
  AIRBOB_DATASET_DB_QUIESCED=true \
  "$capture" "$tmp_dir/release" "$tmp_dir/wrong-database.json"

expect_failure missing-quiescence env \
  PATH="$tmp_dir/bin:$PATH" \
  AIRBOB_FAKE_MYSQL_LOG="$tmp_dir/mysql.log" \
  AIRBOB_DATASET_ETL_REPOSITORY="$tmp_dir/etl" \
  AIRBOB_DATASET_DB_HOST=127.0.0.1 \
  AIRBOB_DATASET_DB_PORT=3307 \
  AIRBOB_DATASET_DB_USER=airbob_capture \
  AIRBOB_DATASET_DB_PASSWORD="$fixture_password" \
  AIRBOB_DATASET_DB_RESTORE_USER=airbob_restore \
  AIRBOB_DATASET_DB_RESTORE_PASSWORD="$restore_fixture_password" \
  AIRBOB_DATASET_DB_NAME=airbobdb \
  "$capture" "$tmp_dir/release" "$tmp_dir/missing-quiescence.json"

expect_failure writable-database run_capture \
  "$tmp_dir/release" "$tmp_dir/writable-database.json" AIRBOB_FAKE_READ_ONLY=0

expect_failure super-writable-database run_capture \
  "$tmp_dir/release" "$tmp_dir/super-writable-database.json" AIRBOB_FAKE_SUPER_READ_ONLY=0

expect_failure unstable-database run_capture \
  "$tmp_dir/release" "$tmp_dir/unstable-database.json" \
  AIRBOB_FAKE_MUTATION_MARKER="$tmp_dir/mutation.marker"

expect_failure v26-history run_capture \
	"$tmp_dir/release" "$tmp_dir/v26.json" \
	AIRBOB_FAKE_FLYWAY_SUMMARY='26\t27\t27\t0'

expect_failure semantic-lineage-drift run_capture \
  "$tmp_dir/release" "$tmp_dir/semantic-lineage-drift.json" \
  AIRBOB_FAKE_LINEAGE_DRIFT_MARKER="$tmp_dir/lineage-drift.marker"

expect_failure history-row-mismatch run_capture \
	"$tmp_dir/release" "$tmp_dir/history-row-mismatch.json" \
	AIRBOB_FAKE_FLYWAY_SUMMARY='27\t28\t27\t0'

expect_failure invalid-published-timezone run_capture \
	"$tmp_dir/release" "$tmp_dir/invalid-published-timezone.json" \
	AIRBOB_FAKE_INVALID_PUBLISHED_TIMEZONES=1

expect_failure nonempty-outbox run_capture \
  "$tmp_dir/release" "$tmp_dir/nonempty-outbox.json" \
  AIRBOB_FAKE_OUTBOX_COUNT=1

expect_failure insufficient-accommodation-capacity run_capture \
  "$tmp_dir/release" "$tmp_dir/insufficient-accommodation-capacity.json" \
  AIRBOB_FAKE_ACCOMMODATION_ROWS=200

write_fake_mysql
sed 's/printf "accommodation\\naccommodation_inventory_day\\nflyway_schema_history\\noutbox\\nreservation\\n"/printf "unsafe-name\\n"/' \
  "$tmp_dir/bin/mysql" > "$tmp_dir/bin/mysql-unsafe"
mv "$tmp_dir/bin/mysql-unsafe" "$tmp_dir/bin/mysql"
chmod 700 "$tmp_dir/bin/mysql"
expect_failure unsafe-table run_capture "$tmp_dir/release" "$tmp_dir/unsafe-table.json"

printf '%s\n' 'dataset attestation tests passed'
