#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../../.." && pwd)
fixture="$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v2.json"
legacy_fixture="$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v1.json"
corpus="$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v2-malformed.json"
validator="$repo_root/infra/aws/scripts/validate-benchmark-dataset-v2.jq"

fail() {
  printf 'benchmark dataset v2 validator test failed: %s\n' "$1" >&2
  exit 1
}

command -v jq >/dev/null 2>&1 || fail 'jq is required'
[[ -f "$validator" ]] || fail 'jq validator is missing'
[[ -f "$fixture" ]] || fail 'canonical v2 fixture is missing'
[[ -f "$corpus" ]] || fail 'shared malformed corpus is missing'

jq -e -f "$validator" "$fixture" >/dev/null \
  || fail 'jq rejected the canonical v2 fixture'

jq '
  .world.provenance.verificationPassed=false |
  .world.provenance.sourceInventorySha256=null |
  .world.provenance.calibrationVersion=null |
  .world.provenance.calibrationSha256=null |
  .world.provenance.assertionSha256=null |
  .world.scopedObservedDistributions={} |
  .world.scopeRanges={} |
  .world.fingerprints={}
' "$fixture" | jq -e -f "$validator" >/dev/null \
  || fail 'jq rejected a closed unverified normal world'

if jq -e -f "$validator" "$legacy_fixture" >/dev/null 2>&1; then
  fail 'jq accepted a v1 fixture'
fi

case_count=$(jq 'length' "$corpus")
for ((index = 0; index < case_count; index += 1)); do
  case_id=$(jq -r ".[$index].id" "$corpus")
  if jq --argjson index "$index" --slurpfile cases "$corpus" '
      $cases[0][$index] as $case
      | if $case.op == "delete" then delpaths([$case.path])
        elif $case.op == "set" then setpath($case.path; $case.value)
        else error("unsupported malformed fixture operation")
        end
    ' "$fixture" | jq -e -f "$validator" >/dev/null 2>&1; then
    fail "jq accepted malformed fixture $case_id"
  fi
done

printf 'benchmark dataset v2 validator accepted canonical fixture and rejected %s malformed cases\n' \
  "$case_count"
