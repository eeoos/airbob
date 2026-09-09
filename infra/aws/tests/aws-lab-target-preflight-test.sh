#!/usr/bin/env bash
set -euo pipefail
tests=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
script_dir="$tests/../scripts"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-target-preflight-test.XXXXXX")
trap 'rm -rf "$temp_dir"' EXIT
fail() { printf '%s\n' "$1" >&2; exit 1; }
sha256_file() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | awk '{print $1}'; }
eval "$(awk '/^load_release_smoke_inputs\(\)/{p=1} p{print} p && /^}/{exit}' "$script_dir/aws-lab.sh")"
dataset_bucket=fixture-bucket
dataset_release=fixture-release
AWS_REGION=ap-northeast-2
printf '%s\n' '{"datasetVersion":"nplus1-v1","hostAccommodations":{"detailAccommodationId":200001}}' > "$temp_dir/legacy.json"
jq '.schemaVersion=2 | .datasetVersion="benchmark-dataset-v2" | .world.version="world-v2" | (.capsules[].mutability)="READ_ONLY"' \
  "$tests/fixtures/production-r3-targets.json" > "$temp_dir/composite.json"
# Only the external S3 reads are replaced; production parsing, byte hashing,
# target calculation and comparisons execute unchanged.
aws() {
  [[ "$1 $2" == 's3api get-object' ]] || fail 'preflight attempted an unexpected AWS operation'
  local key='' destination=''
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --key) key=$2; destination=$3; break ;;
    esac
    shift
  done
  case "$key" in
    datasets/fixture-release/benchmark/manifest.json) cp "$temp_dir/legacy.json" "$destination" ;;
    datasets/fixture-release/benchmark/dataset-manifest.json) cp "$temp_dir/composite.json" "$destination" ;;
    *) fail 'preflight requested an unexpected object' ;;
  esac
}
write_wrapper() {
  jq -n --arg legacy "$(sha256_file "$temp_dir/legacy.json")" \
    --arg composite "$(sha256_file "$temp_dir/composite.json")" --argjson enabled "$1" \
    '{source:{legacyBenchmarkManifestSha256:$legacy,benchmarkDatasetManifestSha256:$composite},
      releaseTuple:{targetFingerprintSha256:"01b3c82a23eda0270bc083f874c23f378067d4114f1f93cd790e584b8ec9637f"},
      search:{enabled:$enabled}}' > "$temp_dir/wrapper.json"
}
for enabled in false true; do
  write_wrapper "$enabled"
  load_release_smoke_inputs "$temp_dir/wrapper.json"
  jq '.releaseTuple.targetFingerprintSha256="0000000000000000000000000000000000000000000000000000000000000000"' \
    "$temp_dir/wrapper.json" > "$temp_dir/wrong-target.json"
  if (load_release_smoke_inputs "$temp_dir/wrong-target.json") > /dev/null 2>&1; then
    fail "preflight accepted a wrong target fingerprint (search=$enabled)"
  fi
done
jq '.targetFingerprint="0000000000000000000000000000000000000000000000000000000000000000"' \
  "$temp_dir/composite.json" > "$temp_dir/changed.json"
mv "$temp_dir/changed.json" "$temp_dir/composite.json"
write_wrapper true
if (load_release_smoke_inputs "$temp_dir/wrapper.json") > /dev/null 2>&1; then
  fail 'preflight accepted disagreement between release and composite fingerprint'
fi
printf '%s\n' 'target preflight tests passed (actual r3 calculation, both search modes, mismatched receipts)'
