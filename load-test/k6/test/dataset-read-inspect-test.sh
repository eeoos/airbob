#!/usr/bin/env bash
set -euo pipefail

test_dir=$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$test_dir/../../.." && pwd -P)
script="$repo_root/load-test/k6/traffic/dataset-read.js"
base_manifest="$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v2.json"
legacy_capsule_fixture="$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v1.json"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/dataset-read-inspect.XXXXXX")
manifest="$temp_dir/benchmark-dataset-v2.json"
server_pid=''
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf -- "$temp_dir"
}
trap cleanup EXIT

jq --slurpfile legacy "$legacy_capsule_fixture" '
  ($legacy[0].capsules | map(select(.capsuleId == "cache-detail-v1"))) as $capsules |
  if ($capsules | length) == 1
  then .capsules += $capsules
  else error("cache-detail-v1 capsule fixture is not exact")
  end
' "$base_manifest" > "$manifest"

if command -v sha256sum >/dev/null 2>&1; then
  manifest_sha=$(sha256sum "$manifest" | awk '{print $1}')
else
  manifest_sha=$(shasum -a 256 "$manifest" | awk '{print $1}')
fi

jq -n \
  --arg sha "$manifest_sha" \
  '{
    schemaVersion: 1,
    manifestSha256: $sha,
    capsuleId: "cache-detail-v1",
    action: "flush-dedicated-cache-redis",
    dbSizeAfter: 0,
    cacheEnabled: true,
    variant: "warm",
    runLabel: "cache-warm-r1",
    generatedAt: "2026-08-26T12:00:00Z"
  }' > "$temp_dir/cache-reset.json"

common_environment=(
  ROLE=guest
  RATE=1
  DURATION=1s
  MIN_COMPLETED_SAMPLES=1
  ROUND=1
  RUN_ORDER=1
  APP_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  APP_INSTANCE_COUNT=1
  BASE_URL=http://127.0.0.1:8080
  BENCHMARK_DATASET_MANIFEST="$manifest"
)

env MODE=inspect TARGET=cache-detail CAPSULE_TARGET=same-key \
  RUN_LABEL=cache-warm-r1 CACHE_VARIANT=warm CACHE_ENABLED=true \
  CACHE_DISTRIBUTION=same-key CACHE_RESET_RECEIPT="$temp_dir/cache-reset.json" \
  "${common_environment[@]}" \
  "${K6_BIN:-k6}" inspect --include-system-env-vars "$script" >/dev/null

env MODE=inspect TARGET=index-query CAPSULE_TARGET=search-medium \
  RUN_LABEL=search-medium-r1 "${common_environment[@]}" \
  "${K6_BIN:-k6}" inspect --include-system-env-vars "$script" >/dev/null

start_mock_server() {
  local failing_resource_id=${1:-}
  local port_file="$temp_dir/mock-port"
  rm -f -- "$port_file"
  node "$repo_root/load-test/k6/test/dataset-read-mock-server.mjs" \
    "$port_file" "$failing_resource_id" &
  server_pid=$!
  for _ in {1..50}; do
    [[ -s "$port_file" ]] && break
    kill -0 "$server_pid" 2>/dev/null || {
      wait "$server_pid"
      return 1
    }
    sleep 0.1
  done
  [[ -s "$port_file" ]] || return 1
  mock_port=$(tr -d '\n' < "$port_file")
}

stop_mock_server() {
  kill "$server_pid" 2>/dev/null || true
  wait "$server_pid" 2>/dev/null || true
  server_pid=''
}

write_reset_receipt() {
  local run_label=$1
  local output=$2
  jq -n \
    --arg sha "$manifest_sha" \
    --arg runLabel "$run_label" \
    '{
      schemaVersion: 1,
      manifestSha256: $sha,
      capsuleId: "cache-detail-v1",
      action: "flush-dedicated-cache-redis",
      dbSizeAfter: 0,
      cacheEnabled: true,
      variant: "warm",
      runLabel: $runLabel,
      generatedAt: "2026-08-26T12:00:00Z"
    }' > "$output"
}

mkdir -p "$temp_dir/build/k6/traffic"
measure_run_label=cache-warm-measure-r1
write_reset_receipt "$measure_run_label" "$temp_dir/measure-reset.json"
start_mock_server
(
  cd "$temp_dir"
  env MODE=measure TARGET=cache-detail CAPSULE_TARGET=detail-pool \
    RUN_LABEL="$measure_run_label" CACHE_VARIANT=warm CACHE_ENABLED=true \
    CACHE_DISTRIBUTION=hotset-80-20 CACHE_RESET_RECEIPT="$temp_dir/measure-reset.json" \
    BASE_URL="http://127.0.0.1:$mock_port" \
    ROLE=guest RATE=1 DURATION=1s MIN_COMPLETED_SAMPLES=1 ROUND=1 RUN_ORDER=1 \
    APP_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa APP_INSTANCE_COUNT=1 \
    BENCHMARK_DATASET_MANIFEST="$manifest" \
    "${K6_BIN:-k6}" run --quiet "$script" >/dev/null
)
stop_mock_server

jq -e '
  .metadata.releaseKind == "pipeline-rehearsal" and
  .metadata.claimScope == "pipeline-only" and
  .metadata.cache.warmKeyCoverage == {
    required: true,
    declaredTargetKeys: 200,
    expectedKeys: 200,
    completedKeys: 200,
    status: "complete"
  } and
  .validity.status == "valid"
' "$temp_dir/build/k6/traffic/$measure_run_label.json" >/dev/null

failing_run_label=cache-warm-failing-r1
write_reset_receipt "$failing_run_label" "$temp_dir/failing-reset.json"
start_mock_server 200
if (
  cd "$temp_dir"
  env MODE=measure TARGET=cache-detail CAPSULE_TARGET=detail-pool \
    RUN_LABEL="$failing_run_label" CACHE_VARIANT=warm CACHE_ENABLED=true \
    CACHE_DISTRIBUTION=uniform CACHE_RESET_RECEIPT="$temp_dir/failing-reset.json" \
    BASE_URL="http://127.0.0.1:$mock_port" \
    ROLE=guest RATE=1 DURATION=1s MIN_COMPLETED_SAMPLES=1 ROUND=1 RUN_ORDER=1 \
    APP_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa APP_INSTANCE_COUNT=1 \
    BENCHMARK_DATASET_MANIFEST="$manifest" \
    "${K6_BIN:-k6}" run --quiet "$script" >/dev/null 2>&1
); then
  printf '%s\n' 'warm prefetch mismatch unexpectedly succeeded' >&2
  exit 1
fi
stop_mock_server

jq -e '
  .metadata.cache.warmKeyCoverage.expectedKeys == 200 and
  .metadata.cache.warmKeyCoverage.completedKeys == 199 and
  .metadata.cache.warmKeyCoverage.status == "incomplete" and
  .validity.status == "invalid" and
  (.validity.reasons | index("cache-warm-coverage-incomplete")) != null
' "$temp_dir/build/k6/traffic/$failing_run_label.json" >/dev/null
