#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

repo_root=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
assembler="$repo_root/infra/aws/scripts/assemble-dataset-release.sh"
release_validator="$repo_root/infra/aws/scripts/verify-dataset-release.sh"
semantic_validator="$repo_root/infra/aws/scripts/validate-benchmark-dataset-v2.jq"
dataset_fixture="$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v2.json"
legacy_fixture="$repo_root/load-test/k6/test/fixtures/nplus1-v1.json"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-v2-assembler-test.XXXXXX")

cleanup() {
  local status=$?
  trap - EXIT
  if [[ "${AIRBOB_KEEP_TEST_TMP:-false}" == true ]]; then
    printf 'kept test workspace: %s\n' "$temp_dir" >&2
  else
    rm -rf "$temp_dir"
  fi
  exit "$status"
}
trap cleanup EXIT
fail() { printf 'dataset release assembler test failed: %s\n' "$1" >&2; exit 1; }
sha256_file() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }

stat_functions="$temp_dir/stat-functions.sh"
awk '
  /^stat_(uid|mode)\(\) \{/ { capture = 1 }
  capture { print }
  capture && /^}/ { capture = 0 }
' "$assembler" > "$stat_functions"
[[ "$(grep -Ec '^stat_(uid|mode)\(\)' "$stat_functions")" == 2 ]] \
  || fail 'assembler stat helpers are missing'
# shellcheck source=/dev/null
source "$stat_functions"
stat_probe="$temp_dir/stat-probe"
fake_bin="$temp_dir/fake-bin"
mkdir -m 700 "$stat_probe" "$fake_bin"
cat > "$fake_bin/stat" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  -c)
    case "$2" in
      %u) id -u ;;
      %a) printf '700\n' ;;
      *) exit 2 ;;
    esac
    ;;
  -f)
    printf 'GNU filesystem report emitted before an operand error\n'
    exit 1
    ;;
  *) exit 2 ;;
esac
EOF
chmod 700 "$fake_bin/stat"
stat_uid_value=$(PATH="$fake_bin:$PATH"; hash -r; stat_uid "$stat_probe")
stat_mode_value=$(PATH="$fake_bin:$PATH"; hash -r; stat_mode "$stat_probe")
[[ "$stat_uid_value" == "$(id -u)" && "$stat_mode_value" == 700 ]] \
  || fail 'assembler stat fallback accepted partial output from a failed dialect'

write_checksums() {
  local root=$1 spec_name=$2 file
  : > "$root/SHA256SUMS"
  for file in PROVENANCE.txt airbob-production-seed.sql.gz backend-migrations.sha256 \
    benchmark-dataset-v2.json benchmark-fixture.json database-fingerprint.tsv etl-code.sha256 \
    generation-qualification-v1.json "$spec_name" release-metadata.txt \
    source-calibration-v1.json source.sha256 traffic-v1.json; do
    printf '%s  %s\n' "$(sha256_file "$root/$file")" "$file" >> "$root/SHA256SUMS"
  done
}

write_metadata() {
  local root=$1 spec_name=$2
  local manifest_sha calibration_sha spec_sha qualification_sha fingerprint_sha traffic_sha
  manifest_sha=$(sha256_file "$root/benchmark-dataset-v2.json")
  calibration_sha=$(sha256_file "$root/source-calibration-v1.json")
  spec_sha=$(sha256_file "$root/$spec_name")
  qualification_sha=$(sha256_file "$root/generation-qualification-v1.json")
  fingerprint_sha=$(sha256_file "$root/database-fingerprint.tsv")
  traffic_sha=$(sha256_file "$root/traffic-v1.json")
  cat > "$root/release-metadata.txt" <<EOF
format=airbob-production-seed-release-v2
release_id=production-seed-20260817t000000z
dump=airbob-production-seed.sql.gz
dump_sha256=$(sha256_file "$root/airbob-production-seed.sql.gz")
manifest=benchmark-fixture.json
manifest_sha256=$(sha256_file "$root/benchmark-fixture.json")
benchmark_dataset_manifest=benchmark-dataset-v2.json
benchmark_dataset_manifest_sha256=$manifest_sha
benchmark_dataset_version=benchmark-dataset-v2
world_version=world-v2
production_spec=$spec_name
production_spec_sha256=$spec_sha
source_calibration=source-calibration-v1.json
source_calibration_sha256=$calibration_sha
source_catalog_inventory_fingerprint=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
generation_qualification=generation-qualification-v1.json
generation_qualification_sha256=$qualification_sha
canonical_scale=true
configured_batch_size=1000
jvm_max_heap_bytes=12884901888
traffic_manifest=traffic-v1.json
traffic_manifest_sha256=$traffic_sha
traffic_dataset_version=traffic-v1
traffic_dataset_run_id=20260817T001530Z-12345678
traffic_flyway_version=27
traffic_migration_digest=sha256:6666666666666666666666666666666666666666666666666666666666666666
fingerprint=database-fingerprint.tsv
fingerprint_sha256=$fingerprint_sha
final_world_fingerprint=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
base_world_fingerprint=0000000000000000000000000000000000000000000000000000000000000000
distribution_fingerprint=dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
target_fingerprint=ace11b7713606a877a12bed71a7c52aebca77851a169c6e25176c137fb77d9ac
inventory_fingerprint=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
etl_code_inventory=etl-code.sha256
etl_code_inventory_sha256=$(sha256_file "$root/etl-code.sha256")
source_inventory=source.sha256
source_inventory_sha256=$(sha256_file "$root/source.sha256")
backend_migration_inventory=backend-migrations.sha256
backend_migration_inventory_sha256=$(sha256_file "$root/backend-migrations.sha256")
provenance=PROVENANCE.txt
provenance_sha256=$(sha256_file "$root/PROVENANCE.txt")
required_rows=201
recovery=reset-flyway-v1-v27-etl-reseed-before-traffic
EOF
}

write_attestation() {
  local root=$1 output=$2 spec_name=$3
  local expected_rows
  expected_rows=$(jq -cS '.world.tableRows + {flyway_schema_history:27,outbox:0}' "$root/benchmark-dataset-v2.json")
  jq -nS \
    --arg payload "$(sha256_file "$root/SHA256SUMS")" \
    --arg dump "$(sha256_file "$root/airbob-production-seed.sql.gz")" \
    --arg fingerprint "$(sha256_file "$root/database-fingerprint.tsv")" \
    --arg assertion "$(jq -r '.world.provenance.assertionSha256' "$root/benchmark-dataset-v2.json")" \
    --arg spec "$(sha256_file "$root/$spec_name")" \
    --argjson expectedRows "$expected_rows" '
    {
      schemaVersion:4,sourceReleasePayloadSha256:$payload,sourceDumpSha256:$dump,
      restoredDumpSha256:$dump,databaseRestoreMethod:"gzip-to-empty-airbobdb-v2",
      sourceDatabaseFingerprintSha256:$fingerprint,
      sourceEtlCommit:"0123456789abcdef0123456789abcdef01234567",
      databaseServerUuid:"00112233-4455-6677-8899-aabbccddeeff",
      verifierContractInventorySha256:("7"*64),databaseFingerprintSha256:$fingerprint,
      verificationOutputSha256:("8"*64),finalWorldFingerprintSha256:("e"*64),
      baseWorldFingerprintSha256:("0"*64),distributionEvidenceSha256:("d"*64),
      distributionAssertionSha256:$assertion,distributionSpecSha256:$spec,
      targetFingerprintSha256:"ace11b7713606a877a12bed71a7c52aebca77851a169c6e25176c137fb77d9ac",
      inventoryFingerprintSha256:("f"*64),flywayVersion:"27",flywayHistoryRows:27,
      migrationChecksumSha256:("4"*64),schemaFingerprintSha256:("5"*64),
      outboxState:"empty",expectedTableRows:$expectedRows,capturedAt:"2026-08-17T00:00:00Z"
    }
  ' > "$output"
}

write_source_release() {
  local root=$1 profile=${2:-production-skew-v1} path name calibration_sha spec_sha spec_name budgets unique_listings
  case "$profile" in
    production-skew-v1)
      spec_name=production-skew-v1.json
      budgets='{"accommodations":50000,"activeWishlists":400000,"members":200000,"reservations":2500000,"reviews":1000000,"wishlistLinks":1500000}'
      unique_listings=50000
      ;;
    production-skew-large-v1)
      spec_name=production-skew-large-v1.json
      budgets='{"accommodations":200000,"activeWishlists":1600000,"members":800000,"reservations":10000000,"reviews":4000000,"wishlistLinks":6000000}'
      unique_listings=200000
      ;;
    *) fail "unsupported fixture profile: $profile" ;;
  esac
  mkdir -m 700 -p "$root"
  printf '%s\n' 'CREATE DATABASE IF NOT EXISTS airbobdb;' 'USE airbobdb;' 'SELECT 1;' | gzip -n > "$root/airbob-production-seed.sql.gz"
  cp "$legacy_fixture" "$root/benchmark-fixture.json"
  jq -nS --argjson uniqueListings "$unique_listings" '{calibrationVersion:"source-calibration-v1",catalogVersion:"source-catalog-v1",inventorySha256:("a"*64),sourceInventory:[{canonicalPath:"inside-airbnb/listings.csv",byteSize:1,sha256:("9"*64),role:"LISTINGS"}],cohorts:[{id:"seoul"}],aggregate:{counts:{uniqueListings:$uniqueListings}},syntheticReviewTemplatePolicy:{reviewerIdentityPolicy:"EXCLUDED",reviewProsePolicy:"EXCLUDED",templatePolicy:"VERSIONED_COHORT_TEMPLATE"}}' > "$root/source-calibration-v1.json"
  jq -nS --arg profile "$profile" --argjson budgets "$budgets" '{profileVersion:$profile,provenance:{generatorVersion:"production-skew-generator-v1",prngAlgorithm:"sha256-splitmix64-counter-v1",seedDerivation:"length-prefixed(profile-version, global-seed, relation-domain, stable-external-key, counter)",globalSeed:20260826,anchor:"2026-07-31T15:00:00Z",timezone:"Asia/Seoul"},targets:{accommodations:{rowBudget:$budgets.accommodations,tolerance:{absoluteRows:0,relativePercent:0}},members:{rowBudget:$budgets.members,tolerance:{absoluteRows:0,relativePercent:0}},reservations:{rowBudget:$budgets.reservations,tolerance:{absoluteRows:0,relativePercent:0}},reviews:{rowBudget:$budgets.reviews,tolerance:{absoluteRows:0,relativePercent:0}},activeWishlists:{rowBudget:$budgets.activeWishlists,tolerance:{absoluteRows:0,relativePercent:0}},wishlistLinks:{rowBudget:$budgets.wishlistLinks,tolerance:{absoluteRows:0,relativePercent:0}}}}' > "$root/$spec_name"
  jq -nS --argjson budgets "$budgets" '{version:"generation-qualification-v1",canonicalScale:true,configuredBatchSize:1000,jvmMaxHeapBytes:12884901888,generatedBudgets:$budgets,configuredLimits:{completedStayCandidates:30000,completedStays:1000,paymentTransactions:1000,payments:1000,reservations:1000,reviews:30000,wishlistLinks:100000,wishlists:1000},retainedMaxima:{completedStayCandidates:30000,completedStays:1000,paymentTransactions:1000,payments:1000,reservations:1000,reviews:30000,wishlistLinks:100000,wishlists:1000}}' > "$root/generation-qualification-v1.json"
  calibration_sha=$(sha256_file "$root/source-calibration-v1.json")
  spec_sha=$(sha256_file "$root/$spec_name")
  jq --arg profile "$profile" --arg calibration "$calibration_sha" --arg spec "$spec_sha" --argjson budgets "$budgets" '
    .world.provenance.profileVersion=$profile |
    .world.provenance.calibrationSha256=$calibration |
    .world.provenance.specSha256=$spec |
    (if $profile=="production-skew-large-v1" then
      .world.tableRows.accommodation=$budgets.accommodations |
      .world.tableRows.member=$budgets.members |
      .world.tableRows.reservation=$budgets.reservations |
      .world.tableRows.review=$budgets.reviews |
      .world.tableRows.wishlist=$budgets.activeWishlists |
      .world.tableRows.wishlist_accommodation=$budgets.wishlistLinks |
      .world.tableRows.payment=9000000 |
      .world.tableRows.payment_transaction=11000000
    else . end) |
    .world.scopeRanges={
      accommodation:{id:"accommodation",minimumId:1,maximumId:.world.tableRows.accommodation,rowCount:.world.tableRows.accommodation},
      member:{id:"member",minimumId:1,maximumId:.world.tableRows.member,rowCount:.world.tableRows.member},
      reservation:{id:"reservation",minimumId:1,maximumId:.world.tableRows.reservation,rowCount:.world.tableRows.reservation},
      review:{id:"review",minimumId:1,maximumId:.world.tableRows.review,rowCount:.world.tableRows.review},
      wishlist:{id:"wishlist",minimumId:1,maximumId:.world.tableRows.wishlist,rowCount:.world.tableRows.wishlist},
      "wishlist-accommodation":{id:"wishlist-accommodation",minimumId:1,maximumId:.world.tableRows.wishlist_accommodation,rowCount:.world.tableRows.wishlist_accommodation},
      payment:{id:"payment",minimumId:1,maximumId:.world.tableRows.payment,rowCount:.world.tableRows.payment},
      "payment-transaction":{id:"payment-transaction",minimumId:1,maximumId:.world.tableRows.payment_transaction,rowCount:.world.tableRows.payment_transaction}
    }
  ' "$dataset_fixture" > "$root/benchmark-dataset-v2.json"
  jq -nS '{datasetVersion:"traffic-v1",datasetRunId:"20260817T001530Z-12345678",seed:20260826,anchorTime:"2026-08-01T00:00:00",validUntil:"2027-08-01T00:00:00",timezone:"Asia/Seoul",schema:{flywayVersion:"27",migrationDigest:("sha256:"+("6"*64))}}' > "$root/traffic-v1.json"
  cat > "$root/PROVENANCE.txt" <<EOF
format=airbob-production-seed-provenance-v2
etl_head=0123456789abcdef0123456789abcdef01234567
backend_head=89abcdef0123456789abcdef0123456789abcdef
release_profile=$profile
distribution_profile=$profile
production_spec=$spec_name
budget_accommodations=$(jq -r '.accommodations' <<<"$budgets")
budget_members=$(jq -r '.members' <<<"$budgets")
budget_reservations=$(jq -r '.reservations' <<<"$budgets")
budget_reviews=$(jq -r '.reviews' <<<"$budgets")
budget_active_wishlists=$(jq -r '.activeWishlists' <<<"$budgets")
budget_wishlist_links=$(jq -r '.wishlistLinks' <<<"$budgets")
options_begin
profile=large
world_version=world-v2
options_end
EOF
  : > "$root/backend-migrations.sha256"
  while IFS= read -r path; do name=${path##*/}; printf '%s  ./%s\n' "$(sha256_file "$path")" "$name" >> "$root/backend-migrations.sha256"; done < <(find "$repo_root/src/main/resources/db/migration" -type f -name 'V*.sql' | sort)
  printf '%s  %s\n' "$(sha256_file "$semantic_validator")" 'infra/aws/scripts/validate-benchmark-dataset-v2.jq' > "$root/etl-code.sha256"
  printf '%s  %s\n' "$(printf source | { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; } | awk '{print $1}')" source.csv > "$root/source.sha256"
  cat > "$root/database-fingerprint.tsv" <<'EOF'
dataset_final_world_fingerprint	eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
dataset_base_world_fingerprint	0000000000000000000000000000000000000000000000000000000000000000
dataset_distribution_fingerprint	dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
dataset_target_fingerprint	ace11b7713606a877a12bed71a7c52aebca77851a169c6e25176c137fb77d9ac
dataset_inventory_fingerprint	ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
world_accommodation_inventory_day_row_count	0
review_summary_missing_count	0
review_summary_stale_count	0
review_summary_extra_count	0
review_summary_symmetric_mismatch_count	0
wishlist_accommodation_count_mismatch_count	0
wishlist_representative_mismatch_count	0
wishlist_denormalized_symmetric_mismatch_count	0
daily_revenue_stats_missing_count	0
daily_revenue_stats_stale_count	0
daily_revenue_stats_extra_count	0
daily_revenue_stats_symmetric_mismatch_count	0
accommodation_inventory_day_row_count	0
orphan_total	0
EOF
  write_metadata "$root" "$spec_name"
  write_checksums "$root" "$spec_name"
  chmod 600 "$root"/*
}

expect_failure() {
  local label=$1 output_root=$2
  shift 2
  if "$@" > "$temp_dir/$label.out" 2>&1; then fail "expected rejection: $label"; fi
  [[ ! -e "$output_root/rehearsal-v20" ]] || fail "failed assembly published: $label"
}

[[ -x "$assembler" && -x "$release_validator" && -f "$semantic_validator" ]] || fail 'release scripts are unavailable'
bash -n "$assembler"
source_release="$temp_dir/source"
attestation="$temp_dir/attestation.json"
output_root="$temp_dir/output"
mkdir -m 700 "$output_root"
write_source_release "$source_release"
write_attestation "$source_release" "$attestation" production-skew-v1.json
"$assembler" "$source_release" "$attestation" "$output_root" rehearsal-v20 \
  2026-08-18T00:00:00Z 2027-07-31T15:00:00Z >/dev/null
release="$output_root/rehearsal-v20"
"$release_validator" "$release" rehearsal-v20 pipeline-rehearsal >/dev/null
expected_inventory=$(printf '%s\n' \
  './attestation' './attestation/restore.json' './benchmark' './benchmark/dataset-manifest.json' \
  './benchmark/generation-qualification-v1.json' './benchmark/manifest.json' \
  './benchmark/production-skew-v1.json' './benchmark/source-calibration-v1.json' \
  './benchmark/validate-benchmark-dataset-v2.jq' './manifest.json' './mysql' \
  './mysql/airbob.sql.zst' './mysql/database-fingerprint.tsv' './mysql/sha256.txt' | sort)
[[ "$(cd "$release" && find . -mindepth 1 -print | sort)" == "$expected_inventory" ]] || fail 'assembled v2 inventory is not exact'
jq -e '.schemaVersion==2 and .releaseTuple.datasetVersion=="benchmark-dataset-v2" and .source.validatorKey=="benchmark/validate-benchmark-dataset-v2.jq"' "$release/manifest.json" >/dev/null || fail 'wrapper v2 tuple is missing'

# Search-enabled assembly accepts only the producer's closed snapshot-reference
# schema, and the standalone verifier binds every repeated component back to
# the trusted wrapper rather than accepting an internally consistent swap.
snapshot_reference="$temp_dir/snapshot-reference.json"
jq -nS '
  {
    schemaVersion:2,repository:"airbob-dataset-readonly",
    bucket:"airbob-performance-lab-dataset-942632789808",
    basePath:"elasticsearch/releases/rehearsal-search-v20",
    snapshot:"airbob-rehearsal-search-v20",logicalAlias:"accommodations",
    snapshotIndex:"accommodations-vfixture",elasticsearchVersion:"8.18.8",
    imageDigest:("sha256:" + ("5" * 64)),documentCount:200201,
    mappingSha256:("1" * 64),dbIdsSha256:("2" * 64),esIdsSha256:("2" * 64),
    dbDocumentIdentityPairsSha256:("3" * 64),
    esDocumentIdentityPairsSha256:("3" * 64),contentFingerprintSha256:("4" * 64)
  }
' > "$snapshot_reference"
search_output="$temp_dir/search-output"
mkdir -m 700 "$search_output"
"$assembler" "$source_release" "$attestation" "$search_output" rehearsal-search-v20 \
  2026-08-18T00:00:00Z 2027-07-31T15:00:00Z "$snapshot_reference" >/dev/null
search_release="$search_output/rehearsal-search-v20"
"$release_validator" "$search_release" rehearsal-search-v20 pipeline-rehearsal >/dev/null
jq -e '
  .search.enabled == true and
  .search.databaseAccommodationIdsSha256 == ("2" * 64) and
  .search.databaseDocumentIdentityPairsSha256 == ("3" * 64)
' "$search_release/manifest.json" >/dev/null || fail 'search wrapper did not bind distinct snapshot components'
swapped_search_release="$temp_dir/swapped-search-release"
cp -R "$search_release" "$swapped_search_release"
jq '
  .dbIdsSha256=.dbDocumentIdentityPairsSha256 |
  .esIdsSha256=.esDocumentIdentityPairsSha256
' "$swapped_search_release/elasticsearch/snapshot-reference.json" \
  > "$swapped_search_release/elasticsearch/snapshot-reference.next"
mv "$swapped_search_release/elasticsearch/snapshot-reference.next" \
  "$swapped_search_release/elasticsearch/snapshot-reference.json"
if "$release_validator" "$swapped_search_release" rehearsal-search-v20 pipeline-rehearsal \
  >/dev/null 2>&1; then
  fail 'standalone verifier accepted swapped snapshot reference components'
fi

# The standalone verifier must reject an otherwise checksum-rebound wrapper
# whose contiguous base scope no longer equals the selected profile budget.
rebound_underfilled_release="$temp_dir/rebound-underfilled-release"
cp -R "$release" "$rebound_underfilled_release"
jq '.world.scopeRanges.review.maximumId=999999 | .world.scopeRanges.review.rowCount=999999' \
  "$rebound_underfilled_release/benchmark/dataset-manifest.json" > "$rebound_underfilled_release/next"
mv "$rebound_underfilled_release/next" "$rebound_underfilled_release/benchmark/dataset-manifest.json"
rebound_manifest_sha=$(sha256_file "$rebound_underfilled_release/benchmark/dataset-manifest.json")
jq --arg sha "$rebound_manifest_sha" \
  '.releaseTuple.manifestSha256=$sha | .source.benchmarkDatasetManifestSha256=$sha' \
  "$rebound_underfilled_release/manifest.json" > "$rebound_underfilled_release/next"
mv "$rebound_underfilled_release/next" "$rebound_underfilled_release/manifest.json"
if "$release_validator" "$rebound_underfilled_release" rehearsal-v20 pipeline-rehearsal >/dev/null 2>&1; then
  fail 'checksum-rebound underfilled base scope passed standalone release verification'
fi

# The companion large profile keeps the same 14-file/13-checksum shape while
# selecting its own closed spec filename and exact 4x budgets.
large_source="$temp_dir/source-large"
large_attestation="$temp_dir/attestation-large.json"
large_output="$temp_dir/output-large"
mkdir -m 700 "$large_output"
write_source_release "$large_source" production-skew-large-v1
write_attestation "$large_source" "$large_attestation" production-skew-large-v1.json
[[ "$(find "$large_source" -mindepth 1 -maxdepth 1 -type f | wc -l | tr -d '[:space:]')" == 14 ]] \
  || fail 'large source inventory is not fourteen files'
[[ "$(wc -l < "$large_source/SHA256SUMS" | tr -d '[:space:]')" == 13 ]] \
  || fail 'large source checksum inventory is not thirteen entries'
"$assembler" "$large_source" "$large_attestation" "$large_output" rehearsal-large-v20 \
  2026-08-18T00:00:00Z 2027-07-31T15:00:00Z >/dev/null
large_release="$large_output/rehearsal-large-v20"
"$release_validator" "$large_release" rehearsal-large-v20 pipeline-rehearsal >/dev/null
jq -e '
  .releaseTuple.profileVersion=="production-skew-large-v1" and
  .source.productionSpecKey=="benchmark/production-skew-large-v1.json"
' "$large_release/manifest.json" >/dev/null || fail 'large wrapper profile/spec binding is invalid'
[[ -f "$large_release/benchmark/production-skew-large-v1.json" \
  && ! -e "$large_release/benchmark/production-skew-v1.json" ]] \
  || fail 'large assembled inventory selected the wrong spec filename'

# A fully checksum-rebound large release still needs enough distinct source listings
# to satisfy the large accommodation budget.
large_source_underflow="$temp_dir/large-source-underflow"
cp -R "$large_source" "$large_source_underflow"
jq '.aggregate.counts.uniqueListings=199999' \
  "$large_source_underflow/source-calibration-v1.json" > "$large_source_underflow/next"
mv "$large_source_underflow/next" "$large_source_underflow/source-calibration-v1.json"
large_underflow_calibration_sha=$(sha256_file "$large_source_underflow/source-calibration-v1.json")
jq --arg sha "$large_underflow_calibration_sha" '.world.provenance.calibrationSha256=$sha' \
  "$large_source_underflow/benchmark-dataset-v2.json" > "$large_source_underflow/next"
mv "$large_source_underflow/next" "$large_source_underflow/benchmark-dataset-v2.json"
write_metadata "$large_source_underflow" production-skew-large-v1.json
write_checksums "$large_source_underflow" production-skew-large-v1.json
write_attestation "$large_source_underflow" "$temp_dir/large-source-underflow-attestation.json" \
  production-skew-large-v1.json
mkdir -m 700 "$temp_dir/large-source-underflow-output"
expect_failure large-source-underflow "$temp_dir/large-source-underflow-output" "$assembler" \
  "$large_source_underflow" "$temp_dir/large-source-underflow-attestation.json" \
  "$temp_dir/large-source-underflow-output" rehearsal-v20 \
  2026-08-18T00:00:00Z 2027-07-31T15:00:00Z

# A renamed spec, a checksum-rebound budget, and a third checksum-rebound
# profile must all fail even when every mutable digest is recomputed.
mixed_filename="$temp_dir/mixed-large-filename"
cp -R "$large_source" "$mixed_filename"
mv "$mixed_filename/production-skew-large-v1.json" "$mixed_filename/production-skew-v1.json"
write_metadata "$mixed_filename" production-skew-v1.json
write_checksums "$mixed_filename" production-skew-v1.json
write_attestation "$mixed_filename" "$temp_dir/mixed-large-filename-attestation.json" production-skew-v1.json
mkdir -m 700 "$temp_dir/mixed-large-filename-output"
expect_failure mixed-large-filename "$temp_dir/mixed-large-filename-output" "$assembler" "$mixed_filename" \
  "$temp_dir/mixed-large-filename-attestation.json" "$temp_dir/mixed-large-filename-output" rehearsal-v20 \
  2026-08-18T00:00:00Z 2027-07-31T15:00:00Z

large_budget_rebound="$temp_dir/large-budget-rebound"
cp -R "$large_source" "$large_budget_rebound"
jq '.targets.reviews.rowBudget=3999999' "$large_budget_rebound/production-skew-large-v1.json" > "$large_budget_rebound/next"
mv "$large_budget_rebound/next" "$large_budget_rebound/production-skew-large-v1.json"
large_budget_spec_sha=$(sha256_file "$large_budget_rebound/production-skew-large-v1.json")
jq --arg sha "$large_budget_spec_sha" '.world.provenance.specSha256=$sha' \
  "$large_budget_rebound/benchmark-dataset-v2.json" > "$large_budget_rebound/next"
mv "$large_budget_rebound/next" "$large_budget_rebound/benchmark-dataset-v2.json"
write_metadata "$large_budget_rebound" production-skew-large-v1.json
write_checksums "$large_budget_rebound" production-skew-large-v1.json
write_attestation "$large_budget_rebound" "$temp_dir/large-budget-rebound-attestation.json" production-skew-large-v1.json
mkdir -m 700 "$temp_dir/large-budget-rebound-output"
expect_failure large-budget-rebound "$temp_dir/large-budget-rebound-output" "$assembler" "$large_budget_rebound" \
  "$temp_dir/large-budget-rebound-attestation.json" "$temp_dir/large-budget-rebound-output" rehearsal-v20 \
  2026-08-18T00:00:00Z 2027-07-31T15:00:00Z

third_profile_rebound="$temp_dir/third-profile-rebound"
cp -R "$large_source" "$third_profile_rebound"
jq '.profileVersion="production-skew-huge-v1"' "$third_profile_rebound/production-skew-large-v1.json" > "$third_profile_rebound/next"
mv "$third_profile_rebound/next" "$third_profile_rebound/production-skew-large-v1.json"
third_spec_sha=$(sha256_file "$third_profile_rebound/production-skew-large-v1.json")
jq --arg sha "$third_spec_sha" '.world.provenance.profileVersion="production-skew-huge-v1"|.world.provenance.specSha256=$sha' \
  "$third_profile_rebound/benchmark-dataset-v2.json" > "$third_profile_rebound/next"
mv "$third_profile_rebound/next" "$third_profile_rebound/benchmark-dataset-v2.json"
write_metadata "$third_profile_rebound" production-skew-large-v1.json
write_checksums "$third_profile_rebound" production-skew-large-v1.json
write_attestation "$third_profile_rebound" "$temp_dir/third-profile-rebound-attestation.json" production-skew-large-v1.json
mkdir -m 700 "$temp_dir/third-profile-rebound-output"
expect_failure third-profile-rebound "$temp_dir/third-profile-rebound-output" "$assembler" "$third_profile_rebound" \
  "$temp_dir/third-profile-rebound-attestation.json" "$temp_dir/third-profile-rebound-output" rehearsal-v20 \
  2026-08-18T00:00:00Z 2027-07-31T15:00:00Z

# PROVENANCE is a semantic receipt, not an opaque checksummed note. Rebinding
# every mutable checksum after changing one selected-profile budget must fail.
provenance_duplicate="$temp_dir/provenance-duplicate"
cp -R "$large_source" "$provenance_duplicate"
printf '%s\n' 'release_profile=production-skew-large-v1' >> "$provenance_duplicate/PROVENANCE.txt"
write_metadata "$provenance_duplicate" production-skew-large-v1.json
write_checksums "$provenance_duplicate" production-skew-large-v1.json
write_attestation "$provenance_duplicate" "$temp_dir/provenance-duplicate-attestation.json" \
  production-skew-large-v1.json
mkdir -m 700 "$temp_dir/provenance-duplicate-output"
expect_failure provenance-duplicate "$temp_dir/provenance-duplicate-output" "$assembler" \
  "$provenance_duplicate" "$temp_dir/provenance-duplicate-attestation.json" \
  "$temp_dir/provenance-duplicate-output" rehearsal-v20 \
  2026-08-18T00:00:00Z 2027-07-31T15:00:00Z

provenance_budget_rebound="$temp_dir/provenance-budget-rebound"
cp -R "$large_source" "$provenance_budget_rebound"
sed 's/^budget_reviews=4000000$/budget_reviews=3999999/' \
  "$provenance_budget_rebound/PROVENANCE.txt" > "$provenance_budget_rebound/next"
mv "$provenance_budget_rebound/next" "$provenance_budget_rebound/PROVENANCE.txt"
write_metadata "$provenance_budget_rebound" production-skew-large-v1.json
write_checksums "$provenance_budget_rebound" production-skew-large-v1.json
write_attestation "$provenance_budget_rebound" "$temp_dir/provenance-budget-rebound-attestation.json" \
  production-skew-large-v1.json
mkdir -m 700 "$temp_dir/provenance-budget-rebound-output"
expect_failure provenance-budget-rebound "$temp_dir/provenance-budget-rebound-output" "$assembler" \
  "$provenance_budget_rebound" "$temp_dir/provenance-budget-rebound-attestation.json" \
  "$temp_dir/provenance-budget-rebound-output" rehearsal-v20 \
  2026-08-18T00:00:00Z 2027-07-31T15:00:00Z

# Schema-4 attestation must carry both distribution proof seals, without omission or rebinding.
for proof_case in missing-assertion missing-spec drift-assertion drift-spec; do
  proof_attestation="$temp_dir/$proof_case-attestation.json"
  case "$proof_case" in
    missing-assertion) jq 'del(.distributionAssertionSha256)' "$attestation" > "$proof_attestation" ;;
    missing-spec) jq 'del(.distributionSpecSha256)' "$attestation" > "$proof_attestation" ;;
    drift-assertion) jq '.distributionAssertionSha256=("f"*64)' "$attestation" > "$proof_attestation" ;;
    drift-spec) jq '.distributionSpecSha256=("f"*64)' "$attestation" > "$proof_attestation" ;;
  esac
  proof_output="$temp_dir/$proof_case-output"
  mkdir -m 700 "$proof_output"
  expect_failure "$proof_case" "$proof_output" "$assembler" "$source_release" \
    "$proof_attestation" "$proof_output" rehearsal-v20 \
    2026-08-18T00:00:00Z 2027-07-31T15:00:00Z
done

# Evidence publication stays closed until a dedicated producer emits its exact causal contract.
if "$release_validator" "$release" rehearsal-v20 evidence > "$temp_dir/evidence-kind.out" 2>&1; then
  fail 'pipeline rehearsal was accepted as evidence'
fi
grep -Fq 'evidence release kind has no trusted producer' "$temp_dir/evidence-kind.out" \
  || fail 'evidence kind did not fail at the producer gate'

# The trusted wrapper is exact and recursively secret-free before any nested value is consumed.
for wrapper_case in search-extra nested-secret; do
  mutated="$temp_dir/wrapper-$wrapper_case"
  cp -R "$release" "$mutated"
  case "$wrapper_case" in
    search-extra)
      jq '.search.unboundField=true' "$mutated/manifest.json" > "$mutated/manifest.next"
      ;;
    nested-secret)
      jq '.couponPreparation=[{metadata:{apiCredential:"must-not-publish"}}]' \
        "$mutated/manifest.json" > "$mutated/manifest.next"
      ;;
  esac
  mv "$mutated/manifest.next" "$mutated/manifest.json"
  if "$release_validator" "$mutated" rehearsal-v20 pipeline-rehearsal >/dev/null 2>&1; then
    fail "unsafe wrapper passed: $wrapper_case"
  fi
done

# Rebinding only the copied attestation and its wrapper file digest cannot break source payload lineage.
rebound_attestation_payload="$temp_dir/rebound-attestation-payload"
cp -R "$release" "$rebound_attestation_payload"
jq '.sourceReleasePayloadSha256=("a"*64)' \
  "$rebound_attestation_payload/attestation/restore.json" > "$rebound_attestation_payload/attestation/next"
mv "$rebound_attestation_payload/attestation/next" \
  "$rebound_attestation_payload/attestation/restore.json"
rebound_attestation_sha=$(sha256_file "$rebound_attestation_payload/attestation/restore.json")
jq --arg sha "$rebound_attestation_sha" \
  '.source.attestationSha256=$sha|.releaseTuple.attestationSha256=$sha' \
  "$rebound_attestation_payload/manifest.json" > "$rebound_attestation_payload/manifest.next"
mv "$rebound_attestation_payload/manifest.next" "$rebound_attestation_payload/manifest.json"
if "$release_validator" "$rebound_attestation_payload" rehearsal-v20 pipeline-rehearsal \
  >/dev/null 2>&1; then
  fail 'checksum-rebound attestation source payload drift passed'
fi

# Byte tamper is rejected even before semantic validation.
for relative in benchmark/dataset-manifest.json benchmark/source-calibration-v1.json \
  benchmark/production-skew-v1.json benchmark/validate-benchmark-dataset-v2.jq mysql/airbob.sql.zst; do
  mutated="$temp_dir/tamper-${relative//\//-}"
  cp -R "$release" "$mutated"
  printf x >> "$mutated/$relative"
  if "$release_validator" "$mutated" rehearsal-v20 pipeline-rehearsal >/dev/null 2>&1; then
    fail "byte tamper passed: $relative"
  fi
done

# Rebinding a validator to a permissive program still fails against the trusted code contract.
rebound_validator="$temp_dir/rebound-validator"
cp -R "$release" "$rebound_validator"
printf '%s\n' '.' > "$rebound_validator/benchmark/validate-benchmark-dataset-v2.jq"
rebound_sha=$(sha256_file "$rebound_validator/benchmark/validate-benchmark-dataset-v2.jq")
jq --arg sha "$rebound_sha" '.source.validatorSha256=$sha|.releaseTuple.validatorSha256=$sha' \
  "$rebound_validator/manifest.json" > "$rebound_validator/manifest.next"
mv "$rebound_validator/manifest.next" "$rebound_validator/manifest.json"
if "$release_validator" "$rebound_validator" rehearsal-v20 pipeline-rehearsal >/dev/null 2>&1; then
  fail 'checksum-rebound permissive validator passed'
fi

# Rebinding plausible-looking semantic artifacts cannot weaken tracked U6 contracts.
for semantic_case in calibration spec qualification; do
  rebound="$temp_dir/rebound-$semantic_case"
  cp -R "$release" "$rebound"
  case "$semantic_case" in
    calibration)
      file=benchmark/source-calibration-v1.json
      jq '.syntheticReviewTemplatePolicy.reviewerIdentityPolicy="INCLUDED"|.reviewer_name="source-person"' "$rebound/$file" > "$rebound/next"
      wrapper_update='.source.calibrationSha256=$sha|.releaseTuple.calibrationSha256=$sha'
      ;;
    spec)
      file=benchmark/production-skew-v1.json
      jq '.provenance.globalSeed=20260827' "$rebound/$file" > "$rebound/next"
      wrapper_update='.source.productionSpecSha256=$sha|.releaseTuple.specSha256=$sha'
      ;;
    qualification)
      file=benchmark/generation-qualification-v1.json
      jq '.retainedMaxima.reservations=1001' "$rebound/$file" > "$rebound/next"
      wrapper_update='.source.generationQualificationSha256=$sha|.releaseTuple.qualificationSha256=$sha'
      ;;
  esac
  mv "$rebound/next" "$rebound/$file"
  rebound_sha=$(sha256_file "$rebound/$file")
  jq --arg sha "$rebound_sha" "$wrapper_update" "$rebound/manifest.json" > "$rebound/next"
  mv "$rebound/next" "$rebound/manifest.json"
  if "$release_validator" "$rebound" rehearsal-v20 pipeline-rehearsal >/dev/null 2>&1; then
    fail "checksum-rebound $semantic_case drift passed"
  fi
done

bad_qualification="$temp_dir/bad-qualification"
cp -R "$source_release" "$bad_qualification"
jq '.retainedMaxima.reservations=1001' "$bad_qualification/generation-qualification-v1.json" > "$bad_qualification/next"
mv "$bad_qualification/next" "$bad_qualification/generation-qualification-v1.json"
write_metadata "$bad_qualification" production-skew-v1.json
write_checksums "$bad_qualification" production-skew-v1.json
write_attestation "$bad_qualification" "$temp_dir/bad-qualification-attestation.json" production-skew-v1.json
mkdir -m 700 "$temp_dir/bad-qualification-output"
expect_failure qualification-rebind "$temp_dir/bad-qualification-output" "$assembler" "$bad_qualification" \
  "$temp_dir/bad-qualification-attestation.json" "$temp_dir/bad-qualification-output" rehearsal-v20 \
  2026-08-18T00:00:00Z 2027-07-31T15:00:00Z

# v1-name/v2-payload and a non-contiguous base scope fail before publication.
bad_name="$temp_dir/bad-name"
cp -R "$source_release" "$bad_name"
mv "$bad_name/benchmark-dataset-v2.json" "$bad_name/benchmark-dataset-v1.json"
mkdir -m 700 "$temp_dir/bad-name-output"
expect_failure v1-name "$temp_dir/bad-name-output" "$assembler" "$bad_name" "$attestation" \
  "$temp_dir/bad-name-output" rehearsal-v20 2026-08-18T00:00:00Z 2027-07-31T15:00:00Z

noncontiguous="$temp_dir/noncontiguous"
cp -R "$source_release" "$noncontiguous"
jq '.world.scopeRanges.accommodation.rowCount -= 1' "$noncontiguous/benchmark-dataset-v2.json" > "$noncontiguous/next"
mv "$noncontiguous/next" "$noncontiguous/benchmark-dataset-v2.json"
write_metadata "$noncontiguous" production-skew-v1.json
write_checksums "$noncontiguous" production-skew-v1.json
write_attestation "$noncontiguous" "$temp_dir/noncontiguous-attestation.json" production-skew-v1.json
mkdir -m 700 "$temp_dir/noncontiguous-output"
expect_failure noncontiguous "$temp_dir/noncontiguous-output" "$assembler" "$noncontiguous" \
  "$temp_dir/noncontiguous-attestation.json" "$temp_dir/noncontiguous-output" rehearsal-v20 \
  2026-08-18T00:00:00Z 2027-07-31T15:00:00Z

# A contiguous, checksum-rebound base scope is still invalid when it falls
# below the exact selected-profile budget.
underfilled_scope="$temp_dir/underfilled-scope"
cp -R "$source_release" "$underfilled_scope"
jq '.world.scopeRanges.review.maximumId=999999 | .world.scopeRanges.review.rowCount=999999' \
  "$underfilled_scope/benchmark-dataset-v2.json" > "$underfilled_scope/next"
mv "$underfilled_scope/next" "$underfilled_scope/benchmark-dataset-v2.json"
write_metadata "$underfilled_scope" production-skew-v1.json
write_checksums "$underfilled_scope" production-skew-v1.json
write_attestation "$underfilled_scope" "$temp_dir/underfilled-scope-attestation.json" production-skew-v1.json
mkdir -m 700 "$temp_dir/underfilled-scope-output"
expect_failure underfilled-scope "$temp_dir/underfilled-scope-output" "$assembler" "$underfilled_scope" \
  "$temp_dir/underfilled-scope-attestation.json" "$temp_dir/underfilled-scope-output" rehearsal-v20 \
  2026-08-18T00:00:00Z 2027-07-31T15:00:00Z

# Final table totals are independently fenced because read-only benchmark
# overlays may add rows, but can never make a canonical base budget disappear.
underfilled_final="$temp_dir/underfilled-final"
cp -R "$source_release" "$underfilled_final"
jq '.world.tableRows.review=999999' \
  "$underfilled_final/benchmark-dataset-v2.json" > "$underfilled_final/next"
mv "$underfilled_final/next" "$underfilled_final/benchmark-dataset-v2.json"
write_metadata "$underfilled_final" production-skew-v1.json
write_checksums "$underfilled_final" production-skew-v1.json
write_attestation "$underfilled_final" "$temp_dir/underfilled-final-attestation.json" production-skew-v1.json
mkdir -m 700 "$temp_dir/underfilled-final-output"
expect_failure underfilled-final "$temp_dir/underfilled-final-output" "$assembler" "$underfilled_final" \
  "$temp_dir/underfilled-final-attestation.json" "$temp_dir/underfilled-final-output" rehearsal-v20 \
  2026-08-18T00:00:00Z 2027-07-31T15:00:00Z

printf '%s\n' 'dataset release assembler tests passed'
