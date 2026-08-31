#!/usr/bin/env bash
set -euo pipefail
umask 077

usage() {
  printf 'usage: %s MANIFEST RECEIPT RDS_INSTANCE_ID SNAPSHOT_ID OUTPUT_JSON\n' "${0##*/}" >&2
  exit 64
}

[[ "$#" -eq 5 ]] || usage
manifest=$1
receipt=$2
rds_instance_id=$3
snapshot_id=$4
output_json=$5
region=${AIRBOB_REGION:-ap-northeast-2}

for input_file in "$manifest" "$receipt"; do
  [[ -f "$input_file" && ! -L "$input_file" ]] || { printf '%s\n' 'snapshot promotion input is missing or unsafe' >&2; exit 1; }
done
[[ "$rds_instance_id" =~ ^airbob-[a-z0-9][a-z0-9-]{1,54}[a-z0-9]$ && "$rds_instance_id" != *--* ]] \
  || { printf '%s\n' 'unsafe RDS instance identifier' >&2; exit 1; }
[[ "$snapshot_id" =~ ^airbob-dataset-[a-z0-9][a-z0-9-]{1,46}[a-z0-9]$ && "$snapshot_id" != *--* ]] \
  || { printf '%s\n' 'unsafe RDS snapshot identifier' >&2; exit 1; }
[[ "$region" == ap-northeast-2 ]] || { printf '%s\n' 'snapshot promotion is pinned to ap-northeast-2' >&2; exit 1; }
command -v aws >/dev/null 2>&1 && command -v jq >/dev/null 2>&1 \
  || { printf '%s\n' 'AWS CLI and jq are required' >&2; exit 1; }

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

manifest_sha=$(sha256_file "$manifest")
dataset_release=$(jq -r '.datasetRelease' "$manifest")
dataset_run_id=$(jq -r '.datasetRunId' "$manifest")
dump_sha=$(jq -r '.mysql.dumpSha256' "$manifest")
flyway_version=$(jq -r '.mysql.flywayVersion' "$manifest")
migration_checksum=$(jq -r '.mysql.migrationChecksumSha256' "$manifest")
schema_fingerprint=$(jq -r '.mysql.schemaFingerprintSha256' "$manifest")
release_kind=$(jq -r '.releaseKind' "$manifest")
search_state=$(jq -r 'if .search.enabled then "restored" else "skipped" end' "$manifest")
kafka_topics=$(jq -c '.kafka.topics' "$manifest")
validator_sha=$(jq -r '.releaseTuple.validatorSha256' "$manifest")
benchmark_dataset_manifest_sha=$(jq -r '.releaseTuple.manifestSha256' "$manifest")
calibration_sha=$(jq -r '.releaseTuple.calibrationSha256' "$manifest")
production_spec_sha=$(jq -r '.releaseTuple.specSha256' "$manifest")
qualification_sha=$(jq -r '.releaseTuple.qualificationSha256' "$manifest")
database_fingerprint_sha=$(jq -r '.releaseTuple.databaseFingerprintSha256' "$manifest")
restore_attestation_sha=$(jq -r '.releaseTuple.attestationSha256' "$manifest")
final_world_fingerprint_sha=$(jq -r '.releaseTuple.finalWorldFingerprintSha256' "$manifest")
base_world_fingerprint_sha=$(jq -r '.releaseTuple.baseWorldFingerprintSha256' "$manifest")
distribution_fingerprint_sha=$(jq -r '.releaseTuple.distributionFingerprintSha256' "$manifest")
target_fingerprint_sha=$(jq -r '.releaseTuple.targetFingerprintSha256' "$manifest")
inventory_fingerprint_sha=$(jq -r '.releaseTuple.inventoryFingerprintSha256' "$manifest")

jq -e \
  --arg release "$dataset_release" --arg runId "$dataset_run_id" --arg dumpSha "$dump_sha" \
  --arg flyway "$flyway_version" --arg migrationChecksum "$migration_checksum" \
  --arg schemaFingerprint "$schema_fingerprint" --arg manifestSha "$manifest_sha" \
  --arg releaseKind "$release_kind" --arg searchState "$search_state" \
  --arg validatorSha "$validator_sha" \
  --arg benchmarkDatasetManifestSha "$benchmark_dataset_manifest_sha" \
  --arg calibrationSha "$calibration_sha" --arg productionSpecSha "$production_spec_sha" \
  --arg qualificationSha "$qualification_sha" --arg databaseFingerprintSha "$database_fingerprint_sha" \
  --arg restoreAttestationSha "$restore_attestation_sha" \
  --arg finalWorldFingerprintSha "$final_world_fingerprint_sha" \
  --arg baseWorldFingerprintSha "$base_world_fingerprint_sha" \
  --arg distributionFingerprintSha "$distribution_fingerprint_sha" \
  --arg targetFingerprintSha "$target_fingerprint_sha" \
  --arg inventoryFingerprintSha "$inventory_fingerprint_sha" \
  --argjson kafkaTopics "$kafka_topics" '
  def sha256: type == "string" and test("^[0-9a-f]{64}$");
  (keys | sort) == ([
    "schemaVersion", "runId", "datasetRelease", "datasetRunId", "releaseKind",
    "databaseBootstrap", "dumpSha256", "flywayVersion", "migrationChecksumSha256",
    "schemaFingerprintSha256", "datasetManifestSha256", "validatorSha256",
    "benchmarkDatasetManifestSha256", "calibrationSha256", "productionSpecSha256",
    "qualificationSha256", "databaseFingerprintSha256", "restoreAttestationSha256",
    "finalWorldFingerprintSha256", "baseWorldFingerprintSha256",
    "distributionFingerprintSha256", "targetFingerprintSha256",
    "inventoryFingerprintSha256", "semanticAttestationSha256", "rdsResourceId",
    "rdsEngineVersion", "outboxState", "redisState", "kafkaTopics",
    "connectorState", "searchState", "verifiedAt"
  ] | sort) and
  .schemaVersion == 2 and
  (.runId | type == "string" and test("^[a-z0-9][a-z0-9-]{2,31}$") and
    (endswith("-") | not) and (contains("--") | not)) and
  (.datasetRelease | type == "string" and test("^[a-z0-9][a-z0-9._-]{2,63}$")) and
  (.datasetRunId | type == "string" and test("^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")) and
  .datasetRelease == $release and
  .datasetRunId == $runId and
  .releaseKind == $releaseKind and .releaseKind == "pipeline-rehearsal" and
  (.databaseBootstrap == "dump" or .databaseBootstrap == "snapshot") and
  ([
    .dumpSha256, .migrationChecksumSha256, .schemaFingerprintSha256,
    .datasetManifestSha256, .validatorSha256, .benchmarkDatasetManifestSha256,
    .calibrationSha256, .productionSpecSha256, .qualificationSha256,
    .databaseFingerprintSha256, .restoreAttestationSha256,
    .finalWorldFingerprintSha256, .baseWorldFingerprintSha256,
    .distributionFingerprintSha256, .targetFingerprintSha256,
    .inventoryFingerprintSha256, .semanticAttestationSha256
  ] | all(.[]; sha256)) and
  .dumpSha256 == $dumpSha and
  .flywayVersion == $flyway and .flywayVersion == "27" and
  .migrationChecksumSha256 == $migrationChecksum and
  .schemaFingerprintSha256 == $schemaFingerprint and
  .datasetManifestSha256 == $manifestSha and
  .validatorSha256 == $validatorSha and
  .benchmarkDatasetManifestSha256 == $benchmarkDatasetManifestSha and
  .calibrationSha256 == $calibrationSha and
  .productionSpecSha256 == $productionSpecSha and
  .qualificationSha256 == $qualificationSha and
  .databaseFingerprintSha256 == $databaseFingerprintSha and
  .restoreAttestationSha256 == $restoreAttestationSha and
  .finalWorldFingerprintSha256 == $finalWorldFingerprintSha and
  .baseWorldFingerprintSha256 == $baseWorldFingerprintSha and
  .distributionFingerprintSha256 == $distributionFingerprintSha and
  .targetFingerprintSha256 == $targetFingerprintSha and
  .inventoryFingerprintSha256 == $inventoryFingerprintSha and
  (.rdsResourceId | type == "string" and test("^db-[A-Z0-9]{24}$")) and
  (.rdsEngineVersion | type == "string" and test("^8\\.0\\.[0-9]+$")) and
  .outboxState == "empty" and
  (.redisState == "empty" or .redisState == "coupon-prepared") and
  .kafkaTopics == $kafkaTopics and
  .connectorState == "RUNNING" and
  .searchState == $searchState and
  (.verifiedAt | type == "string" and
    test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$") and
    (fromdateiso8601 | type == "number"))
' "$receipt" >/dev/null || { printf '%s\n' 'data bootstrap receipt cannot promote a snapshot' >&2; exit 1; }

instance_json=$(aws --region "$region" rds describe-db-instances --db-instance-identifier "$rds_instance_id")
jq -e --arg resourceId "$(jq -r '.rdsResourceId' "$receipt")" --arg engineVersion "$(jq -r '.rdsEngineVersion' "$receipt")" '
  .DBInstances | length == 1 and
  .[0].DBInstanceStatus == "available" and
  .[0].Engine == "mysql" and
  .[0].EngineVersion == $engineVersion and
  .[0].DbiResourceId == $resourceId and
  .[0].MultiAZ == false and
  .[0].StorageEncrypted == true
' <<<"$instance_json" >/dev/null || { printf '%s\n' 'RDS instance is not eligible for dataset snapshot promotion' >&2; exit 1; }

if ! aws --region "$region" rds describe-db-snapshots --db-snapshot-identifier "$snapshot_id" >/dev/null 2>&1; then
  aws --region "$region" rds create-db-snapshot \
    --db-instance-identifier "$rds_instance_id" \
    --db-snapshot-identifier "$snapshot_id" \
    --tags \
      Key=Project,Value=airbob \
      Key=Environment,Value=performance-lab \
      Key=Stack,Value=dataset \
      Key=ManagedBy,Value=dataset-publisher \
      Key=Persistence,Value=persistent \
      "Key=DatasetRelease,Value=$dataset_release" \
      "Key=DatasetRunId,Value=$dataset_run_id" \
      "Key=DumpSha256,Value=$dump_sha" \
      "Key=FlywayVersion,Value=$flyway_version" \
      "Key=ManifestSha256,Value=$manifest_sha" >/dev/null
fi
aws --region "$region" rds wait db-snapshot-available --db-snapshot-identifier "$snapshot_id"
snapshot_json=$(aws --region "$region" rds describe-db-snapshots --db-snapshot-identifier "$snapshot_id")
jq -e \
  --arg snapshot "$snapshot_id" --arg release "$dataset_release" --arg runId "$dataset_run_id" \
  --arg dumpSha "$dump_sha" --arg flyway "$flyway_version" --arg manifestSha "$manifest_sha" '
  .DBSnapshots as $snapshots |
  $snapshots[0] as $candidate |
  ($candidate.TagList | map({key: .Key, value: .Value}) | from_entries) as $tags |
  ($snapshots | length == 1) and
  $candidate.DBSnapshotIdentifier == $snapshot and
  $candidate.Status == "available" and
  $candidate.Engine == "mysql" and
  $candidate.Encrypted == true and
  $tags.DatasetRelease == $release and
  $tags.DatasetRunId == $runId and
  $tags.DumpSha256 == $dumpSha and
  $tags.FlywayVersion == $flyway and
  $tags.ManifestSha256 == $manifestSha and
  $tags.Persistence == "persistent"
' <<<"$snapshot_json" >/dev/null || { printf '%s\n' 'created RDS snapshot tags do not match the release tuple' >&2; exit 1; }

created_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
jq -n \
  --arg snapshotIdentifier "$snapshot_id" --arg datasetRelease "$dataset_release" \
  --arg datasetRunId "$dataset_run_id" --arg dumpSha256 "$dump_sha" \
  --arg flywayVersion "$flyway_version" --arg manifestSha256 "$manifest_sha" \
  --arg sourceRdsResourceId "$(jq -r '.rdsResourceId' "$receipt")" --arg createdAt "$created_at" '
  {
    schemaVersion: 1,
    snapshotIdentifier: $snapshotIdentifier,
    datasetRelease: $datasetRelease,
    datasetRunId: $datasetRunId,
    dumpSha256: $dumpSha256,
    flywayVersion: $flywayVersion,
    manifestSha256: $manifestSha256,
    sourceRdsResourceId: $sourceRdsResourceId,
    persistence: "persistent",
    createdAt: $createdAt
  }
' > "$output_json"

printf '%s\n' 'RDS snapshot promotion candidate verified'
