#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$test_dir/../../.." && pwd -P)"
script="$repo_root/load-test/k6/bulk-write/reservation-history-insert-comparison.js"
base_manifest="$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v2.json"
legacy_capsule_fixture="$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v1.json"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/reservation-history-inspect.XXXXXX")
trap 'rm -rf -- "$temp_dir"' EXIT
manifest="$temp_dir/benchmark-dataset-v2.json"

jq --slurpfile legacy "$legacy_capsule_fixture" '
  ($legacy[0].capsules | map(select(.capsuleId == "bulk-expiration-history-v1"))) as $capsules |
  if ($capsules | length) == 1
  then .capsules += $capsules
  else error("bulk-expiration-history-v1 capsule fixture is not exact")
  end
' "$base_manifest" > "$manifest"

BASE_URL=http://localhost:8080 \
VARIANT=BEFORE \
PHASE=measure \
DATASET_SIZE=100 \
SAMPLES=1 \
BENCHMARK_BULK_WRITE_TOKEN=0123456789abcdef0123456789abcdef \
BENCHMARK_EMAIL=benchmark@example.com \
TEST_PASSWORD=test-password \
ROUND=1 \
RUN_ORDER=1 \
APP_COMMIT=local-test \
APP_INSTANCE_COUNT=1 \
SCHEMA_LABEL=airbob-bulk-write-v1 \
JVM_VERSION=21 \
MYSQL_VERSION=8.0 \
REWRITE_BATCHED_STATEMENTS=false \
BENCHMARK_DATASET_MANIFEST="$manifest" \
"${K6_BIN:-k6}" inspect --include-system-env-vars "$script" >/dev/null

BASE_URL=http://localhost:8080 \
VARIANT=AFTER \
PHASE=measure \
DATASET_SIZE=100 \
SAMPLES=1 \
BENCHMARK_BULK_WRITE_TOKEN=0123456789abcdef0123456789abcdef \
BENCHMARK_EMAIL=benchmark@example.com \
TEST_PASSWORD=test-password \
ROUND=1 \
RUN_ORDER=1 \
APP_COMMIT=local-test \
APP_INSTANCE_COUNT=1 \
SCHEMA_LABEL=airbob-bulk-write-v1 \
JVM_VERSION=21 \
MYSQL_VERSION=8.0 \
REWRITE_BATCHED_STATEMENTS=false \
BENCHMARK_DATASET_MANIFEST="$manifest" \
"${K6_BIN:-k6}" inspect --include-system-env-vars "$script" >/dev/null
