#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$test_dir/../../.." && pwd -P)"
runner="$repo_root/load-test/k6/bulk-write/run-wishlist-delete-observations.sh"
temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/wishlist-observation-runner-test.XXXXXX")"
label="wishlist-observation-contract-$$"
failure_label="wishlist-observation-failure-$$"
first_failure_label="wishlist-observation-first-failure-$$"
aggregator_failure_label="wishlist-observation-aggregator-failure-$$"
result="build/k6/bulk-write/$label-observations.json"
failure_result="build/k6/bulk-write/$failure_label-observations.json"
first_failure_result="build/k6/bulk-write/$first_failure_label-observations.json"
aggregator_failure_result="build/k6/bulk-write/$aggregator_failure_label-observations.json"
token='token-sentinel-0123456789abcdef0123456789'
trap 'rm -rf -- "$temp_dir" "$repo_root/build/k6/bulk-write/$label"* "$repo_root/build/k6/bulk-write/$failure_label"* "$repo_root/build/k6/bulk-write/$first_failure_label"* "$repo_root/build/k6/bulk-write/$aggregator_failure_label"*' EXIT

if [[ ! -x "$runner" ]]; then
  printf 'Wishlist observation runner is missing or not executable\n' >&2
  exit 1
fi

assert_rejected() {
  local description="$1"
  shift
  if "$@" >"$temp_dir/rejected-output" 2>&1; then
    printf 'runner accepted %s\n' "$description" >&2
    exit 1
  fi
  if grep -Fq -- "$token" "$temp_dir/rejected-output"; then
    printf 'runner disclosed the token while rejecting %s\n' "$description" >&2
    exit 1
  fi
}

assert_rejected 'a positional argument' env \
  PHASE=measure RAW_OBSERVATION_SAMPLES=2 RUN_LABEL="$label" \
  RAW_OBSERVATION_RESULT_PATH="$result" BENCHMARK_BULK_WRITE_TOKEN="$token" \
  "$runner" unexpected
assert_rejected 'a warmup phase' env \
  PHASE=warmup RAW_OBSERVATION_SAMPLES=2 RUN_LABEL="$label" \
  RAW_OBSERVATION_RESULT_PATH="$result" BENCHMARK_BULK_WRITE_TOKEN="$token" \
  "$runner"

cat >"$temp_dir/capture-child" <<'CHILD'
#!/usr/bin/env bash
set -euo pipefail

printf 'child samples=%s phase=%s label=%s order=%s path=%s\n' \
  "$SAMPLES" "$PHASE" "$RUN_LABEL" "$RUN_ORDER" "$K6_RESULT_PATH" >>"$CAPTURE_LOG"
mkdir -p -- "$(dirname -- "$K6_RESULT_PATH")"
printf '{}\n' >"$K6_RESULT_PATH"
CHILD

cat >"$temp_dir/capture-node" <<'NODE'
#!/usr/bin/env bash
set -euo pipefail

[[ -z "${NODE_OPTIONS+x}" ]]
[[ -z "${NODE_PATH+x}" ]]
[[ -n "${BENCHMARK_BULK_WRITE_TOKEN:-}" ]]
printf 'node' >>"$CAPTURE_LOG"
for argument in "$@"; do
  printf ' arg=%s' "$argument" >>"$CAPTURE_LOG"
done
printf '\n' >>"$CAPTURE_LOG"

shift
[[ "$1" == '--candidate' ]]
[[ "$2" == 'WISHLIST_DELETE' ]]
shift 2
[[ "$1" == '--output' ]]
output="$2"
shift 2
[[ "$1" == '--run-label' ]]
shift 2
for source in "$@"; do
  [[ -f "$source" ]]
done
printf '{"complete":true}\n' >"$output"
NODE

cat >"$temp_dir/fail-second-child" <<'FAIL_CHILD'
#!/usr/bin/env bash
set -euo pipefail

printf 'child label=%s\n' "$RUN_LABEL" >>"$CAPTURE_LOG"
mkdir -p -- "$(dirname -- "$K6_RESULT_PATH")"
printf '{}\n' >"$K6_RESULT_PATH"
if [[ "$RUN_LABEL" == *'-sample-002' ]]; then
  exit 23
fi
FAIL_CHILD

cat >"$temp_dir/fail-first-child" <<'FAIL_FIRST_CHILD'
#!/usr/bin/env bash
set -euo pipefail

mkdir -p -- "$(dirname -- "$K6_RESULT_PATH")"
printf '{}\n' >"$K6_RESULT_PATH"
exit 31
FAIL_FIRST_CHILD

cat >"$temp_dir/fail-after-companion" <<'FAIL_AFTER_COMPANION'
#!/usr/bin/env bash
set -euo pipefail

shift
[[ "$1" == '--candidate' ]]
shift 2
[[ "$1" == '--output' ]]
output="$2"
printf '{"must_not_survive":true}\n' >"$output"
exit 41
FAIL_AFTER_COMPANION
chmod +x "$temp_dir/capture-child" "$temp_dir/capture-node" \
	"$temp_dir/fail-second-child" "$temp_dir/fail-first-child" \
	"$temp_dir/fail-after-companion"

: >"$temp_dir/calls"
output="$({
  cd -- "${TMPDIR:-/tmp}"
  CAPTURE_LOG="$temp_dir/calls" \
  PHASE=measure \
  RAW_OBSERVATION_SAMPLES=2 \
  RUN_LABEL="$label" \
  RAW_OBSERVATION_RESULT_PATH="$result" \
  BENCHMARK_BULK_WRITE_TOKEN="$token" \
  BULK_WRITE_BENCHMARK_TEST_MODE=1 \
  NODE_OPTIONS=--trace-warnings \
  NODE_PATH="$temp_dir/untrusted-node-modules" \
  WISHLIST_DELETE_RUNNER="$temp_dir/capture-child" \
  NODE_BIN="$temp_dir/capture-node" \
    "$runner"
} 2>&1)"

if [[ -n "$output" || "$output" == *"$token"* ]]; then
  printf 'Wishlist observation runner produced unsafe or unexpected output\n' >&2
  exit 1
fi

expected_calls="$(printf '%s\n' \
  "child samples=1 phase=measure label=$label-sample-001 order=1 path=build/k6/bulk-write/$label-sample-001.json" \
  "child samples=1 phase=measure label=$label-sample-002 order=2 path=build/k6/bulk-write/$label-sample-002.json" \
  "node arg=$repo_root/load-test/k6/bulk-write/aggregate-bulk-write-observations.mjs arg=--candidate arg=WISHLIST_DELETE arg=--output arg=$result arg=--run-label arg=$label arg=build/k6/bulk-write/$label-sample-001.json arg=build/k6/bulk-write/$label-sample-002.json")"
if [[ "$(cat "$temp_dir/calls")" != "$expected_calls" ]]; then
  printf 'unexpected Wishlist observation child/aggregator order\n' >&2
  exit 1
fi
if [[ ! -f "$repo_root/$result" ]]; then
  printf 'Wishlist aggregator did not create the companion artifact\n' >&2
  exit 1
fi

if first_failure_output="$({
  cd -- "${TMPDIR:-/tmp}"
  PHASE=measure \
  RAW_OBSERVATION_SAMPLES=2 \
  RUN_LABEL="$first_failure_label" \
  RAW_OBSERVATION_RESULT_PATH="$first_failure_result" \
  BENCHMARK_BULK_WRITE_TOKEN="$token" \
  BULK_WRITE_BENCHMARK_TEST_MODE=1 \
  WISHLIST_DELETE_RUNNER="$temp_dir/fail-first-child" \
  NODE_BIN="$temp_dir/capture-node" \
    "$runner"
} 2>&1)"; then
  printf 'Wishlist observation runner accepted a failed first child\n' >&2
  exit 1
fi
if [[ "$first_failure_output" == *'unbound variable'* ]]; then
  printf 'Wishlist observation cleanup dereferenced an empty array under nounset\n' >&2
  exit 1
fi
if [[ "$first_failure_output" == *"$token"* \
  || -e "$repo_root/$first_failure_result" \
  || -e "$repo_root/build/k6/bulk-write/$first_failure_label-sample-001.json" ]]; then
  printf 'Wishlist observation first-child failure was not cleaned safely\n' >&2
  exit 1
fi

: >"$temp_dir/failure-calls"
if failure_output="$({
  cd -- "${TMPDIR:-/tmp}"
  CAPTURE_LOG="$temp_dir/failure-calls" \
  PHASE=measure \
  RAW_OBSERVATION_SAMPLES=2 \
  RUN_LABEL="$failure_label" \
  RAW_OBSERVATION_RESULT_PATH="$failure_result" \
  BENCHMARK_BULK_WRITE_TOKEN="$token" \
  BULK_WRITE_BENCHMARK_TEST_MODE=1 \
  WISHLIST_DELETE_RUNNER="$temp_dir/fail-second-child" \
  NODE_BIN="$temp_dir/capture-node" \
    "$runner"
} 2>&1)"; then
  printf 'Wishlist observation runner did not fail with its second child\n' >&2
  exit 1
fi
if [[ "$failure_output" == *"$token"* ]] || grep -q '^node' "$temp_dir/failure-calls"; then
  printf 'Wishlist observation runner leaked data or aggregated a partial run\n' >&2
  exit 1
fi
if [[ -e "$repo_root/$failure_result" ]]; then
  printf 'Wishlist observation runner emitted a partial companion artifact\n' >&2
  exit 1
fi
for failed_child in \
  "$repo_root/build/k6/bulk-write/$failure_label-sample-001.json" \
  "$repo_root/build/k6/bulk-write/$failure_label-sample-002.json"; do
  if [[ -e "$failed_child" || -L "$failed_child" ]]; then
    printf 'Wishlist observation runner retained a child after failure\n' >&2
    exit 1
  fi
done

: >"$temp_dir/aggregator-failure-calls"
if aggregator_failure_output="$({
  cd -- "${TMPDIR:-/tmp}"
  CAPTURE_LOG="$temp_dir/aggregator-failure-calls" \
  PHASE=measure \
  RAW_OBSERVATION_SAMPLES=2 \
  RUN_LABEL="$aggregator_failure_label" \
  RAW_OBSERVATION_RESULT_PATH="$aggregator_failure_result" \
  BENCHMARK_BULK_WRITE_TOKEN="$token" \
  BULK_WRITE_BENCHMARK_TEST_MODE=1 \
  WISHLIST_DELETE_RUNNER="$temp_dir/capture-child" \
  NODE_BIN="$temp_dir/fail-after-companion" \
    "$runner"
} 2>&1)"; then
  printf 'Wishlist observation runner accepted a failed aggregator\n' >&2
  exit 1
fi
if [[ "$aggregator_failure_output" == *"$token"* ]]; then
  printf 'Wishlist observation runner leaked data after aggregator failure\n' >&2
  exit 1
fi
if [[ -e "$repo_root/$aggregator_failure_result" \
  || -L "$repo_root/$aggregator_failure_result" ]]; then
  printf 'Wishlist observation runner retained a companion after aggregator failure\n' >&2
  exit 1
fi
for failed_child in \
  "$repo_root/build/k6/bulk-write/$aggregator_failure_label-sample-001.json" \
  "$repo_root/build/k6/bulk-write/$aggregator_failure_label-sample-002.json"; do
  if [[ -e "$failed_child" || -L "$failed_child" ]]; then
    printf 'Wishlist observation runner retained a child after aggregator failure\n' >&2
    exit 1
  fi
done
