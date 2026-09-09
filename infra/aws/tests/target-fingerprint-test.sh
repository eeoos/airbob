#!/usr/bin/env bash
set -euo pipefail
script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
calculator="$script_dir/../scripts/compute-target-fingerprint.sh"
fixture="$script_dir/fixtures/production-r3-targets.json"
fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ -x "$calculator" ]] || fail 'shared target fingerprint calculator is missing'
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-target-fingerprint-test.XXXXXX")
trap 'rm -rf "$temp_dir"' EXIT
expected=01b3c82a23eda0270bc083f874c23f378067d4114f1f93cd790e584b8ec9637f
[[ "$("$calculator" "$fixture")" == "$expected" ]] \
  || fail 'actual r3 target fingerprint differs from the immutable release'
# Exercise the actual SSM bootstrap caller with the calculator adjacent to it,
# exactly as Terraform installs the checksum-verified helper on the host.
awk '/^recompute_target_fingerprint\(\)/{p=1} p{print} p && /^}/{exit}' \
  "$script_dir/../scripts/bootstrap-data.sh" > "$temp_dir/bootstrap-data.sh"
[[ -s "$temp_dir/bootstrap-data.sh" ]] || fail 'bootstrap fingerprint caller is missing'
cp "$calculator" "$temp_dir/compute-target-fingerprint.sh"
# shellcheck source=/dev/null
source "$temp_dir/bootstrap-data.sh"
benchmark_dataset_manifest=$fixture
[[ "$(recompute_target_fingerprint)" == "$expected" ]] \
  || fail 'AWS bootstrap does not use the canonical release calculator'
mv "$temp_dir/compute-target-fingerprint.sh" "$temp_dir/calculator.saved"
if recompute_target_fingerprint > /dev/null 2>&1; then
  fail 'bootstrap accepted a missing shared calculator'
fi
jq '.capsules |= (reverse | map(.targets |= reverse))' "$fixture" > "$temp_dir/reordered.json"
[[ "$("$calculator" "$temp_dir/reordered.json")" == "$expected" ]] \
  || fail 'capsule and target ordering changed the canonical fingerprint'
jq '(.capsules[].targets[] | select(.id=="search-broad") | .query.topLeftLat) = 60' \
  "$fixture" > "$temp_dir/drift.json"
[[ "$("$calculator" "$temp_dir/drift.json")" != "$expected" ]] \
  || fail 'changed coordinates were not bound to the target fingerprint'
for mutation in \
  '.capsules = []' \
  '(.capsules[].targets[] | select(.id=="search-broad") | .query.kind) = "UNKNOWN"' \
  '(.capsules[].targets[] | select(.id=="search-broad") | .query.topLeftLat) = "invalid"'
do
  jq "$mutation" "$fixture" > "$temp_dir/invalid.json"
  if "$calculator" "$temp_dir/invalid.json" > "$temp_dir/invalid.out" 2>/dev/null; then
    fail "invalid target input accepted: $mutation"
  fi
  [[ ! -s "$temp_dir/invalid.out" ]] || fail 'invalid input emitted an apparently usable hash'
done
printf '%s\n' 'target fingerprint tests passed (immutable r3, ordering, drift, invalid inputs)'
