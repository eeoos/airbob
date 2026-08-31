#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

usage() {
  printf 'usage: %s RELEASE_DIR EXPECTED_RELEASE EXPECTED_KIND [--metadata-only]\n' "${0##*/}" >&2
  exit 64
}

fail() {
  printf 'dataset release verification failed: %s\n' "$1" >&2
  exit 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    fail 'a SHA-256 implementation is required'
  fi
}

require_regular_file() {
  [[ -f "$1" && ! -L "$1" ]] || fail "artifact is missing or unsafe: ${1##*/}"
}

require_sha() {
  local file=$1
  local expected=$2
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || fail 'manifest contains an invalid artifact digest'
  [[ "$(sha256_file "$file")" == "$expected" ]] || fail "artifact digest mismatch: ${file##*/}"
}
scan_sensitive_file() {
  local file=$1 email emails status
  if grep -Eiq -- '-----BEGIN .*PRIVATE KEY-----|(AKIA|ASIA)[0-9A-Z]{16}|(password|secret|credential|authorization)[[:space:]]*[:=][[:space:]]*[^[:space:]",}]+|raw_pii|raw-reviewer|reviewer_name|"comments"' "$file"; then return 1
  else status=$?; [[ "$status" -eq 1 ]] || return 1; fi
  if emails=$(grep -Eio -- '[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}' "$file"); then emails=$(printf '%s' "$emails" | tr '[:upper:]' '[:lower:]') || return 1
  else status=$?; [[ "$status" -eq 1 ]] || return 1; emails=''; fi
  while IFS= read -r email; do
    [[ -z "$email" || "$email" == benchmark-nplus1@airbob.cloud || "$email" == benchmark-nplus1-helper@airbob.cloud \
      || "$email" =~ ^coupon-benchmark-[0-9]{5,6}@airbob\.cloud$ \
      || "$email" =~ ^benchmark-read-model-wishlist-(hot|median|cold|empty)@airbob\.cloud$ \
      || "$email" == benchmark-read-model-revenue-admin@airbob.cloud \
      || "$email" =~ ^(host|member)-[0-9a-f]+@benchmark\.airbob\.local$ ]] || return 1
  done <<<"$emails"
}

scan_sensitive_json() {
  jq -e '
    def normalized: gsub("[^A-Za-z0-9]"; "") | ascii_downcase;
    def sensitive_key:
      normalized as $key
      | ["password","passwd","secret","credential","authorization","token","sessionid",
         "cookie","apikey","accesskey","privatekey","serviceaccount","rawpii"]
      | any(. as $fragment | $key | contains($fragment));
    ([paths as $path
      | select(($path[-1] | type) == "string" and ($path[-1] | sensitive_key))]
      | length) == 0 and
    ([.. | strings
      | select(test("-----BEGIN[[:space:]].*PRIVATE KEY-----|(AKIA|ASIA)[0-9A-Z]{16}|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}"; "i"))]
      | length) == 0
  ' "$1" >/dev/null
}

[[ "$#" -eq 3 || ( "$#" -eq 4 && "$4" == --metadata-only ) ]] || usage
release_dir=$1
expected_release=$2
expected_kind=$3
metadata_only=false
[[ "$#" -eq 3 ]] || metadata_only=true

[[ "$expected_release" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ ]] || fail 'invalid expected dataset release'
case "$expected_kind" in
  pipeline-rehearsal) ;;
  evidence) fail 'evidence release kind has no trusted producer' ;;
  *) fail 'invalid expected release kind' ;;
esac
[[ -d "$release_dir" && ! -L "$release_dir" ]] || fail 'dataset release directory is missing or unsafe'
release_dir=$(CDPATH= cd -P -- "$release_dir" && pwd -P)

for command_name in jq awk find sort cmp wc grep tr; do
  command -v "$command_name" >/dev/null 2>&1 || fail "required command is unavailable: $command_name"
done

manifest="$release_dir/manifest.json"
require_regular_file "$manifest"
profile_version=$(jq -er '.releaseTuple.profileVersion | select(type=="string")' "$manifest") \
  || fail 'wrapper production profile is missing'
case "$profile_version" in
  production-skew-v1)
    production_spec_name=production-skew-v1.json
    expected_budgets='{"accommodations":50000,"activeWishlists":400000,"members":200000,"reservations":2500000,"reviews":1000000,"wishlistLinks":1500000}'
    ;;
  production-skew-large-v1)
    production_spec_name=production-skew-large-v1.json
    expected_budgets='{"accommodations":200000,"activeWishlists":1600000,"members":800000,"reservations":10000000,"reviews":4000000,"wishlistLinks":6000000}'
    ;;
  *) fail 'wrapper selects an unsupported production profile' ;;
esac
production_spec_key="benchmark/$production_spec_name"
legacy_manifest="$release_dir/benchmark/manifest.json"
dataset_manifest="$release_dir/benchmark/dataset-manifest.json"
validator="$release_dir/benchmark/validate-benchmark-dataset-v2.jq"
calibration="$release_dir/benchmark/source-calibration-v1.json"
production_spec="$release_dir/$production_spec_key"
qualification="$release_dir/benchmark/generation-qualification-v1.json"
database_fingerprint="$release_dir/mysql/database-fingerprint.tsv"
attestation="$release_dir/attestation/restore.json"
dump="$release_dir/mysql/airbob.sql.zst"
checksum="$release_dir/mysql/sha256.txt"

required_files=(
  "$legacy_manifest" "$dataset_manifest" "$validator" "$calibration"
  "$production_spec" "$qualification" "$database_fingerprint" "$attestation" "$checksum"
)
[[ "$metadata_only" == true ]] || required_files+=("$dump")
for required_file in "${required_files[@]}"; do
  require_regular_file "$required_file"
done

# Accept only a fixed shallow wrapper before trusting any digest-bound payload.
jq -se \
  --arg release "$expected_release" \
  --arg kind "$expected_kind" \
  --arg profile "$profile_version" \
  --arg specKey "$production_spec_key" '
  def exact($wanted): type == "object" and keys == ($wanted | sort);
  def sha: type == "string" and test("^[0-9a-f]{64}$");
  def utc: type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$");
  length == 1 and (.[0] |
    (if $kind == "evidence" then
      exact(["schemaVersion","releaseKind","datasetRelease","datasetRunId","releaseTuple","source","mysql","couponPreparation","kafka","search","evidence"])
    else
      exact(["schemaVersion","releaseKind","datasetRelease","datasetRunId","releaseTuple","source","mysql","couponPreparation","kafka","search"])
    end) and
    .schemaVersion == 2 and .releaseKind == $kind and .datasetRelease == $release and
    (.datasetRunId | type == "string" and test("^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")) and
    (.releaseTuple | exact([
      "datasetVersion","worldVersion","calibrationVersion","profileVersion","generatorVersion",
      "dumpSha256","migrationChecksumSha256","schemaFingerprintSha256","manifestSha256",
      "validatorSha256","calibrationSha256","specSha256","qualificationSha256",
      "databaseFingerprintSha256","attestationSha256","finalWorldFingerprintSha256",
      "baseWorldFingerprintSha256","distributionFingerprintSha256","targetFingerprintSha256",
      "inventoryFingerprintSha256"
    ])) and
    .releaseTuple.datasetVersion == "benchmark-dataset-v2" and
    .releaseTuple.worldVersion == "world-v2" and
    .releaseTuple.calibrationVersion == "source-calibration-v1" and
    .releaseTuple.profileVersion == $profile and
    .releaseTuple.generatorVersion == "production-skew-generator-v1" and
    all(.releaseTuple | to_entries[] | select(.key | endswith("Sha256")); .value | sha) and
    (.source | exact([
      "datasetVersion","worldVersion","etlCommit","seed","profile","manifestVersion",
      "canonicalPayloadSha256","legacyBenchmarkManifestKey","legacyBenchmarkManifestSha256",
      "benchmarkDatasetManifestKey","benchmarkDatasetManifestSha256","validatorKey","validatorSha256",
      "calibrationKey","calibrationSha256","productionSpecKey","productionSpecSha256",
      "generationQualificationKey","generationQualificationSha256","databaseFingerprintKey",
      "databaseFingerprintSha256","attestationKey","attestationSha256","sourceInventorySha256"
    ])) and
    .source.datasetVersion == "benchmark-dataset-v2" and .source.worldVersion == "world-v2" and
    (.source.etlCommit | test("^[0-9a-f]{40}$")) and .source.seed == "airbob-production-seed-v2" and
    .source.profile == "large" and .source.manifestVersion == "benchmark-dataset-v2" and
    .source.productionSpecKey == $specKey and
    all(.source | to_entries[] | select(.key | endswith("Sha256")); .value | sha) and
    (.mysql | exact(["dumpKey","dumpSha256","flywayVersion","migrationChecksumSha256","schemaFingerprintSha256","timezone","evaluationTime","validUntil","outboxPolicy","expectedTableRows"])) and
    .mysql.dumpKey == "mysql/airbob.sql.zst" and (.mysql.dumpSha256 | sha) and
    .mysql.flywayVersion == "27" and (.mysql.migrationChecksumSha256 | sha) and
    (.mysql.schemaFingerprintSha256 | sha) and .mysql.timezone == "UTC" and
    (.mysql.evaluationTime | utc) and (.mysql.validUntil | utc) and
    (.mysql.evaluationTime | fromdateiso8601) < (.mysql.validUntil | fromdateiso8601) and
    (.mysql.outboxPolicy == "absent" or .mysql.outboxPolicy == "truncate-after-import") and
    (.mysql.expectedTableRows | type == "object" and has("flyway_schema_history") and has("outbox") and has("accommodation_inventory_day")) and
    .mysql.expectedTableRows.flyway_schema_history == 27 and .mysql.expectedTableRows.outbox == 0 and
    .mysql.expectedTableRows.accommodation_inventory_day == 0 and
    all(.mysql.expectedTableRows | to_entries[]; (.key | test("^[a-z][a-z0-9_]{0,63}$")) and (.value | type == "number" and floor == . and . >= 0)) and
    (.couponPreparation | type == "array") and (.kafka.topics | type == "array" and length == 12) and
    (.search |
      if .enabled == false then
        exact(["enabled"])
      elif .enabled == true then
        exact(["enabled","snapshotReferenceKey","repository","elasticsearchVersion","imageDigest",
          "requiredPlugins","logicalAlias","snapshotIndex","documentCount","mappingSha256",
          "databaseAccommodationIdsSha256","elasticsearchAccommodationIdsSha256",
          "databaseDocumentIdentityPairsSha256","elasticsearchDocumentIdentityPairsSha256",
          "contentFingerprintSha256"]) and
        .snapshotReferenceKey == "elasticsearch/snapshot-reference.json" and
        .repository == "airbob-dataset-readonly" and
        (.elasticsearchVersion | type == "string" and length > 0) and
        (.imageDigest | type == "string" and test("^sha256:[0-9a-f]{64}$")) and
        .requiredPlugins == ["analysis-nori","repository-s3"] and
        .logicalAlias == "accommodations" and
        (.snapshotIndex | type == "string" and test("^[a-z0-9][a-z0-9._-]{2,254}$")) and
        (.documentCount | type == "number" and floor == . and . >= 0) and
        all([.mappingSha256,.databaseAccommodationIdsSha256,
          .elasticsearchAccommodationIdsSha256,.databaseDocumentIdentityPairsSha256,
          .elasticsearchDocumentIdentityPairsSha256,.contentFingerprintSha256][]; sha) and
        .databaseAccommodationIdsSha256 == .elasticsearchAccommodationIdsSha256 and
        .databaseDocumentIdentityPairsSha256 == .elasticsearchDocumentIdentityPairsSha256
      else false end)
  )
' "$manifest" >/dev/null || fail 'wrapper does not satisfy the fixed v2 envelope'
scan_sensitive_json "$manifest" \
  || fail 'wrapper contains secret-like keys, credentials, or unapproved identity material'

for key_contract in \
  'legacyBenchmarkManifestKey:benchmark/manifest.json' \
  'benchmarkDatasetManifestKey:benchmark/dataset-manifest.json' \
  'validatorKey:benchmark/validate-benchmark-dataset-v2.jq' \
  'calibrationKey:benchmark/source-calibration-v1.json' \
  "productionSpecKey:$production_spec_key" \
  'generationQualificationKey:benchmark/generation-qualification-v1.json' \
  'databaseFingerprintKey:mysql/database-fingerprint.tsv' \
  'attestationKey:attestation/restore.json'; do
  key=${key_contract%%:*}
  expected=${key_contract#*:}
  [[ "$(jq -r ".source.$key" "$manifest")" == "$expected" ]] || fail "wrapper key drift: $key"
done

require_sha "$legacy_manifest" "$(jq -r '.source.legacyBenchmarkManifestSha256' "$manifest")"
require_sha "$dataset_manifest" "$(jq -r '.source.benchmarkDatasetManifestSha256' "$manifest")"
require_sha "$validator" "$(jq -r '.source.validatorSha256' "$manifest")"
trusted_validator=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)/validate-benchmark-dataset-v2.jq
if [[ -f "$trusted_validator" && ! -L "$trusted_validator" ]]; then
  [[ "$(sha256_file "$validator")" == "$(sha256_file "$trusted_validator")" ]] \
    || fail 'validator payload does not match the trusted release-builder contract'
fi
require_sha "$calibration" "$(jq -r '.source.calibrationSha256' "$manifest")"
require_sha "$production_spec" "$(jq -r '.source.productionSpecSha256' "$manifest")"
require_sha "$qualification" "$(jq -r '.source.generationQualificationSha256' "$manifest")"
require_sha "$database_fingerprint" "$(jq -r '.source.databaseFingerprintSha256' "$manifest")"
require_sha "$attestation" "$(jq -r '.source.attestationSha256' "$manifest")"

jq -e '
  .releaseTuple.manifestSha256 == .source.benchmarkDatasetManifestSha256 and
  .releaseTuple.validatorSha256 == .source.validatorSha256 and
  .releaseTuple.calibrationSha256 == .source.calibrationSha256 and
  .releaseTuple.specSha256 == .source.productionSpecSha256 and
  .releaseTuple.qualificationSha256 == .source.generationQualificationSha256 and
  .releaseTuple.databaseFingerprintSha256 == .source.databaseFingerprintSha256 and
  .releaseTuple.attestationSha256 == .source.attestationSha256 and
  .releaseTuple.dumpSha256 == .mysql.dumpSha256 and
  .releaseTuple.migrationChecksumSha256 == .mysql.migrationChecksumSha256 and
  .releaseTuple.schemaFingerprintSha256 == .mysql.schemaFingerprintSha256
' "$manifest" >/dev/null || fail 'release tuple contradicts wrapper artifact bindings'

# Execute the downloaded validator only after its digest is verified.
jq -e -f "$validator" "$dataset_manifest" >/dev/null || fail 'benchmark-dataset-v2 semantic validation failed'

scan_sensitive_file "$calibration" || fail 'source calibration contains unapproved identity, prose, email, or secret material'
jq -e --argjson budgets "$expected_budgets" '
  .calibrationVersion=="source-calibration-v1" and .catalogVersion=="source-catalog-v1" and (.inventorySha256|test("^[0-9a-f]{64}$")) and
  (.sourceInventory|type=="array" and length>0 and all(.[];
    (.canonicalPath|type=="string" and length>0 and (startswith("/")|not) and (test("(^|/)\\.\\.(/|$)")|not)) and
    (.byteSize|type=="number" and floor==. and .>=0) and (.sha256|test("^[0-9a-f]{64}$")) and
    (.role=="LISTINGS" or .role=="REVIEWS" or .role=="AMENITIES"))) and
  ([.sourceInventory[].canonicalPath]|unique|length)==(.sourceInventory|length) and
  (.cohorts|type=="array" and length>0) and
  .aggregate.counts.uniqueListings >= $budgets.accommodations and
  .syntheticReviewTemplatePolicy.reviewerIdentityPolicy=="EXCLUDED" and .syntheticReviewTemplatePolicy.reviewProsePolicy=="EXCLUDED" and
  .syntheticReviewTemplatePolicy.templatePolicy=="VERSIONED_COHORT_TEMPLATE" and
  ([paths as $p|($p[-1]|tostring|ascii_downcase)|select(.!="revieweridentitypolicy" and .!="reviewprosepolicy")|select(test("reviewer(name|id)|comments|rawreview|raw-review"))]|length==0)
' "$calibration" >/dev/null || fail 'source calibration aggregate-only contract failed'
jq -e --arg profile "$profile_version" --argjson budgets "$expected_budgets" '
  .profileVersion==$profile and .provenance.generatorVersion=="production-skew-generator-v1" and
  .provenance.prngAlgorithm=="sha256-splitmix64-counter-v1" and
  .provenance.seedDerivation=="length-prefixed(profile-version, global-seed, relation-domain, stable-external-key, counter)" and
  .provenance.globalSeed==20260826 and .provenance.anchor=="2026-07-31T15:00:00Z" and .provenance.timezone=="Asia/Seoul" and
  (.targets|{accommodations:.accommodations.rowBudget,members:.members.rowBudget,reservations:.reservations.rowBudget,reviews:.reviews.rowBudget,activeWishlists:.activeWishlists.rowBudget,wishlistLinks:.wishlistLinks.rowBudget})==$budgets and
  ([.targets[]|select(.rowBudget!=null)|.tolerance]|all(.absoluteRows==0 and .relativePercent==0))
' "$production_spec" >/dev/null || fail 'tracked production-skew specification contract failed'
jq -e --argjson budgets "$expected_budgets" '
  (keys|sort)==["canonicalScale","configuredBatchSize","configuredLimits","generatedBudgets","jvmMaxHeapBytes","retainedMaxima","version"] and
  .version=="generation-qualification-v1" and .canonicalScale==true and .configuredBatchSize==1000 and .jvmMaxHeapBytes==12884901888 and
  .generatedBudgets==$budgets and
  .configuredLimits=={completedStayCandidates:30000,completedStays:1000,paymentTransactions:1000,payments:1000,reservations:1000,reviews:30000,wishlistLinks:100000,wishlists:1000} and
  (.retainedMaxima|keys|sort)==(.configuredLimits|keys|sort) and
  (.configuredLimits as $l|[.retainedMaxima|to_entries[]|(.value|type=="number" and floor==. and .>0) and (.value<=$l[.key])]|all)
' "$qualification" >/dev/null || fail 'generation qualification receipt contract failed'

jq -e --arg profile "$profile_version" --argjson budgets "$expected_budgets" --slurpfile wrapper "$manifest" --slurpfile calibration "$calibration" --slurpfile spec "$production_spec" --slurpfile qualification "$qualification" '
  .datasetVersion == "benchmark-dataset-v2" and .schemaVersion == 2 and .world.version == "world-v2" and
  .world.flywayVersion == 27 and .world.tableRows.accommodation_inventory_day == 0 and
  .world.provenance.calibrationVersion == "source-calibration-v1" and
  .world.provenance.profileVersion == $profile and
  .world.provenance.generatorVersion == "production-skew-generator-v1" and
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
  }) as $finalRows | all($finalRows | to_entries[]; .value >= $budgets[.key])) and
  .world.provenance.calibrationSha256 == $wrapper[0].releaseTuple.calibrationSha256 and
  .world.provenance.specSha256 == $wrapper[0].releaseTuple.specSha256 and
  .world.provenance.sourceInventorySha256 == $wrapper[0].source.sourceInventorySha256 and
  .world.fingerprints["final-world"] == $wrapper[0].releaseTuple.finalWorldFingerprintSha256 and
  .world.fingerprints["base-world"] == $wrapper[0].releaseTuple.baseWorldFingerprintSha256 and
  .world.fingerprints["final-inventory"] == $wrapper[0].releaseTuple.inventoryFingerprintSha256 and
  .world.provenance.assertionSha256 == $wrapper[0].releaseTuple.distributionFingerprintSha256 and
  .targetFingerprint == $wrapper[0].releaseTuple.targetFingerprintSha256 and
  ($calibration[0].calibrationVersion == "source-calibration-v1") and
  ($calibration[0].inventorySha256 == .world.provenance.sourceInventorySha256) and
  ($spec[0].profileVersion == $profile) and
  ($spec[0].provenance.generatorVersion == "production-skew-generator-v1") and
  ($qualification[0].version == "generation-qualification-v1") and
  ($qualification[0].canonicalScale == true) and
  ($qualification[0].configuredBatchSize == 1000) and
  ($qualification[0].jvmMaxHeapBytes == 12884901888)
' "$dataset_manifest" >/dev/null || fail 'v2 semantic artifacts contradict the release tuple'

jq -e --slurpfile wrapper "$manifest" --slurpfile dataset "$dataset_manifest" '
  def sha: type == "string" and test("^[0-9a-f]{64}$");
  (keys | sort) == ([
    "schemaVersion","sourceReleasePayloadSha256","sourceDumpSha256","restoredDumpSha256",
    "databaseRestoreMethod","sourceDatabaseFingerprintSha256","sourceEtlCommit","databaseServerUuid",
    "verifierContractInventorySha256","databaseFingerprintSha256","verificationOutputSha256",
    "finalWorldFingerprintSha256","baseWorldFingerprintSha256","distributionEvidenceSha256",
    "distributionAssertionSha256","distributionSpecSha256",
    "targetFingerprintSha256","inventoryFingerprintSha256","flywayVersion","flywayHistoryRows",
    "migrationChecksumSha256","schemaFingerprintSha256","outboxState","expectedTableRows","capturedAt"
  ] | sort) and
  .schemaVersion == 4 and .databaseRestoreMethod == "gzip-to-empty-airbobdb-v2" and
  .sourceReleasePayloadSha256 == $wrapper[0].source.canonicalPayloadSha256 and
  .sourceDumpSha256 == .restoredDumpSha256 and
  .sourceDatabaseFingerprintSha256 == $wrapper[0].source.databaseFingerprintSha256 and
  .sourceEtlCommit == $wrapper[0].source.etlCommit and
  .databaseFingerprintSha256 == $wrapper[0].source.databaseFingerprintSha256 and
  .finalWorldFingerprintSha256 == $wrapper[0].releaseTuple.finalWorldFingerprintSha256 and
  .baseWorldFingerprintSha256 == $wrapper[0].releaseTuple.baseWorldFingerprintSha256 and
  .distributionAssertionSha256 == $wrapper[0].releaseTuple.distributionFingerprintSha256 and
  .distributionAssertionSha256 == $dataset[0].world.provenance.assertionSha256 and
  .distributionSpecSha256 == $wrapper[0].releaseTuple.specSha256 and
  .distributionSpecSha256 == $dataset[0].world.provenance.specSha256 and
  .targetFingerprintSha256 == $wrapper[0].releaseTuple.targetFingerprintSha256 and
  .inventoryFingerprintSha256 == $wrapper[0].releaseTuple.inventoryFingerprintSha256 and
  (.verifierContractInventorySha256 | sha) and (.verificationOutputSha256 | sha) and
  (.distributionEvidenceSha256 | sha) and (.distributionAssertionSha256 | sha) and
  (.distributionSpecSha256 | sha) and .flywayVersion == "27" and .flywayHistoryRows == 27 and
  .migrationChecksumSha256 == $wrapper[0].mysql.migrationChecksumSha256 and
  .schemaFingerprintSha256 == $wrapper[0].mysql.schemaFingerprintSha256 and
  .outboxState == "empty" and .expectedTableRows == $wrapper[0].mysql.expectedTableRows and
  all($dataset[0].world.tableRows | to_entries[]; . as $entry | $wrapper[0].mysql.expectedTableRows[$entry.key] == $entry.value)
' "$attestation" >/dev/null || fail 'restore attestation does not bind the v2 release tuple'

jq -e '
  .datasetVersion == "nplus1-v1" and .requiredRows == (.maxRequestedSize + 1) and
  .review.publishedReviewCount >= .requiredRows and .review.reviewsWithImages == .requiredRows and
  .hostAccommodations.expectedRows == .requiredRows and .guestReservations.expectedRows == .requiredRows and
  .hostReservations.expectedRows == .requiredRows and .wishlists.expectedRows == .requiredRows and
  ([.. | objects | keys[]] | all(test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not)) and
  ([.. | strings] | all(test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not))
' "$legacy_manifest" >/dev/null || fail 'standalone nplus1-v1 manifest contract failed'

for fingerprint_contract in \
  "dataset_final_world_fingerprint:$(jq -r '.releaseTuple.finalWorldFingerprintSha256' "$manifest")" \
  "dataset_base_world_fingerprint:$(jq -r '.releaseTuple.baseWorldFingerprintSha256' "$manifest")" \
  "dataset_distribution_fingerprint:$(jq -r '.releaseTuple.distributionFingerprintSha256' "$manifest")" \
  "dataset_target_fingerprint:$(jq -r '.releaseTuple.targetFingerprintSha256' "$manifest")" \
  "dataset_inventory_fingerprint:$(jq -r '.releaseTuple.inventoryFingerprintSha256' "$manifest")"; do
  metric=${fingerprint_contract%%:*}
  expected=${fingerprint_contract#*:}
  [[ "$(awk -F '\t' -v target="$metric" '$1 == target { count++; value=$2 } END { if (count == 1) print value }' "$database_fingerprint")" == "$expected" ]] \
    || fail "database fingerprint tuple metric mismatch: $metric"
done
awk -F '\t' '
  NF != 2 || $1 !~ /^[a-z][a-z0-9_.-]*$/ { exit 1 }
  seen[$1]++ { exit 1 }
  $1 ~ /^(review_summary_(missing|stale|extra|symmetric_mismatch)|wishlist_(accommodation_count|representative|denormalized_symmetric)_mismatch|daily_revenue_stats_(missing|stale|extra|symmetric_mismatch))_count$/ && $2 != "0" { exit 1 }
  $1 == "world_accommodation_inventory_day_row_count" && $2 != "0" { exit 1 }
  $1 == "accommodation_inventory_day_row_count" && $2 != "0" { exit 1 }
  $1 == "orphan_total" && $2 != "0" { exit 1 }
' "$database_fingerprint" || fail 'database fingerprint integrity metrics are malformed or nonzero'

search_enabled=$(jq -r '.search.enabled' "$manifest")
if [[ "$search_enabled" == true ]]; then
  snapshot_reference="$release_dir/elasticsearch/snapshot-reference.json"
  require_regular_file "$snapshot_reference"
  jq -e --slurpfile wrapper "$manifest" '
    .schemaVersion == 2 and .repository == $wrapper[0].search.repository and
    .basePath == ("elasticsearch/releases/" + $wrapper[0].datasetRelease) and
    .logicalAlias == $wrapper[0].search.logicalAlias and .snapshotIndex == $wrapper[0].search.snapshotIndex and
    .elasticsearchVersion == $wrapper[0].search.elasticsearchVersion and
    .imageDigest == $wrapper[0].search.imageDigest and .documentCount == $wrapper[0].search.documentCount and
    .mappingSha256 == $wrapper[0].search.mappingSha256 and
    .dbIdsSha256 == .esIdsSha256 and .dbDocumentIdentityPairsSha256 == .esDocumentIdentityPairsSha256 and
    .contentFingerprintSha256 == $wrapper[0].search.contentFingerprintSha256
  ' "$snapshot_reference" >/dev/null || fail 'Elasticsearch snapshot reference contradicts wrapper'
else
  [[ "$expected_kind" == pipeline-rehearsal && ! -e "$release_dir/elasticsearch/snapshot-reference.json" ]] \
    || fail 'disabled search contract is inconsistent'
fi

expected_dump_sha=$(jq -r '.mysql.dumpSha256' "$manifest")
[[ "$(cat "$checksum")" == "$expected_dump_sha  airbob.sql.zst" ]] || fail 'dataset dump checksum file is not canonical'
if [[ "$metadata_only" == false ]]; then
  require_sha "$dump" "$expected_dump_sha"
fi

if [[ "$metadata_only" == false ]]; then
  actual_inventory=$(CDPATH= cd -P -- "$release_dir" && find . -mindepth 1 -print | sort)
  expected_inventory=(
    './attestation' './attestation/restore.json' './benchmark'
    './benchmark/dataset-manifest.json' './benchmark/generation-qualification-v1.json'
    './benchmark/manifest.json' "./$production_spec_key"
    './benchmark/source-calibration-v1.json' './benchmark/validate-benchmark-dataset-v2.jq'
  )
  [[ "$search_enabled" != true ]] || expected_inventory+=('./elasticsearch' './elasticsearch/snapshot-reference.json')
  expected_inventory+=(
    './manifest.json' './mysql' './mysql/airbob.sql.zst'
    './mysql/database-fingerprint.tsv' './mysql/sha256.txt'
  )
  expected_inventory_text=$(printf '%s\n' "${expected_inventory[@]}" | sort)
  [[ "$actual_inventory" == "$expected_inventory_text" ]] || fail 'dataset release inventory is not exact'
fi

printf '%s\n' 'dataset release verified'
