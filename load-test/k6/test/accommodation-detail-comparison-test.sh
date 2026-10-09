#!/usr/bin/env bash
set -euo pipefail

test_dir=$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$test_dir/../../.." && pwd -P)
script="$repo_root/load-test/k6/cache/accommodation-detail-comparison.js"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/cache-detail-comparison.XXXXXX")
server_pid=''
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf -- "$temp_dir"
}
trap cleanup EXIT

node --input-type=module - "$temp_dir/fixture.json" <<'NODE'
import { writeFileSync } from 'node:fs';
writeFileSync(process.argv[2], JSON.stringify({
  schemaVersion: 1,
  datasetId: 'offline-http-fixture',
  accommodations: Array.from({ length: 5 }, (_, index) => ({
    id: index + 1,
    data: {
      id: index + 1,
      name: `Offline stay ${index + 1}`,
      amenities: [{ type: 'wifi', count: 1 }, { type: 'bed', count: 2 }],
      images: [{ id: 2, url: '/first' }, { id: 3, url: '/second' }],
      review_summary: { average_rating: 4.7, review_count: 12 },
    },
  })),
}));
NODE

start_server() {
  local server_mode=$1
  rm -f -- "$temp_dir/port" "$temp_dir/observations.json"
  env BENCHMARK_READ_MODEL_TOKEN=offline-http-token \
    node "$test_dir/accommodation-detail-comparison-mock-server.mjs" \
    "$temp_dir/port" "$temp_dir/fixture.json" "$temp_dir/observations.json" "$server_mode" &
  server_pid=$!
  for _ in {1..50}; do
    [[ -s "$temp_dir/port" ]] && break
    kill -0 "$server_pid" 2>/dev/null || { wait "$server_pid"; return 1; }
    sleep 0.1
  done
  [[ -s "$temp_dir/port" ]]
  mock_port=$(tr -d '\n' < "$temp_dir/port")
}

stop_server() {
  kill "$server_pid" 2>/dev/null || true
  wait "$server_pid" 2>/dev/null || true
  server_pid=''
}

invoke_k6() {
  local variant=$1
  local mode=$2
  local label=$3
  shift 3
  env K6_NO_USAGE_REPORT=true \
    BASE_URL="http://127.0.0.1:$mock_port" \
    BENCHMARK_READ_MODEL_TOKEN=offline-http-token \
    CACHE_BENCHMARK_FIXTURE="$temp_dir/fixture.json" \
    VARIANT="$variant" MODE="$mode" DISTRIBUTION=hotset-80-20 RATE=20 DURATION=1s \
    RUN_LABEL="$label" RESULT_PATH="$temp_dir/$label.json" \
    APP_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    "$@" "${K6_BIN:-k6}" run --quiet "$script" >"$temp_dir/$label.log" 2>&1
}

verify_requests() {
  local version=$1
  local expected_count=$2
  jq -e --arg version "$version" --argjson count "$expected_count" '
    length == $count and all(.[];
      .method == "GET" and .tokenMatches and (.hasSession | not) and
      (.path | test("^/api/" + $version + "/accommodations/[1-5]$")))
  ' "$temp_dir/observations.json" >/dev/null
}

for variant in before after; do
  start_server success
  invoke_k6 "$variant" measure "$variant"
  stop_server
  jq -e --arg variant "$variant" '
    .validity.status == "valid" and .metadata.phase == "measure" and
    .metadata.variant == $variant and .load.iterations.completed >= 20 and
    .load.iterations.successful == .load.iterations.completed and
    .load.achievedRps == .load.iterations.completed and
    .performance.errorRate == 0 and .performance.latencyMs.p95 >= 0 and
    .performance.latencyMs.p99 >= .performance.latencyMs.p50
  ' "$temp_dir/$variant.json" >/dev/null
  count=$(jq '.load.iterations.completed' "$temp_dir/$variant.json")
  version=v1
  [[ "$variant" != before ]] || version=v2
  verify_requests "$version" "$count"
done

start_server success
invoke_k6 after warmup warmup
stop_server
[[ ! -e "$temp_dir/warmup.json" ]]
jq -e 'length >= 20 and all(.[]; .tokenMatches and (.hasSession | not))' \
  "$temp_dir/observations.json" >/dev/null

for failure_mode in payload status json redirect; do
  start_server "$failure_mode"
  if invoke_k6 after measure "$failure_mode"; then
    printf '%s\n' "expected $failure_mode failure was accepted" >&2
    exit 1
  fi
  stop_server
  jq -e '.validity.status == "invalid" and .performance.errorRate == 1 and
    (.validity.reasons | index("request-errors")) != null' \
    "$temp_dir/$failure_mode.json" >/dev/null
  count=$(jq '.load.iterations.completed' "$temp_dir/$failure_mode.json")
  verify_requests v1 "$count"
done

start_server slow
if invoke_k6 before measure overloaded RATE=100 PRE_ALLOCATED_VUS=1 MAX_VUS=1; then
  printf '%s\n' 'dropped requests unexpectedly passed thresholds' >&2
  exit 1
fi
stop_server
jq -e '.validity.status == "invalid" and .load.iterations.dropped > 0 and
  .load.iterations.completed < .load.iterations.minimumRequired and
  (.validity.reasons | index("dropped-iterations")) != null and
  (.validity.reasons | index("minimum-samples-not-met")) != null' \
  "$temp_dir/overloaded.json" >/dev/null

if rg -q 'offline-http-token|Offline stay' "$temp_dir"/*.log \
  "$temp_dir/before.json" "$temp_dir/after.json"; then
  printf '%s\n' 'benchmark leaked token or response data into output' >&2
  exit 1
fi
printf '%s\n' 'accommodation detail comparison local HTTP checks passed'
