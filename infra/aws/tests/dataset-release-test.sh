#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
release_validator="$repo_root/infra/aws/scripts/verify-dataset-release.sh"
semantic_validator="$repo_root/infra/aws/scripts/validate-benchmark-dataset-v2.jq"
fixture="$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v2.json"
validator_agreement_test="$repo_root/infra/aws/tests/benchmark-dataset-v2-validator-test.sh"
ci_workflow="$repo_root/.github/workflows/ci.yml"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-dataset-release-test.XXXXXX")
trap 'rm -rf "$temp_dir"' EXIT HUP INT TERM

fail() { printf 'dataset release test failed: %s\n' "$1" >&2; exit 1; }
assert_contains() { grep -Fq -- "$2" "$1" || fail "missing contract: $2"; }

[[ -x "$release_validator" && -x "$validator_agreement_test" \
  && -f "$semantic_validator" && -f "$fixture" && -f "$ci_workflow" ]] \
  || fail 'v2 validators, fixture, or CI workflow are missing'
bash -n "$release_validator"
bash -n "$validator_agreement_test"
jq -e -f "$semantic_validator" "$fixture" >/dev/null || fail 'canonical v2 fixture was rejected'

missing_k6_log="$temp_dir/missing-k6.log"
if K6_BIN=airbob-intentionally-missing-k6 "$validator_agreement_test" \
  >"$missing_k6_log" 2>&1; then
  fail 'validator agreement wrapper silently skipped a missing k6 executable'
fi
grep -Fq 'k6 is required: airbob-intentionally-missing-k6' "$missing_k6_log" \
  || fail 'validator agreement wrapper did not attribute the missing k6 failure'

k6_install_line=$(grep -n 'name: Install pinned k6 for traffic contracts' "$ci_workflow" | cut -d: -f1)
validator_agreement_line=$(grep -n 'bash infra/aws/tests/benchmark-dataset-v2-validator-test.sh' "$ci_workflow" | cut -d: -f1)
[[ "$k6_install_line" =~ ^[0-9]+$ && "$validator_agreement_line" =~ ^[0-9]+$ \
  && "$k6_install_line" -lt "$validator_agreement_line" ]] \
  || fail 'CI must execute the validator agreement wrapper after pinned k6 installation'
assert_contains "$ci_workflow" 'source infra/aws/toolchain.env'
assert_contains "$ci_workflow" 'AIRBOB_K6_LINUX_AMD64_SHA256'

assert_contains "$release_validator" 'wrapper does not satisfy the fixed v2 envelope'
assert_contains "$release_validator" 'validator payload does not match the trusted release-builder contract'
assert_contains "$release_validator" 'jq -e -f "$validator" "$dataset_manifest"'
assert_contains "$release_validator" 'benchmark/validate-benchmark-dataset-v2.jq'
assert_contains "$release_validator" 'benchmark/source-calibration-v1.json'
assert_contains "$release_validator" 'production-skew-v1.json'
assert_contains "$release_validator" 'production-skew-large-v1.json'
assert_contains "$release_validator" 'production_spec_key="benchmark/$production_spec_name"'
assert_contains "$release_validator" '.aggregate.counts.uniqueListings >= $budgets.accommodations'
assert_contains "$release_validator" 'activeWishlists: .wishlist.rowCount'
assert_contains "$release_validator" 'wishlistLinks: .["wishlist-accommodation"].rowCount'
assert_contains "$release_validator" '.value >= $budgets[.key]'
assert_contains "$release_validator" 'benchmark/generation-qualification-v1.json'
assert_contains "$release_validator" 'mysql/database-fingerprint.tsv'
assert_contains "$release_validator" 'attestation/restore.json'
assert_contains "$release_validator" '.schemaVersion == 4'
assert_contains "$release_validator" '.distributionAssertionSha256'
assert_contains "$release_validator" '.distributionSpecSha256'
assert_contains "$release_validator" 'dataset_target_fingerprint'
assert_contains "$release_validator" 'review_summary_(missing|stale|extra|symmetric_mismatch)'
assert_contains "$release_validator" 'daily_revenue_stats_(missing|stale|extra|symmetric_mismatch)'
assert_contains "$release_validator" 'accommodation_inventory_day_row_count'
assert_contains "$release_validator" 'dataset release inventory is not exact'

# A v1 payload and a structurally plausible v2 target drift both fail the standalone schema.
if jq '.datasetVersion="benchmark-dataset-v1"' "$fixture" | jq -e -f "$semantic_validator" >/dev/null 2>&1; then
  fail 'v1 payload passed the v2 validator'
fi
if jq '(.capsules[]|select(.capsuleId=="read-model-v2").targets[]|select(.id=="wishlist-hot").account.status)="BLOCKED"' "$fixture" \
  | jq -e -f "$semantic_validator" >/dev/null 2>&1; then
  fail 'target-account semantic drift passed the v2 validator'
fi

printf '%s\n' 'dataset release tests passed'
