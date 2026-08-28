#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

validate_document_identity_pairs() {
  local database_pairs=$1
  local elasticsearch_pairs=$2
  local expected_database_sha=$3
  local expected_elasticsearch_sha=$4
  local expected_count=$5
  local actual_database_sha actual_elasticsearch_sha
  local actual_database_count actual_elasticsearch_count

  [[ -f "$database_pairs" && -f "$elasticsearch_pairs" \
    && "$expected_database_sha" =~ ^[0-9a-f]{64}$ \
    && "$expected_elasticsearch_sha" =~ ^[0-9a-f]{64}$ \
    && "$expected_count" =~ ^[0-9]+$ ]] || return 1
  actual_database_count=$(wc -l < "$database_pairs" | tr -d '[:space:]')
  actual_elasticsearch_count=$(wc -l < "$elasticsearch_pairs" | tr -d '[:space:]')
  actual_database_sha=$(sha256sum "$database_pairs" | awk '{print $1}')
  actual_elasticsearch_sha=$(sha256sum "$elasticsearch_pairs" | awk '{print $1}')
  [[ "$actual_database_count" == "$expected_count" \
    && "$actual_elasticsearch_count" == "$expected_count" \
    && "$actual_database_sha" == "$expected_database_sha" \
    && "$actual_elasticsearch_sha" == "$expected_elasticsearch_sha" \
    && "$actual_database_sha" == "$actual_elasticsearch_sha" ]]
}

required_environment=(
  AIRBOB_REGION AIRBOB_RUN_ID AIRBOB_DATASET_BUCKET AIRBOB_EVIDENCE_BUCKET
  AIRBOB_DATASET_RELEASE AIRBOB_DATASET_MANIFEST_SHA256 AIRBOB_DATABASE_BOOTSTRAP
  AIRBOB_RDS_ENDPOINT AIRBOB_RDS_RESOURCE_ID AIRBOB_RDS_ENGINE_VERSION
  AIRBOB_RDS_MASTER_SECRET_ARN AIRBOB_DEBEZIUM_SECRET_ARN
  AIRBOB_ELASTICSEARCH_IMAGE_DIGEST AIRBOB_COUPON_LUA_FILE
)
for environment_name in "${required_environment[@]}"; do
  [[ -n "${!environment_name:-}" ]] || { printf 'missing bootstrap environment: %s\n' "$environment_name" >&2; exit 1; }
done
case "$AIRBOB_DATABASE_BOOTSTRAP" in dump|snapshot) ;; *) printf '%s\n' 'unsupported database bootstrap mode' >&2; exit 1 ;; esac
[[ "$AIRBOB_DATASET_RELEASE" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ ]] || { printf '%s\n' 'unsafe dataset release' >&2; exit 1; }
[[ "$AIRBOB_DATASET_MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]] || { printf '%s\n' 'unsafe dataset manifest digest' >&2; exit 1; }
[[ "$AIRBOB_ELASTICSEARCH_IMAGE_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || { printf '%s\n' 'unsafe Elasticsearch image digest' >&2; exit 1; }
[[ -f "$AIRBOB_COUPON_LUA_FILE" && ! -L "$AIRBOB_COUPON_LUA_FILE" ]] || { printf '%s\n' 'trusted coupon helper is missing' >&2; exit 1; }

command -v aws >/dev/null 2>&1 || { printf '%s\n' 'AWS CLI is required' >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { printf '%s\n' 'jq is required' >&2; exit 1; }
if ! command -v mysql >/dev/null 2>&1 || ! command -v zstd >/dev/null 2>&1 || ! command -v xxd >/dev/null 2>&1; then
  dnf install -y mariadb105 zstd vim-minimal || dnf install -y mariadb zstd vim-minimal
fi
command -v mysql >/dev/null 2>&1 && command -v zstd >/dev/null 2>&1 && command -v xxd >/dev/null 2>&1 \
  || { printf '%s\n' 'MySQL client, zstd, and xxd are required' >&2; exit 1; }
for runtime_command in curl docker openssl sha256sum; do
  command -v "$runtime_command" >/dev/null 2>&1 \
    || { printf 'required bootstrap command is unavailable: %s\n' "$runtime_command" >&2; exit 1; }
done

curl_http() {
  command curl --connect-timeout 10 --max-time 60 "$@"
}
curl_restore() {
  command curl --connect-timeout 10 --max-time 900 "$@"
}

work_root=/var/lib/airbob/data-bootstrap
release_root="$work_root/release"
secret_root="$work_root/secrets"
install -d -m 700 "$release_root/mysql" "$release_root/benchmark" "$release_root/attestation" "$secret_root"
manifest="$release_root/manifest.json"
benchmark_manifest="$release_root/benchmark/manifest.json"
benchmark_dataset_manifest="$release_root/benchmark/dataset-manifest.json"
semantic_validator="$release_root/benchmark/validate-benchmark-dataset-v2.jq"
calibration="$release_root/benchmark/source-calibration-v1.json"
production_spec=''
qualification="$release_root/benchmark/generation-qualification-v1.json"
database_fingerprint="$release_root/mysql/database-fingerprint.tsv"
attestation="$release_root/attestation/restore.json"
dump="$release_root/mysql/airbob.sql.zst"
checksum="$release_root/mysql/sha256.txt"
dataset_uri="s3://$AIRBOB_DATASET_BUCKET/datasets/$AIRBOB_DATASET_RELEASE"
master_secret_file="$secret_root/rds-master.json"
debezium_secret_file="$secret_root/debezium.json"
connector_payload="$secret_root/connector.json"
cleanup() {
  unset MYSQL_PWD master_password debezium_password
  rm -f "$master_secret_file" "$debezium_secret_file" "$connector_payload"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
cleanup

aws_cp() {
  aws --region "$AIRBOB_REGION" --cli-connect-timeout 10 --cli-read-timeout 60 \
    s3 cp "$1" "$2" --only-show-errors
}
require_sha() {
  [[ "$2" =~ ^[0-9a-f]{64}$ && -f "$1" && ! -L "$1" ]] \
    && [[ "$(sha256sum "$1" | awk '{print $1}')" == "$2" ]]
}
download_sha() {
  aws_cp "$dataset_uri/$1" "$2"
  require_sha "$2" "$3" || { printf 'dataset artifact digest mismatch: %s\n' "$1" >&2; exit 1; }
}
validate_source_calibration() {
  local calibration_file=$1 budgets_json=$2
  jq -e --argjson budgets "$budgets_json" '
    .calibrationVersion=="source-calibration-v1" and .catalogVersion=="source-catalog-v1" and
    (.inventorySha256|test("^[0-9a-f]{64}$")) and (.sourceInventory|type=="array" and length>0 and all(.[];
      (.canonicalPath|type=="string" and length>0 and (startswith("/")|not) and (test("(^|/)\\.\\.(/|$)")|not)) and
      (.byteSize|type=="number" and floor==. and .>=0) and (.sha256|test("^[0-9a-f]{64}$")) and
      (.role=="LISTINGS" or .role=="REVIEWS" or .role=="AMENITIES"))) and
    ([.sourceInventory[].canonicalPath]|unique|length)==(.sourceInventory|length) and (.cohorts|type=="array" and length>0) and
    .aggregate.counts.uniqueListings >= $budgets.accommodations and
    .syntheticReviewTemplatePolicy.reviewerIdentityPolicy=="EXCLUDED" and
    .syntheticReviewTemplatePolicy.reviewProsePolicy=="EXCLUDED" and .syntheticReviewTemplatePolicy.templatePolicy=="VERSIONED_COHORT_TEMPLATE" and
    ([paths as $p|($p[-1]|tostring|ascii_downcase)|select(.!="revieweridentitypolicy" and .!="reviewprosepolicy")|select(test("reviewer(name|id)|comments|rawreview|raw-review|raw_pii"))]|length==0) and
    ([..|strings]|all((test("@")|not) or test("^(benchmark-nplus1(-helper)?|coupon-benchmark-[0-9]{5,6}|benchmark-read-model-wishlist-(hot|median|cold|empty)|benchmark-read-model-revenue-admin)@airbob\\.cloud$|^(host|member)-[0-9a-f]+@benchmark\\.airbob\\.local$")))
  ' "$calibration_file" >/dev/null
}

# The externally supplied wrapper digest is the only trust anchor.  Accept a
# fixed shallow v2 envelope before using any value inside it.
aws_cp "$dataset_uri/manifest.json" "$manifest"
actual_manifest_sha=$(sha256sum "$manifest" | awk '{print $1}')
[[ "$actual_manifest_sha" == "$AIRBOB_DATASET_MANIFEST_SHA256" ]] \
  || { printf '%s\n' 'dataset manifest digest mismatch' >&2; exit 1; }
profile_version=$(jq -er '.releaseTuple.profileVersion | select(type=="string")' "$manifest") \
  || { printf '%s\n' 'dataset production profile is missing' >&2; exit 1; }
case "$profile_version" in
  production-skew-v1)
    production_spec_name=production-skew-v1.json
    expected_budgets='{"accommodations":50000,"activeWishlists":400000,"members":200000,"reservations":2500000,"reviews":1000000,"wishlistLinks":1500000}'
    ;;
  production-skew-large-v1)
    production_spec_name=production-skew-large-v1.json
    expected_budgets='{"accommodations":200000,"activeWishlists":1600000,"members":800000,"reservations":10000000,"reviews":4000000,"wishlistLinks":6000000}'
    ;;
  *) printf '%s\n' 'dataset production profile is unsupported' >&2; exit 1 ;;
esac
production_spec_key="benchmark/$production_spec_name"
production_spec="$release_root/$production_spec_key"
jq -e --arg release "$AIRBOB_DATASET_RELEASE" --arg profile "$profile_version" --arg specKey "$production_spec_key" '
  def sha: type=="string" and test("^[0-9a-f]{64}$");
  def exact($wanted): type=="object" and keys==($wanted|sort);
  .schemaVersion==2 and .datasetRelease==$release and .releaseKind=="pipeline-rehearsal" and
  (keys)==["couponPreparation","datasetRelease","datasetRunId","kafka","mysql","releaseKind","releaseTuple","schemaVersion","search","source"] and
  (.releaseTuple|keys)==["attestationSha256","baseWorldFingerprintSha256","calibrationSha256","calibrationVersion","databaseFingerprintSha256","datasetVersion","distributionFingerprintSha256","dumpSha256","finalWorldFingerprintSha256","generatorVersion","inventoryFingerprintSha256","manifestSha256","migrationChecksumSha256","profileVersion","qualificationSha256","schemaFingerprintSha256","specSha256","targetFingerprintSha256","validatorSha256","worldVersion"] and
  .releaseTuple.datasetVersion=="benchmark-dataset-v2" and .releaseTuple.worldVersion=="world-v2" and
  .releaseTuple.calibrationVersion=="source-calibration-v1" and .releaseTuple.profileVersion==$profile and
  .releaseTuple.generatorVersion=="production-skew-generator-v1" and
  all(.releaseTuple|to_entries[]|select(.key|endswith("Sha256"));.value|sha) and
  .source.datasetVersion=="benchmark-dataset-v2" and .source.worldVersion=="world-v2" and
  .source.benchmarkDatasetManifestKey=="benchmark/dataset-manifest.json" and
  .source.validatorKey=="benchmark/validate-benchmark-dataset-v2.jq" and
  .source.calibrationKey=="benchmark/source-calibration-v1.json" and
  .source.productionSpecKey==$specKey and
  .source.generationQualificationKey=="benchmark/generation-qualification-v1.json" and
  .source.databaseFingerprintKey=="mysql/database-fingerprint.tsv" and
  .source.attestationKey=="attestation/restore.json" and
  .mysql.dumpKey=="mysql/airbob.sql.zst" and .mysql.dumpSha256==.releaseTuple.dumpSha256 and .mysql.outboxPolicy=="absent" and
  .source.benchmarkDatasetManifestSha256==.releaseTuple.manifestSha256 and
  .source.validatorSha256==.releaseTuple.validatorSha256 and
  .source.calibrationSha256==.releaseTuple.calibrationSha256 and
  .source.productionSpecSha256==.releaseTuple.specSha256 and
  .source.generationQualificationSha256==.releaseTuple.qualificationSha256 and
  .source.databaseFingerprintSha256==.releaseTuple.databaseFingerprintSha256 and
  .source.attestationSha256==.releaseTuple.attestationSha256 and
  (.search |
    if .enabled==false then exact(["enabled"])
    elif .enabled==true then
      exact(["enabled","snapshotReferenceKey","repository","elasticsearchVersion","imageDigest",
        "requiredPlugins","logicalAlias","snapshotIndex","documentCount","mappingSha256",
        "databaseAccommodationIdsSha256","elasticsearchAccommodationIdsSha256",
        "databaseDocumentIdentityPairsSha256","elasticsearchDocumentIdentityPairsSha256",
        "contentFingerprintSha256"]) and
      .snapshotReferenceKey=="elasticsearch/snapshot-reference.json" and
      .repository=="airbob-dataset-readonly" and
      (.elasticsearchVersion|type=="string" and length>0) and
      (.imageDigest|type=="string" and test("^sha256:[0-9a-f]{64}$")) and
      .requiredPlugins==["analysis-nori","repository-s3"] and
      .logicalAlias=="accommodations" and
      (.snapshotIndex|type=="string" and test("^[a-z0-9][a-z0-9._-]{2,254}$")) and
      (.documentCount|type=="number" and floor==. and .>=0) and
      all([.mappingSha256,.databaseAccommodationIdsSha256,
        .elasticsearchAccommodationIdsSha256,.databaseDocumentIdentityPairsSha256,
        .elasticsearchDocumentIdentityPairsSha256,.contentFingerprintSha256][];sha) and
      .databaseAccommodationIdsSha256==.elasticsearchAccommodationIdsSha256 and
      .databaseDocumentIdentityPairsSha256==.elasticsearchDocumentIdentityPairsSha256
    else false end)
' "$manifest" >/dev/null || { printf '%s\n' 'dataset wrapper does not satisfy the fixed v2 envelope' >&2; exit 1; }
jq -e '
  def normalized: gsub("[^A-Za-z0-9]";"")|ascii_downcase;
  def sensitive_key:
    normalized as $key
    | ["password","passwd","secret","credential","authorization","token","sessionid",
       "cookie","apikey","accesskey","privatekey","serviceaccount","rawpii"]
    | any(. as $fragment|$key|contains($fragment));
  ([paths as $path
    | select(($path[-1]|type)=="string" and ($path[-1]|sensitive_key))]|length)==0 and
  ([..|strings
    | select(test("-----BEGIN[[:space:]].*PRIVATE KEY-----|(AKIA|ASIA)[0-9A-Z]{16}|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}";"i"))]
    | length)==0
' "$manifest" >/dev/null \
  || { printf '%s\n' 'wrapper contains secret-like keys, credentials, or unapproved identity material' >&2; exit 1; }
release_kind=$(jq -r '.releaseKind' "$manifest")
search_enabled=$(jq -r '.search.enabled' "$manifest")

download_sha benchmark/manifest.json "$benchmark_manifest" "$(jq -r '.source.legacyBenchmarkManifestSha256' "$manifest")"
download_sha benchmark/dataset-manifest.json "$benchmark_dataset_manifest" "$(jq -r '.releaseTuple.manifestSha256' "$manifest")"
download_sha benchmark/validate-benchmark-dataset-v2.jq "$semantic_validator" "$(jq -r '.releaseTuple.validatorSha256' "$manifest")"
download_sha benchmark/source-calibration-v1.json "$calibration" "$(jq -r '.releaseTuple.calibrationSha256' "$manifest")"
download_sha "$production_spec_key" "$production_spec" "$(jq -r '.releaseTuple.specSha256' "$manifest")"
download_sha benchmark/generation-qualification-v1.json "$qualification" "$(jq -r '.releaseTuple.qualificationSha256' "$manifest")"
download_sha mysql/database-fingerprint.tsv "$database_fingerprint" "$(jq -r '.releaseTuple.databaseFingerprintSha256' "$manifest")"
download_sha attestation/restore.json "$attestation" "$(jq -r '.releaseTuple.attestationSha256' "$manifest")"

# Only execute the release validator after its bytes are bound to the trusted wrapper.
jq -e -f "$semantic_validator" "$benchmark_dataset_manifest" >/dev/null \
  || { printf '%s\n' 'benchmark-dataset-v2 semantic validation failed' >&2; exit 1; }
validate_source_calibration "$calibration" "$expected_budgets" \
  || { printf '%s\n' 'source calibration aggregate-only contract failed' >&2; exit 1; }
jq -e --arg profile "$profile_version" --argjson budgets "$expected_budgets" '
  .profileVersion==$profile and .provenance.generatorVersion=="production-skew-generator-v1" and
  .provenance.prngAlgorithm=="sha256-splitmix64-counter-v1" and .provenance.seedDerivation=="length-prefixed(profile-version, global-seed, relation-domain, stable-external-key, counter)" and
  .provenance.globalSeed==20260826 and .provenance.anchor=="2026-07-31T15:00:00Z" and .provenance.timezone=="Asia/Seoul" and
  (.targets|{accommodations:.accommodations.rowBudget,members:.members.rowBudget,reservations:.reservations.rowBudget,reviews:.reviews.rowBudget,activeWishlists:.activeWishlists.rowBudget,wishlistLinks:.wishlistLinks.rowBudget})==$budgets and
  ([.targets[]|select(.rowBudget!=null)|.tolerance]|all(.absoluteRows==0 and .relativePercent==0))
' "$production_spec" >/dev/null || { printf '%s\n' 'tracked production-skew specification contract failed' >&2; exit 1; }
jq -e --argjson budgets "$expected_budgets" '
  (keys|sort)==["canonicalScale","configuredBatchSize","configuredLimits","generatedBudgets","jvmMaxHeapBytes","retainedMaxima","version"] and
  .version=="generation-qualification-v1" and .canonicalScale==true and .configuredBatchSize==1000 and .jvmMaxHeapBytes==12884901888 and
  .generatedBudgets==$budgets and
  .configuredLimits=={completedStayCandidates:30000,completedStays:1000,paymentTransactions:1000,payments:1000,reservations:1000,reviews:30000,wishlistLinks:100000,wishlists:1000} and
  (.retainedMaxima|keys|sort)==(.configuredLimits|keys|sort) and (.configuredLimits as $l|[.retainedMaxima|to_entries[]|(.value|type=="number" and floor==. and .>0) and (.value<=$l[.key])]|all)
' "$qualification" >/dev/null || { printf '%s\n' 'generation qualification receipt contract failed' >&2; exit 1; }
jq -e --arg profile "$profile_version" --argjson budgets "$expected_budgets" --slurpfile w "$manifest" --slurpfile c "$calibration" --slurpfile s "$production_spec" --slurpfile q "$qualification" --slurpfile a "$attestation" '
  .datasetVersion=="benchmark-dataset-v2" and .world.version=="world-v2" and
  ($a[0]|keys|sort)==(["schemaVersion","sourceReleasePayloadSha256","sourceDumpSha256","restoredDumpSha256","databaseRestoreMethod","sourceDatabaseFingerprintSha256","sourceEtlCommit","databaseServerUuid","verifierContractInventorySha256","databaseFingerprintSha256","verificationOutputSha256","finalWorldFingerprintSha256","baseWorldFingerprintSha256","distributionEvidenceSha256","distributionAssertionSha256","distributionSpecSha256","targetFingerprintSha256","inventoryFingerprintSha256","flywayVersion","flywayHistoryRows","migrationChecksumSha256","schemaFingerprintSha256","outboxState","expectedTableRows","capturedAt"]|sort) and
  .world.provenance.calibrationSha256==$w[0].releaseTuple.calibrationSha256 and
  .world.provenance.specSha256==$w[0].releaseTuple.specSha256 and
  .world.fingerprints["final-world"]==$w[0].releaseTuple.finalWorldFingerprintSha256 and
  .world.fingerprints["base-world"]==$w[0].releaseTuple.baseWorldFingerprintSha256 and
  .world.fingerprints["final-inventory"]==$w[0].releaseTuple.inventoryFingerprintSha256 and
  .world.provenance.assertionSha256==$w[0].releaseTuple.distributionFingerprintSha256 and
  .targetFingerprint==$w[0].releaseTuple.targetFingerprintSha256 and
  $c[0].calibrationVersion=="source-calibration-v1" and $c[0].inventorySha256==.world.provenance.sourceInventorySha256 and
  .world.provenance.profileVersion==$profile and
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
  $s[0].profileVersion==$profile and $s[0].provenance.generatorVersion=="production-skew-generator-v1" and
  $q[0].version=="generation-qualification-v1" and $q[0].canonicalScale==true and
  $a[0].schemaVersion==4 and $a[0].sourceDumpSha256==$a[0].restoredDumpSha256 and
  $a[0].databaseFingerprintSha256==$w[0].releaseTuple.databaseFingerprintSha256 and
  $a[0].finalWorldFingerprintSha256==$w[0].releaseTuple.finalWorldFingerprintSha256 and
  $a[0].baseWorldFingerprintSha256==$w[0].releaseTuple.baseWorldFingerprintSha256 and
  $a[0].distributionAssertionSha256==$w[0].releaseTuple.distributionFingerprintSha256 and
  $a[0].distributionAssertionSha256==.world.provenance.assertionSha256 and
  $a[0].distributionSpecSha256==$w[0].releaseTuple.specSha256 and
  $a[0].distributionSpecSha256==.world.provenance.specSha256 and
  $a[0].targetFingerprintSha256==$w[0].releaseTuple.targetFingerprintSha256 and
  $a[0].inventoryFingerprintSha256==$w[0].releaseTuple.inventoryFingerprintSha256 and
  $a[0].expectedTableRows==$w[0].mysql.expectedTableRows
' "$benchmark_dataset_manifest" >/dev/null || { printf '%s\n' 'semantic artifacts contradict the release tuple' >&2; exit 1; }

aws_cp "$dataset_uri/mysql/sha256.txt" "$checksum"
[[ "$(cat "$checksum")" == "$(jq -r '.releaseTuple.dumpSha256' "$manifest")  airbob.sql.zst" ]] \
  || { printf '%s\n' 'dataset dump checksum contract mismatch' >&2; exit 1; }
if [[ "$AIRBOB_DATABASE_BOOTSTRAP" == dump ]]; then
  download_sha mysql/airbob.sql.zst "$dump" "$(jq -r '.releaseTuple.dumpSha256' "$manifest")"
fi
if [[ "$search_enabled" == true ]]; then
  install -d -m 700 "$release_root/elasticsearch"
  aws_cp "$dataset_uri/elasticsearch/snapshot-reference.json" "$release_root/elasticsearch/snapshot-reference.json"
fi
[[ "$(jq -r '.search.imageDigest // empty' "$manifest")" == "$AIRBOB_ELASTICSEARCH_IMAGE_DIGEST" || "$search_enabled" == false ]] \
  || { printf '%s\n' 'dataset Elasticsearch image digest mismatch' >&2; exit 1; }

aws --region "$AIRBOB_REGION" secretsmanager get-secret-value \
  --secret-id "$AIRBOB_RDS_MASTER_SECRET_ARN" --query SecretString --output text > "$master_secret_file"
chmod 600 "$master_secret_file"
master_username=$(jq -r '.username' "$master_secret_file")
master_password=$(jq -r '.password' "$master_secret_file")
[[ "$master_username" =~ ^[a-zA-Z][a-zA-Z0-9_]{0,31}$ && -n "$master_password" ]] \
  || { printf '%s\n' 'RDS managed secret has an invalid contract' >&2; exit 1; }

mysql_exec() {
  MYSQL_PWD="$master_password" mysql \
    --protocol=TCP --default-character-set=utf8mb4 --host="$AIRBOB_RDS_ENDPOINT" --port=3306 --user="$master_username" \
    --ssl --batch --raw --skip-column-names "$@"
}
for attempt in $(seq 1 120); do
  if mysql_exec --execute='SELECT 1' >/dev/null 2>&1; then
    break
  fi
  [[ "$attempt" -lt 120 ]] || { printf '%s\n' 'RDS did not become ready' >&2; exit 1; }
  sleep 10
done

if [[ "$AIRBOB_DATABASE_BOOTSTRAP" == dump ]]; then
  zstd --decompress --stdout "$dump" | mysql_exec airbobdb >/dev/null
fi

for variable_contract in 'binlog_format:ROW' 'binlog_row_image:FULL' 'performance_schema:ON'; do
  variable_name=${variable_contract%%:*}
  expected_value=${variable_contract#*:}
  actual_value=$(mysql_exec --execute="SHOW GLOBAL VARIABLES LIKE '$variable_name'" | awk '{print $2}')
  [[ "$actual_value" =~ ^($expected_value)$ ]] || { printf 'RDS variable contract failed: %s\n' "$variable_name" >&2; exit 1; }
done
time_zone=$(mysql_exec --execute="SHOW GLOBAL VARIABLES LIKE 'time_zone'" | awk '{print $2}')
[[ "$time_zone" == UTC || "$time_zone" == +00:00 ]] \
  || { printf '%s\n' 'RDS timezone contract failed' >&2; exit 1; }

outbox_count=$(mysql_exec airbobdb --execute='SELECT COUNT(*) FROM outbox')
[[ "$outbox_count" == 0 ]] || { printf '%s\n' 'dataset outbox is not empty' >&2; exit 1; }

flyway_version=$(mysql_exec airbobdb --execute='SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1')
[[ "$flyway_version" == "$(jq -r '.mysql.flywayVersion' "$manifest")" ]] \
  || { printf '%s\n' 'restored Flyway lineage does not match the dataset release' >&2; exit 1; }
migration_file="$work_root/flyway-migrations.tsv"
mysql_exec airbobdb --execute="
  SELECT installed_rank, COALESCE(version, '<NULL>'), description, type, script,
         COALESCE(checksum, '<NULL>'), success
  FROM flyway_schema_history
  ORDER BY installed_rank;
" > "$migration_file"
migration_checksum=$(sha256sum "$migration_file" | awk '{print $1}')
[[ "$migration_checksum" == "$(jq -r '.mysql.migrationChecksumSha256' "$manifest")" ]] \
  || { printf '%s\n' 'Flyway migration checksum does not match the dataset release' >&2; exit 1; }

while IFS=$'\t' read -r table_name expected_rows; do
  [[ "$table_name" =~ ^[a-z][a-z0-9_]{0,63}$ && "$expected_rows" =~ ^[0-9]+$ ]] \
    || { printf '%s\n' 'unsafe expected table-row contract' >&2; exit 1; }
  actual_rows=$(mysql_exec airbobdb --execute="SELECT COUNT(*) FROM \`$table_name\`")
  [[ "$actual_rows" == "$expected_rows" ]] || { printf 'row-count contract failed: %s\n' "$table_name" >&2; exit 1; }
done < <(jq -r '.mysql.expectedTableRows | to_entries | sort_by(.key)[] | [.key, (.value | tostring)] | @tsv' "$manifest")

invalid_published_timezone_count=$(mysql_exec airbobdb --execute="
  SELECT COUNT(*)
  FROM accommodation
  WHERE status = 'PUBLISHED'
    AND (
      time_zone_id IS NULL
      OR TRIM(time_zone_id) = ''
      OR time_zone_id NOT REGEXP '^[A-Za-z][A-Za-z0-9._+-]*(/[A-Za-z0-9._+-]+)*$'
    );
")
[[ "$invalid_published_timezone_count" == 0 ]] \
  || { printf '%s\n' 'published accommodation timezone contract failed' >&2; exit 1; }

schema_unsorted_file="$work_root/schema-fingerprint.unsorted.tsv"
schema_file="$work_root/schema-fingerprint.tsv"
mysql_exec --execute="
  SELECT 'COLUMN', HEX(TABLE_NAME), HEX(COLUMN_NAME), HEX(CAST(ORDINAL_POSITION AS CHAR)),
         HEX(COLUMN_NAME), HEX(COLUMN_TYPE), HEX(IS_NULLABLE),
         COALESCE(HEX(CAST(COLUMN_DEFAULT AS CHAR)), '<NULL>'), HEX(EXTRA),
         COALESCE(HEX(COLLATION_NAME), '<NULL>'),
         COALESCE(HEX(CHARACTER_SET_NAME), '<NULL>'),
         COALESCE(HEX(GENERATION_EXPRESSION), '<NULL>')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'airbobdb'
  UNION ALL
  SELECT 'INDEX', HEX(TABLE_NAME), HEX(INDEX_NAME), HEX(CAST(SEQ_IN_INDEX AS CHAR)),
         COALESCE(HEX(COLUMN_NAME), '<NULL>'), HEX(CAST(NON_UNIQUE AS CHAR)),
         COALESCE(HEX(COLLATION), '<NULL>'), COALESCE(HEX(CAST(SUB_PART AS CHAR)), '<NULL>'),
         HEX(NULLABLE), HEX(INDEX_TYPE), HEX(IS_VISIBLE), COALESCE(HEX(EXPRESSION), '<NULL>')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'airbobdb'
  UNION ALL
  SELECT 'CONSTRAINT', HEX(tc.TABLE_NAME), HEX(tc.CONSTRAINT_NAME),
         COALESCE(HEX(CAST(kcu.ORDINAL_POSITION AS CHAR)), '<NULL>'),
         COALESCE(HEX(kcu.COLUMN_NAME), '<NULL>'), HEX(tc.CONSTRAINT_TYPE),
         COALESCE(HEX(CAST(kcu.POSITION_IN_UNIQUE_CONSTRAINT AS CHAR)), '<NULL>'),
         COALESCE(HEX(kcu.REFERENCED_TABLE_SCHEMA), '<NULL>'),
         COALESCE(HEX(kcu.REFERENCED_TABLE_NAME), '<NULL>'),
         COALESCE(HEX(kcu.REFERENCED_COLUMN_NAME), '<NULL>'), HEX(tc.ENFORCED), '<NULL>'
  FROM information_schema.TABLE_CONSTRAINTS AS tc
  LEFT JOIN information_schema.KEY_COLUMN_USAGE AS kcu
    ON kcu.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
   AND kcu.TABLE_NAME = tc.TABLE_NAME
   AND kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
  WHERE tc.CONSTRAINT_SCHEMA = 'airbobdb'
  UNION ALL
  SELECT 'REFERENTIAL', HEX(TABLE_NAME), HEX(CONSTRAINT_NAME), '<NULL>', '<NULL>',
         HEX(UNIQUE_CONSTRAINT_SCHEMA), HEX(UNIQUE_CONSTRAINT_NAME), HEX(MATCH_OPTION),
         HEX(UPDATE_RULE), HEX(DELETE_RULE), HEX(REFERENCED_TABLE_NAME), '<NULL>'
  FROM information_schema.REFERENTIAL_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA = 'airbobdb'
  UNION ALL
  SELECT 'CHECK', HEX(tc.TABLE_NAME), HEX(cc.CONSTRAINT_NAME), '<NULL>', '<NULL>',
         HEX(cc.CHECK_CLAUSE), HEX(tc.ENFORCED), '<NULL>', '<NULL>', '<NULL>', '<NULL>', '<NULL>'
  FROM information_schema.CHECK_CONSTRAINTS AS cc
  INNER JOIN information_schema.TABLE_CONSTRAINTS AS tc
    ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
   AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
   AND tc.CONSTRAINT_TYPE = 'CHECK'
  WHERE cc.CONSTRAINT_SCHEMA = 'airbobdb';
" > "$schema_unsorted_file"
LC_ALL=C sort "$schema_unsorted_file" > "$schema_file"
rm -f "$schema_unsorted_file"
schema_fingerprint=$(sha256sum "$schema_file" | awk '{print $1}')
[[ "$schema_fingerprint" == "$(jq -r '.mysql.schemaFingerprintSha256' "$manifest")" ]] \
  || { printf '%s\n' 'schema fingerprint does not match the dataset release' >&2; exit 1; }

# Re-attest the restored database before the first database mutation or any
# Redis, Kafka, Debezium, or Elasticsearch state change.
semantic_restore_pass() {
  mysql_exec airbobdb <<'AIRBOB_SEMANTIC_SQL'
WITH review_expected AS (
  SELECT a.id accommodation_id,COUNT(r.id) total_review_count,COALESCE(SUM(r.rating),0) rating_sum,
         CAST(COALESCE(AVG(r.rating),0) AS DECIMAL(3,2)) average_rating
  FROM accommodation a LEFT JOIN review r ON r.accommodation_id=a.id AND r.status='PUBLISHED' GROUP BY a.id
), review_counts AS (
  SELECT
    SUM(s.accommodation_id IS NULL) review_summary_missing_count,
    SUM(s.accommodation_id IS NOT NULL AND (s.total_review_count<>e.total_review_count OR s.rating_sum<>e.rating_sum OR s.average_rating<>e.average_rating)) review_summary_stale_count
  FROM review_expected e LEFT JOIN accommodation_review_summary s ON s.accommodation_id=e.accommodation_id
), wishlist_expected AS (
  SELECT w.id,COUNT(wa.id) accommodation_count,
    (SELECT wa2.accommodation_id FROM wishlist_accommodation wa2 WHERE wa2.wishlist_id=w.id ORDER BY wa2.created_at DESC,wa2.id DESC LIMIT 1) representative_accommodation_id
  FROM wishlist w LEFT JOIN wishlist_accommodation wa ON wa.wishlist_id=w.id GROUP BY w.id
), wishlist_counts AS (
  SELECT SUM(w.accommodation_count<>e.accommodation_count) wishlist_accommodation_count_mismatch_count,
    SUM(NOT (w.representative_accommodation_id<=>e.representative_accommodation_id)) wishlist_representative_mismatch_count
  FROM wishlist_expected e JOIN wishlist w ON w.id=e.id
), ledger AS (
  SELECT DATE(pt.created_at) stat_date,r.accommodation_id,COALESCE(pt.amount,0) gross,0 refund,1 payment_count,0 refund_count
  FROM payment_transaction pt JOIN reservation r ON r.id=pt.reservation_id WHERE pt.transaction_type='CONFIRM'
  UNION ALL
  SELECT DATE(COALESCE(pt.canceled_at,pt.created_at)),r.accommodation_id,0,COALESCE(pt.cancel_amount,0),0,1
  FROM payment_transaction pt JOIN reservation r ON r.id=pt.reservation_id WHERE pt.transaction_type IN ('CANCEL','PARTIAL_CANCEL')
), revenue_expected AS (
  SELECT stat_date,accommodation_id,SUM(gross) gross_amount,SUM(refund) refund_amount,SUM(gross)-SUM(refund) net_amount,
         SUM(payment_count) payment_count,SUM(refund_count) refund_count FROM ledger GROUP BY stat_date,accommodation_id
), revenue_counts AS (
  SELECT SUM(s.stat_date IS NULL) daily_revenue_stats_missing_count,
    SUM(s.stat_date IS NOT NULL AND (s.gross_amount<>e.gross_amount OR s.refund_amount<>e.refund_amount OR s.net_amount<>e.net_amount OR s.payment_count<>e.payment_count OR s.refund_count<>e.refund_count)) daily_revenue_stats_stale_count
  FROM revenue_expected e LEFT JOIN daily_revenue_stats s ON s.stat_date=e.stat_date AND s.accommodation_id=e.accommodation_id
)
SELECT review_summary_missing_count,review_summary_stale_count,
  (SELECT COUNT(*) FROM accommodation_review_summary s LEFT JOIN review_expected e ON e.accommodation_id=s.accommodation_id WHERE e.accommodation_id IS NULL) review_summary_extra_count,
  review_summary_missing_count+review_summary_stale_count+(SELECT COUNT(*) FROM accommodation_review_summary s LEFT JOIN review_expected e ON e.accommodation_id=s.accommodation_id WHERE e.accommodation_id IS NULL) review_summary_symmetric_mismatch_count,
  wishlist_accommodation_count_mismatch_count,wishlist_representative_mismatch_count,
  wishlist_accommodation_count_mismatch_count+wishlist_representative_mismatch_count wishlist_denormalized_symmetric_mismatch_count,
  daily_revenue_stats_missing_count,daily_revenue_stats_stale_count,
  (SELECT COUNT(*) FROM daily_revenue_stats s LEFT JOIN revenue_expected e ON e.stat_date=s.stat_date AND e.accommodation_id=s.accommodation_id WHERE e.stat_date IS NULL) daily_revenue_stats_extra_count,
  daily_revenue_stats_missing_count+daily_revenue_stats_stale_count+(SELECT COUNT(*) FROM daily_revenue_stats s LEFT JOIN revenue_expected e ON e.stat_date=s.stat_date AND e.accommodation_id=s.accommodation_id WHERE e.stat_date IS NULL) daily_revenue_stats_symmetric_mismatch_count,
  (SELECT COUNT(*) FROM accommodation_inventory_day) accommodation_inventory_day_row_count,
  (SELECT COUNT(*) FROM outbox) outbox_row_count
FROM review_counts CROSS JOIN wishlist_counts CROSS JOIN revenue_counts;
AIRBOB_SEMANTIC_SQL
}

semantic_one="$work_root/semantic-restore-one.tsv"
semantic_two="$work_root/semantic-restore-two.tsv"
semantic_restore_pass > "$semantic_one"
semantic_restore_pass > "$semantic_two"
cmp -s "$semantic_one" "$semantic_two" \
  || { printf '%s\n' 'semantic restore result changed between passes' >&2; exit 1; }
awk -F '\t' 'NF!=13{exit 1}{for(i=1;i<=NF;i++)if($i!="0")exit 1}END{if(NR!=1)exit 1}' "$semantic_one" \
  || { printf '%s\n' 'semantic restore reconciliation failed' >&2; exit 1; }

result_field() { printf "IF(%s IS NULL,'00000000',CONCAT(LPAD(HEX(OCTET_LENGTH(CAST(%s AS CHAR))),8,'0'),HEX(CAST(%s AS CHAR))))" "$1" "$1" "$1"; }
mysql_result_hash() {
  mysql_exec airbobdb --execute="$1" | tr -d '\n' | xxd -r -p | sha256sum | awk '{print $1}'
}
row_text() { printf "IF(%s IS NULL,'FFFFFFFF',CONCAT(LPAD(HEX(OCTET_LENGTH(CAST(%s AS CHAR))),8,'0'),HEX(CAST(%s AS CHAR))))" "$1" "$1" "$1"; }
row_binary() { printf "IF(%s IS NULL,'FFFFFFFF',CONCAT(LPAD(HEX(OCTET_LENGTH(%s)),8,'0'),HEX(%s)))" "$1" "$1" "$1"; }
row_hex() {
  local expression="'FFFFFFFE'" field kind
  while [[ "$#" -gt 0 ]]; do field=$1; kind=$2; shift 2; [[ "$kind" == binary ]] && expression="$expression,$(row_binary "$field")" || expression="$expression,$(row_text "$field")"; done
  printf 'CONCAT(%s)' "$expression"
}
fingerprint_table() {
  local id=$1 table=$2 predicate=$3 order=$4 expected=$5; shift 5
  local rows digest
  rows=$(mysql_exec airbobdb --execute="SELECT COUNT(*) FROM $table WHERE $predicate")
  [[ "$rows" == "$expected" ]] || { printf 'fingerprint row count drifted: %s\n' "$id" >&2; return 1; }
  digest=$(mysql_result_hash "SELECT $(row_hex "$@") FROM $table WHERE $predicate ORDER BY $order")
  printf '%s\t%s\n' "$id" "$digest" >> "$live_fingerprint_rows"
}
table_rows() { jq -er --arg key "$1" '.world.tableRows[$key]|select(type=="number" and floor==. and .>=0)' "$benchmark_dataset_manifest"; }
scope_value() { jq -er --arg key "$1" --arg field "$2" '.world.scopeRanges[$key][$field]' "$benchmark_dataset_manifest"; }
append_lp() {
  local output=$1 value=$2 length=${#2} escaped
  printf -v escaped '\\x%02x\\x%02x\\x%02x\\x%02x' $(((length>>24)&255)) $(((length>>16)&255)) $(((length>>8)&255)) $((length&255))
  printf '%b%s' "$escaped" "$value" >> "$output"
}
combine_fingerprints() {
  local prefix=$1 output="$work_root/combined-fingerprint.bin" id digest
  : > "$output"
  while IFS=$'\t' read -r id digest; do append_lp "$output" "$id"; append_lp "$output" "$digest"; done < <(awk -F '\t' -v p="$prefix" 'index($1,p)==1' "$live_fingerprint_rows" | sort)
  sha256sum "$output" | awk '{print $1}'
}
base_fingerprint() {
  local scope=$1 id=$2 table=$3 id_field=$4 extra=$5 order=$6; shift 6
  local predicate="$id_field BETWEEN $(scope_value "$scope" minimumId) AND $(scope_value "$scope" maximumId)"
  [[ "$extra" == 1=1 ]] || predicate="$predicate AND $extra"
  fingerprint_table "$id" "$table" "$predicate" "$order" "$(scope_value "$scope" rowCount)" "$@"
}
live_restore_fingerprints() {
  jq -e '(.world.scopeRanges|keys)==["accommodation","member","payment","payment-transaction","reservation","review","wishlist","wishlist-accommodation"] and all(.world.scopeRanges|to_entries[];.key==.value.id and .value.minimumId>0 and .value.maximumId>=.value.minimumId and .value.rowCount==(.value.maximumId-.value.minimumId+1))' "$benchmark_dataset_manifest" >/dev/null || return 1
  local accommodation=(a.id text a.base_price text a.created_at text a.member_id text a.description text a.name text a.thumbnail_url text a.type text a.check_in_time text a.check_out_time text a.time_zone_id text a.status text a.currency text a.accommodation_uid binary)
  local member=(m.id text m.created_at text m.email text m.nickname text m.role text m.status text)
  local reservation=(r.id text r.reservation_uid binary r.accommodation_id text r.guest_id text r.check_in_date text r.check_out_date text r.check_in_at text r.check_out_at text r.time_zone_id text r.guest_count text r.total_price text r.discount_amount text r.status text r.message text r.reservation_code text r.created_at text r.expires_at text r.currency text)
  local review=(r.id text r.rating text r.accommodation_id text r.created_at text r.member_id text r.content text r.status text)
  local wishlist=(w.id text w.name text w.created_at text w.member_id text w.status text w.accommodation_count text w.representative_accommodation_id text)
  local wishlist_link=(wa.id text wa.memo text wa.created_at text wa.wishlist_id text wa.accommodation_id text)
  local payment=(p.id text p.payment_uid binary p.payment_key text p.order_id text p.amount text p.method text p.approved_at text p.created_at text p.reservation_id text p.status text p.balance_amount text)
  local transaction=(pt.id text pt.reservation_id text pt.payment_id text pt.transaction_type text pt.status text pt.amount text pt.payment_key text pt.order_id text pt.method text pt.failure_code text pt.cancel_amount text pt.cancel_reason text pt.transaction_key text pt.canceled_at text pt.created_at text)
  live_fingerprint_rows="$work_root/live-fingerprint-rows.tsv"; : > "$live_fingerprint_rows"
  fingerprint_table final-accommodation 'accommodation a' '1=1' a.id "$(table_rows accommodation)" "${accommodation[@]}"
  fingerprint_table final-member 'member m' '1=1' m.id "$(table_rows member)" "${member[@]}"
  fingerprint_table final-reservation 'reservation r' '1=1' r.id "$(table_rows reservation)" "${reservation[@]}"
  fingerprint_table final-review 'review r' '1=1' r.id "$(table_rows review)" "${review[@]}"
  fingerprint_table final-wishlist 'wishlist w' '1=1' w.id "$(table_rows wishlist)" "${wishlist[@]}"
  fingerprint_table final-wishlist-accommodation 'wishlist_accommodation wa' '1=1' wa.id "$(table_rows wishlist_accommodation)" "${wishlist_link[@]}"
  fingerprint_table final-payment 'payment p' '1=1' p.id "$(table_rows payment)" "${payment[@]}"
  fingerprint_table final-payment-transaction 'payment_transaction pt' '1=1' pt.id "$(table_rows payment_transaction)" "${transaction[@]}"
  fingerprint_table final-review-summary 'accommodation_review_summary s' '1=1' s.accommodation_id "$(table_rows accommodation_review_summary)" s.accommodation_id text s.total_review_count text s.rating_sum text s.average_rating text
  fingerprint_table final-daily-revenue 'daily_revenue_stats s' '1=1' 's.stat_date,s.accommodation_id' "$(table_rows daily_revenue_stats)" s.stat_date text s.accommodation_id text s.gross_amount text s.refund_amount text s.net_amount text s.payment_count text s.refund_count text
  fingerprint_table final-inventory 'accommodation_inventory_day i' '1=1' 'i.accommodation_id,i.stay_date' "$(table_rows accommodation_inventory_day)" i.accommodation_id text i.stay_date text i.state text i.reservation_id text i.hold_expires_at text
  base_fingerprint accommodation base-accommodation 'accommodation a' a.id '1=1' a.id "${accommodation[@]}"
  base_fingerprint member base-member 'member m' m.id '1=1' m.id "${member[@]}"
  base_fingerprint reservation base-reservation 'reservation r' r.id '1=1' r.id "${reservation[@]}"
  base_fingerprint review base-review 'review r' r.id '1=1' r.id "${review[@]}"
  base_fingerprint wishlist base-wishlist 'wishlist w' w.id "w.status='ACTIVE'" w.id "${wishlist[@]}"
  base_fingerprint wishlist-accommodation base-wishlist-accommodation 'wishlist_accommodation wa' wa.id '1=1' wa.id "${wishlist_link[@]}"
  base_fingerprint payment base-payment 'payment p' p.id '1=1' p.id "${payment[@]}"
  base_fingerprint payment-transaction base-payment-transaction 'payment_transaction pt' pt.id '1=1' pt.id "${transaction[@]}"
  final_world_fingerprint=$(combine_fingerprints final-)
  base_world_fingerprint=$(combine_fingerprints base-)
  inventory_fingerprint=$(awk -F '\t' '$1=="final-inventory"{print $2}' "$live_fingerprint_rows")
  [[ "$final_world_fingerprint" == "$(jq -r '.releaseTuple.finalWorldFingerprintSha256' "$manifest")" \
    && "$base_world_fingerprint" == "$(jq -r '.releaseTuple.baseWorldFingerprintSha256' "$manifest")" \
    && "$inventory_fingerprint" == "$(jq -r '.releaseTuple.inventoryFingerprintSha256' "$manifest")" ]]
}
verify_targets() {
  local receipt=$1 target id kind expected_rows expected_hash actual_rows actual_hash account member_id size cursor_id cursor_time from to predicate ledger sql
  local adult child infant pet total_occupancy top_left_lat top_left_lng bottom_right_lat bottom_right_lng minimum_price maximum_price search_scope
  : > "$receipt"
  while IFS= read -r target; do
    id=$(jq -r '.id' <<<"$target"); kind=$(jq -r '.query.kind' <<<"$target")
    expected_rows=$(jq -r '.expectedRows' <<<"$target"); expected_hash=$(jq -r '.expectedResultHash' <<<"$target")
    if account=$(jq -er '.account|select(.!=null)' <<<"$target" 2>/dev/null); then
      member_id=$(jq -r '.memberId' <<<"$account")
      [[ "$(mysql_exec airbobdb --execute="SELECT COUNT(*) FROM member WHERE id=$member_id AND email='$(jq -r '.email' <<<"$account")' AND role='$(jq -r '.role' <<<"$account")' AND status='$(jq -r '.status' <<<"$account")'")" == 1 ]] \
        || { printf 'target account drifted: %s\n' "$id" >&2; return 1; }
    fi
    case "$kind" in
      REVIEW_SUMMARY_V1)
        member_id=$(jq -r '.query.accommodationId' <<<"$target")
        actual_rows=$(mysql_exec airbobdb --execute="SELECT COUNT(*) FROM review WHERE accommodation_id=$member_id AND status='PUBLISHED'")
        sql="SELECT CONCAT($(result_field 'COUNT(*)'),$(result_field 'CAST(CAST(COALESCE(AVG(rating),0) AS DECIMAL(20,2)) AS CHAR)')) FROM review WHERE accommodation_id=$member_id AND status='PUBLISHED'"
        ;;
      WISHLIST_PAGE_V1)
        member_id=$(jq -r '.query.memberId' <<<"$target"); size=$(jq -r '.query.size' <<<"$target")
        cursor_id=$(jq -r '.query.lastId // empty' <<<"$target"); cursor_time=$(jq -r '.query.lastCreatedAt // empty' <<<"$target")
        predicate="w.member_id=$member_id AND w.status='ACTIVE'"
        [[ -z "$cursor_id" ]] || predicate="$predicate AND (w.created_at<'$cursor_time' OR (w.created_at='$cursor_time' AND w.id<$cursor_id))"
        [[ "$(mysql_exec airbobdb --execute="SELECT COUNT(*) FROM wishlist WHERE member_id=$member_id AND status='ACTIVE'")" == "$(jq -r '.query.totalActiveRows' <<<"$target")" ]] \
          || { printf 'wishlist totalActiveRows drifted: %s\n' "$id" >&2; return 1; }
        actual_rows=$(mysql_exec airbobdb --execute="SELECT COUNT(*) FROM (SELECT w.id FROM wishlist w WHERE $predicate ORDER BY w.created_at DESC,w.id DESC LIMIT $size) x")
        sql="SELECT CONCAT($(result_field 'w.id'),$(result_field 'w.name'),$(result_field \"DATE_FORMAT(w.created_at,'%Y-%m-%dT%H:%i:%s.%f')\"),$(result_field 'w.accommodation_count'),$(result_field 'a.thumbnail_url')) FROM wishlist w LEFT JOIN accommodation a ON a.id=w.representative_accommodation_id WHERE $predicate ORDER BY w.created_at DESC,w.id DESC LIMIT $size"
        ;;
      REVENUE_RANGE_V1)
        from=$(jq -r '.query.from' <<<"$target"); to=$(jq -r '.query.to' <<<"$target")
        ledger="(SELECT DATE(pt.created_at) d,COALESCE(pt.amount,0) gross,0 refund,1 pc,0 rc FROM payment_transaction pt WHERE pt.transaction_type='CONFIRM' AND DATE(pt.created_at) BETWEEN '$from' AND '$to' UNION ALL SELECT DATE(COALESCE(pt.canceled_at,pt.created_at)),0,COALESCE(pt.cancel_amount,0),0,1 FROM payment_transaction pt WHERE pt.transaction_type IN ('CANCEL','PARTIAL_CANCEL') AND DATE(COALESCE(pt.canceled_at,pt.created_at)) BETWEEN '$from' AND '$to')"
        actual_rows=$(mysql_exec airbobdb --execute="SELECT COUNT(*) FROM (SELECT d FROM $ledger t GROUP BY d) x")
        sql="SELECT CONCAT($(result_field 't.d'),$(result_field 'SUM(t.gross)'),$(result_field 'SUM(t.refund)'),$(result_field 'SUM(t.gross)-SUM(t.refund)'),$(result_field 'SUM(t.pc)'),$(result_field 'SUM(t.rc)')) FROM $ledger t GROUP BY t.d ORDER BY t.d"
        ;;
      ACCOMMODATION_SEARCH_V1)
        adult=$(jq -r '.query.adultOccupancy' <<<"$target"); child=$(jq -r '.query.childOccupancy' <<<"$target")
        infant=$(jq -r '.query.infantOccupancy' <<<"$target"); pet=$(jq -r '.query.petOccupancy' <<<"$target")
        total_occupancy=$((adult + child))
        top_left_lat=$(jq -r '.query.topLeftLat' <<<"$target"); top_left_lng=$(jq -r '.query.topLeftLng' <<<"$target")
        bottom_right_lat=$(jq -r '.query.bottomRightLat' <<<"$target"); bottom_right_lng=$(jq -r '.query.bottomRightLng' <<<"$target")
        minimum_price=$(jq -r '.query.minPrice' <<<"$target"); maximum_price=$(jq -r '.query.maxPrice' <<<"$target")
        search_scope="FROM accommodation a JOIN address addr ON addr.id=a.address_id JOIN occupancy_policy op ON op.id=a.occupancy_policy_id WHERE a.status='PUBLISHED' AND a.base_price BETWEEN 0 AND 2147483647 AND addr.latitude BETWEEN -90 AND 90 AND addr.longitude BETWEEN -180 AND 180 AND op.max_occupancy>=1 AND addr.latitude<=$top_left_lat AND addr.latitude>=$bottom_right_lat AND addr.longitude>=$top_left_lng AND addr.longitude<=$bottom_right_lng AND a.base_price BETWEEN $minimum_price AND $maximum_price AND op.max_occupancy>=$total_occupancy AND ($infant=0 OR op.infant_occupancy>=$infant) AND ($pet=0 OR op.pet_occupancy>=$pet)"
        actual_rows=$(mysql_exec airbobdb --execute="SELECT COUNT(*) $search_scope")
        sql="SELECT CONCAT($(result_field 'a.id')) $search_scope ORDER BY a.id ASC"
        ;;
      *) printf 'unsupported semantic target: %s\n' "$kind" >&2; return 1 ;;
    esac
    [[ "$actual_rows" == "$expected_rows" ]] || { printf 'target expectedRows drifted: %s\n' "$id" >&2; return 1; }
    actual_hash=$(mysql_result_hash "$sql")
    [[ "$actual_hash" == "$expected_hash" ]] || { printf 'target result hash drifted: %s\n' "$id" >&2; return 1; }
    printf '%s\t%s\t%s\n' "$id" "$actual_rows" "$actual_hash" >> "$receipt"
  done < <(jq -c '.capsules[]|select(.capsuleId=="read-model-v2" or .capsuleId=="index-query-v1").targets[]' "$benchmark_dataset_manifest")
  [[ "$(wc -l < "$receipt" | tr -d '[:space:]')" == 19 ]]
}
join_query() { local first=true field; for field in "$@"; do [[ "$first" == true ]] && first=false || printf '\x1f'; printf '%s' "$field"; done; }
query_null() { jq -r --arg field "$1" 'if .query[$field]==null then "<null>" else (.query[$field]|tostring) end' <<<"$2"; }
double_hex() { local value; printf -v value '%a' "$1"; value=${value/p+/p}; [[ "$value" == *.*p* ]] || value=${value/p/.0p}; printf '%s' "$value"; }
canonical_query() {
  local target=$1 kind; kind=$(jq -r '.query.kind // empty' <<<"$target")
  case "$kind" in
    REVIEW_SUMMARY_V1) join_query "$kind" "$(jq -r '.query.accommodationId' <<<"$target")" ;;
    WISHLIST_PAGE_V1) join_query "$kind" "$(jq -r '.query.memberId' <<<"$target")" "$(jq -r '.query.size' <<<"$target")" "$(query_null lastId "$target")" "$(query_null lastCreatedAt "$target")" "$(query_null accommodationId "$target")" "$(jq -r '.query.totalActiveRows' <<<"$target")" ;;
    REVENUE_RANGE_V1) join_query "$kind" "$(jq -r '.query.from' <<<"$target")" "$(jq -r '.query.to' <<<"$target")" "$(jq -r '.query.dayBoundary' <<<"$target")" ;;
    ACCOMMODATION_SEARCH_V1) join_query "$kind" "$(jq -r '.query.destination' <<<"$target")" "$(jq -r '.query.minPrice' <<<"$target")" "$(jq -r '.query.maxPrice' <<<"$target")" "$(jq -r '.query.adultOccupancy' <<<"$target")" "$(jq -r '.query.childOccupancy' <<<"$target")" "$(jq -r '.query.infantOccupancy' <<<"$target")" "$(jq -r '.query.petOccupancy' <<<"$target")" "$(double_hex "$(jq -r '.query.topLeftLat' <<<"$target")")" "$(double_hex "$(jq -r '.query.topLeftLng' <<<"$target")")" "$(double_hex "$(jq -r '.query.bottomRightLat' <<<"$target")")" "$(double_hex "$(jq -r '.query.bottomRightLng' <<<"$target")")" "$(jq -r '.query.page' <<<"$target")" ;;
    '') printf '' ;;
    *) return 1 ;;
  esac
}
recompute_target_fingerprint() {
  local output="$work_root/target-fingerprint.bin" capsule target resource account
  : > "$output"
  while IFS= read -r capsule; do
    append_lp "$output" "$(jq -r '.capsuleId' <<<"$capsule")"
    while IFS= read -r target; do
      append_lp "$output" "$(jq -r '.id' <<<"$target")"; append_lp "$output" "$(jq -r '.expectedRows|tostring' <<<"$target")"
      while IFS= read -r resource; do append_lp "$output" "$resource"; done < <(jq -r '.resourceIds[]|tostring' <<<"$target")
      append_lp "$output" "$(canonical_query "$target")"; append_lp "$output" "$(jq -r '.expectedResultHash // empty' <<<"$target")"
      append_lp "$output" "$(jq -r '.account.memberId // empty' <<<"$target")"; append_lp "$output" "$(jq -r '.account.email // empty' <<<"$target")"
      append_lp "$output" "$(jq -r '.account.role // empty' <<<"$target")"; append_lp "$output" "$(jq -r '.account.status // empty' <<<"$target")"
    done < <(jq -c '.targets|sort_by(.id)[]' <<<"$capsule")
  done < <(jq -c '.capsules|sort_by(.capsuleId)[]' "$benchmark_dataset_manifest")
  sha256sum "$output" | awk '{print $1}'
}
targets_one="$work_root/semantic-targets-one.tsv"
targets_two="$work_root/semantic-targets-two.tsv"
verify_targets "$targets_one" && verify_targets "$targets_two" \
  || { printf '%s\n' 'restored target attestation failed' >&2; exit 1; }
cmp -s "$targets_one" "$targets_two" \
  || { printf '%s\n' 'restored read-model targets changed between passes' >&2; exit 1; }
target_fingerprint=$(recompute_target_fingerprint)
[[ "$target_fingerprint" == "$(jq -r '.releaseTuple.targetFingerprintSha256' "$manifest")" ]] \
  || { printf '%s\n' 'targetFingerprint does not bind live-verified targets' >&2; exit 1; }
live_restore_fingerprints || { printf '%s\n' 'live final/base/inventory fingerprint attestation failed' >&2; exit 1; }
live_rows_one="$work_root/live-fingerprint-rows-one.tsv"; cp "$live_fingerprint_rows" "$live_rows_one"
live_summary_one="$final_world_fingerprint\t$base_world_fingerprint\t$inventory_fingerprint\t$target_fingerprint"
live_restore_fingerprints || { printf '%s\n' 'second live fingerprint attestation failed' >&2; exit 1; }
[[ "$live_summary_one" == "$final_world_fingerprint\t$base_world_fingerprint\t$inventory_fingerprint\t$target_fingerprint" ]] \
  && cmp -s "$live_rows_one" "$live_fingerprint_rows" \
  || { printf '%s\n' 'live restore fingerprints changed between passes' >&2; exit 1; }
live_fingerprint_receipt="$work_root/live-fingerprint-receipt.tsv"
printf 'final-world\t%s\nbase-world\t%s\ninventory\t%s\ntarget\t%s\n' "$final_world_fingerprint" "$base_world_fingerprint" "$inventory_fingerprint" "$target_fingerprint" > "$live_fingerprint_receipt"
semantic_attestation_sha256=$({ cat "$semantic_one"; cat "$targets_one"; cat "$live_fingerprint_receipt"; } | sha256sum | awk '{print $1}')

# Operational mutations are allowed only after the semantic gate above.
mysql_exec --execute="CALL mysql.rds_set_configuration('binlog retention hours', 24);" >/dev/null
mysql_exec --execute="
  UPDATE performance_schema.setup_consumers SET ENABLED='YES'
  WHERE NAME IN ('events_statements_current','events_statements_history','events_statements_history_long');
  UPDATE performance_schema.setup_instruments SET ENABLED='YES',TIMED='YES' WHERE NAME LIKE 'statement/%';
" >/dev/null

debezium_username=airbob_debezium
if aws --region "$AIRBOB_REGION" secretsmanager get-secret-value \
  --secret-id "$AIRBOB_DEBEZIUM_SECRET_ARN" --query SecretString --output text > "$debezium_secret_file" 2>/dev/null; then
  debezium_password=$(jq -r '.password' "$debezium_secret_file")
else
  debezium_password=$(openssl rand -hex 32)
  jq -n --arg username "$debezium_username" --arg password "$debezium_password" \
    '{username: $username, password: $password}' > "$debezium_secret_file"
  aws --region "$AIRBOB_REGION" secretsmanager put-secret-value \
    --secret-id "$AIRBOB_DEBEZIUM_SECRET_ARN" --secret-string "file://$debezium_secret_file" >/dev/null
fi
chmod 600 "$debezium_secret_file"
[[ "$debezium_password" =~ ^[0-9a-f]{64}$ ]] || { printf '%s\n' 'Debezium secret has an invalid contract' >&2; exit 1; }
mysql_exec >/dev/null <<AIRBOB_DEBEZIUM_SQL
CREATE USER IF NOT EXISTS '$debezium_username'@'%' IDENTIFIED BY '$debezium_password';
ALTER USER '$debezium_username'@'%' IDENTIFIED BY '$debezium_password';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT, LOCK TABLES ON *.* TO '$debezium_username'@'%';
AIRBOB_DEBEZIUM_SQL

if [[ "$search_enabled" == true ]]; then
  snapshot_reference="$release_root/elasticsearch/snapshot-reference.json"
  repository=$(jq -r '.repository' "$snapshot_reference")
  snapshot_bucket=$(jq -r '.bucket' "$snapshot_reference")
  snapshot_base_path=$(jq -r '.basePath' "$snapshot_reference")
  snapshot_name=$(jq -r '.snapshot' "$snapshot_reference")
  expected_documents=$(jq -r '.documentCount' "$snapshot_reference")
  expected_mapping_sha=$(jq -r '.mappingSha256' "$snapshot_reference")
  expected_database_document_identity_pairs_sha=$(
    jq -r '.dbDocumentIdentityPairsSha256' "$snapshot_reference"
  )
  expected_elasticsearch_document_identity_pairs_sha=$(
    jq -r '.esDocumentIdentityPairsSha256' "$snapshot_reference"
  )
  logical_alias=$(jq -r '.logicalAlias' "$snapshot_reference")
  snapshot_index=$(jq -r '.snapshotIndex' "$snapshot_reference")
  [[ "$logical_alias" == accommodations ]] \
    || { printf '%s\n' 'unsupported Elasticsearch snapshot index' >&2; exit 1; }
  [[ "$snapshot_index" =~ ^accommodations-v[a-z0-9][a-z0-9._-]*$ ]] \
    || { printf '%s\n' 'unsafe Elasticsearch snapshot source index' >&2; exit 1; }
  restored_index="${logical_alias}-vdataset-${AIRBOB_DATASET_RELEASE}"
  [[ "$restored_index" =~ ^accommodations-vdataset-[a-z0-9][a-z0-9._-]{2,63}$ ]] \
    || { printf '%s\n' 'unsafe Elasticsearch restore index' >&2; exit 1; }

  elasticsearch_info=$(curl_http --fail --silent --show-error http://elasticsearch.lab.airbob.internal:9200/)
  [[ "$(jq -r '.version.number' <<<"$elasticsearch_info")" == 8.18.8 ]] \
    || { printf '%s\n' 'Elasticsearch version does not match the dataset release' >&2; exit 1; }
  plugin_info=$(curl_http --fail --silent --show-error 'http://elasticsearch.lab.airbob.internal:9200/_nodes/plugins?filter_path=nodes.*.modules.name,nodes.*.plugins.name')
  for plugin in analysis-nori repository-s3; do
    jq -e --arg plugin "$plugin" '[.. | objects | .name? // empty] | index($plugin) != null' <<<"$plugin_info" >/dev/null \
      || { printf 'required Elasticsearch plugin is missing: %s\n' "$plugin" >&2; exit 1; }
  done
  repository_body=$(jq -n --arg bucket "$snapshot_bucket" --arg basePath "$snapshot_base_path" \
    '{type: "s3", settings: {bucket: $bucket, base_path: $basePath, readonly: true}}')
  curl_http --fail --silent --show-error --request PUT \
    --header 'Content-Type: application/json' --data-binary "$repository_body" \
    "http://elasticsearch.lab.airbob.internal:9200/_snapshot/$repository" >/dev/null
  delete_status=$(curl_http --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --request DELETE "http://elasticsearch.lab.airbob.internal:9200/$restored_index")
  [[ "$delete_status" == 200 || "$delete_status" == 404 ]] \
    || { printf '%s\n' 'unable to clear the Elasticsearch restore index' >&2; exit 1; }
  restore_body=$(jq -n --arg sourceIndex "$snapshot_index" --arg restoredIndex "$restored_index" '
    {
      indices: $sourceIndex,
      include_global_state: false,
      include_aliases:false,
      feature_states:["none"],
      rename_pattern: ("^" + $sourceIndex + "$"),
      rename_replacement: $restoredIndex,
      index_settings: {
        "index.number_of_replicas": 0,
        "index.blocks.write":false
      }
    }
  ')
  curl_restore --fail --silent --show-error --request POST --header 'Content-Type: application/json' \
    --data-binary "$restore_body" \
    "http://elasticsearch.lab.airbob.internal:9200/_snapshot/$repository/$snapshot_name/_restore?wait_for_completion=true" >/dev/null
  restored_documents=$(curl_http --fail --silent --show-error \
    "http://elasticsearch.lab.airbob.internal:9200/$restored_index/_count" | jq -r '.count')
  [[ "$restored_documents" == "$expected_documents" ]] \
    || { printf '%s\n' 'Elasticsearch document count does not match the snapshot reference' >&2; exit 1; }
  mapping_file="$work_root/elasticsearch-mapping.json"
  curl_http --fail --silent --show-error \
    "http://elasticsearch.lab.airbob.internal:9200/$restored_index/_mapping" \
    | jq -S --arg restoredIndex "$restored_index" '.[$restoredIndex].mappings' > "$mapping_file"
  [[ "$(sha256sum "$mapping_file" | awk '{print $1}')" == "$expected_mapping_sha" ]] \
    || { printf '%s\n' 'Elasticsearch mapping fingerprint does not match the snapshot reference' >&2; exit 1; }

  database_ids="$work_root/database-accommodation-ids.txt"
  database_document_identity_pairs="$work_root/database-document-identity-pairs.tsv"
  elasticsearch_ids="$work_root/elasticsearch-accommodation-ids.txt"
  elasticsearch_document_identity_pairs="$work_root/elasticsearch-document-identity-pairs.tsv"
  elasticsearch_content="$work_root/elasticsearch-content.jsonl"
  elasticsearch_page="$work_root/elasticsearch-page.json"
  mysql_exec airbobdb --execute="SELECT id FROM accommodation WHERE status = 'PUBLISHED' ORDER BY id" > "$database_ids"
  mysql_exec airbobdb --execute="
    SELECT LOWER(BIN_TO_UUID(accommodation_uid)), id
    FROM accommodation
    WHERE status = 'PUBLISHED'
    ORDER BY LOWER(BIN_TO_UUID(accommodation_uid)), id
  " > "$database_document_identity_pairs"
  : > "$elasticsearch_ids"
  : > "$elasticsearch_document_identity_pairs"
  : > "$elasticsearch_content"
  curl_http --fail --silent --show-error --request POST --header 'Content-Type: application/json' \
    --data-binary '{"size":1000,"sort":["_doc"],"_source":true}' \
    "http://elasticsearch.lab.airbob.internal:9200/$restored_index/_search?scroll=2m" > "$elasticsearch_page"
  while :; do
    jq -e '
      .timed_out == false and ._shards.failed == 0 and
      (.hits.hits | type == "array") and
      all(.hits.hits[];
        (._id | type == "string" and
          test("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) and
        (._source | type == "object") and
        (._source.accommodationId | type == "number" and floor == . and . > 0)
      )
    ' "$elasticsearch_page" >/dev/null \
      || { printf '%s\n' 'Elasticsearch scroll did not complete cleanly' >&2; exit 1; }
    page_hits=$(jq '.hits.hits | length' "$elasticsearch_page")
    scroll_id=$(jq -r '._scroll_id' "$elasticsearch_page")
    [[ "$page_hits" =~ ^[0-9]+$ && "$scroll_id" != null && -n "$scroll_id" ]] \
      || { printf '%s\n' 'Elasticsearch scroll response is invalid' >&2; exit 1; }
    [[ "$page_hits" -gt 0 ]] || break
    jq -r '.hits.hits[]._source.accommodationId' "$elasticsearch_page" >> "$elasticsearch_ids"
    jq -r '.hits.hits[] | [._id, (._source.accommodationId | tostring)] | @tsv' \
      "$elasticsearch_page" >> "$elasticsearch_document_identity_pairs"
    jq -S -c '.hits.hits[]._source' "$elasticsearch_page" >> "$elasticsearch_content"
    scroll_body=$(jq -n --arg scrollId "$scroll_id" '{scroll: "2m", scroll_id: $scrollId}')
    curl_http --fail --silent --show-error --request POST --header 'Content-Type: application/json' \
      --data-binary "$scroll_body" 'http://elasticsearch.lab.airbob.internal:9200/_search/scroll' \
      > "$elasticsearch_page.next"
    mv "$elasticsearch_page.next" "$elasticsearch_page"
  done
  scroll_delete_body=$(jq -n --arg scrollId "$scroll_id" '{scroll_id: [$scrollId]}')
  curl_http --fail --silent --show-error --request DELETE --header 'Content-Type: application/json' \
    --data-binary "$scroll_delete_body" 'http://elasticsearch.lab.airbob.internal:9200/_search/scroll' >/dev/null

  awk 'NF != 1 || $1 !~ /^[1-9][0-9]*$/ { exit 1 }' "$database_ids" \
    || { printf '%s\n' 'database accommodation id stream is invalid' >&2; exit 1; }
  awk 'NF != 1 || $1 !~ /^[1-9][0-9]*$/ { exit 1 }' "$elasticsearch_ids" \
    || { printf '%s\n' 'Elasticsearch accommodation id stream is invalid' >&2; exit 1; }
  awk -F '\t' '
    NF != 2 ||
    $1 !~ /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/ ||
    $2 !~ /^[1-9][0-9]*$/ { exit 1 }
  ' "$database_document_identity_pairs" \
    || { printf '%s\n' 'database document identity pair stream is invalid' >&2; exit 1; }
  awk -F '\t' '
    NF != 2 ||
    $1 !~ /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/ ||
    $2 !~ /^[1-9][0-9]*$/ { exit 1 }
  ' "$elasticsearch_document_identity_pairs" \
    || { printf '%s\n' 'Elasticsearch document identity pair stream is invalid' >&2; exit 1; }
  LC_ALL=C sort -n "$database_ids" -o "$database_ids"
  LC_ALL=C sort "$database_document_identity_pairs" -o "$database_document_identity_pairs"
  LC_ALL=C sort -n "$elasticsearch_ids" -o "$elasticsearch_ids"
  LC_ALL=C sort "$elasticsearch_document_identity_pairs" -o "$elasticsearch_document_identity_pairs"
  LC_ALL=C sort "$elasticsearch_content" -o "$elasticsearch_content"
  [[ "$(wc -l < "$database_ids" | tr -d ' ')" == "$expected_documents" && \
      "$(wc -l < "$elasticsearch_ids" | tr -d ' ')" == "$expected_documents" ]] \
    || { printf '%s\n' 'cross-store accommodation id counts do not match the release' >&2; exit 1; }
  [[ "$(sha256sum "$database_ids" | awk '{print $1}')" == "$(jq -r '.dbIdsSha256' "$snapshot_reference")" ]] \
    || { printf '%s\n' 'database accommodation id fingerprint does not match the release' >&2; exit 1; }
  [[ "$(sha256sum "$elasticsearch_ids" | awk '{print $1}')" == "$(jq -r '.esIdsSha256' "$snapshot_reference")" ]] \
    || { printf '%s\n' 'Elasticsearch accommodation id fingerprint does not match the release' >&2; exit 1; }
  validate_document_identity_pairs \
    "$database_document_identity_pairs" \
    "$elasticsearch_document_identity_pairs" \
    "$expected_database_document_identity_pairs_sha" \
    "$expected_elasticsearch_document_identity_pairs_sha" \
    "$expected_documents" \
    || { printf '%s\n' 'document identity pair fingerprint does not match the release' >&2; exit 1; }
  [[ "$(sha256sum "$elasticsearch_content" | awk '{print $1}')" == "$(jq -r '.contentFingerprintSha256' "$snapshot_reference")" ]] \
    || { printf '%s\n' 'Elasticsearch content fingerprint does not match the release' >&2; exit 1; }

  existing_aliases_file="$work_root/elasticsearch-existing-aliases.json"
  alias_status=$(curl_http --silent --show-error --output "$existing_aliases_file" --write-out '%{http_code}' \
    "http://elasticsearch.lab.airbob.internal:9200/_alias/$logical_alias")
  case "$alias_status" in
    200) existing_aliases=$(cat "$existing_aliases_file") ;;
    404) existing_aliases='{}' ;;
    *) printf '%s\n' 'unable to inspect the Elasticsearch alias' >&2; exit 1 ;;
  esac
  jq -e --arg alias "$logical_alias" '
    all(to_entries[];
      (.key | startswith($alias + "-v")) and
      (.value.aliases[$alias] != null)
    )
  ' <<<"$existing_aliases" >/dev/null \
    || { printf '%s\n' 'Elasticsearch alias contains an unmanaged index' >&2; exit 1; }
  alias_body=$(jq -n \
    --arg alias "$logical_alias" \
    --arg restoredIndex "$restored_index" \
    --argjson existingAliases "$existing_aliases" '
    {
      actions: (
        [$existingAliases | keys[] as $index |
          {remove: {index: $index, alias: $alias, must_exist: true}}] +
        [{add: {index: $restoredIndex, alias: $alias, is_write_index: true}}]
      )
    }
  ')
  alias_update=$(curl_http --fail --silent --show-error --request POST \
    --header 'Content-Type: application/json' --data-binary "$alias_body" \
    'http://elasticsearch.lab.airbob.internal:9200/_aliases')
  jq -e '.acknowledged == true' <<<"$alias_update" >/dev/null \
    || { printf '%s\n' 'Elasticsearch alias update was not acknowledged' >&2; exit 1; }
  alias_state=$(curl_http --fail --silent --show-error \
    "http://elasticsearch.lab.airbob.internal:9200/_alias/$logical_alias")
  jq -e --arg alias "$logical_alias" --arg restoredIndex "$restored_index" '
    (keys == [$restoredIndex]) and
    .[$restoredIndex].aliases[$alias].is_write_index == true
  ' <<<"$alias_state" >/dev/null \
    || { printf '%s\n' 'Elasticsearch write alias does not target the restored index' >&2; exit 1; }
  search_state=restored
else
  search_state=skipped
fi

redis_image=$(awk -F= '$1 == "REDIS_IMAGE" {print substr($0, index($0, "=") + 1)}' /etc/airbob/images.env)
[[ "$redis_image" =~ @sha256:[0-9a-f]{64}$ ]] || { printf '%s\n' 'immutable Redis image is unavailable' >&2; exit 1; }
redis_cli() {
  local port=$1
  shift
  docker run --rm --network host "$redis_image" redis-cli --host redis-general.lab.airbob.internal --port "$port" "$@"
}
redis_cli 6379 FLUSHDB >/dev/null
redis_cli 6380 FLUSHDB >/dev/null
coupon_count=$(jq '.couponPreparation | length' "$manifest")
while IFS=$'\t' read -r coupon_id expected_quantity; do
  [[ "$coupon_id" =~ ^[1-9][0-9]*$ && "$expected_quantity" =~ ^[0-9]+$ ]] \
    || { printf '%s\n' 'unsafe coupon preparation contract' >&2; exit 1; }
  coupon_row=$(mysql_exec airbobdb --execute="
    SELECT id, total_quantity,
           UNIX_TIMESTAMP(issue_start_at) * 1000,
           UNIX_TIMESTAMP(issue_end_at) * 1000,
           IF(is_active, 1, 0),
           (UNIX_TIMESTAMP(issue_end_at) + 604800) * 1000,
           issued_quantity,
           IF(redis_stock_prepared_at IS NULL, 0, 1)
    FROM coupon WHERE id = $coupon_id;
  ")
  IFS=$'\t' read -r actual_id total_quantity issue_start issue_end active expires_at issued_quantity already_prepared <<<"$coupon_row"
  [[ "$actual_id" == "$coupon_id" && "$total_quantity" == "$expected_quantity" && "$active" == 1 && "$issued_quantity" == 0 && "$already_prepared" == 0 ]] \
    || { printf 'coupon preparation invariant failed: %s\n' "$coupon_id" >&2; exit 1; }
  prepare_result=$(docker run --rm --network host \
    --volume "$AIRBOB_COUPON_LUA_FILE:/tmp/coupon_prepare.lua:ro" "$redis_image" \
    redis-cli --host redis-general.lab.airbob.internal --port 6379 --raw \
    --eval /tmp/coupon_prepare.lua "coupon:{$coupon_id}:meta" "coupon:{$coupon_id}:issued" , \
    "$total_quantity" "$issue_start" "$issue_end" 1 "$expires_at" 0)
  [[ "$prepare_result" == 1 ]] || { printf 'coupon preparation failed: %s\n' "$coupon_id" >&2; exit 1; }
  mysql_exec airbobdb --execute="UPDATE coupon SET redis_stock_prepared_at = UTC_TIMESTAMP(6) WHERE id = $coupon_id" >/dev/null
done < <(jq -r '.couponPreparation[] | [.couponId, .quantity] | @tsv' "$manifest")
[[ "$(redis_cli 6379 DBSIZE | tr -d '\r')" == "$coupon_count" ]] \
  || { printf '%s\n' 'general Redis contains undeclared bootstrap keys' >&2; exit 1; }
[[ "$(redis_cli 6380 DBSIZE | tr -d '\r')" == 0 ]] \
  || { printf '%s\n' 'detail-cache Redis must start empty' >&2; exit 1; }
redis_state=$([[ "$coupon_count" -eq 0 ]] && printf empty || printf coupon-prepared)

debezium_compose=/opt/airbob/release/infra/aws/bundles/debezium/compose.yml
compose=(docker compose --env-file /etc/airbob/images.env -f "$debezium_compose")
kafka_exec=("${compose[@]}" exec --no-TTY debezium env KAFKA_OPTS= KAFKA_HEAP_OPTS=-Xms64m\ -Xmx64m)
while IFS=$'\t' read -r topic partitions retention_ms; do
  case "$topic" in
    PAYMENT_OPERATION.events|PAYMENT_OPERATION.events.RETRY|PAYMENT_OPERATION.events.DLT|\
    ACCOMMODATION_INDEX.events|ACCOMMODATION_INDEX.events.RETRY|ACCOMMODATION_INDEX.events.DLT|\
    ACCOMMODATION_CACHE.events|ACCOMMODATION_CACHE.events.RETRY|ACCOMMODATION_CACHE.events.DLT|\
    OPERATOR_ALERT.events|OPERATOR_ALERT.events.RETRY|OPERATOR_ALERT.events.DLT) ;;
    *) printf '%s\n' 'unsafe Kafka topic contract' >&2; exit 1 ;;
  esac
  [[ "$partitions" == 3 && "$retention_ms" == 86400000 ]] \
    || { printf '%s\n' 'unsafe Kafka topic contract' >&2; exit 1; }
  "${kafka_exec[@]}" /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka.lab.airbob.internal:9092 --create --if-not-exists \
    --topic "$topic" --partitions "$partitions" --replication-factor 1 \
    --config "retention.ms=$retention_ms" >/dev/null
  topic_description=$("${kafka_exec[@]}" /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka.lab.airbob.internal:9092 --describe --topic "$topic")
  actual_partitions=$(awk '{ for (i = 1; i <= NF; i++) if ($i == "PartitionCount:") { print $(i + 1); exit } }' <<<"$topic_description")
  [[ "$actual_partitions" == "$partitions" ]] \
    || { printf 'Kafka partition contract failed: %s\n' "$topic" >&2; exit 1; }
  topic_config=$("${kafka_exec[@]}" /opt/kafka/bin/kafka-configs.sh \
    --bootstrap-server kafka.lab.airbob.internal:9092 --describe \
    --entity-type topics --entity-name "$topic")
  [[ "$topic_config" =~ retention\.ms=([0-9]+) && "${BASH_REMATCH[1]}" == "$retention_ms" ]] \
    || { printf 'Kafka retention contract failed: %s\n' "$topic" >&2; exit 1; }
  topic_offsets=$("${kafka_exec[@]}" /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server kafka.lab.airbob.internal:9092 --topic "$topic" --time -1)
  if [[ -z "$topic_offsets" ]] || ! awk -F: -v expected="$partitions" \
    'NF != 3 || $3 != 0 { exit 1 } END { if (NR != expected) exit 1 }' <<<"$topic_offsets"; then
    printf 'Kafka topic is not empty or complete: %s\n' "$topic" >&2
    exit 1
  fi
done < <(jq -r '.kafka.topics[] | [.name, .partitions, .retentionMs] | @tsv' "$manifest")

connector_template=/opt/airbob/release/infra/aws/bundles/debezium/connector.aws.json.tmpl
jq --arg endpoint "$AIRBOB_RDS_ENDPOINT" --arg username "$debezium_username" --rawfile password "$debezium_secret_file" '
  ($password | fromjson | .password) as $secret |
  walk(if type == "string" then
    gsub("\\$\\{RDS_ENDPOINT\\}"; $endpoint) |
    gsub("\\$\\{DEBEZIUM_USERNAME\\}"; $username) |
    gsub("\\$\\{DEBEZIUM_PASSWORD\\}"; $secret)
  else . end)
' "$connector_template" > "$connector_payload"
chmod 600 "$connector_payload"
curl_http --fail --silent --show-error --request PUT --header 'Content-Type: application/json' \
  --data-binary "@$connector_payload" \
  'http://127.0.0.1:8083/connectors/airbob-outbox-connector/config' >/dev/null
for attempt in $(seq 1 60); do
  connector_status=$(curl_http --fail --silent --show-error \
    'http://127.0.0.1:8083/connectors/airbob-outbox-connector/status')
  connector_state=$(jq -r '.connector.state' <<<"$connector_status")
  if [[ "$connector_state" == RUNNING ]] && jq -e \
    '.tasks | length == 1 and all(.[]; .state == "RUNNING")' <<<"$connector_status" >/dev/null; then
    break
  fi
  [[ "$attempt" -lt 60 ]] || { printf '%s\n' 'Debezium connector did not become RUNNING' >&2; exit 1; }
  sleep 5
done

targets_final="$work_root/semantic-targets-final.tsv"
verify_targets "$targets_final" \
  || { printf '%s\n' 'final live target attestation failed' >&2; exit 1; }
cmp -s "$targets_one" "$targets_final" \
  || { printf '%s\n' 'live targets drifted after the semantic gate' >&2; exit 1; }

cleanup

verified_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
receipt="$work_root/data-bootstrap-receipt.json"
jq -n \
  --arg runId "$AIRBOB_RUN_ID" \
  --arg datasetRelease "$AIRBOB_DATASET_RELEASE" \
  --arg datasetRunId "$(jq -r '.datasetRunId' "$manifest")" \
  --arg releaseKind "$release_kind" \
  --arg databaseBootstrap "$AIRBOB_DATABASE_BOOTSTRAP" \
  --arg dumpSha256 "$(jq -r '.mysql.dumpSha256' "$manifest")" \
  --arg flywayVersion "$flyway_version" \
  --arg migrationChecksumSha256 "$migration_checksum" \
  --arg schemaFingerprintSha256 "$schema_fingerprint" \
  --arg datasetManifestSha256 "$actual_manifest_sha" \
  --arg validatorSha256 "$(jq -r '.releaseTuple.validatorSha256' "$manifest")" \
  --arg benchmarkDatasetManifestSha256 "$(jq -r '.releaseTuple.manifestSha256' "$manifest")" \
  --arg calibrationSha256 "$(jq -r '.releaseTuple.calibrationSha256' "$manifest")" \
  --arg productionSpecSha256 "$(jq -r '.releaseTuple.specSha256' "$manifest")" \
  --arg qualificationSha256 "$(jq -r '.releaseTuple.qualificationSha256' "$manifest")" \
  --arg databaseFingerprintSha256 "$(jq -r '.releaseTuple.databaseFingerprintSha256' "$manifest")" \
  --arg restoreAttestationSha256 "$(jq -r '.releaseTuple.attestationSha256' "$manifest")" \
  --arg finalWorldFingerprintSha256 "$final_world_fingerprint" \
  --arg baseWorldFingerprintSha256 "$base_world_fingerprint" \
  --arg distributionFingerprintSha256 "$(jq -r '.releaseTuple.distributionFingerprintSha256' "$manifest")" \
  --arg targetFingerprintSha256 "$target_fingerprint" \
  --arg inventoryFingerprintSha256 "$inventory_fingerprint" \
  --arg semanticAttestationSha256 "$semantic_attestation_sha256" \
  --arg rdsResourceId "$AIRBOB_RDS_RESOURCE_ID" \
  --arg rdsEngineVersion "$AIRBOB_RDS_ENGINE_VERSION" \
  --arg redisState "$redis_state" \
  --arg connectorState "$connector_state" \
  --arg searchState "$search_state" \
  --arg verifiedAt "$verified_at" \
  --argjson kafkaTopics "$(jq '.kafka.topics' "$manifest")" \
  '{
    schemaVersion: 2,
    runId: $runId,
    datasetRelease: $datasetRelease,
    datasetRunId: $datasetRunId,
    releaseKind: $releaseKind,
    databaseBootstrap: $databaseBootstrap,
    dumpSha256: $dumpSha256,
    flywayVersion: $flywayVersion,
    migrationChecksumSha256: $migrationChecksumSha256,
    schemaFingerprintSha256: $schemaFingerprintSha256,
    datasetManifestSha256: $datasetManifestSha256,
    validatorSha256: $validatorSha256,
    benchmarkDatasetManifestSha256: $benchmarkDatasetManifestSha256,
    calibrationSha256: $calibrationSha256,
    productionSpecSha256: $productionSpecSha256,
    qualificationSha256: $qualificationSha256,
    databaseFingerprintSha256: $databaseFingerprintSha256,
    restoreAttestationSha256: $restoreAttestationSha256,
    finalWorldFingerprintSha256: $finalWorldFingerprintSha256,
    baseWorldFingerprintSha256: $baseWorldFingerprintSha256,
    distributionFingerprintSha256: $distributionFingerprintSha256,
    targetFingerprintSha256: $targetFingerprintSha256,
    inventoryFingerprintSha256: $inventoryFingerprintSha256,
    semanticAttestationSha256: $semanticAttestationSha256,
    rdsResourceId: $rdsResourceId,
    rdsEngineVersion: $rdsEngineVersion,
    outboxState: "empty",
    redisState: $redisState,
    kafkaTopics: $kafkaTopics,
    connectorState: $connectorState,
    searchState: $searchState,
    verifiedAt: $verifiedAt
  }' > "$receipt"
aws --region "$AIRBOB_REGION" s3api put-object \
  --bucket "$AIRBOB_EVIDENCE_BUCKET" \
  --key "data-bootstrap/$AIRBOB_RUN_ID/$AIRBOB_DATASET_RELEASE.json" \
  --body "$receipt" --tagging Retention=summary >/dev/null

printf '%s\n' 'data bootstrap verified'
