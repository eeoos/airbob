#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../../.." && pwd)
capture="$repo_root/infra/aws/scripts/capture-dataset-attestation.sh"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-dataset-attestation-test.XXXXXX")
fixture_password='attestation-test-password-do-not-log'

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

write_release() {
  local root=$1
  local traffic_sha
  local migration_digest
  local migration_stream

  mkdir -p "$root"
  printf '%s\n' 'provenance fixture' > "$root/PROVENANCE.txt"
  printf '%s\n' 'canonical gzip bytes' | gzip -n > "$root/airbob-production-seed.sql.gz"
  : > "$root/backend-migrations.sha256"
  for migration_version in 10 11 12 13 14 15 16 17 18 19 1 20 2 3 4 5 6 7 8 9; do
    printf '%s  ./V%s__migration_%s.sql\n' \
      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
      "$migration_version" \
      "$migration_version" \
      >> "$root/backend-migrations.sha256"
  done
  migration_stream="$root/.traffic-migration-canonical"
  : > "$migration_stream"
  for migration_version in $(seq 1 20); do
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
      schema: {flywayVersion: "20", migrationDigest: $migrationDigest}
    }
  ' > "$root/traffic-v1.json"
  printf '%s\n' $'accommodation\t201\tbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' > "$root/database-fingerprint.tsv"
  printf '%s\n' 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc' > "$root/etl-code.sha256"
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
    printf '%s\n' 'traffic_flyway_version=20'
    printf 'traffic_migration_digest=%s\n' "$migration_digest"
    printf '%s\n' 'fingerprint=database-fingerprint.tsv'
    printf '%s\n' 'required_rows=201'
    printf '%s\n' 'recovery=reset-flyway-v1-v20-etl-reseed-before-traffic'
  } > "$root/release-metadata.txt"

  write_checksums "$root"
}

write_fake_mysql() {
  mkdir -p "$tmp_dir/bin"
  printf '%s\n' '#!/usr/bin/env bash' > "$tmp_dir/bin/mysql"
  printf '%s\n' 'set -euo pipefail' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '[[ "${MYSQL_PWD:-}" == "attestation-test-password-do-not-log" ]] || exit 91' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '[[ -z "${AIRBOB_DATASET_DB_PASSWORD:-}" ]] || exit 93' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'query=$(sed -e "s/[[:space:]][[:space:]]*/ /g" -e "s/^ //" -e "s/ $//")' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '{ printf "ARGS"; for argument in "$@"; do printf " <%s>" "$argument"; done; printf "\nQUERY <%s>\n---\n" "$query"; } >> "$AIRBOB_FAKE_MYSQL_LOG"' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'case "$query" in' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"SELECT @@GLOBAL.read_only"*) printf "%s\n" "${AIRBOB_FAKE_READ_ONLY:-1}" ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"COUNT(*) AS history_rows"*) printf "%b\n" "${AIRBOB_FAKE_FLYWAY_SUMMARY:-20\\t20\\t20\\t0}" ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"SELECT installed_rank, COALESCE(version,"*) for rank in $(seq 1 20); do printf "%s\t%s\tmigration %s\tSQL\tV%s__migration_%s.sql\t%s\t1\n" "$rank" "$rank" "$rank" "$rank" "$rank" "$rank"; done ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"FROM information_schema.COLUMNS"*"UNION ALL"*) printf "INDEX\t62\t69\t31\t3C4E554C4C3E\t30\t41\t3C4E554C4C3E\t\t4254524545\t594553\t3C4E554C4C3E\nCOLUMN\t61\t62\t31\t62\t626967696E74\t4E4F\t3C4E554C4C3E\t\t3C4E554C4C3E\t3C4E554C4C3E\t\n" ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"FROM information_schema.TABLES"*) printf "accommodation\nflyway_schema_history\noutbox\n" ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"SELECT COUNT(*) FROM outbox"*) printf "%s\n" "${AIRBOB_FAKE_OUTBOX_COUNT:-0}" ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"SELECT COUNT(*) FROM "*"accommodation"*) if [[ -n "${AIRBOB_FAKE_MUTATION_MARKER:-}" ]]; then if [[ -e "$AIRBOB_FAKE_MUTATION_MARKER" ]]; then printf "202\n"; else : > "$AIRBOB_FAKE_MUTATION_MARKER"; printf "201\n"; fi; else printf "%s\n" "${AIRBOB_FAKE_ACCOMMODATION_ROWS:-201}"; fi ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"SELECT COUNT(*) FROM "*"flyway_schema_history"*) printf "20\n" ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *"SELECT COUNT(*) FROM "*"outbox"*) printf "%s\n" "${AIRBOB_FAKE_OUTBOX_COUNT:-0}" ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' '  *) exit 92 ;;' >> "$tmp_dir/bin/mysql"
  printf '%s\n' 'esac' >> "$tmp_dir/bin/mysql"
  chmod 700 "$tmp_dir/bin/mysql"
}

run_capture() {
  local release=$1
  local output=$2
  shift 2
  env \
    PATH="$tmp_dir/bin:$PATH" \
    AIRBOB_FAKE_MYSQL_LOG="$tmp_dir/mysql.log" \
    AIRBOB_DATASET_DB_HOST=127.0.0.1 \
    AIRBOB_DATASET_DB_PORT=3307 \
    AIRBOB_DATASET_DB_USER=airbob_capture \
    AIRBOB_DATASET_DB_PASSWORD="$fixture_password" \
    AIRBOB_DATASET_DB_NAME=airbobdb \
    AIRBOB_DATASET_DB_QUIESCED=true \
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
}

[[ -x "$capture" ]] || fail 'capture script is missing or not executable'
/bin/bash -n "$capture"
command -v jq >/dev/null 2>&1 || fail 'jq is required for the test'
write_fake_mysql
write_release "$tmp_dir/release"
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
  --arg fingerprintSha "$expected_fingerprint_sha" '
  (keys | sort) == ([
    "capturedAt", "expectedTableRows", "flywayHistoryRows", "flywayVersion",
    "migrationChecksumSha256", "outboxState", "schemaFingerprintSha256",
    "schemaVersion", "sourceDatabaseFingerprintSha256", "sourceDumpSha256",
    "sourceReleasePayloadSha256"
  ] | sort) and
  .schemaVersion == 1 and
  .sourceReleasePayloadSha256 == $releaseSha and
  .sourceDumpSha256 == $dumpSha and
  .sourceDatabaseFingerprintSha256 == $fingerprintSha and
  .flywayVersion == "20" and
  .flywayHistoryRows == 20 and
  (.migrationChecksumSha256 | test("^[0-9a-f]{64}$")) and
  (.schemaFingerprintSha256 | test("^[0-9a-f]{64}$")) and
  .outboxState == "empty" and
  .expectedTableRows == {
    accommodation: 201,
    flyway_schema_history: 20,
    outbox: 0
  } and
  .capturedAt == "2026-08-17T03:04:05Z"
' "$tmp_dir/attestation.json" >/dev/null || fail 'attestation JSON contract mismatch'

if stat -f '%Lp' "$tmp_dir/attestation.json" >/dev/null 2>&1; then
  output_mode=$(stat -f '%Lp' "$tmp_dir/attestation.json")
else
  output_mode=$(stat -c '%a' "$tmp_dir/attestation.json")
fi
[[ "$output_mode" == 600 ]] || fail 'attestation output mode is not 0600'

[[ $(grep -c '^ARGS ' "$tmp_dir/mysql.log") -eq 16 ]] || fail 'unexpected MySQL query count'
[[ $(grep -c '<airbobdb>' "$tmp_dir/mysql.log") -eq 16 ]] || fail 'every query must explicitly select airbobdb'
! grep -En -- '--password|attestation-test-password' "$tmp_dir/mysql.log" >/dev/null \
  || fail 'database password reached MySQL argv/query logs'
for required_query in \
  'COUNT(*) AS history_rows' \
  'SELECT @@GLOBAL.read_only' \
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
  'SELECT COUNT(*) FROM `flyway_schema_history`' \
  'SELECT COUNT(*) FROM `outbox`'; do
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
  AIRBOB_DATASET_DB_HOST=127.0.0.1 \
  AIRBOB_DATASET_DB_PORT=3307 \
  AIRBOB_DATASET_DB_USER=airbob_capture \
  AIRBOB_DATASET_DB_PASSWORD="$fixture_password" \
  AIRBOB_DATASET_DB_NAME=otherdb \
  AIRBOB_DATASET_DB_QUIESCED=true \
  "$capture" "$tmp_dir/release" "$tmp_dir/wrong-database.json"

expect_failure missing-quiescence env \
  PATH="$tmp_dir/bin:$PATH" \
  AIRBOB_FAKE_MYSQL_LOG="$tmp_dir/mysql.log" \
  AIRBOB_DATASET_DB_HOST=127.0.0.1 \
  AIRBOB_DATASET_DB_PORT=3307 \
  AIRBOB_DATASET_DB_USER=airbob_capture \
  AIRBOB_DATASET_DB_PASSWORD="$fixture_password" \
  AIRBOB_DATASET_DB_NAME=airbobdb \
  "$capture" "$tmp_dir/release" "$tmp_dir/missing-quiescence.json"

expect_failure writable-database run_capture \
  "$tmp_dir/release" "$tmp_dir/writable-database.json" AIRBOB_FAKE_READ_ONLY=0

expect_failure unstable-database run_capture \
  "$tmp_dir/release" "$tmp_dir/unstable-database.json" \
  AIRBOB_FAKE_MUTATION_MARKER="$tmp_dir/mutation.marker"

expect_failure v19-history run_capture \
  "$tmp_dir/release" "$tmp_dir/v19.json" \
  AIRBOB_FAKE_FLYWAY_SUMMARY='19\t20\t20\t0'

expect_failure history-row-mismatch run_capture \
  "$tmp_dir/release" "$tmp_dir/history-row-mismatch.json" \
  AIRBOB_FAKE_FLYWAY_SUMMARY='20\t21\t20\t0'

expect_failure nonempty-outbox run_capture \
  "$tmp_dir/release" "$tmp_dir/nonempty-outbox.json" \
  AIRBOB_FAKE_OUTBOX_COUNT=1

expect_failure insufficient-accommodation-capacity run_capture \
  "$tmp_dir/release" "$tmp_dir/insufficient-accommodation-capacity.json" \
  AIRBOB_FAKE_ACCOMMODATION_ROWS=200

write_fake_mysql
sed 's/printf "accommodation\\nflyway_schema_history\\noutbox\\n"/printf "unsafe-name\\n"/' \
  "$tmp_dir/bin/mysql" > "$tmp_dir/bin/mysql-unsafe"
mv "$tmp_dir/bin/mysql-unsafe" "$tmp_dir/bin/mysql"
chmod 700 "$tmp_dir/bin/mysql"
expect_failure unsafe-table run_capture "$tmp_dir/release" "$tmp_dir/unsafe-table.json"

printf '%s\n' 'dataset attestation tests passed'
