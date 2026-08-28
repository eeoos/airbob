#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
release_validator="$repo_root/infra/aws/scripts/verify-dataset-release.sh"
semantic_validator="$repo_root/infra/aws/scripts/validate-benchmark-dataset-v2.jq"
fixture="$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v2.json"

fail() { printf 'dataset release test failed: %s\n' "$1" >&2; exit 1; }
assert_contains() { grep -Fq -- "$2" "$1" || fail "missing contract: $2"; }

[[ -x "$release_validator" && -f "$semantic_validator" && -f "$fixture" ]] || fail 'v2 validators or fixture are missing'
bash -n "$release_validator"
jq -e -f "$semantic_validator" "$fixture" >/dev/null || fail 'canonical v2 fixture was rejected'

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
