#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$test_dir/../../.." && pwd -P)"
runner="$repo_root/load-test/k6/bulk-write/run-reservation-history-insert-observations.sh"
temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/reservation-observation-runner-test.XXXXXX")"
label="observation-contract-$$"
failure_label="observation-failure-$$"
result="build/k6/bulk-write/$label-observations.json"
failure_result="build/k6/bulk-write/$failure_label-observations.json"
token='token-sentinel-0123456789abcdef0123456789'
trap 'rm -rf -- "$temp_dir" "$repo_root/build/k6/bulk-write/$label"* "$repo_root/build/k6/bulk-write/$failure_label"*' EXIT

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

assert_rejected 'a warmup phase' env \
  PHASE=warmup RAW_OBSERVATION_SAMPLES=3 RUN_LABEL="$label" \
  RAW_OBSERVATION_RESULT_PATH="$result" BENCHMARK_BULK_WRITE_TOKEN="$token" \
  "$runner"
assert_rejected 'a zero sample count' env \
  PHASE=measure RAW_OBSERVATION_SAMPLES=0 RUN_LABEL="$label" \
  RAW_OBSERVATION_RESULT_PATH="$result" BENCHMARK_BULK_WRITE_TOKEN="$token" \
  "$runner"
assert_rejected 'a missing parent run label' env \
  PHASE=measure RAW_OBSERVATION_SAMPLES=3 \
  RAW_OBSERVATION_RESULT_PATH="$result" BENCHMARK_BULK_WRITE_TOKEN="$token" \
  "$runner"
assert_rejected 'a missing companion result path' env \
  PHASE=measure RAW_OBSERVATION_SAMPLES=3 RUN_LABEL="$label" \
  BENCHMARK_BULK_WRITE_TOKEN="$token" \
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

printf 'node' >>"$CAPTURE_LOG"
for argument in "$@"; do
  printf ' arg=%s' "$argument" >>"$CAPTURE_LOG"
done
printf '\n' >>"$CAPTURE_LOG"

shift
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
if [[ "$RUN_LABEL" == *'-sample-002' ]]; then
  exit 23
fi
mkdir -p -- "$(dirname -- "$K6_RESULT_PATH")"
printf '{}\n' >"$K6_RESULT_PATH"
FAIL_CHILD
chmod +x "$temp_dir/capture-child" "$temp_dir/capture-node" "$temp_dir/fail-second-child"

: >"$temp_dir/calls"
output="$({
  cd -- "${TMPDIR:-/tmp}"
  CAPTURE_LOG="$temp_dir/calls" \
  PHASE=measure \
  RAW_OBSERVATION_SAMPLES=3 \
  RUN_LABEL="$label" \
  RAW_OBSERVATION_RESULT_PATH="$result" \
  BENCHMARK_BULK_WRITE_TOKEN="$token" \
  RESERVATION_HISTORY_RUNNER="$temp_dir/capture-child" \
  NODE_BIN="$temp_dir/capture-node" \
    "$runner"
} 2>&1)"

if [[ -n "$output" ]]; then
  printf 'observation runner produced unexpected output: %s\n' "$output" >&2
  exit 1
fi
if [[ "$output" == *"$token"* ]]; then
  printf 'observation runner disclosed the token\n' >&2
  exit 1
fi

expected_calls="$(printf '%s\n' \
  "child samples=1 phase=measure label=$label-sample-001 order=1 path=build/k6/bulk-write/$label-sample-001.json" \
  "child samples=1 phase=measure label=$label-sample-002 order=2 path=build/k6/bulk-write/$label-sample-002.json" \
  "child samples=1 phase=measure label=$label-sample-003 order=3 path=build/k6/bulk-write/$label-sample-003.json" \
  "node arg=$repo_root/load-test/k6/bulk-write/aggregate-reservation-history-observations.mjs arg=--output arg=$result arg=--run-label arg=$label arg=build/k6/bulk-write/$label-sample-001.json arg=build/k6/bulk-write/$label-sample-002.json arg=build/k6/bulk-write/$label-sample-003.json")"
actual_calls="$(cat "$temp_dir/calls")"
if [[ "$actual_calls" != "$expected_calls" ]]; then
  printf 'unexpected observation child/aggregator order\nexpected:\n%s\nactual:\n%s\n' \
    "$expected_calls" "$actual_calls" >&2
  exit 1
fi
if [[ ! -f "$repo_root/$result" ]]; then
  printf 'aggregator did not create the companion artifact\n' >&2
  exit 1
fi

: >"$temp_dir/failure-calls"
if failure_output="$({
  cd -- "${TMPDIR:-/tmp}"
  CAPTURE_LOG="$temp_dir/failure-calls" \
  PHASE=measure \
  RAW_OBSERVATION_SAMPLES=3 \
  RUN_LABEL="$failure_label" \
  RAW_OBSERVATION_RESULT_PATH="$failure_result" \
  BENCHMARK_BULK_WRITE_TOKEN="$token" \
  RESERVATION_HISTORY_RUNNER="$temp_dir/fail-second-child" \
  NODE_BIN="$temp_dir/capture-node" \
    "$runner"
} 2>&1)"; then
  printf 'observation runner did not fail when the second child failed\n' >&2
  exit 1
fi
if [[ "$failure_output" == *"$token"* ]]; then
  printf 'observation runner disclosed the token on child failure\n' >&2
  exit 1
fi
if [[ "$(wc -l <"$temp_dir/failure-calls" | tr -d ' ')" != '2' ]]; then
  printf 'observation runner did not stop immediately after the second child failure\n' >&2
  exit 1
fi
if grep -q '^node' "$temp_dir/failure-calls"; then
  printf 'observation runner called the aggregator after a child failure\n' >&2
  exit 1
fi
if [[ -e "$repo_root/$failure_result" ]]; then
  printf 'observation runner emitted a partial companion artifact\n' >&2
  exit 1
fi
