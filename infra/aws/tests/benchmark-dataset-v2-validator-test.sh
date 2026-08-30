#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../../.." && pwd)
fixture="$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v2.json"
legacy_fixture="$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v1.json"
corpus="$repo_root/load-test/k6/test/fixtures/benchmark-dataset-v2-malformed.json"
validator="$repo_root/infra/aws/scripts/validate-benchmark-dataset-v2.jq"
cjs_validator="$repo_root/load-test/k6/coupon/benchmark-dataset-manifest-validator.js"
k6_test="$repo_root/load-test/k6/test/benchmark-dataset-manifest-test.js"

fail() {
  printf 'benchmark dataset v2 validator test failed: %s\n' "$1" >&2
  exit 1
}

command -v jq >/dev/null 2>&1 || fail 'jq is required'
command -v node >/dev/null 2>&1 || fail 'node is required'
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

node - "$fixture" "$legacy_fixture" "$corpus" "$cjs_validator" <<'NODE'
const fs = require('fs');

const [fixturePath, legacyPath, corpusPath, validatorPath] = process.argv.slice(2);
const { parseBenchmarkDatasetManifest } = require(validatorPath);
const canonicalRaw = fs.readFileSync(fixturePath, 'utf8');
const canonical = JSON.parse(canonicalRaw);
const malformedCases = JSON.parse(fs.readFileSync(corpusPath, 'utf8'));

parseBenchmarkDatasetManifest(canonicalRaw);

const normal = JSON.parse(canonicalRaw);
normal.world.provenance.verificationPassed = false;
normal.world.provenance.sourceInventorySha256 = null;
normal.world.provenance.calibrationVersion = null;
normal.world.provenance.calibrationSha256 = null;
normal.world.provenance.assertionSha256 = null;
normal.world.scopedObservedDistributions = {};
normal.world.scopeRanges = {};
normal.world.fingerprints = {};
parseBenchmarkDatasetManifest(JSON.stringify(normal));

let legacyRejected = false;
try {
  parseBenchmarkDatasetManifest(fs.readFileSync(legacyPath, 'utf8'));
} catch (_) {
  legacyRejected = true;
}
if (!legacyRejected) {
  throw new Error('coupon CJS accepted a v1 fixture');
}

for (const malformedCase of malformedCases) {
  const copy = JSON.parse(JSON.stringify(canonical));
  let parent = copy;
  for (let index = 0; index < malformedCase.path.length - 1; index += 1) {
    parent = parent[malformedCase.path[index]];
  }
  const key = malformedCase.path[malformedCase.path.length - 1];
  if (malformedCase.op === 'delete') {
    delete parent[key];
  } else if (malformedCase.op === 'set') {
    parent[key] = malformedCase.value;
  } else {
    throw new Error(`unsupported malformed fixture operation ${malformedCase.op}`);
  }
  let rejected = false;
  try {
    parseBenchmarkDatasetManifest(JSON.stringify(copy));
  } catch (_) {
    rejected = true;
  }
  if (!rejected) {
    throw new Error(`coupon CJS accepted malformed fixture ${malformedCase.id}`);
  }
}
NODE

if command -v k6 >/dev/null 2>&1; then
  k6 run --address '' --quiet "$k6_test" >/dev/null \
    || fail 'k6 ESM validator agreement test failed'
fi

printf 'benchmark dataset v2 validators accepted canonical fixture and rejected %s malformed cases\n' \
  "$case_count"
