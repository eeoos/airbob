#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$test_dir/../../.." && pwd -P)"
runner="$repo_root/load-test/k6/bulk-write/run-accommodation-amenity-delete-observations.sh"
temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/amenity-observation-runner-test.XXXXXX")"
label="amenity-observation-contract-$$"
result="build/k6/bulk-write/$label-observations.json"
token='amenity-token-sentinel-0123456789abcdef'
trap 'rm -rf -- "$temp_dir" "$repo_root/build/k6/bulk-write/$label"*' EXIT

if [[ ! -x "$runner" ]]; then
  printf 'AccommodationAmenity observation runner is missing or not executable\n' >&2
  exit 1
fi

cat >"$temp_dir/capture-child" <<'CHILD'
#!/usr/bin/env bash
set -euo pipefail

[[ "$MEASUREMENT" == 'FULL_REPLACEMENT' ]]
printf 'child samples=%s phase=%s measurement=%s label=%s order=%s path=%s\n' \
  "$SAMPLES" "$PHASE" "$MEASUREMENT" "$RUN_LABEL" "$RUN_ORDER" "$K6_RESULT_PATH" \
  >>"$CAPTURE_LOG"
mkdir -p -- "$(dirname -- "$K6_RESULT_PATH")"
printf '{}\n' >"$K6_RESULT_PATH"
CHILD

cat >"$temp_dir/capture-node" <<'NODE'
#!/usr/bin/env bash
set -euo pipefail

shift
[[ "$1" == '--candidate' ]]
[[ "$2" == 'ACCOMMODATION_AMENITY_DELETE' ]]
shift 2
[[ "$1" == '--output' ]]
output="$2"
shift 2
[[ "$1" == '--run-label' ]]
shift 2
for source in "$@"; do
  [[ -f "$source" ]]
done
printf 'node candidate=ACCOMMODATION_AMENITY_DELETE\n' >>"$CAPTURE_LOG"
printf '{"complete":true}\n' >"$output"
NODE

chmod +x "$temp_dir/capture-child" "$temp_dir/capture-node"
: >"$temp_dir/calls"
output="$({
  cd -- "${TMPDIR:-/tmp}"
  CAPTURE_LOG="$temp_dir/calls" \
  PHASE=measure \
  MEASUREMENT=FULL_REPLACEMENT \
  RAW_OBSERVATION_SAMPLES=2 \
  RUN_LABEL="$label" \
  RAW_OBSERVATION_RESULT_PATH="$result" \
  BENCHMARK_BULK_WRITE_TOKEN="$token" \
  BULK_WRITE_BENCHMARK_TEST_MODE=1 \
  ACCOMMODATION_AMENITY_DELETE_RUNNER="$temp_dir/capture-child" \
  NODE_BIN="$temp_dir/capture-node" \
    "$runner"
} 2>&1)"

if [[ -n "$output" || "$output" == *"$token"* ]]; then
  printf 'AccommodationAmenity observation runner produced unsafe output\n' >&2
  exit 1
fi
if [[ "$(grep -c '^child ' "$temp_dir/calls")" != '2' ]] \
  || ! grep -Fxq 'node candidate=ACCOMMODATION_AMENITY_DELETE' "$temp_dir/calls"; then
  printf 'AccommodationAmenity observation runner did not preserve child/aggregator contract\n' >&2
  exit 1
fi
if [[ ! -f "$repo_root/$result" ]]; then
  printf 'AccommodationAmenity observation runner did not create a companion\n' >&2
  exit 1
fi
