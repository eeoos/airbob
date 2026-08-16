#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../../.." && pwd)
validator="$repo_root/infra/aws/scripts/verify-dataset-release.sh"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-dataset-test.XXXXXX")
cleanup() {
  [[ "${AIRBOB_KEEP_TEST_TMP:-false}" == true ]] || rm -rf "$tmp_dir"
}
trap cleanup EXIT HUP INT TERM

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

write_release() {
  local root=$1
  local kind=$2
  local search_enabled=$3
  local dump_sha
  local benchmark_manifest_sha
  local search_json
  local evidence_json=''

  mkdir -p "$root/mysql" "$root/elasticsearch" "$root/benchmark"
  printf '%s' 'canonical-zstd-fixture' > "$root/mysql/airbob.sql.zst"
  dump_sha=$(sha256_file "$root/mysql/airbob.sql.zst")
  printf '%s  %s\n' "$dump_sha" airbob.sql.zst > "$root/mysql/sha256.txt"

  if [[ "$kind" == evidence ]]; then
    printf '%s\n' '{"datasetVersion":"traffic-v1"}' > "$root/benchmark/manifest.json"
  else
    cp "$repo_root/load-test/k6/test/fixtures/nplus1-v1.json" "$root/benchmark/manifest.json"
  fi
  benchmark_manifest_sha=$(sha256_file "$root/benchmark/manifest.json")

  if [[ "$search_enabled" == true ]]; then
    search_json='{
      "enabled": true,
      "snapshotReferenceKey": "elasticsearch/snapshot-reference.json",
      "repository": "airbob-dataset-readonly",
      "elasticsearchVersion": "8.18.8",
      "imageDigest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "requiredPlugins": ["analysis-nori", "repository-s3"],
      "index": "accommodations",
      "documentCount": 730702,
      "mappingSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      "databaseAccommodationIdsSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
      "elasticsearchAccommodationIdsSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
      "contentFingerprintSha256": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }'
    cat > "$root/elasticsearch/snapshot-reference.json" <<'JSON'
{
  "schemaVersion": 1,
  "repository": "airbob-dataset-readonly",
  "bucket": "airbob-performance-lab-dataset-942632789808",
  "basePath": "elasticsearch/releases/rehearsal-v16",
  "snapshot": "airbob-rehearsal-v16",
  "index": "accommodations",
  "elasticsearchVersion": "8.18.8",
  "imageDigest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "documentCount": 730702,
  "mappingSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "dbIdsSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "esIdsSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "contentFingerprintSha256": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
}
JSON
  else
    search_json='{ "enabled": false }'
    rmdir "$root/elasticsearch"
  fi

  if [[ "$kind" == evidence ]]; then
    evidence_json=',
    "evidence": {
      "trafficAccountCapacity": 250,
      "targetFingerprints": {
        "databaseAccommodationIdsSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        "contentSha256": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
      },
      "releaseTuple": {
        "sourceManifestSha256": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
        "releaseMetadataSha256": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "databaseFingerprintSha256": "1111111111111111111111111111111111111111111111111111111111111111",
        "checksumsSha256": "2222222222222222222222222222222222222222222222222222222222222222"
      },
      "outbox": { "initialState": "empty", "highWatermark": 0 },
      "kafkaCausalFence": {
        "topic": "ACCOMMODATIONS.events",
        "partitionOffsets": { "0": 0 }
      }
    }'
  fi

  jq -n \
    --arg kind "$kind" \
    --arg datasetVersion "$([[ "$kind" == evidence ]] && printf traffic-v1 || printf nplus1-v1)" \
    --arg dumpSha "$dump_sha" \
    --arg benchmarkManifestSha "$benchmark_manifest_sha" \
    --argjson search "$search_json" \
    --argjson evidenceFragment "{${evidence_json#,}}" '
    {
      schemaVersion: 1,
      releaseKind: $kind,
      datasetRelease: "rehearsal-v16",
      datasetRunId: "etl-20260816-001",
      source: {
        datasetVersion: $datasetVersion,
        etlCommit: "0123456789abcdef0123456789abcdef01234567",
        seed: "airbob-production-seed-v1",
        profile: "large",
        manifestVersion: "benchmark-fixture-v1",
        canonicalPayloadSha256: "3333333333333333333333333333333333333333333333333333333333333333",
        benchmarkManifestKey: "benchmark/manifest.json",
        benchmarkManifestSha256: $benchmarkManifestSha
      },
      mysql: {
        dumpKey: "mysql/airbob.sql.zst",
        dumpSha256: $dumpSha,
        flywayVersion: "16",
        migrationChecksumSha256: "4444444444444444444444444444444444444444444444444444444444444444",
        schemaFingerprintSha256: "5555555555555555555555555555555555555555555555555555555555555555",
        timezone: "UTC",
        evaluationTime: "2026-08-16T00:00:00Z",
        validUntil: "2099-12-31T00:00:00Z",
        outboxPolicy: "absent",
        expectedTableRows: { flyway_schema_history: 16, outbox: 0, accommodation: 730702 }
      },
      couponPreparation: [],
      kafka: {
        topics: [
          { name: "PAYMENT.events", partitions: 1, retentionMs: 86400000 },
          { name: "RESERVATION.events", partitions: 1, retentionMs: 86400000 },
          { name: "ACCOMMODATIONS.events", partitions: 1, retentionMs: 86400000 }
        ]
      },
      search: $search
    } + $evidenceFragment
  ' > "$root/manifest.json"
}

rewrite_benchmark_manifest() {
  local root=$1
  local filter=$2
  local benchmark_sha

  jq "$filter" "$root/benchmark/manifest.json" > "$root/benchmark/manifest.next"
  mv "$root/benchmark/manifest.next" "$root/benchmark/manifest.json"
  benchmark_sha=$(sha256_file "$root/benchmark/manifest.json")
  jq --arg sha "$benchmark_sha" '.source.benchmarkManifestSha256 = $sha' \
    "$root/manifest.json" > "$root/manifest.next"
  mv "$root/manifest.next" "$root/manifest.json"
}

expect_failure() {
  local label=$1
  shift
  if "$@" >"$tmp_dir/$label.out" 2>&1; then
    printf 'expected failure: %s\n' "$label" >&2
    exit 1
  fi
  if rg -n 'canonical-zstd-fixture|hunter2' "$tmp_dir/$label.out" >/dev/null 2>&1; then
    printf 'validator leaked fixture content: %s\n' "$label" >&2
    exit 1
  fi
}

[[ -x "$validator" ]] || { printf '%s\n' 'dataset release validator is missing or not executable' >&2; exit 1; }

write_release "$tmp_dir/rehearsal" pipeline-rehearsal false
"$validator" "$tmp_dir/rehearsal" rehearsal-v16 pipeline-rehearsal >/dev/null

write_release "$tmp_dir/evidence" evidence true
"$validator" "$tmp_dir/evidence" rehearsal-v16 evidence >/dev/null

cp -R "$tmp_dir/rehearsal" "$tmp_dir/unknown-source-key"
jq '.source.unreviewed = "value"' \
  "$tmp_dir/unknown-source-key/manifest.json" > "$tmp_dir/unknown-source-key/manifest.next"
mv "$tmp_dir/unknown-source-key/manifest.next" "$tmp_dir/unknown-source-key/manifest.json"
expect_failure unknown-source-key "$validator" "$tmp_dir/unknown-source-key" rehearsal-v16 pipeline-rehearsal

cp -R "$tmp_dir/rehearsal" "$tmp_dir/missing-benchmark-manifest"
rm "$tmp_dir/missing-benchmark-manifest/benchmark/manifest.json"
expect_failure missing-benchmark-manifest "$validator" "$tmp_dir/missing-benchmark-manifest" rehearsal-v16 pipeline-rehearsal

cp -R "$tmp_dir/rehearsal" "$tmp_dir/symlink-benchmark-manifest"
rm "$tmp_dir/symlink-benchmark-manifest/benchmark/manifest.json"
ln -s ../manifest.json "$tmp_dir/symlink-benchmark-manifest/benchmark/manifest.json"
expect_failure symlink-benchmark-manifest "$validator" "$tmp_dir/symlink-benchmark-manifest" rehearsal-v16 pipeline-rehearsal

cp -R "$tmp_dir/rehearsal" "$tmp_dir/wrong-benchmark-hash"
jq '.account.email = "other@airbob.cloud"' \
  "$tmp_dir/wrong-benchmark-hash/benchmark/manifest.json" \
  > "$tmp_dir/wrong-benchmark-hash/benchmark/manifest.next"
mv "$tmp_dir/wrong-benchmark-hash/benchmark/manifest.next" \
  "$tmp_dir/wrong-benchmark-hash/benchmark/manifest.json"
expect_failure wrong-benchmark-hash "$validator" "$tmp_dir/wrong-benchmark-hash" rehearsal-v16 pipeline-rehearsal

cp -R "$tmp_dir/rehearsal" "$tmp_dir/wrong-benchmark-version"
rewrite_benchmark_manifest "$tmp_dir/wrong-benchmark-version" '.datasetVersion = "traffic-v1"'
expect_failure wrong-benchmark-version "$validator" "$tmp_dir/wrong-benchmark-version" rehearsal-v16 pipeline-rehearsal

cp -R "$tmp_dir/rehearsal" "$tmp_dir/benchmark-secret-key"
rewrite_benchmark_manifest "$tmp_dir/benchmark-secret-key" '.account.sessionToken = "hunter2"'
expect_failure benchmark-secret-key "$validator" "$tmp_dir/benchmark-secret-key" rehearsal-v16 pipeline-rehearsal

cp -R "$tmp_dir/rehearsal" "$tmp_dir/duplicate-recent-id"
rewrite_benchmark_manifest \
  "$tmp_dir/duplicate-recent-id" \
  '.recentlyViewed.accommodationIds[1] = .recentlyViewed.accommodationIds[0]'
expect_failure duplicate-recent-id "$validator" "$tmp_dir/duplicate-recent-id" rehearsal-v16 pipeline-rehearsal

cp -R "$tmp_dir/rehearsal" "$tmp_dir/wrong-flyway"
jq '.mysql.flywayVersion = "12"' "$tmp_dir/wrong-flyway/manifest.json" > "$tmp_dir/wrong-flyway/manifest.next"
mv "$tmp_dir/wrong-flyway/manifest.next" "$tmp_dir/wrong-flyway/manifest.json"
expect_failure wrong-flyway "$validator" "$tmp_dir/wrong-flyway" rehearsal-v16 pipeline-rehearsal

cp -R "$tmp_dir/rehearsal" "$tmp_dir/wrong-dump"
printf '%s' tampered >> "$tmp_dir/wrong-dump/mysql/airbob.sql.zst"
expect_failure wrong-dump "$validator" "$tmp_dir/wrong-dump" rehearsal-v16 pipeline-rehearsal

cp -R "$tmp_dir/rehearsal" "$tmp_dir/rehearsal-traffic"
jq '.source.datasetVersion = "traffic-v1"' "$tmp_dir/rehearsal-traffic/manifest.json" > "$tmp_dir/rehearsal-traffic/manifest.next"
mv "$tmp_dir/rehearsal-traffic/manifest.next" "$tmp_dir/rehearsal-traffic/manifest.json"
expect_failure rehearsal-traffic "$validator" "$tmp_dir/rehearsal-traffic" rehearsal-v16 pipeline-rehearsal

cp -R "$tmp_dir/evidence" "$tmp_dir/evidence-no-search"
jq '.search = {enabled: false}' "$tmp_dir/evidence-no-search/manifest.json" > "$tmp_dir/evidence-no-search/manifest.next"
mv "$tmp_dir/evidence-no-search/manifest.next" "$tmp_dir/evidence-no-search/manifest.json"
expect_failure evidence-no-search "$validator" "$tmp_dir/evidence-no-search" rehearsal-v16 evidence

cp -R "$tmp_dir/evidence" "$tmp_dir/evidence-mismatch"
jq '.search.elasticsearchAccommodationIdsSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"' "$tmp_dir/evidence-mismatch/manifest.json" > "$tmp_dir/evidence-mismatch/manifest.next"
mv "$tmp_dir/evidence-mismatch/manifest.next" "$tmp_dir/evidence-mismatch/manifest.json"
expect_failure evidence-mismatch "$validator" "$tmp_dir/evidence-mismatch" rehearsal-v16 evidence

cp -R "$tmp_dir/evidence" "$tmp_dir/evidence-content-mismatch"
jq '.evidence.targetFingerprints.contentSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"' \
  "$tmp_dir/evidence-content-mismatch/manifest.json" > "$tmp_dir/evidence-content-mismatch/manifest.next"
mv "$tmp_dir/evidence-content-mismatch/manifest.next" "$tmp_dir/evidence-content-mismatch/manifest.json"
expect_failure evidence-content-mismatch "$validator" "$tmp_dir/evidence-content-mismatch" rehearsal-v16 evidence

cp -R "$tmp_dir/evidence" "$tmp_dir/evidence-wrong-causal-topic"
jq '.evidence.kafkaCausalFence.topic = "PAYMENT.events"' \
  "$tmp_dir/evidence-wrong-causal-topic/manifest.json" > "$tmp_dir/evidence-wrong-causal-topic/manifest.next"
mv "$tmp_dir/evidence-wrong-causal-topic/manifest.next" "$tmp_dir/evidence-wrong-causal-topic/manifest.json"
expect_failure evidence-wrong-causal-topic "$validator" "$tmp_dir/evidence-wrong-causal-topic" rehearsal-v16 evidence

cp -R "$tmp_dir/rehearsal" "$tmp_dir/secret-key"
jq '.mysql.password = "hunter2"' "$tmp_dir/secret-key/manifest.json" > "$tmp_dir/secret-key/manifest.next"
mv "$tmp_dir/secret-key/manifest.next" "$tmp_dir/secret-key/manifest.json"
expect_failure secret-key "$validator" "$tmp_dir/secret-key" rehearsal-v16 pipeline-rehearsal

cp -R "$tmp_dir/rehearsal" "$tmp_dir/duplicate-coupon"
jq '.couponPreparation = [{couponId: 1, quantity: 10}, {couponId: 1, quantity: 10}]' \
  "$tmp_dir/duplicate-coupon/manifest.json" > "$tmp_dir/duplicate-coupon/manifest.next"
mv "$tmp_dir/duplicate-coupon/manifest.next" "$tmp_dir/duplicate-coupon/manifest.json"
expect_failure duplicate-coupon "$validator" "$tmp_dir/duplicate-coupon" rehearsal-v16 pipeline-rehearsal

cp -R "$tmp_dir/evidence" "$tmp_dir/cross-release-snapshot"
jq '.basePath = "elasticsearch/releases/another-release"' \
  "$tmp_dir/cross-release-snapshot/elasticsearch/snapshot-reference.json" \
  > "$tmp_dir/cross-release-snapshot/elasticsearch/snapshot-reference.next"
mv "$tmp_dir/cross-release-snapshot/elasticsearch/snapshot-reference.next" \
  "$tmp_dir/cross-release-snapshot/elasticsearch/snapshot-reference.json"
expect_failure cross-release-snapshot "$validator" "$tmp_dir/cross-release-snapshot" rehearsal-v16 evidence

printf '%s\n' 'dataset release tests passed'
