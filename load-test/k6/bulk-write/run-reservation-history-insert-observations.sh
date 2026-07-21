#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  printf 'run-reservation-history-insert-observations.sh does not accept arguments\n' >&2
  exit 2
fi
if [[ "${PHASE:-}" != 'measure' ]]; then
  printf 'PHASE must be measure\n' >&2
  exit 2
fi
if [[ ! "${RAW_OBSERVATION_SAMPLES:-}" =~ ^[1-9][0-9]*$ ]] \
  || (( RAW_OBSERVATION_SAMPLES > 100 )); then
  printf 'RAW_OBSERVATION_SAMPLES must be an integer between 1 and 100\n' >&2
  exit 2
fi
if [[ ! "${RUN_LABEL:-}" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]*$ ]] \
  || (( ${#RUN_LABEL} > 128 )); then
  printf 'RUN_LABEL must contain only public filename-safe characters\n' >&2
  exit 2
fi
if [[ ! "${RAW_OBSERVATION_RESULT_PATH:-}" =~ ^build/k6/bulk-write/[a-zA-Z0-9][a-zA-Z0-9._-]*\.json$ ]] \
  || (( ${#RAW_OBSERVATION_RESULT_PATH} > 255 )); then
  printf 'RAW_OBSERVATION_RESULT_PATH must be an explicit JSON file under build/k6/bulk-write\n' >&2
  exit 2
fi

: "${BENCHMARK_BULK_WRITE_TOKEN:?BENCHMARK_BULK_WRITE_TOKEN is required}"

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/../../.." && pwd -P)"
child_runner="${RESERVATION_HISTORY_RUNNER:-$script_dir/run-reservation-history-insert.sh}"
aggregator="$script_dir/aggregate-reservation-history-observations.mjs"
node_bin="${NODE_BIN:-node}"

cd -- "$repo_root"
mkdir -p -- build/k6/bulk-write
if [[ -e "$RAW_OBSERVATION_RESULT_PATH" ]]; then
  printf 'RAW_OBSERVATION_RESULT_PATH already exists\n' >&2
  exit 2
fi

source_paths=()
for (( sample_index = 1; sample_index <= RAW_OBSERVATION_SAMPLES; sample_index++ )); do
  printf -v sample_suffix '%03d' "$sample_index"
  child_label="$RUN_LABEL-sample-$sample_suffix"
  child_path="build/k6/bulk-write/$child_label.json"
  if [[ "$child_path" == "$RAW_OBSERVATION_RESULT_PATH" || -e "$child_path" ]]; then
    printf 'child observation artifact path is not fresh\n' >&2
    exit 2
  fi

  PHASE=measure \
  SAMPLES=1 \
  RUN_LABEL="$child_label" \
  RUN_ORDER="$sample_index" \
  K6_RESULT_PATH="$child_path" \
    "$child_runner"

  if [[ ! -f "$child_path" ]]; then
    printf 'successful child run did not emit its artifact\n' >&2
    exit 1
  fi
  source_paths+=("$child_path")
done

"$node_bin" "$aggregator" \
  --output "$RAW_OBSERVATION_RESULT_PATH" \
  --run-label "$RUN_LABEL" \
  "${source_paths[@]}"
