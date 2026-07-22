#!/usr/bin/env bash
set -euo pipefail

if (( $# != 1 )); then
  printf 'bulk-write observation runner requires one internal candidate argument\n' >&2
  exit 2
fi

candidate="$1"
if [[ "$candidate" != 'RESERVATION_HISTORY_INSERT' \
  && "$candidate" != 'WISHLIST_DELETE' \
  && "$candidate" != 'ACCOMMODATION_AMENITY_DELETE' ]]; then
  printf 'bulk-write observation candidate is not allowlisted\n' >&2
  exit 2
fi
if [[ "$candidate" == 'ACCOMMODATION_AMENITY_DELETE' \
  && "${MEASUREMENT:-}" != 'FULL_REPLACEMENT' \
  && "${MEASUREMENT:-}" != 'DELETE_ONLY' ]]; then
  printf 'MEASUREMENT must be FULL_REPLACEMENT or DELETE_ONLY\n' >&2
  exit 2
fi
if [[ "${PHASE:-}" != 'measure' ]]; then
  printf 'PHASE must be measure\n' >&2
  exit 2
fi
if [[ ! "${RAW_OBSERVATION_SAMPLES:-}" =~ ^([1-9]|[1-9][0-9]|100)$ ]]; then
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
aggregator="$script_dir/aggregate-bulk-write-observations.mjs"
test_mode="${BULK_WRITE_BENCHMARK_TEST_MODE:-0}"
if [[ "$test_mode" != '0' && "$test_mode" != '1' ]]; then
  printf 'BULK_WRITE_BENCHMARK_TEST_MODE must be 0 or 1\n' >&2
  exit 2
fi

case "$candidate" in
  RESERVATION_HISTORY_INSERT)
    trusted_child_runner="$script_dir/run-reservation-history-insert.sh"
    if [[ "$test_mode" == '1' ]]; then
      child_runner="${RESERVATION_HISTORY_RUNNER:-$trusted_child_runner}"
    fi
    ;;
  WISHLIST_DELETE)
    trusted_child_runner="$script_dir/run-wishlist-delete.sh"
    if [[ "$test_mode" == '1' ]]; then
      child_runner="${WISHLIST_DELETE_RUNNER:-$trusted_child_runner}"
    fi
    ;;
  ACCOMMODATION_AMENITY_DELETE)
    trusted_child_runner="$script_dir/run-accommodation-amenity-delete.sh"
    if [[ "$test_mode" == '1' ]]; then
      child_runner="${ACCOMMODATION_AMENITY_DELETE_RUNNER:-$trusted_child_runner}"
    fi
    ;;
esac

if [[ "$test_mode" == '1' ]]; then
  node_bin="${NODE_BIN:-node}"
else
  if [[ -n "${RESERVATION_HISTORY_RUNNER+x}" \
    || -n "${WISHLIST_DELETE_RUNNER+x}" \
    || -n "${ACCOMMODATION_AMENITY_DELETE_RUNNER+x}" \
    || -n "${NODE_BIN+x}" ]]; then
    printf 'executable overrides require explicit test mode\n' >&2
    exit 2
  fi
  child_runner="$trusted_child_runner"
  node_bin='node'
fi
if [[ ! -f "$aggregator" || -L "$aggregator" ]]; then
  printf 'trusted observation aggregator is unavailable\n' >&2
  exit 2
fi
if [[ "$test_mode" == '0' \
  && ( ! -f "$child_runner" || -L "$child_runner" || ! -x "$child_runner" ) ]]; then
  printf 'trusted benchmark child runner is unavailable\n' >&2
  exit 2
fi

cd -- "$repo_root"
for artifact_directory in build build/k6 build/k6/bulk-write; do
  if [[ -L "$artifact_directory" \
    || ( -e "$artifact_directory" && ! -d "$artifact_directory" ) ]]; then
    printf 'artifact directory contains an unsafe path component\n' >&2
    exit 2
  fi
  if [[ ! -d "$artifact_directory" ]]; then
    mkdir -- "$artifact_directory"
  fi
done
if [[ "$(cd -- build/k6/bulk-write && pwd -P)" != "$repo_root/build/k6/bulk-write" ]]; then
  printf 'artifact directory is outside the repository boundary\n' >&2
  exit 2
fi
if [[ -e "$RAW_OBSERVATION_RESULT_PATH" || -L "$RAW_OBSERVATION_RESULT_PATH" ]]; then
  printf 'RAW_OBSERVATION_RESULT_PATH already exists\n' >&2
  exit 2
fi

created_paths=()
current_child_path=''
cleanup_created_artifacts_on_failure() {
  local status=$?
  if (( status != 0 )); then
    local path
    if [[ -e "$RAW_OBSERVATION_RESULT_PATH" \
      || -L "$RAW_OBSERVATION_RESULT_PATH" ]]; then
      rm -f -- "$RAW_OBSERVATION_RESULT_PATH" || true
    fi
    if [[ -n "$current_child_path" \
      && ( -e "$current_child_path" || -L "$current_child_path" ) ]]; then
      rm -f -- "$current_child_path" || true
    fi
    if (( ${#created_paths[@]} > 0 )); then
      for path in "${created_paths[@]}"; do
        if [[ -e "$path" || -L "$path" ]]; then
          rm -f -- "$path" || true
        fi
      done
    fi
  fi
}
trap cleanup_created_artifacts_on_failure EXIT

source_paths=()
for (( sample_index = 1; sample_index <= RAW_OBSERVATION_SAMPLES; sample_index++ )); do
  printf -v sample_suffix '%03d' "$sample_index"
  child_label="$RUN_LABEL-sample-$sample_suffix"
  child_path="build/k6/bulk-write/$child_label.json"
  if [[ "$child_path" == "$RAW_OBSERVATION_RESULT_PATH" \
    || -e "$child_path" || -L "$child_path" ]]; then
    printf 'child observation artifact path is not fresh\n' >&2
    exit 2
  fi

  current_child_path="$child_path"
  PHASE=measure \
  SAMPLES=1 \
  RUN_LABEL="$child_label" \
  RUN_ORDER="$sample_index" \
  K6_RESULT_PATH="$child_path" \
    "$child_runner"

  if [[ ! -f "$child_path" || -L "$child_path" ]]; then
    printf 'successful child run did not emit its artifact\n' >&2
    exit 1
  fi
  created_paths+=("$child_path")
  current_child_path=''
  source_paths+=("$child_path")
done

env -u NODE_OPTIONS -u NODE_PATH "$node_bin" "$aggregator" \
  --candidate "$candidate" \
  --output "$RAW_OBSERVATION_RESULT_PATH" \
  --run-label "$RUN_LABEL" \
  "${source_paths[@]}"
