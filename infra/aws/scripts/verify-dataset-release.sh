#!/usr/bin/env bash
set -euo pipefail
umask 077

usage() {
  printf 'usage: %s RELEASE_DIR EXPECTED_RELEASE EXPECTED_KIND [--metadata-only]\n' "${0##*/}" >&2
  exit 64
}

[[ "$#" -eq 3 || ( "$#" -eq 4 && "$4" == --metadata-only ) ]] || usage
release_dir=$1
expected_release=$2
expected_kind=$3
metadata_only=false
[[ "$#" -eq 3 ]] || metadata_only=true

[[ "$expected_release" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ ]] || { printf '%s\n' 'invalid expected dataset release' >&2; exit 1; }
case "$expected_kind" in
  pipeline-rehearsal|evidence) ;;
  *) printf '%s\n' 'invalid expected release kind' >&2; exit 1 ;;
esac
[[ -d "$release_dir" && ! -L "$release_dir" ]] || { printf '%s\n' 'dataset release directory is missing or unsafe' >&2; exit 1; }
release_dir=$(cd "$release_dir" && pwd -P)

manifest="$release_dir/manifest.json"
benchmark_manifest="$release_dir/benchmark/manifest.json"
dump="$release_dir/mysql/airbob.sql.zst"
checksum="$release_dir/mysql/sha256.txt"
required_files=("$manifest" "$benchmark_manifest" "$checksum")
[[ "$metadata_only" == true ]] || required_files+=("$dump")
for required_file in "${required_files[@]}"; do
  [[ -f "$required_file" && ! -L "$required_file" ]] || { printf '%s\n' 'dataset release artifact is missing or unsafe' >&2; exit 1; }
done

command -v jq >/dev/null 2>&1 || { printf '%s\n' 'jq is required to validate a dataset release' >&2; exit 1; }

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    printf '%s\n' 'a SHA-256 implementation is required' >&2
    return 1
  fi
}

fail_manifest() {
  printf 'dataset manifest does not satisfy the Phase 3 contract: %s\n' "${1:-unknown stage}" >&2
  exit 1
}

common_jq='
  def exact_keys($wanted): (keys | sort) == ($wanted | sort);
  def sha256: type == "string" and test("^[0-9a-f]{64}$");
  def image_digest: type == "string" and test("^sha256:[0-9a-f]{64}$");
  def timestamp: type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$");
  def safe_name: type == "string" and test("^[a-zA-Z0-9._-]+$");
  exact_keys($topKeys) and
  .schemaVersion == 1 and
  .releaseKind == $expectedKind and
  .datasetRelease == $expectedRelease and
  (.datasetRunId | type == "string" and test("^[a-z0-9][a-z0-9._-]{2,63}$")) and
  (.source | exact_keys(["datasetVersion", "etlCommit", "seed", "profile", "manifestVersion", "canonicalPayloadSha256", "benchmarkManifestKey", "benchmarkManifestSha256"])) and
  (.source.etlCommit | type == "string" and test("^[0-9a-f]{40}$")) and
  (.source.seed | safe_name) and
  (.source.profile | safe_name) and
  (.source.manifestVersion | safe_name) and
  (.source.canonicalPayloadSha256 | sha256) and
  .source.benchmarkManifestKey == "benchmark/manifest.json" and
  (.source.benchmarkManifestSha256 | sha256) and
  (.mysql | exact_keys(["dumpKey", "dumpSha256", "flywayVersion", "migrationChecksumSha256", "schemaFingerprintSha256", "timezone", "evaluationTime", "validUntil", "outboxPolicy", "expectedTableRows"])) and
  .mysql.dumpKey == "mysql/airbob.sql.zst" and
  (.mysql.dumpSha256 | sha256) and
  .mysql.flywayVersion == "16" and
  (.mysql.migrationChecksumSha256 | sha256) and
  (.mysql.schemaFingerprintSha256 | sha256) and
  .mysql.timezone == "UTC" and
  (.mysql.evaluationTime | timestamp) and
  (.mysql.validUntil | timestamp) and
  .mysql.validUntil > .mysql.evaluationTime and
  (.mysql.validUntil | fromdateiso8601) > now and
  (.mysql.outboxPolicy == "absent" or .mysql.outboxPolicy == "truncate-after-import") and
  (.mysql.expectedTableRows | type == "object") and
  (.mysql.expectedTableRows | has("flyway_schema_history") and has("outbox") and has("accommodation")) and
  all(.mysql.expectedTableRows | to_entries[]; (.key | test("^[a-z][a-z0-9_]{0,63}$")) and (.value | type == "number" and floor == . and . >= 0)) and
  (.couponPreparation | type == "array") and
  all(.couponPreparation[]; exact_keys(["couponId", "quantity"]) and (.couponId | type == "number" and floor == . and . > 0) and (.quantity | type == "number" and floor == . and . >= 0)) and
  ([.couponPreparation[].couponId] | length == (unique | length)) and
  (.kafka | exact_keys(["topics"])) and
  (.kafka.topics | type == "array" and length == 3) and
  ([.kafka.topics[].name] | sort) == ["ACCOMMODATIONS.events", "PAYMENT.events", "RESERVATION.events"] and
  all(.kafka.topics[]; exact_keys(["name", "partitions", "retentionMs"]) and (.partitions | type == "number" and floor == . and . >= 1 and . <= 12) and (.retentionMs | type == "number" and floor == . and . >= 3600000)) and
  ([.. | objects | keys[]] | all(test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not))
'

if [[ "$expected_kind" == evidence ]]; then
  top_keys='["schemaVersion","releaseKind","datasetRelease","datasetRunId","source","mysql","couponPreparation","kafka","search","evidence"]'
else
  top_keys='["schemaVersion","releaseKind","datasetRelease","datasetRunId","source","mysql","couponPreparation","kafka","search"]'
fi

jq -e \
  --arg expectedRelease "$expected_release" \
  --arg expectedKind "$expected_kind" \
  --argjson topKeys "$top_keys" \
  "$common_jq" "$manifest" >/dev/null || fail_manifest common

[[ "$(wc -c < "$benchmark_manifest" | tr -d '[:space:]')" -le 1048576 ]] \
  || fail_manifest benchmark-size
expected_benchmark_sha=$(jq -r '.source.benchmarkManifestSha256' "$manifest")
actual_benchmark_sha=$(sha256_file "$benchmark_manifest")
[[ "$actual_benchmark_sha" == "$expected_benchmark_sha" ]] || fail_manifest benchmark-sha256
jq -e \
  --arg datasetVersion "$(jq -r '.source.datasetVersion' "$manifest")" '
    .datasetVersion == $datasetVersion and
    ([.. | objects | keys[]] | all(test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not))
  ' "$benchmark_manifest" >/dev/null || fail_manifest benchmark-common

if [[ "$expected_kind" == pipeline-rehearsal ]]; then
  jq -e '.source.datasetVersion == "nplus1-v1"' "$manifest" >/dev/null || fail_manifest rehearsal-source
  jq -e '
    def positive_integer: type == "number" and floor == . and . > 0;
    .datasetVersion == "nplus1-v1" and
    .account.email == "benchmark-nplus1@airbob.cloud" and
    (.maxRequestedSize | positive_integer) and
    (.requiredRows | positive_integer) and
    .requiredRows == (.maxRequestedSize + 1) and
    (.review.accommodationId | positive_integer) and
    (.review.publishedReviewCount | positive_integer) and
    (.review.reviewsWithImages | positive_integer) and
    .review.publishedReviewCount >= .requiredRows and
    .review.reviewsWithImages == .requiredRows and
    (.hostAccommodations.detailAccommodationId | positive_integer) and
    .hostAccommodations.expectedRows == .requiredRows and
    .guestReservations.filterType == "PAST" and
    .guestReservations.expectedRows == .requiredRows and
    .hostReservations.filterType == "PAST" and
    .hostReservations.expectedRows == .requiredRows and
    (.wishlists.primaryWishlistId | positive_integer) and
    .wishlists.expectedRows == .requiredRows and
    .wishlists.primaryWishlistAccommodationRows == .requiredRows and
    (.recentlyViewed.maxRows | positive_integer) and
    .recentlyViewed.maxRows == ([100, .requiredRows] | min) and
    (.recentlyViewed.accommodationIds | type == "array") and
    (.recentlyViewed.accommodationIds | length) == .recentlyViewed.maxRows and
    all(.recentlyViewed.accommodationIds[]; positive_integer) and
    ([.recentlyViewed.accommodationIds[]] | length == (unique | length))
  ' "$benchmark_manifest" >/dev/null || fail_manifest rehearsal-benchmark
else
  jq -e '
    def exact_keys($wanted): (keys | sort) == ($wanted | sort);
    def sha256: type == "string" and test("^[0-9a-f]{64}$");
    .source.datasetVersion == "traffic-v1" and
    .search.enabled == true and
    (.evidence | exact_keys(["trafficAccountCapacity", "targetFingerprints", "releaseTuple", "outbox", "kafkaCausalFence"])) and
    (.evidence.trafficAccountCapacity | type == "number" and floor == . and . > 0) and
    (.evidence.targetFingerprints | exact_keys(["databaseAccommodationIdsSha256", "contentSha256"])) and
    (.evidence.targetFingerprints.databaseAccommodationIdsSha256 | sha256) and
    (.evidence.targetFingerprints.contentSha256 | sha256) and
    .evidence.targetFingerprints.databaseAccommodationIdsSha256 == .search.databaseAccommodationIdsSha256 and
    .evidence.targetFingerprints.contentSha256 == .search.contentFingerprintSha256 and
    (.evidence.releaseTuple | exact_keys(["sourceManifestSha256", "releaseMetadataSha256", "databaseFingerprintSha256", "checksumsSha256"])) and
    all(.evidence.releaseTuple[]; sha256) and
    (.evidence.outbox | exact_keys(["initialState", "highWatermark"])) and
    .evidence.outbox.initialState == "empty" and
    (.evidence.outbox.highWatermark | type == "number" and floor == . and . >= 0) and
    (.evidence.kafkaCausalFence | exact_keys(["topic", "partitionOffsets"])) and
    .evidence.kafkaCausalFence.topic == "ACCOMMODATIONS.events" and
    (.evidence.kafkaCausalFence.partitionOffsets | type == "object" and length > 0) and
    all(.evidence.kafkaCausalFence.partitionOffsets | to_entries[]; (.key | test("^[0-9]+$")) and (.value | type == "number" and floor == . and . >= 0))
  ' "$manifest" >/dev/null || fail_manifest evidence
fi

search_enabled=$(jq -r '.search.enabled // false' "$manifest")
case "$search_enabled" in
  false)
    [[ "$expected_kind" == pipeline-rehearsal ]] || fail_manifest search-required
    jq -e '.search | (keys | sort) == ["enabled"]' "$manifest" >/dev/null || fail_manifest disabled-search
    [[ ! -e "$release_dir/elasticsearch/snapshot-reference.json" ]] || fail_manifest disabled-search-artifact
    ;;
  true)
    snapshot_reference="$release_dir/elasticsearch/snapshot-reference.json"
    [[ -f "$snapshot_reference" && ! -L "$snapshot_reference" ]] || fail_manifest snapshot-reference
    jq -e '
      def exact_keys($wanted): (keys | sort) == ($wanted | sort);
      def sha256: type == "string" and test("^[0-9a-f]{64}$");
      def image_digest: type == "string" and test("^sha256:[0-9a-f]{64}$");
      .search |
      exact_keys(["enabled", "snapshotReferenceKey", "repository", "elasticsearchVersion", "imageDigest", "requiredPlugins", "index", "documentCount", "mappingSha256", "databaseAccommodationIdsSha256", "elasticsearchAccommodationIdsSha256", "contentFingerprintSha256"]) and
      .enabled == true and
      .snapshotReferenceKey == "elasticsearch/snapshot-reference.json" and
      .repository == "airbob-dataset-readonly" and
      .elasticsearchVersion == "8.18.8" and
      (.imageDigest | image_digest) and
      (.requiredPlugins | sort) == ["analysis-nori", "repository-s3"] and
      .index == "accommodations" and
      (.documentCount | type == "number" and floor == . and . >= 0) and
      (.mappingSha256 | sha256) and
      (.databaseAccommodationIdsSha256 | sha256) and
      (.elasticsearchAccommodationIdsSha256 | sha256) and
      .databaseAccommodationIdsSha256 == .elasticsearchAccommodationIdsSha256 and
      (.contentFingerprintSha256 | sha256)
    ' "$manifest" >/dev/null || fail_manifest search
    jq -e --slurpfile manifest "$manifest" '
      def exact_keys($wanted): (keys | sort) == ($wanted | sort);
      exact_keys(["schemaVersion", "repository", "bucket", "basePath", "snapshot", "index", "elasticsearchVersion", "imageDigest", "documentCount", "mappingSha256", "dbIdsSha256", "esIdsSha256", "contentFingerprintSha256"]) and
      .schemaVersion == 1 and
      .repository == $manifest[0].search.repository and
      (.bucket | type == "string" and test("^airbob-performance-lab-dataset-[0-9]{12}$")) and
      .basePath == ("elasticsearch/releases/" + $manifest[0].datasetRelease) and
      (.snapshot | type == "string" and test("^[a-z0-9._-]+$")) and
      .index == $manifest[0].search.index and
      .elasticsearchVersion == $manifest[0].search.elasticsearchVersion and
      .imageDigest == $manifest[0].search.imageDigest and
      .documentCount == $manifest[0].search.documentCount and
      .mappingSha256 == $manifest[0].search.mappingSha256 and
      .dbIdsSha256 == $manifest[0].search.databaseAccommodationIdsSha256 and
      .esIdsSha256 == $manifest[0].search.elasticsearchAccommodationIdsSha256 and
      .contentFingerprintSha256 == $manifest[0].search.contentFingerprintSha256
    ' "$snapshot_reference" >/dev/null || fail_manifest snapshot-reference
    ;;
  *) fail_manifest search-enabled ;;
esac

expected_dump_sha=$(jq -r '.mysql.dumpSha256' "$manifest")
[[ "$(cat "$checksum")" == "$expected_dump_sha  airbob.sql.zst" ]] || { printf '%s\n' 'dataset checksum file is not canonical' >&2; exit 1; }
if [[ "$metadata_only" == false ]]; then
  actual_dump_sha=$(sha256_file "$dump")
  [[ "$actual_dump_sha" == "$expected_dump_sha" ]] || { printf '%s\n' 'dataset dump checksum mismatch' >&2; exit 1; }
fi

printf '%s\n' 'dataset release verified'
