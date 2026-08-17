#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
assembler="$repo_root/infra/aws/scripts/assemble-dataset-release.sh"
validator="$repo_root/infra/aws/scripts/verify-dataset-release.sh"
benchmark_fixture="$repo_root/load-test/k6/test/fixtures/nplus1-v1.json"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-dataset-assembler-test.XXXXXX")

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
  printf '%s\n' "$1" >&2
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
  local release_dir=$1
  local release_name
  : > "$release_dir/SHA256SUMS"
  for release_name in \
    PROVENANCE.txt \
    airbob-production-seed.sql.gz \
    backend-migrations.sha256 \
    benchmark-fixture.json \
    database-fingerprint.tsv \
    etl-code.sha256 \
    release-metadata.txt \
    source.sha256 \
    traffic-v1.json
  do
    printf '%s  %s\n' \
      "$(sha256_file "$release_dir/$release_name")" \
      "$release_name" >> "$release_dir/SHA256SUMS"
  done
}

write_attestation() {
  local release_dir=$1
  local attestation=$2
  local accommodation_rows=${3:-201}
  jq -nS \
    --arg sourceReleasePayloadSha256 "$(sha256_file "$release_dir/SHA256SUMS")" \
    --arg sourceDumpSha256 "$(sha256_file "$release_dir/airbob-production-seed.sql.gz")" \
    --arg sourceDatabaseFingerprintSha256 "$(sha256_file "$release_dir/database-fingerprint.tsv")" \
    --argjson accommodationRows "$accommodation_rows" '
    {
      schemaVersion: 1,
      sourceReleasePayloadSha256: $sourceReleasePayloadSha256,
      sourceDumpSha256: $sourceDumpSha256,
      sourceDatabaseFingerprintSha256: $sourceDatabaseFingerprintSha256,
      flywayVersion: "17",
      flywayHistoryRows: 17,
      migrationChecksumSha256: "4444444444444444444444444444444444444444444444444444444444444444",
      schemaFingerprintSha256: "5555555555555555555555555555555555555555555555555555555555555555",
      outboxState: "empty",
      expectedTableRows: {
        accommodation: $accommodationRows,
        flyway_schema_history: 17,
        member: 3,
        outbox: 0
      },
      capturedAt: "2026-08-17T00:00:00Z"
    }
  ' > "$attestation"
}

refresh_traffic_binding() {
  local release_dir=$1
  local traffic_sha
  traffic_sha=$(sha256_file "$release_dir/traffic-v1.json")
  sed "s/^traffic_manifest_sha256=.*/traffic_manifest_sha256=$traffic_sha/" \
    "$release_dir/release-metadata.txt" > "$release_dir/release-metadata.next"
  mv "$release_dir/release-metadata.next" "$release_dir/release-metadata.txt"
  write_checksums "$release_dir"
}

write_source_release() {
  local release_dir=$1
  local release_id=${2:-production-seed-20260817t000000z}
  local traffic_run_id=${3:-20260817T001530Z-12345678}
  local migration_path
  local migration_name
  local traffic_sha

  mkdir -m 700 -p "$release_dir"
  printf '%s\n' \
    'CREATE DATABASE IF NOT EXISTS airbobdb;' \
    'USE airbobdb;' \
    'INSERT INTO accommodation(id) VALUES (1);' \
    | gzip -n > "$release_dir/airbob-production-seed.sql.gz"
  cp "$benchmark_fixture" "$release_dir/benchmark-fixture.json"
  : > "$release_dir/backend-migrations.sha256"
  while IFS= read -r migration_path; do
    migration_name=${migration_path##*/}
    printf '%s  ./%s\n' \
      "$(sha256_file "$migration_path")" \
      "$migration_name" >> "$release_dir/backend-migrations.sha256"
  done < <(find "$repo_root/src/main/resources/db/migration" -maxdepth 1 -type f -name 'V*.sql' | LC_ALL=C sort)
  printf '%s\n' \
    'format_version	1' \
    'flyway_applied_count	17' \
    'outbox_count	0' \
    > "$release_dir/database-fingerprint.tsv"
  printf '%s\n' \
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  build.gradle' \
    > "$release_dir/etl-code.sha256"
  printf '%s\n' \
    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb  source.csv' \
    > "$release_dir/source.sha256"

  jq -nS --arg runId "$traffic_run_id" '
    {
      datasetVersion: "traffic-v1",
      datasetRunId: $runId,
      seed: 20260817,
      anchorTime: "2026-08-17T09:00:00",
      timezone: "Asia/Seoul",
      validUntil: "2099-12-31T09:00:00",
      schema: {
        flywayVersion: "17",
        migrationDigest: "sha256:6666666666666666666666666666666666666666666666666666666666666666"
      }
    }
  ' > "$release_dir/traffic-v1.json"
  traffic_sha=$(sha256_file "$release_dir/traffic-v1.json")

  cat > "$release_dir/PROVENANCE.txt" <<'EOF'
format=airbob-production-seed-provenance-v1
etl_head=0123456789abcdef0123456789abcdef01234567
backend_head=89abcdef0123456789abcdef0123456789abcdef
java=openjdk version 21
gradle=Gradle 8.4
mysql=mysql Ver 8.0.46
mysqldump=mysqldump Ver 8.0.46
porcelain_status_begin

porcelain_status_end
backend_porcelain_status_begin

backend_porcelain_status_end
options_begin
profile=large
service_schema=airbobdb
etl_schema=airbob_etl
benchmark_fixtures=true
traffic_fixtures=true
traffic_seed=20260817
traffic_anchor_time=2026-08-17T09:00:00
traffic_valid_until=2099-12-31T09:00:00
traffic_timezone=Asia/Seoul
options_end
EOF

  cat > "$release_dir/release-metadata.txt" <<EOF
format=airbob-production-seed-release-v1
release_id=$release_id
dump=airbob-production-seed.sql.gz
manifest=benchmark-fixture.json
traffic_manifest=traffic-v1.json
traffic_manifest_sha256=$traffic_sha
traffic_dataset_version=traffic-v1
traffic_dataset_run_id=$traffic_run_id
traffic_flyway_version=17
traffic_migration_digest=sha256:6666666666666666666666666666666666666666666666666666666666666666
fingerprint=database-fingerprint.tsv
required_rows=201
recovery=reset-flyway-v1-v17-etl-reseed-before-traffic
EOF

  write_checksums "$release_dir"
  chmod 600 "$release_dir"/*
}

make_output_root() {
  local output_root=$1
  mkdir -m 700 -p "$output_root"
  chmod 700 "$output_root"
}

expect_failure() {
  local label=$1
  local output_root=$2
  local dataset_release=$3
  shift 3
  if "$@" >"$temp_dir/$label.out" 2>&1; then
    fail "expected assembler failure: $label"
  fi
  [[ ! -e "$output_root/$dataset_release" && ! -L "$output_root/$dataset_release" ]] \
    || fail "failed assembly published a final release: $label"
  if grep -Eq 'hunter2|CREATE DATABASE|INSERT INTO' "$temp_dir/$label.out"; then
    fail "assembler leaked source or secret content: $label"
  fi
}

[[ -f "$assembler" && ! -L "$assembler" && -x "$assembler" ]] \
  || fail 'dataset release assembler is missing or not executable'
[[ -x "$validator" ]] || fail 'dataset release validator is missing or not executable'
command -v jq >/dev/null 2>&1 || fail 'jq is required'
command -v zstd >/dev/null 2>&1 || fail 'zstd is required'

source_release="$temp_dir/etl-release"
attestation="$temp_dir/attestation.json"
output_one="$temp_dir/output-one"
output_two="$temp_dir/output-two"
dataset_release=rehearsal-v17
evaluation_time=2026-08-18T00:00:00Z
valid_until=2099-12-31T00:00:00Z
write_source_release "$source_release"
write_attestation "$source_release" "$attestation"
make_output_root "$output_one"
make_output_root "$output_two"

"$assembler" \
  "$source_release" "$attestation" "$output_one" "$dataset_release" \
  "$evaluation_time" "$valid_until" >/dev/null

release_one="$output_one/$dataset_release"
[[ -d "$release_one" && ! -L "$release_one" ]] || fail 'final dataset release was not created'
[[ ! -e "$output_one/$dataset_release.incomplete" ]] || fail 'successful assembly retained incomplete output'
actual_inventory=$(cd "$release_one" && find . -mindepth 1 -print | LC_ALL=C sort)
expected_inventory=$(printf '%s\n' \
  './benchmark' \
  './benchmark/manifest.json' \
  './manifest.json' \
  './mysql' \
  './mysql/airbob.sql.zst' \
  './mysql/sha256.txt')
[[ "$actual_inventory" == "$expected_inventory" ]] || fail 'assembled release inventory is not exact'
cmp -s "$source_release/benchmark-fixture.json" "$release_one/benchmark/manifest.json" \
  || fail 'assembler changed benchmark manifest bytes'
gzip -dc "$source_release/airbob-production-seed.sql.gz" > "$temp_dir/source.sql"
zstd -q -dc "$release_one/mysql/airbob.sql.zst" > "$temp_dir/released.sql"
cmp -s "$temp_dir/source.sql" "$temp_dir/released.sql" \
  || fail 'gzip to zstd conversion changed SQL bytes'
"$validator" "$release_one" "$dataset_release" pipeline-rehearsal >/dev/null

canonical_payload_sha=$(sha256_file "$source_release/SHA256SUMS")
benchmark_sha=$(sha256_file "$source_release/benchmark-fixture.json")
dump_sha=$(sha256_file "$release_one/mysql/airbob.sql.zst")
jq -e \
  --arg canonicalPayloadSha256 "$canonical_payload_sha" \
  --arg benchmarkManifestSha256 "$benchmark_sha" \
  --arg dumpSha256 "$dump_sha" \
  --arg evaluationTime "$evaluation_time" \
  --arg validUntil "$valid_until" '
  (keys | sort) == ([
    "schemaVersion", "releaseKind", "datasetRelease", "datasetRunId", "source",
    "mysql", "couponPreparation", "kafka", "search"
  ] | sort) and
  .schemaVersion == 1 and
  .releaseKind == "pipeline-rehearsal" and
  .datasetRelease == "rehearsal-v17" and
  .datasetRunId == "20260817T001530Z-12345678" and
  .source == {
    datasetVersion: "nplus1-v1",
    etlCommit: "0123456789abcdef0123456789abcdef01234567",
    seed: "airbob-production-seed-v1",
    profile: "large",
    manifestVersion: "benchmark-fixture-v1",
    canonicalPayloadSha256: $canonicalPayloadSha256,
    benchmarkManifestKey: "benchmark/manifest.json",
    benchmarkManifestSha256: $benchmarkManifestSha256
  } and
  .mysql.dumpKey == "mysql/airbob.sql.zst" and
  .mysql.dumpSha256 == $dumpSha256 and
  .mysql.flywayVersion == "17" and
  .mysql.migrationChecksumSha256 == "4444444444444444444444444444444444444444444444444444444444444444" and
  .mysql.schemaFingerprintSha256 == "5555555555555555555555555555555555555555555555555555555555555555" and
  .mysql.timezone == "UTC" and
  .mysql.evaluationTime == $evaluationTime and
  .mysql.validUntil == $validUntil and
  .mysql.outboxPolicy == "absent" and
  .mysql.expectedTableRows == {
    accommodation: 201,
    flyway_schema_history: 17,
    member: 3,
    outbox: 0
  } and
  .couponPreparation == [] and
  ([.kafka.topics[].name] | sort) == ["ACCOMMODATIONS.events", "PAYMENT.events", "RESERVATION.events"] and
  all(.kafka.topics[]; .partitions == 1 and .retentionMs == 86400000) and
  .search == {enabled: false}
' "$release_one/manifest.json" >/dev/null || fail 'assembled manifest does not match the pipeline contract'
[[ "$(cat "$release_one/mysql/sha256.txt")" == "$dump_sha  airbob.sql.zst" ]] \
  || fail 'assembled MySQL checksum file is not canonical'

"$assembler" \
  "$source_release" "$attestation" "$output_two" "$dataset_release" \
  "$evaluation_time" "$valid_until" >/dev/null
release_two="$output_two/$dataset_release"
cmp -s "$release_one/manifest.json" "$release_two/manifest.json" \
  || fail 'same inputs did not produce a deterministic manifest'
cmp -s "$release_one/mysql/airbob.sql.zst" "$release_two/mysql/airbob.sql.zst" \
  || fail 'same inputs did not produce a deterministic zstd dump'
cmp -s "$release_one/mysql/sha256.txt" "$release_two/mysql/sha256.txt" \
  || fail 'same inputs did not produce a deterministic dump checksum'

preserved_manifest_sha=$(sha256_file "$release_one/manifest.json")
if "$assembler" \
  "$source_release" "$attestation" "$output_one" "$dataset_release" \
  "$evaluation_time" "$valid_until" >/dev/null 2>&1
then
  fail 'assembler overwrote an existing dataset release'
fi
[[ "$(sha256_file "$release_one/manifest.json")" == "$preserved_manifest_sha" ]] \
  || fail 'overwrite refusal changed the existing release'

multi_benchmark_source="$temp_dir/multi-benchmark-source"
multi_benchmark_attestation="$temp_dir/multi-benchmark-attestation.json"
cp -R "$source_release" "$multi_benchmark_source"
{
  printf '%s\n' '{"password":"hunter2"}'
  cat "$source_release/benchmark-fixture.json"
} > "$multi_benchmark_source/benchmark-fixture.json"
write_checksums "$multi_benchmark_source"
write_attestation "$multi_benchmark_source" "$multi_benchmark_attestation"
multi_benchmark_output="$temp_dir/multi-benchmark-output"
make_output_root "$multi_benchmark_output"
expect_failure multi-document-benchmark "$multi_benchmark_output" "$dataset_release" \
  "$assembler" "$multi_benchmark_source" "$multi_benchmark_attestation" \
  "$multi_benchmark_output" "$dataset_release" "$evaluation_time" "$valid_until"

multi_traffic_source="$temp_dir/multi-traffic-source"
multi_traffic_attestation="$temp_dir/multi-traffic-attestation.json"
cp -R "$source_release" "$multi_traffic_source"
{
  printf '%s\n' '{"password":"hunter2"}'
  cat "$source_release/traffic-v1.json"
} > "$multi_traffic_source/traffic-v1.json"
refresh_traffic_binding "$multi_traffic_source"
write_attestation "$multi_traffic_source" "$multi_traffic_attestation"
multi_traffic_output="$temp_dir/multi-traffic-output"
make_output_root "$multi_traffic_output"
expect_failure multi-document-traffic "$multi_traffic_output" "$dataset_release" \
  "$assembler" "$multi_traffic_source" "$multi_traffic_attestation" \
  "$multi_traffic_output" "$dataset_release" "$evaluation_time" "$valid_until"

multi_attestation="$temp_dir/multi-attestation.json"
{
  printf '%s\n' '{"password":"hunter2"}'
  cat "$attestation"
} > "$multi_attestation"
multi_attestation_output="$temp_dir/multi-attestation-output"
make_output_root "$multi_attestation_output"
expect_failure multi-document-attestation "$multi_attestation_output" "$dataset_release" \
  "$assembler" "$source_release" "$multi_attestation" \
  "$multi_attestation_output" "$dataset_release" "$evaluation_time" "$valid_until"

extended_source="$temp_dir/extended-source"
extended_attestation="$temp_dir/extended-attestation.json"
cp -R "$source_release" "$extended_source"
jq '
  .validUntil = "2027-12-31T09:00:00" |
  .validUntilInstant = "2027-12-31T00:00:00Z"
' "$extended_source/traffic-v1.json" > "$extended_source/traffic-v1.next"
mv "$extended_source/traffic-v1.next" "$extended_source/traffic-v1.json"
refresh_traffic_binding "$extended_source"
write_attestation "$extended_source" "$extended_attestation"
extended_output="$temp_dir/extended-output"
make_output_root "$extended_output"
expect_failure source-validity-extension "$extended_output" "$dataset_release" \
  "$assembler" "$extended_source" "$extended_attestation" "$extended_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"

mismatched_validity_output="$temp_dir/mismatched-validity-output"
make_output_root "$mismatched_validity_output"
expect_failure supplied-validity-mismatch "$mismatched_validity_output" "$dataset_release" \
  "$assembler" "$source_release" "$attestation" "$mismatched_validity_output" \
  "$dataset_release" "$evaluation_time" 2099-12-30T00:00:00Z

instant_mismatch_source="$temp_dir/instant-mismatch-source"
instant_mismatch_attestation="$temp_dir/instant-mismatch-attestation.json"
cp -R "$source_release" "$instant_mismatch_source"
jq '
  .anchorInstant = "2026-08-17T01:00:00Z" |
  .validUntilInstant = "2099-12-31T00:00:00Z"
' "$instant_mismatch_source/traffic-v1.json" > "$instant_mismatch_source/traffic-v1.next"
mv "$instant_mismatch_source/traffic-v1.next" "$instant_mismatch_source/traffic-v1.json"
refresh_traffic_binding "$instant_mismatch_source"
write_attestation "$instant_mismatch_source" "$instant_mismatch_attestation"
instant_mismatch_output="$temp_dir/instant-mismatch-output"
make_output_root "$instant_mismatch_output"
expect_failure optional-instant-mismatch "$instant_mismatch_output" "$dataset_release" \
  "$assembler" "$instant_mismatch_source" "$instant_mismatch_attestation" \
  "$instant_mismatch_output" "$dataset_release" "$evaluation_time" "$valid_until"

late_attestation="$temp_dir/late-attestation.json"
jq '.capturedAt = "2026-08-19T00:00:00Z"' "$attestation" > "$late_attestation"
late_attestation_output="$temp_dir/late-attestation-output"
make_output_root "$late_attestation_output"
expect_failure captured-after-evaluation "$late_attestation_output" "$dataset_release" \
  "$assembler" "$source_release" "$late_attestation" "$late_attestation_output" \
  "$dataset_release" "$evaluation_time" "$valid_until"

locked_output="$temp_dir/locked-output"
make_output_root "$locked_output"
mkdir -m 700 "$locked_output/.$dataset_release.assemble.lock"
printf '%s\n' owner > "$locked_output/.$dataset_release.assemble.lock/owner"
mkdir -m 700 "$locked_output/$dataset_release.incomplete"
printf '%s\n' preserve-me > "$locked_output/$dataset_release.incomplete/.manifest.json.tmp"
expect_failure concurrent-owner "$locked_output" "$dataset_release" \
  "$assembler" "$source_release" "$attestation" "$locked_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"
[[ "$(cat "$locked_output/.$dataset_release.assemble.lock/owner")" == owner \
  && "$(cat "$locked_output/$dataset_release.incomplete/.manifest.json.tmp")" == preserve-me ]] \
  || fail 'concurrent assembler changed another process owner state'

preexisting_output="$temp_dir/preexisting-output"
make_output_root "$preexisting_output"
mkdir -m 700 "$preexisting_output/$dataset_release.incomplete"
printf '%s\n' preserve-me > "$preexisting_output/$dataset_release.incomplete/.manifest.json.tmp"
expect_failure preexisting-incomplete "$preexisting_output" "$dataset_release" \
  "$assembler" "$source_release" "$attestation" "$preexisting_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"
[[ "$(cat "$preexisting_output/$dataset_release.incomplete/.manifest.json.tmp")" == preserve-me ]] \
  || fail 'assembler changed a pre-existing incomplete release it did not own'
[[ ! -e "$preexisting_output/.$dataset_release.assemble.lock" ]] \
  || fail 'assembler retained its lock after refusing a pre-existing incomplete release'

tampered_source="$temp_dir/tampered-source"
cp -R "$source_release" "$tampered_source"
printf '%s' tampered >> "$tampered_source/traffic-v1.json"
tampered_output="$temp_dir/tampered-output"
make_output_root "$tampered_output"
expect_failure tampered "$tampered_output" "$dataset_release" \
  "$assembler" "$tampered_source" "$attestation" "$tampered_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"

short_checksums="$temp_dir/short-checksums"
cp -R "$source_release" "$short_checksums"
sed '$d' "$short_checksums/SHA256SUMS" > "$short_checksums/SHA256SUMS.next"
mv "$short_checksums/SHA256SUMS.next" "$short_checksums/SHA256SUMS"
short_output="$temp_dir/short-output"
make_output_root "$short_output"
expect_failure checksum-count "$short_output" "$dataset_release" \
  "$assembler" "$short_checksums" "$attestation" "$short_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"

v16_source="$temp_dir/v16-source"
v16_attestation="$temp_dir/v16-attestation.json"
cp -R "$source_release" "$v16_source"
jq '.schema.flywayVersion = "16"' "$v16_source/traffic-v1.json" > "$v16_source/traffic-v1.next"
mv "$v16_source/traffic-v1.next" "$v16_source/traffic-v1.json"
v16_traffic_sha=$(sha256_file "$v16_source/traffic-v1.json")
sed \
  -e "s/^traffic_manifest_sha256=.*/traffic_manifest_sha256=$v16_traffic_sha/" \
  -e 's/^traffic_flyway_version=17$/traffic_flyway_version=16/' \
  -e 's/reset-flyway-v1-v17/reset-flyway-v1-v16/' \
  "$v16_source/release-metadata.txt" > "$v16_source/release-metadata.next"
mv "$v16_source/release-metadata.next" "$v16_source/release-metadata.txt"
write_checksums "$v16_source"
write_attestation "$v16_source" "$v16_attestation"
jq '.flywayVersion = "16" | .flywayHistoryRows = 16 | .expectedTableRows.flyway_schema_history = 16' \
  "$v16_attestation" > "$v16_attestation.next"
mv "$v16_attestation.next" "$v16_attestation"
v16_output="$temp_dir/v16-output"
make_output_root "$v16_output"
expect_failure v16 "$v16_output" "$dataset_release" \
  "$assembler" "$v16_source" "$v16_attestation" "$v16_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"

mismatch_attestation="$temp_dir/mismatch-attestation.json"
jq '.sourceDumpSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"' \
  "$attestation" > "$mismatch_attestation"
mismatch_output="$temp_dir/mismatch-output"
make_output_root "$mismatch_output"
expect_failure attestation-source-mismatch "$mismatch_output" "$dataset_release" \
  "$assembler" "$source_release" "$mismatch_attestation" "$mismatch_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"

low_capacity_attestation="$temp_dir/low-capacity-attestation.json"
write_attestation "$source_release" "$low_capacity_attestation" 200
low_capacity_output="$temp_dir/low-capacity-output"
make_output_root "$low_capacity_output"
expect_failure insufficient-accommodation-capacity \
  "$low_capacity_output" "$dataset_release" \
  "$assembler" "$source_release" "$low_capacity_attestation" "$low_capacity_output" \
  "$dataset_release" "$evaluation_time" "$valid_until"

symlink_source="$temp_dir/symlink-source"
cp -R "$source_release" "$symlink_source"
mv "$symlink_source/source.sha256" "$temp_dir/source.inventory"
ln -s "$temp_dir/source.inventory" "$symlink_source/source.sha256"
symlink_output="$temp_dir/symlink-output"
make_output_root "$symlink_output"
expect_failure source-symlink "$symlink_output" "$dataset_release" \
  "$assembler" "$symlink_source" "$attestation" "$symlink_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"

secret_source="$temp_dir/secret-source"
secret_attestation="$temp_dir/secret-attestation.json"
cp -R "$source_release" "$secret_source"
sed 's/^options_end$/database_password=hunter2\noptions_end/' \
  "$secret_source/PROVENANCE.txt" > "$secret_source/PROVENANCE.next"
mv "$secret_source/PROVENANCE.next" "$secret_source/PROVENANCE.txt"
write_checksums "$secret_source"
write_attestation "$secret_source" "$secret_attestation"
secret_output="$temp_dir/secret-output"
make_output_root "$secret_output"
expect_failure secret-provenance "$secret_output" "$dataset_release" \
  "$assembler" "$secret_source" "$secret_attestation" "$secret_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"

secret_value_source="$temp_dir/secret-value-source"
secret_value_attestation="$temp_dir/secret-value-attestation.json"
cp -R "$source_release" "$secret_value_source"
jq '.notes = "aws_secret_access_key=hunter2"' \
  "$secret_value_source/benchmark-fixture.json" \
  > "$secret_value_source/benchmark-fixture.next"
mv "$secret_value_source/benchmark-fixture.next" \
  "$secret_value_source/benchmark-fixture.json"
write_checksums "$secret_value_source"
write_attestation "$secret_value_source" "$secret_value_attestation"
secret_value_output="$temp_dir/secret-value-output"
make_output_root "$secret_value_output"
expect_failure secret-benchmark-value "$secret_value_output" "$dataset_release" \
  "$assembler" "$secret_value_source" "$secret_value_attestation" \
  "$secret_value_output" "$dataset_release" "$evaluation_time" "$valid_until"

duplicate_source="$temp_dir/duplicate-source"
duplicate_attestation="$temp_dir/duplicate-attestation.json"
cp -R "$source_release" "$duplicate_source"
awk '/^backend_head=/{print "etl_head=0123456789abcdef0123456789abcdef01234567"} {print}' \
  "$duplicate_source/PROVENANCE.txt" > "$duplicate_source/PROVENANCE.next"
mv "$duplicate_source/PROVENANCE.next" "$duplicate_source/PROVENANCE.txt"
write_checksums "$duplicate_source"
write_attestation "$duplicate_source" "$duplicate_attestation"
duplicate_output="$temp_dir/duplicate-output"
make_output_root "$duplicate_output"
expect_failure duplicate-provenance-key "$duplicate_output" "$dataset_release" \
  "$assembler" "$duplicate_source" "$duplicate_attestation" "$duplicate_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"

invalid_run_source="$temp_dir/invalid-run-source"
invalid_run_attestation="$temp_dir/invalid-run-attestation.json"
write_source_release "$invalid_run_source" production-seed-20260817t000000z traffic-20260817-001
write_attestation "$invalid_run_source" "$invalid_run_attestation"
invalid_run_output="$temp_dir/invalid-run-output"
make_output_root "$invalid_run_output"
expect_failure invalid-traffic-run-id "$invalid_run_output" "$dataset_release" \
  "$assembler" "$invalid_run_source" "$invalid_run_attestation" "$invalid_run_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"

run_mismatch_source="$temp_dir/run-mismatch-source"
run_mismatch_attestation="$temp_dir/run-mismatch-attestation.json"
cp -R "$source_release" "$run_mismatch_source"
sed 's/^traffic_dataset_run_id=.*/traffic_dataset_run_id=20260817T001530Z-87654321/' \
  "$run_mismatch_source/release-metadata.txt" > "$run_mismatch_source/release-metadata.next"
mv "$run_mismatch_source/release-metadata.next" "$run_mismatch_source/release-metadata.txt"
write_checksums "$run_mismatch_source"
write_attestation "$run_mismatch_source" "$run_mismatch_attestation"
run_mismatch_output="$temp_dir/run-mismatch-output"
make_output_root "$run_mismatch_output"
expect_failure traffic-run-mismatch "$run_mismatch_output" "$dataset_release" \
  "$assembler" "$run_mismatch_source" "$run_mismatch_attestation" "$run_mismatch_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"

provenance_mismatch_source="$temp_dir/provenance-mismatch-source"
provenance_mismatch_attestation="$temp_dir/provenance-mismatch-attestation.json"
cp -R "$source_release" "$provenance_mismatch_source"
sed 's/^traffic_timezone=Asia\/Seoul$/traffic_timezone=UTC/' \
  "$provenance_mismatch_source/PROVENANCE.txt" \
  > "$provenance_mismatch_source/PROVENANCE.next"
mv "$provenance_mismatch_source/PROVENANCE.next" \
  "$provenance_mismatch_source/PROVENANCE.txt"
write_checksums "$provenance_mismatch_source"
write_attestation "$provenance_mismatch_source" "$provenance_mismatch_attestation"
provenance_mismatch_output="$temp_dir/provenance-mismatch-output"
make_output_root "$provenance_mismatch_output"
expect_failure provenance-traffic-mismatch "$provenance_mismatch_output" "$dataset_release" \
  "$assembler" "$provenance_mismatch_source" "$provenance_mismatch_attestation" \
  "$provenance_mismatch_output" "$dataset_release" "$evaluation_time" "$valid_until"

extra_source="$temp_dir/extra-source"
cp -R "$source_release" "$extra_source"
printf '%s\n' unexpected > "$extra_source/extra.txt"
extra_output="$temp_dir/extra-output"
make_output_root "$extra_output"
expect_failure extra-source-file "$extra_output" "$dataset_release" \
  "$assembler" "$extra_source" "$attestation" "$extra_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"

unsafe_output="$temp_dir/unsafe-output"
mkdir -m 755 "$unsafe_output"
chmod 755 "$unsafe_output"
expect_failure unsafe-output-root "$unsafe_output" "$dataset_release" \
  "$assembler" "$source_release" "$attestation" "$unsafe_output" "$dataset_release" \
  "$evaluation_time" "$valid_until"

path_output="$temp_dir/path-output"
make_output_root "$path_output"
expect_failure unsafe-release-path "$path_output" ../escape \
  "$assembler" "$source_release" "$attestation" "$path_output" ../escape \
  "$evaluation_time" "$valid_until"

printf '%s\n' 'dataset release assembler tests passed'
