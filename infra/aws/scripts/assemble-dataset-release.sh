#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

fail() { printf 'dataset release assembly failed: %s\n' "$1" >&2; exit 1; }
usage() {
  printf 'usage: %s ETL_RELEASE_DIR ATTESTATION_JSON OUTPUT_ROOT DATASET_RELEASE EVALUATION_TIME VALID_UNTIL [SNAPSHOT_REFERENCE_JSON]\n' "${0##*/}" >&2
  exit 64
}
sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else fail 'a SHA-256 implementation is required'; fi
}
stat_uid() {
  local value
  if value=$(stat -f '%u' "$1" 2>/dev/null); then printf '%s' "$value"; else stat -c '%u' "$1"; fi
}
stat_mode() {
  local value
  if value=$(stat -f '%Lp' "$1" 2>/dev/null); then printf '%s' "$value"; else stat -c '%a' "$1"; fi
}
require_file() { [[ -f "$1" && ! -L "$1" ]] || fail "artifact is missing or unsafe: ${1##*/}"; }
contains_secret_marker() {
  printf '%s\n' "$1" | grep -Eqi 'password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account'
}
scan_sensitive_file() {
  local file=$1 email emails status
  if grep -Eiq -- '-----BEGIN .*PRIVATE KEY-----|(AKIA|ASIA)[0-9A-Z]{16}|(password|secret|credential|authorization)[[:space:]]*[:=][[:space:]]*[^[:space:]",}]+|raw_pii|raw-reviewer|reviewer_name|"comments"' "$file"; then
    return 1
  else status=$?; [[ "$status" -eq 1 ]] || return 1; fi
  if emails=$(grep -Eio -- '[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}' "$file"); then
    emails=$(printf '%s' "$emails" | tr '[:upper:]' '[:lower:]') || return 1
  else status=$?; [[ "$status" -eq 1 ]] || return 1; emails=''; fi
  while IFS= read -r email; do
    [[ -z "$email" || "$email" == benchmark-nplus1@airbob.cloud || "$email" == benchmark-nplus1-helper@airbob.cloud \
      || "$email" =~ ^coupon-benchmark-[0-9]{5,6}@airbob\.cloud$ \
      || "$email" =~ ^benchmark-read-model-wishlist-(hot|median|cold|empty)@airbob\.cloud$ \
      || "$email" == benchmark-read-model-revenue-admin@airbob.cloud \
      || "$email" =~ ^(host|member)-[0-9a-f]+@benchmark\.airbob\.local$ ]] || return 1
  done <<<"$emails"
}
local_timestamp_to_utc() {
  local local_timestamp=$1 timezone=$2 epoch round_trip
  if date -u -d '@0' '+%s' >/dev/null 2>&1; then
    epoch=$(TZ="$timezone" date -d "$local_timestamp" '+%s') || return 1
    round_trip=$(TZ="$timezone" date -d "@$epoch" '+%Y-%m-%dT%H:%M:%S') || return 1
    [[ "$round_trip" == "$local_timestamp" ]] || return 1
    date -u -d "@$epoch" '+%Y-%m-%dT%H:%M:%SZ'
  else
    epoch=$(TZ="$timezone" date -j -f '%Y-%m-%dT%H:%M:%S' "$local_timestamp" '+%s') || return 1
    round_trip=$(TZ="$timezone" date -r "$epoch" '+%Y-%m-%dT%H:%M:%S') || return 1
    [[ "$round_trip" == "$local_timestamp" ]] || return 1
    date -u -r "$epoch" '+%Y-%m-%dT%H:%M:%SZ'
  fi
}

[[ "$#" -eq 6 || "$#" -eq 7 ]] || usage
etl_release_dir=$1
attestation_file=$2
output_root=$3
dataset_release=$4
evaluation_time=$5
valid_until=$6
snapshot_reference_file=${7:-}

for command_name in jq gzip zstd stat id cp tr grep find sort awk date mkdir mv; do
  command -v "$command_name" >/dev/null 2>&1 || fail "required command is unavailable: $command_name"
done
[[ "$dataset_release" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ ]] || fail 'dataset release must be a lowercase safe name'
[[ "$evaluation_time" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || fail 'evaluation time is invalid'
[[ "$valid_until" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || fail 'valid-until is invalid'
jq -en --arg start "$evaluation_time" --arg finish "$valid_until" \
  '($start|fromdateiso8601) < ($finish|fromdateiso8601) and ($finish|fromdateiso8601) > now' >/dev/null \
  || fail 'dataset evaluation window is invalid or expired'

[[ -d "$etl_release_dir" && ! -L "$etl_release_dir" ]] || fail 'ETL release directory is missing or unsafe'
require_file "$attestation_file"
[[ -z "$snapshot_reference_file" ]] || require_file "$snapshot_reference_file"
[[ -d "$output_root" && ! -L "$output_root" ]] || fail 'output root is missing or unsafe'
etl_release_dir=$(CDPATH= cd -P -- "$etl_release_dir" && pwd -P)
attestation_file=$(CDPATH= cd -P -- "$(dirname -- "$attestation_file")" && pwd -P)/$(basename -- "$attestation_file")
[[ -z "$snapshot_reference_file" ]] || snapshot_reference_file=$(CDPATH= cd -P -- "$(dirname -- "$snapshot_reference_file")" && pwd -P)/$(basename -- "$snapshot_reference_file")
output_root=$(CDPATH= cd -P -- "$output_root" && pwd -P)
[[ "$output_root" != / && "$output_root" != "$etl_release_dir" ]] || fail 'output root is unsafe'
[[ "$(stat_uid "$output_root")" == "$(id -u)" && "$(stat_mode "$output_root")" == 700 ]] || fail 'output root ownership or mode is unsafe'

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
semantic_validator="$script_dir/validate-benchmark-dataset-v2.jq"
release_validator="$script_dir/verify-dataset-release.sh"
require_file "$semantic_validator"
[[ -x "$release_validator" && ! -L "$release_validator" ]] || fail 'release validator is missing or unsafe'

require_file "$etl_release_dir/release-metadata.txt"
production_spec_name=$(awk -F= '$1=="production_spec"{count++;value=substr($0,index($0,"=")+1)} END{if(count==1)print value}' "$etl_release_dir/release-metadata.txt")
case "$production_spec_name" in
  production-skew-v1.json)
    profile_version=production-skew-v1
    expected_budgets='{"accommodations":50000,"activeWishlists":400000,"members":200000,"reservations":2500000,"reviews":1000000,"wishlistLinks":1500000}'
    ;;
  production-skew-large-v1.json)
    profile_version=production-skew-large-v1
    expected_budgets='{"accommodations":200000,"activeWishlists":1600000,"members":800000,"reservations":10000000,"reviews":4000000,"wishlistLinks":6000000}'
    ;;
  *) fail 'release metadata selects an unsupported production profile' ;;
esac
production_spec_key="benchmark/$production_spec_name"

source_files=(
  PROVENANCE.txt SHA256SUMS airbob-production-seed.sql.gz backend-migrations.sha256
  benchmark-dataset-v2.json benchmark-fixture.json database-fingerprint.tsv etl-code.sha256
  generation-qualification-v1.json "$production_spec_name" release-metadata.txt
  source-calibration-v1.json source.sha256 traffic-v1.json
)
actual_source_files=$(find "$etl_release_dir" -mindepth 1 -maxdepth 1 -exec basename {} \; | sort)
expected_source_files=$(printf '%s\n' "${source_files[@]}" | sort)
[[ "$actual_source_files" == "$expected_source_files" ]] || fail 'ETL source release must contain the exact v2 inventory'
for source_name in "${source_files[@]}"; do require_file "$etl_release_dir/$source_name"; done

final_dir="$output_root/$dataset_release"
incomplete_dir="$output_root/$dataset_release.incomplete"
lock_dir="$output_root/.$dataset_release.assemble.lock"
mkdir -m 700 "$lock_dir" || fail 'another assembler owns the release lock'
owned_lock=true
owned_incomplete=false
cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ "$status" -ne 0 && "$owned_incomplete" == true && -d "$incomplete_dir" && ! -L "$incomplete_dir" ]]; then
    find "$incomplete_dir" -depth -mindepth 1 -delete >/dev/null 2>&1 || true
    rmdir "$incomplete_dir" >/dev/null 2>&1 || true
  fi
  [[ "$owned_lock" != true ]] || rmdir "$lock_dir" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
[[ ! -e "$final_dir" && ! -L "$final_dir" && ! -e "$incomplete_dir" && ! -L "$incomplete_dir" ]] || fail 'release destination already exists'
mkdir -m 700 "$incomplete_dir"
owned_incomplete=true
staging="$incomplete_dir/.staging"
mkdir -m 700 "$staging"
for source_name in "${source_files[@]}"; do cp "$etl_release_dir/$source_name" "$staging/$source_name"; chmod 600 "$staging/$source_name"; done
cp "$attestation_file" "$staging/attestation.json"
cp "$semantic_validator" "$staging/validate-benchmark-dataset-v2.jq"
chmod 600 "$staging/attestation.json" "$staging/validate-benchmark-dataset-v2.jq"
if [[ -n "$snapshot_reference_file" ]]; then cp "$snapshot_reference_file" "$staging/snapshot-reference.json"; chmod 600 "$staging/snapshot-reference.json"; fi

checksummed_files=(
  PROVENANCE.txt airbob-production-seed.sql.gz backend-migrations.sha256 benchmark-dataset-v2.json
  benchmark-fixture.json database-fingerprint.tsv etl-code.sha256 generation-qualification-v1.json
  "$production_spec_name" release-metadata.txt source-calibration-v1.json source.sha256 traffic-v1.json
)
exec 3< "$staging/SHA256SUMS"
for source_name in "${checksummed_files[@]}"; do
  checksum_line=''
  IFS= read -r checksum_line <&3 || fail 'SHA256SUMS is short'
  [[ "$checksum_line" =~ ^([0-9a-f]{64})\ \ ([A-Za-z0-9._-]+)$ && "${BASH_REMATCH[2]}" == "$source_name" ]] || fail 'SHA256SUMS is not canonical'
  [[ "$(sha256_file "$staging/$source_name")" == "${BASH_REMATCH[1]}" ]] || fail "source checksum mismatch: $source_name"
done
if IFS= read -r _ <&3; then fail 'SHA256SUMS contains extra entries'; fi
exec 3<&-
canonical_payload_sha=$(sha256_file "$staging/SHA256SUMS")
checksum_digest() {
  local target=$1
  awk -v target="$target" '$2 == target { count++; value=$1 } END { if (count == 1) print value }' "$staging/SHA256SUMS"
}

metadata_keys=(
  format release_id dump dump_sha256 manifest manifest_sha256 benchmark_dataset_manifest
  benchmark_dataset_manifest_sha256 benchmark_dataset_version world_version production_spec
  production_spec_sha256 source_calibration source_calibration_sha256
  source_catalog_inventory_fingerprint generation_qualification generation_qualification_sha256
  canonical_scale configured_batch_size jvm_max_heap_bytes traffic_manifest traffic_manifest_sha256
  traffic_dataset_version traffic_dataset_run_id traffic_flyway_version traffic_migration_digest
  fingerprint fingerprint_sha256 final_world_fingerprint base_world_fingerprint
  distribution_fingerprint target_fingerprint inventory_fingerprint etl_code_inventory
  etl_code_inventory_sha256 source_inventory source_inventory_sha256 backend_migration_inventory
  backend_migration_inventory_sha256 provenance provenance_sha256 required_rows recovery
)
metadata_index=0
while IFS= read -r metadata_line; do
  [[ "$metadata_index" -lt "${#metadata_keys[@]}" ]] || fail 'release metadata has extra entries'
  metadata_key=${metadata_keys[$metadata_index]}
  [[ "$metadata_line" == "$metadata_key="* ]] || fail 'release metadata key order is invalid'
  metadata_value=${metadata_line#*=}
  [[ -n "$metadata_value" ]] || fail 'release metadata contains a blank value'
  contains_secret_marker "$metadata_key" && fail 'release metadata contains a secret-like key'
  contains_secret_marker "$metadata_value" && fail 'release metadata contains a secret-like value'
  metadata_index=$((metadata_index + 1))
done < "$staging/release-metadata.txt"
[[ "$metadata_index" -eq "${#metadata_keys[@]}" ]] || fail 'release metadata is incomplete'
metadata_value() {
  local target=$1
  awk -F= -v target="$target" '$1 == target { count++; value=substr($0,index($0,"=")+1) } END { if (count == 1) print value }' "$staging/release-metadata.txt"
}
provenance_value() {
  local target=$1
  awk -F= -v target="$target" '$1 == target { count++; value=substr($0,index($0,"=")+1) } END { if (count == 1) print value }' "$staging/PROVENANCE.txt"
}

[[ "$(metadata_value format)" == airbob-production-seed-release-v2 ]] || fail 'release metadata format is not v2'
[[ "$(metadata_value dump)" == airbob-production-seed.sql.gz && "$(metadata_value manifest)" == benchmark-fixture.json && \
   "$(metadata_value benchmark_dataset_manifest)" == benchmark-dataset-v2.json && "$(metadata_value benchmark_dataset_version)" == benchmark-dataset-v2 && \
   "$(metadata_value world_version)" == world-v2 && "$(metadata_value production_spec)" == "$production_spec_name" && \
   "$(metadata_value source_calibration)" == source-calibration-v1.json && "$(metadata_value generation_qualification)" == generation-qualification-v1.json && \
   "$(metadata_value traffic_manifest)" == traffic-v1.json && "$(metadata_value fingerprint)" == database-fingerprint.tsv ]] || fail 'release metadata names a v1 or unsupported payload'
[[ "$(metadata_value canonical_scale)" == true && "$(metadata_value configured_batch_size)" == 1000 && \
   "$(metadata_value jvm_max_heap_bytes)" == 12884901888 && "$(metadata_value traffic_flyway_version)" == 27 && \
   "$(metadata_value required_rows)" == 201 ]] || fail 'release metadata qualification is unsupported'
for binding in \
  "dump_sha256:airbob-production-seed.sql.gz" "manifest_sha256:benchmark-fixture.json" \
  "benchmark_dataset_manifest_sha256:benchmark-dataset-v2.json" "production_spec_sha256:$production_spec_name" \
  "source_calibration_sha256:source-calibration-v1.json" "generation_qualification_sha256:generation-qualification-v1.json" \
  "traffic_manifest_sha256:traffic-v1.json" "fingerprint_sha256:database-fingerprint.tsv" \
  "etl_code_inventory_sha256:etl-code.sha256" "source_inventory_sha256:source.sha256" \
  "backend_migration_inventory_sha256:backend-migrations.sha256" "provenance_sha256:PROVENANCE.txt"; do
  key=${binding%%:*}; file=${binding#*:}
  [[ "$(metadata_value "$key")" == "$(checksum_digest "$file")" ]] || fail "metadata checksum binding failed: $key"
done

[[ "$(provenance_value release_profile)" == "$profile_version" && \
   "$(provenance_value distribution_profile)" == "$profile_version" && \
   "$(provenance_value production_spec)" == "$production_spec_name" && \
   "$(provenance_value budget_accommodations)" == "$(jq -r '.accommodations' <<<"$expected_budgets")" && \
   "$(provenance_value budget_members)" == "$(jq -r '.members' <<<"$expected_budgets")" && \
   "$(provenance_value budget_reservations)" == "$(jq -r '.reservations' <<<"$expected_budgets")" && \
   "$(provenance_value budget_reviews)" == "$(jq -r '.reviews' <<<"$expected_budgets")" && \
   "$(provenance_value budget_active_wishlists)" == "$(jq -r '.activeWishlists' <<<"$expected_budgets")" && \
   "$(provenance_value budget_wishlist_links)" == "$(jq -r '.wishlistLinks' <<<"$expected_budgets")" ]] \
  || fail 'provenance profile, specification, or budget contract failed'

jq -e -f "$staging/validate-benchmark-dataset-v2.jq" "$staging/benchmark-dataset-v2.json" >/dev/null || fail 'benchmark dataset semantic validation failed'
jq -e --arg profile "$profile_version" --arg calibrationSha "$(metadata_value source_calibration_sha256)" --arg specSha "$(metadata_value production_spec_sha256)" \
  --arg sourceInventory "$(metadata_value source_catalog_inventory_fingerprint)" --arg finalWorld "$(metadata_value final_world_fingerprint)" \
  --arg baseWorld "$(metadata_value base_world_fingerprint)" --arg distribution "$(metadata_value distribution_fingerprint)" \
  --arg target "$(metadata_value target_fingerprint)" --arg inventory "$(metadata_value inventory_fingerprint)" \
  --argjson budgets "$expected_budgets" '
  .schemaVersion == 2 and .datasetVersion == "benchmark-dataset-v2" and .world.version == "world-v2" and
  .world.provenance.profileVersion == $profile and
  .world.provenance.calibrationSha256 == $calibrationSha and .world.provenance.specSha256 == $specSha and
  .world.provenance.sourceInventorySha256 == $sourceInventory and .world.fingerprints["final-world"] == $finalWorld and
  .world.fingerprints["base-world"] == $baseWorld and .world.provenance.assertionSha256 == $distribution and
  .targetFingerprint == $target and .world.fingerprints["final-inventory"] == $inventory and
  (.world.scopeRanges | keys) == ["accommodation","member","payment","payment-transaction","reservation","review","wishlist","wishlist-accommodation"] and
  all(.world.scopeRanges | to_entries[]; .key == .value.id and .value.minimumId > 0 and
    .value.maximumId >= .value.minimumId and .value.rowCount == (.value.maximumId - .value.minimumId + 1)) and
  (.world.scopeRanges | {
    accommodations: .accommodation.rowCount,
    members: .member.rowCount,
    reservations: .reservation.rowCount,
    reviews: .review.rowCount,
    activeWishlists: .wishlist.rowCount,
    wishlistLinks: .["wishlist-accommodation"].rowCount
  }) == $budgets and
  ((.world.tableRows | {
    accommodations: .accommodation,
    members: .member,
    reservations: .reservation,
    reviews: .review,
    activeWishlists: .wishlist,
    wishlistLinks: .wishlist_accommodation
  }) as $finalRows | all($finalRows | to_entries[]; .value >= $budgets[.key]))
' "$staging/benchmark-dataset-v2.json" >/dev/null || fail 'benchmark manifest release tuple or contiguous base scopes drifted'
scan_sensitive_file "$staging/source-calibration-v1.json" || fail 'source calibration contains unapproved identity, prose, email, or secret material'
jq -e --arg inventory "$(metadata_value source_catalog_inventory_fingerprint)" \
  --argjson budgets "$expected_budgets" '
  .calibrationVersion=="source-calibration-v1" and .catalogVersion=="source-catalog-v1" and .inventorySha256==$inventory and
  (.sourceInventory|type=="array" and length>0 and all(.[];
    (.canonicalPath|type=="string" and length>0 and (startswith("/")|not) and (test("(^|/)\\.\\.(/|$)")|not)) and
    (.byteSize|type=="number" and floor==. and .>=0) and (.sha256|test("^[0-9a-f]{64}$")) and
    (.role=="LISTINGS" or .role=="REVIEWS" or .role=="AMENITIES"))) and
  ([.sourceInventory[].canonicalPath]|unique|length)==(.sourceInventory|length) and
  (.cohorts|type=="array" and length>0) and
  .aggregate.counts.uniqueListings >= $budgets.accommodations and
  .syntheticReviewTemplatePolicy.reviewerIdentityPolicy=="EXCLUDED" and
  .syntheticReviewTemplatePolicy.reviewProsePolicy=="EXCLUDED" and
  .syntheticReviewTemplatePolicy.templatePolicy=="VERSIONED_COHORT_TEMPLATE" and
  ([paths as $p|($p[-1]|tostring|ascii_downcase)|select(.!="revieweridentitypolicy" and .!="reviewprosepolicy")|select(test("reviewer(name|id)|comments|rawreview|raw-review"))]|length==0)
' "$staging/source-calibration-v1.json" >/dev/null || fail 'source calibration aggregate-only contract failed'
jq -e --arg profile "$profile_version" --argjson budgets "$expected_budgets" '
  .profileVersion==$profile and .provenance.generatorVersion=="production-skew-generator-v1" and
  .provenance.prngAlgorithm=="sha256-splitmix64-counter-v1" and
  .provenance.seedDerivation=="length-prefixed(profile-version, global-seed, relation-domain, stable-external-key, counter)" and
  .provenance.globalSeed==20260826 and .provenance.anchor=="2026-07-31T15:00:00Z" and .provenance.timezone=="Asia/Seoul" and
  (.targets|{accommodations:.accommodations.rowBudget,members:.members.rowBudget,reservations:.reservations.rowBudget,reviews:.reviews.rowBudget,activeWishlists:.activeWishlists.rowBudget,wishlistLinks:.wishlistLinks.rowBudget})==$budgets and
  ([.targets[]|select(.rowBudget!=null)|.tolerance]|all(.absoluteRows==0 and .relativePercent==0))
' "$staging/$production_spec_name" >/dev/null || fail 'tracked production-skew specification contract failed'
jq -e --argjson budgets "$expected_budgets" '
  (keys|sort)==["canonicalScale","configuredBatchSize","configuredLimits","generatedBudgets","jvmMaxHeapBytes","retainedMaxima","version"] and
  .version=="generation-qualification-v1" and .canonicalScale==true and .configuredBatchSize==1000 and .jvmMaxHeapBytes==12884901888 and
  .generatedBudgets==$budgets and
  .configuredLimits=={completedStayCandidates:30000,completedStays:1000,paymentTransactions:1000,payments:1000,reservations:1000,reviews:30000,wishlistLinks:100000,wishlists:1000} and
  (.retainedMaxima|keys|sort)==(.configuredLimits|keys|sort) and
  (.configuredLimits as $l|[.retainedMaxima|to_entries[]|(.value|type=="number" and floor==. and .>0) and (.value<=$l[.key])]|all)
' "$staging/generation-qualification-v1.json" >/dev/null || fail 'generation qualification receipt contract failed'
jq -e '.datasetVersion == "nplus1-v1" and .requiredRows == (.maxRequestedSize + 1)' "$staging/benchmark-fixture.json" >/dev/null || fail 'legacy nplus1 contract failed'

traffic_anchor=$(jq -r '.anchorTime' "$staging/traffic-v1.json")
traffic_valid_until=$(jq -r '.validUntil' "$staging/traffic-v1.json")
traffic_timezone=$(jq -r '.timezone' "$staging/traffic-v1.json")
traffic_run_id=$(jq -r '.datasetRunId' "$staging/traffic-v1.json")
[[ "$traffic_run_id" == "$(metadata_value traffic_dataset_run_id)" && "$traffic_run_id" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$ ]] || fail 'traffic run identity drifted'
converted_anchor=$(local_timestamp_to_utc "$traffic_anchor" "$traffic_timezone") || fail 'traffic anchor is invalid'
converted_valid_until=$(local_timestamp_to_utc "$traffic_valid_until" "$traffic_timezone") || fail 'traffic validity is invalid'
[[ "$converted_valid_until" == "$valid_until" ]] || fail 'supplied validity does not match traffic manifest'

attestation_sha=$(sha256_file "$staging/attestation.json")
jq -e --arg payload "$canonical_payload_sha" --arg dump "$(checksum_digest airbob-production-seed.sql.gz)" \
  --arg fingerprint "$(checksum_digest database-fingerprint.tsv)" --arg etlCommit "$(awk -F= '$1=="etl_head"{print $2}' "$staging/PROVENANCE.txt")" \
  --arg finalWorld "$(metadata_value final_world_fingerprint)" --arg baseWorld "$(metadata_value base_world_fingerprint)" \
  --arg distributionAssertion "$(metadata_value distribution_fingerprint)" \
  --arg distributionSpec "$(metadata_value production_spec_sha256)" \
  --arg target "$(metadata_value target_fingerprint)" --arg inventory "$(metadata_value inventory_fingerprint)" '
  (keys|sort)==(["schemaVersion","sourceReleasePayloadSha256","sourceDumpSha256","restoredDumpSha256","databaseRestoreMethod","sourceDatabaseFingerprintSha256","sourceEtlCommit","databaseServerUuid","verifierContractInventorySha256","databaseFingerprintSha256","verificationOutputSha256","finalWorldFingerprintSha256","baseWorldFingerprintSha256","distributionEvidenceSha256","distributionAssertionSha256","distributionSpecSha256","targetFingerprintSha256","inventoryFingerprintSha256","flywayVersion","flywayHistoryRows","migrationChecksumSha256","schemaFingerprintSha256","outboxState","expectedTableRows","capturedAt"]|sort) and
  .schemaVersion == 4 and .databaseRestoreMethod == "gzip-to-empty-airbobdb-v2" and
  .sourceReleasePayloadSha256 == $payload and .sourceDumpSha256 == $dump and .restoredDumpSha256 == $dump and
  .sourceDatabaseFingerprintSha256 == $fingerprint and .databaseFingerprintSha256 == $fingerprint and
  .sourceEtlCommit == $etlCommit and .finalWorldFingerprintSha256 == $finalWorld and
  .baseWorldFingerprintSha256 == $baseWorld and
  (.distributionEvidenceSha256|test("^[0-9a-f]{64}$")) and
  .distributionAssertionSha256 == $distributionAssertion and
  .distributionSpecSha256 == $distributionSpec and .targetFingerprintSha256 == $target and
  .inventoryFingerprintSha256 == $inventory and .flywayVersion == "27" and .flywayHistoryRows == 27 and
  .outboxState == "empty" and .expectedTableRows.accommodation_inventory_day == 0
' "$staging/attestation.json" >/dev/null || fail 'attestation does not bind the source v2 release'
captured_at=$(jq -r '.capturedAt' "$staging/attestation.json")
jq -en --arg anchor "$converted_anchor" --arg captured "$captured_at" --arg evaluation "$evaluation_time" --arg finish "$valid_until" \
  '($anchor|fromdateiso8601) <= ($captured|fromdateiso8601) and ($captured|fromdateiso8601) <= ($evaluation|fromdateiso8601) and ($evaluation|fromdateiso8601) < ($finish|fromdateiso8601)' >/dev/null || fail 'attestation timestamp is outside the release window'

search_json='{"enabled":false}'
if [[ -n "$snapshot_reference_file" ]]; then
  jq -e --arg release "$dataset_release" '
    def sha: type == "string" and test("^[0-9a-f]{64}$");
    (keys | sort) == ([
      "schemaVersion", "repository", "bucket", "basePath", "snapshot",
      "logicalAlias", "snapshotIndex", "elasticsearchVersion", "imageDigest",
      "documentCount", "mappingSha256", "dbIdsSha256", "esIdsSha256",
      "dbDocumentIdentityPairsSha256", "esDocumentIdentityPairsSha256",
      "contentFingerprintSha256"
    ] | sort) and
    .schemaVersion == 2 and .repository == "airbob-dataset-readonly" and
    .bucket == "airbob-performance-lab-dataset-942632789808" and
    .basePath == ("elasticsearch/releases/" + $release) and
    .snapshot == ("airbob-" + $release) and
    .logicalAlias == "accommodations" and
    (.snapshotIndex | type == "string" and test("^accommodations-v[a-z0-9][a-z0-9._-]*$")) and
    .elasticsearchVersion == "8.18.8" and
    (.imageDigest | type == "string" and test("^sha256:[0-9a-f]{64}$")) and
    (.documentCount | type == "number" and floor == . and . >= 0) and
    all([
      .mappingSha256, .dbIdsSha256, .esIdsSha256,
      .dbDocumentIdentityPairsSha256, .esDocumentIdentityPairsSha256,
      .contentFingerprintSha256
    ][]; sha) and
    .dbIdsSha256 == .esIdsSha256 and
    .dbDocumentIdentityPairsSha256 == .esDocumentIdentityPairsSha256
  ' "$staging/snapshot-reference.json" >/dev/null || fail 'snapshot reference contract failed'
  search_json=$(jq -cS '{enabled:true,snapshotReferenceKey:"elasticsearch/snapshot-reference.json",repository:.repository,elasticsearchVersion:.elasticsearchVersion,imageDigest:.imageDigest,requiredPlugins:["analysis-nori","repository-s3"],logicalAlias:.logicalAlias,snapshotIndex:.snapshotIndex,documentCount:.documentCount,mappingSha256:.mappingSha256,databaseAccommodationIdsSha256:.dbIdsSha256,elasticsearchAccommodationIdsSha256:.esIdsSha256,databaseDocumentIdentityPairsSha256:.dbDocumentIdentityPairsSha256,elasticsearchDocumentIdentityPairsSha256:.esDocumentIdentityPairsSha256,contentFingerprintSha256:.contentFingerprintSha256}' "$staging/snapshot-reference.json")
fi

mkdir -m 700 "$incomplete_dir/attestation" "$incomplete_dir/benchmark" "$incomplete_dir/mysql"
cp "$staging/attestation.json" "$incomplete_dir/attestation/restore.json"
cp "$staging/benchmark-fixture.json" "$incomplete_dir/benchmark/manifest.json"
cp "$staging/benchmark-dataset-v2.json" "$incomplete_dir/benchmark/dataset-manifest.json"
cp "$staging/source-calibration-v1.json" "$incomplete_dir/benchmark/source-calibration-v1.json"
cp "$staging/$production_spec_name" "$incomplete_dir/benchmark/$production_spec_name"
cp "$staging/generation-qualification-v1.json" "$incomplete_dir/benchmark/generation-qualification-v1.json"
cp "$staging/validate-benchmark-dataset-v2.jq" "$incomplete_dir/benchmark/validate-benchmark-dataset-v2.jq"
cp "$staging/database-fingerprint.tsv" "$incomplete_dir/mysql/database-fingerprint.tsv"
if [[ -n "$snapshot_reference_file" ]]; then mkdir -m 700 "$incomplete_dir/elasticsearch"; cp "$staging/snapshot-reference.json" "$incomplete_dir/elasticsearch/snapshot-reference.json"; fi
gzip -dc "$staging/airbob-production-seed.sql.gz" | zstd --threads=1 --no-progress --quiet --stdout > "$incomplete_dir/mysql/airbob.sql.zst" || fail 'dump conversion failed'
dump_sha=$(sha256_file "$incomplete_dir/mysql/airbob.sql.zst")
printf '%s  airbob.sql.zst\n' "$dump_sha" > "$incomplete_dir/mysql/sha256.txt"
chmod 600 "$incomplete_dir"/{attestation,benchmark,mysql}/*
[[ -z "$snapshot_reference_file" ]] || chmod 600 "$incomplete_dir/elasticsearch/snapshot-reference.json"

benchmark_manifest_sha=$(sha256_file "$incomplete_dir/benchmark/manifest.json")
dataset_manifest_sha=$(sha256_file "$incomplete_dir/benchmark/dataset-manifest.json")
validator_sha=$(sha256_file "$incomplete_dir/benchmark/validate-benchmark-dataset-v2.jq")
expected_rows=$(jq -cS '.expectedTableRows' "$incomplete_dir/attestation/restore.json")
etl_commit=$(jq -r '.sourceEtlCommit' "$incomplete_dir/attestation/restore.json")
migration_sha=$(jq -r '.migrationChecksumSha256' "$incomplete_dir/attestation/restore.json")
schema_sha=$(jq -r '.schemaFingerprintSha256' "$incomplete_dir/attestation/restore.json")
jq -nS \
  --arg release "$dataset_release" --arg runId "$traffic_run_id" --arg etlCommit "$etl_commit" \
  --arg payload "$canonical_payload_sha" --arg legacySha "$benchmark_manifest_sha" --arg manifestSha "$dataset_manifest_sha" \
  --arg validatorSha "$validator_sha" --arg calibrationSha "$(metadata_value source_calibration_sha256)" \
  --arg profileVersion "$profile_version" --arg productionSpecKey "$production_spec_key" \
  --arg specSha "$(metadata_value production_spec_sha256)" --arg qualificationSha "$(metadata_value generation_qualification_sha256)" \
  --arg fingerprintSha "$(metadata_value fingerprint_sha256)" --arg attestationSha "$attestation_sha" \
  --arg sourceInventory "$(metadata_value source_catalog_inventory_fingerprint)" --arg dumpSha "$dump_sha" \
  --arg migrationSha "$migration_sha" --arg schemaSha "$schema_sha" --arg evaluation "$evaluation_time" --arg validUntil "$valid_until" \
  --arg finalWorld "$(metadata_value final_world_fingerprint)" --arg baseWorld "$(metadata_value base_world_fingerprint)" \
  --arg distribution "$(metadata_value distribution_fingerprint)" --arg target "$(metadata_value target_fingerprint)" \
  --arg inventory "$(metadata_value inventory_fingerprint)" --argjson expectedRows "$expected_rows" --argjson search "$search_json" '
  {
    schemaVersion:2, releaseKind:"pipeline-rehearsal", datasetRelease:$release, datasetRunId:$runId,
    releaseTuple:{datasetVersion:"benchmark-dataset-v2",worldVersion:"world-v2",calibrationVersion:"source-calibration-v1",profileVersion:$profileVersion,generatorVersion:"production-skew-generator-v1",dumpSha256:$dumpSha,migrationChecksumSha256:$migrationSha,schemaFingerprintSha256:$schemaSha,manifestSha256:$manifestSha,validatorSha256:$validatorSha,calibrationSha256:$calibrationSha,specSha256:$specSha,qualificationSha256:$qualificationSha,databaseFingerprintSha256:$fingerprintSha,attestationSha256:$attestationSha,finalWorldFingerprintSha256:$finalWorld,baseWorldFingerprintSha256:$baseWorld,distributionFingerprintSha256:$distribution,targetFingerprintSha256:$target,inventoryFingerprintSha256:$inventory},
    source:{datasetVersion:"benchmark-dataset-v2",worldVersion:"world-v2",etlCommit:$etlCommit,seed:"airbob-production-seed-v2",profile:"large",manifestVersion:"benchmark-dataset-v2",canonicalPayloadSha256:$payload,legacyBenchmarkManifestKey:"benchmark/manifest.json",legacyBenchmarkManifestSha256:$legacySha,benchmarkDatasetManifestKey:"benchmark/dataset-manifest.json",benchmarkDatasetManifestSha256:$manifestSha,validatorKey:"benchmark/validate-benchmark-dataset-v2.jq",validatorSha256:$validatorSha,calibrationKey:"benchmark/source-calibration-v1.json",calibrationSha256:$calibrationSha,productionSpecKey:$productionSpecKey,productionSpecSha256:$specSha,generationQualificationKey:"benchmark/generation-qualification-v1.json",generationQualificationSha256:$qualificationSha,databaseFingerprintKey:"mysql/database-fingerprint.tsv",databaseFingerprintSha256:$fingerprintSha,attestationKey:"attestation/restore.json",attestationSha256:$attestationSha,sourceInventorySha256:$sourceInventory},
    mysql:{dumpKey:"mysql/airbob.sql.zst",dumpSha256:$dumpSha,flywayVersion:"27",migrationChecksumSha256:$migrationSha,schemaFingerprintSha256:$schemaSha,timezone:"UTC",evaluationTime:$evaluation,validUntil:$validUntil,outboxPolicy:"absent",expectedTableRows:$expectedRows},
    couponPreparation:[], kafka:{topics:["PAYMENT_OPERATION.events","PAYMENT_OPERATION.events.RETRY","PAYMENT_OPERATION.events.DLT","ACCOMMODATION_INDEX.events","ACCOMMODATION_INDEX.events.RETRY","ACCOMMODATION_INDEX.events.DLT","ACCOMMODATION_CACHE.events","ACCOMMODATION_CACHE.events.RETRY","ACCOMMODATION_CACHE.events.DLT","OPERATOR_ALERT.events","OPERATOR_ALERT.events.RETRY","OPERATOR_ALERT.events.DLT"]|map({name:.,partitions:3,retentionMs:86400000})}, search:$search
  }
' > "$incomplete_dir/manifest.json"
chmod 600 "$incomplete_dir/manifest.json"
find "$staging" -depth -mindepth 1 -delete
rmdir "$staging"

"$release_validator" "$incomplete_dir" "$dataset_release" pipeline-rehearsal >/dev/null || fail 'assembled release failed v2 verification'
[[ ! -e "$final_dir" && ! -L "$final_dir" ]] || fail 'final release appeared before promotion'
mv "$incomplete_dir" "$final_dir"
owned_incomplete=false
rmdir "$lock_dir"
owned_lock=false
trap - EXIT HUP INT TERM
printf '%s\n' 'dataset release assembled and verified'
