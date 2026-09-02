#!/usr/bin/env bash
set -euo pipefail
umask 077

usage() {
  printf 'usage: %s MANIFEST DATA_RECEIPT DATA_RECEIPT_VERSION_ID DIRECT_READINESS DIRECT_READINESS_VERSION_ID RDS_INSTANCE_ID SNAPSHOT_ID OUTPUT_JSON\n' "${0##*/}" >&2
  exit 64
}

[[ "$#" -eq 8 ]] || usage
manifest=$1
data_receipt=$2
data_receipt_version_id=$3
direct_readiness=$4
direct_readiness_version_id=$5
rds_instance_id=$6
snapshot_id=$7
output_json=$8
region=${AIRBOB_REGION:-ap-northeast-2}
script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
comparison_projection_filter="$script_dir/readiness-comparison-projection.jq"

for input_file in "$manifest" "$data_receipt" "$direct_readiness" "$comparison_projection_filter"; do
  [[ -f "$input_file" && ! -L "$input_file" ]] || { printf '%s\n' 'snapshot promotion input is missing or unsafe' >&2; exit 1; }
done
[[ ! -e "$output_json" && ! -L "$output_json" ]] \
  || { printf '%s\n' 'snapshot promotion receipt output already exists' >&2; exit 1; }
output_parent=${output_json%/*}
output_name=${output_json##*/}
[[ "$output_parent" != "$output_json" ]] || output_parent=.
[[ -d "$output_parent" && ! -L "$output_parent" && -n "$output_name" ]] \
  || { printf '%s\n' 'snapshot promotion receipt parent is missing or unsafe' >&2; exit 1; }
output_parent=$(CDPATH= cd -P -- "$output_parent" && pwd -P)
output_json="$output_parent/$output_name"
[[ "$rds_instance_id" =~ ^airbob-[a-z0-9][a-z0-9-]{1,54}[a-z0-9]$ && "$rds_instance_id" != *--* ]] \
  || { printf '%s\n' 'unsafe RDS instance identifier' >&2; exit 1; }
[[ "$snapshot_id" =~ ^airbob-dataset-[a-z0-9][a-z0-9-]{1,46}[a-z0-9]$ && "$snapshot_id" != *--* ]] \
  || { printf '%s\n' 'unsafe RDS snapshot identifier' >&2; exit 1; }
for version_id in "$data_receipt_version_id" "$direct_readiness_version_id"; do
  [[ "$version_id" =~ ^[A-Za-z0-9._~+/=-]+$ && ${#version_id} -le 1024 ]] \
    || { printf '%s\n' 'immutable receipt VersionId is missing or unsafe' >&2; exit 1; }
done
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

sha256_text() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
  else
    shasum -a 256 | awk '{print $1}'
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
manifest_search_enabled=$(jq -er '.search.enabled | select(type == "boolean")' "$manifest") \
  || { printf '%s\n' 'dataset manifest search contract is invalid' >&2; exit 1; }

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
  .databaseBootstrap == "dump" and
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
' "$data_receipt" >/dev/null || { printf '%s\n' 'data bootstrap receipt cannot promote a snapshot' >&2; exit 1; }

source_lab_run_id=$(jq -r '.runId' "$data_receipt")
source_rds_resource_id=$(jq -r '.rdsResourceId' "$data_receipt")
[[ "$rds_instance_id" == "airbob-$source_lab_run_id" ]] \
  || { printf '%s\n' 'RDS instance identifier does not match the bootstrap receipt run' >&2; exit 1; }

data_receipt_key="data-bootstrap/$source_lab_run_id/$dataset_release.json"
direct_readiness_key="measurements/$source_lab_run_id/direct-readiness.json"
data_receipt_sha=$(sha256_file "$data_receipt")
direct_readiness_sha=$(sha256_file "$direct_readiness")
data_receipt_version_id_sha=$(printf '%s' "$data_receipt_version_id" | sha256_text)
direct_readiness_version_id_sha=$(printf '%s' "$direct_readiness_version_id" | sha256_text)
data_projection=$(jq -cS 'del(.runId,.databaseBootstrap,.rdsResourceId,.verifiedAt)' "$data_receipt")
data_projection_sha=$(printf '%s' "$data_projection" | sha256_text)

jq -e \
  --arg run "$source_lab_run_id" --arg release "$dataset_release" \
  --arg manifestSha "$manifest_sha" --arg dataKey "$data_receipt_key" \
  --arg dataVersionId "$data_receipt_version_id" --arg dataSha "$data_receipt_sha" \
  --arg dataProjectionSha "$data_projection_sha" --arg rdsIdentifier "$rds_instance_id" \
  --arg rdsResourceId "$source_rds_resource_id" \
  --arg rdsEngineVersion "$(jq -r '.rdsEngineVersion' "$data_receipt")" \
  --argjson searchEnabled "$manifest_search_enabled" '
  def exact_keys($expected): (keys | sort) == ($expected | sort);
  def sha256: type == "string" and test("^[0-9a-f]{64}$");
  def version_id: type == "string" and length > 0 and length <= 1024 and
    test("^[A-Za-z0-9._~+/=-]+$");
  def utc_time: type == "string" and
    test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$");
  def object_time: type == "string" and
    test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?(Z|[+]00:00)$");
  def image_ref: type == "string" and test("^[^[:space:]@]+@sha256:[0-9a-f]{64}$");

  exact_keys([
    "schemaVersion", "status", "runId", "fencingToken", "executionCode",
    "dataset", "bundle", "images", "bootstrap", "networkClearance", "actual",
    "topology", "ociAuthority", "smoke", "timing", "comparisonProjection",
    "comparisonProjectionSha256"
  ]) and
  .schemaVersion == 1 and .status == "ready" and .runId == $run and
  (.fencingToken | type == "number" and floor == . and . > 0) and

  (.executionCode | exact_keys(["commit", "operatorTreeSha256"])) and
  (.executionCode.commit | type == "string" and test("^[0-9a-f]{40}$")) and
  (.executionCode.operatorTreeSha256 | sha256) and

  (.dataset | exact_keys(["release", "manifestVersionId", "manifestSha256"])) and
  .dataset.release == $release and (.dataset.manifestVersionId | version_id) and
  .dataset.manifestSha256 == $manifestSha and

  (.bundle | exact_keys(["commit", "archiveSha256", "checksumVersionId", "manifestVersionId", "manifestSha256"])) and
  (.bundle.commit | type == "string" and test("^[0-9a-f]{40}$")) and
  (.bundle.archiveSha256 | sha256) and (.bundle.checksumVersionId | version_id) and
  (.bundle.manifestVersionId | version_id) and (.bundle.manifestSha256 | sha256) and

  (.images | exact_keys(["app", "infra"])) and (.images.app | image_ref) and
  (.images.infra | exact_keys([
    "REDIS_IMAGE", "REDIS_EXPORTER_IMAGE", "NODE_EXPORTER_IMAGE", "KAFKA_IMAGE",
    "DEBEZIUM_IMAGE", "ELASTICSEARCH_IMAGE", "ELASTICSEARCH_EXPORTER_IMAGE",
    "PROMETHEUS_IMAGE", "GRAFANA_IMAGE"
  ])) and (.images.infra | all(.[]; image_ref)) and

  (.bootstrap | exact_keys([
    "mode", "rdsSnapshotIdentifier", "rdsSnapshotSourceRunId",
    "rdsSnapshotSourceResourceId", "dataProjectionSha256", "receipt"
  ])) and
  .bootstrap.mode == "dump" and .bootstrap.rdsSnapshotIdentifier == null and
  .bootstrap.rdsSnapshotSourceRunId == null and .bootstrap.rdsSnapshotSourceResourceId == null and
  .bootstrap.dataProjectionSha256 == $dataProjectionSha and
  (.bootstrap.receipt | exact_keys(["key", "versionId", "sha256", "lastModified"])) and
  .bootstrap.receipt.key == $dataKey and .bootstrap.receipt.versionId == $dataVersionId and
  .bootstrap.receipt.sha256 == $dataSha and (.bootstrap.receipt.lastModified | object_time) and

  (.networkClearance | exact_keys(["key", "versionId", "sha256", "lastModified", "projectionSha256"])) and
  (.networkClearance.key | type == "string" and
    startswith("network-clearance/" + $run + "/") and endswith(".json")) and
  (.networkClearance.versionId | version_id) and (.networkClearance.sha256 | sha256) and
  (.networkClearance.lastModified | object_time) and (.networkClearance.projectionSha256 | sha256) and

  (.actual | exact_keys(["ami", "rds", "rdsParameterGroupFamily", "alb", "autoScalingGroup"])) and
  (.actual.ami | exact_keys(["id", "shape"])) and
  (.actual.ami.id | type == "string" and test("^ami-[0-9a-f]{17}$")) and
  (.actual.ami.shape | exact_keys(["imageId", "creationDate", "architecture", "rootDeviceType", "virtualizationType"])) and
  .actual.ami.shape.imageId == .actual.ami.id and
  (.actual.ami.shape.creationDate | type == "string" and length > 0) and
  (.actual.ami.shape.architecture | type == "string" and length > 0) and
  (.actual.ami.shape.rootDeviceType | type == "string" and length > 0) and
  (.actual.ami.shape.virtualizationType | type == "string" and length > 0) and
  (.actual.rds | exact_keys([
    "identifier", "resourceId", "class", "engine", "engineVersion", "allocatedStorageGiB",
    "storageType", "iops", "storageThroughputMiBps", "multiAz", "storageEncrypted",
    "publiclyAccessible", "availabilityZone", "parameterGroups"
  ])) and
  .actual.rds.identifier == $rdsIdentifier and .actual.rds.resourceId == $rdsResourceId and
  .actual.rds.class == "db.t3.micro" and .actual.rds.engine == "mysql" and
  .actual.rds.engineVersion == $rdsEngineVersion and .actual.rds.allocatedStorageGiB == 100 and
  .actual.rds.storageType == "gp3" and .actual.rds.iops == 3000 and
  .actual.rds.storageThroughputMiBps == 125 and .actual.rds.multiAz == false and
  .actual.rds.storageEncrypted == true and .actual.rds.publiclyAccessible == false and
  (.actual.rds.availabilityZone | type == "string" and test("^ap-northeast-2[a-z]$")) and
  (.actual.rds.parameterGroups | type == "array" and length == 1 and
    all(.[]; type == "string" and length > 0)) and
  .actual.rdsParameterGroupFamily == "mysql8.0" and
  (.actual.alb | exact_keys([
    "arn", "dnsName", "targetGroupArn", "autoScalingGroupName", "securityGroupId",
    "shape", "observedIngress"
  ])) and
  (.actual.alb.arn | type == "string" and length > 0) and
  (.actual.alb.dnsName | type == "string" and length > 0) and
  (.actual.alb.targetGroupArn | type == "string" and length > 0) and
  (.actual.alb.autoScalingGroupName | type == "string" and length > 0) and
  (.actual.alb.securityGroupId | type == "string" and test("^sg-[0-9a-f]+$")) and
  (.actual.alb.shape | exact_keys(["arn", "dnsName", "scheme", "type", "ipAddressType", "availabilityZones", "securityGroups"])) and
  .actual.alb.shape.arn == .actual.alb.arn and .actual.alb.shape.dnsName == .actual.alb.dnsName and
  .actual.alb.shape.scheme == "internet-facing" and .actual.alb.shape.type == "application" and
  .actual.alb.shape.ipAddressType == "ipv4" and
  (.actual.alb.shape.availabilityZones | type == "array" and length == 2 and
    ((unique | length) == 2)) and
  .actual.alb.shape.securityGroups == [.actual.alb.securityGroupId] and
  (.actual.alb.observedIngress | type == "array" and length == 1) and
  (.actual.alb.observedIngress[0] | exact_keys([
    "ruleId", "groupId", "isEgress", "ipProtocol", "fromPort", "toPort", "cidrIpv4",
    "cidrIpv6", "prefixListId", "referencedGroupId"
  ])) and
  (.actual.alb.observedIngress[0].ruleId | type == "string" and test("^sgr-[0-9a-f]+$")) and
  .actual.alb.observedIngress[0].groupId == .actual.alb.securityGroupId and
  .actual.alb.observedIngress[0].isEgress == false and
  .actual.alb.observedIngress[0].ipProtocol == "tcp" and
  .actual.alb.observedIngress[0].fromPort == 443 and .actual.alb.observedIngress[0].toPort == 443 and
  .actual.alb.observedIngress[0].cidrIpv4 == .topology.albIngressCidr and
  .actual.alb.observedIngress[0].cidrIpv6 == null and
  .actual.alb.observedIngress[0].prefixListId == null and
  .actual.alb.observedIngress[0].referencedGroupId == null and
  (.actual.autoScalingGroup | exact_keys(["name", "min", "desired", "max"])) and
  .actual.autoScalingGroup.name == .actual.alb.autoScalingGroupName and
  .actual.autoScalingGroup.min == 1 and .actual.autoScalingGroup.desired == 1 and
  .actual.autoScalingGroup.max == 1 and

  (.topology | exact_keys(["mode", "policy", "dnsMode", "albIngressCidr", "cacheEnabled", "loadGeneratorEnabled"])) and
  .topology.mode == "performance" and .topology.policy == "integrated-smoke" and
  .topology.dnsMode == "direct-only" and
  (.topology.albIngressCidr | type == "string" and
    test("^([0-9]{1,3}[.]){3}[0-9]{1,3}/32$")) and
  (.topology.cacheEnabled | type == "boolean") and .topology.loadGeneratorEnabled == false and

  (.ociAuthority | exact_keys([
    "status", "observedAt", "zoneId", "fqdn", "originIpv4", "recordSetSha256",
    "route53", "directHealth", "publicHealth"
  ])) and
  .ociAuthority.status == "verified" and (.ociAuthority.observedAt | utc_time) and
  (.ociAuthority.zoneId | type == "string" and length > 0) and
  .ociAuthority.fqdn == "api.airbob.cloud." and
  (.ociAuthority.originIpv4 | type == "string" and test("^([0-9]{1,3}[.]){3}[0-9]{1,3}$")) and
  (.ociAuthority.recordSetSha256 | sha256) and .ociAuthority.route53 == "oci-only" and
  .ociAuthority.directHealth == "healthy" and .ociAuthority.publicHealth == "healthy" and

  (.smoke | exact_keys(["health", "accommodationDetail", "search"])) and
  (.smoke.health | exact_keys(["passed"])) and .smoke.health.passed == true and
  (.smoke.accommodationDetail | exact_keys(["id", "passed"])) and
  (.smoke.accommodationDetail.id | type == "number" and floor == . and . > 0) and
  .smoke.accommodationDetail.passed == true and
  (.smoke.search | exact_keys(["enabled", "querySha256", "passed"])) and
  .smoke.search.enabled == $searchEnabled and .smoke.search.passed == true and
  (if $searchEnabled then (.smoke.search.querySha256 | sha256)
   else .smoke.search.querySha256 == null end) and

  (.timing | exact_keys([
    "resourceStartedAt", "dataReadyAt", "directReadyAt", "resourceToDataReadySeconds",
    "resourceToDirectReadySeconds"
  ])) and
  (.timing.resourceStartedAt | utc_time) and (.timing.dataReadyAt | utc_time) and
  (.timing.directReadyAt | utc_time) and
  (.timing.resourceToDataReadySeconds | type == "number" and floor == . and . >= 0) and
  (.timing.resourceToDirectReadySeconds | type == "number" and floor == . and . >= 0) and
  (.comparisonProjection | type == "object") and (.comparisonProjectionSha256 | sha256)
' "$direct_readiness" >/dev/null \
  || { printf '%s\n' 'direct-readiness receipt cannot promote a snapshot' >&2; exit 1; }

comparison_projection=$(jq -cSf "$comparison_projection_filter" "$direct_readiness") \
  || { printf '%s\n' 'direct-readiness comparison projection is invalid' >&2; exit 1; }
comparison_projection_sha=$(printf '%s\n' "$comparison_projection" | sha256_text)
jq -e --argjson projection "$comparison_projection" --arg sha "$comparison_projection_sha" '
  .comparisonProjection == $projection and .comparisonProjectionSha256 == $sha
' "$direct_readiness" >/dev/null \
  || { printf '%s\n' 'direct-readiness comparison projection binding is invalid' >&2; exit 1; }

evidence_bucket=airbob-performance-lab-evidence-942632789808
verification_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-snapshot-promotion.XXXXXX") \
  || { printf '%s\n' 'cannot create immutable receipt verification directory' >&2; exit 1; }
cleanup_verification() {
  rm -rf "$verification_dir"
}
trap cleanup_verification EXIT HUP INT TERM
aws --region "$region" s3api get-object --bucket "$evidence_bucket" \
  --key "$data_receipt_key" --version-id "$data_receipt_version_id" \
  "$verification_dir/data-bootstrap.json" >/dev/null \
  || { printf '%s\n' 'exact immutable data bootstrap receipt is unavailable' >&2; exit 1; }
cmp -s "$data_receipt" "$verification_dir/data-bootstrap.json" \
  || { printf '%s\n' 'local data bootstrap receipt differs from the exact immutable object' >&2; exit 1; }
aws --region "$region" s3api get-object --bucket "$evidence_bucket" \
  --key "$direct_readiness_key" --version-id "$direct_readiness_version_id" \
  "$verification_dir/direct-readiness.json" >/dev/null \
  || { printf '%s\n' 'exact immutable direct-readiness receipt is unavailable' >&2; exit 1; }
cmp -s "$direct_readiness" "$verification_dir/direct-readiness.json" \
  || { printf '%s\n' 'local direct-readiness receipt differs from the exact immutable object' >&2; exit 1; }

instance_json=$(aws --region "$region" rds describe-db-instances --db-instance-identifier "$rds_instance_id")
jq -e --arg resourceId "$source_rds_resource_id" --arg engineVersion "$(jq -r '.rdsEngineVersion' "$data_receipt")" '
  .DBInstances | length == 1 and
  .[0].DBInstanceStatus == "available" and
  .[0].Engine == "mysql" and
  .[0].EngineVersion == $engineVersion and
  .[0].DbiResourceId == $resourceId and
  .[0].DBInstanceClass == "db.t3.micro" and
  .[0].AllocatedStorage == 100 and
  .[0].StorageType == "gp3" and
  .[0].Iops == 3000 and
  .[0].StorageThroughput == 125 and
  .[0].MultiAZ == false and
  .[0].StorageEncrypted == true and
  .[0].PubliclyAccessible == false
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
      "Key=ManifestSha256,Value=$manifest_sha" \
      "Key=SourceLabRunId,Value=$source_lab_run_id" \
      "Key=SourceRdsResourceId,Value=$source_rds_resource_id" \
      "Key=DataBootstrapKey,Value=$data_receipt_key" \
      "Key=DataBootstrapVersionIdSha256,Value=$data_receipt_version_id_sha" \
      "Key=DataBootstrapSha256,Value=$data_receipt_sha" \
      "Key=DirectReadinessKey,Value=$direct_readiness_key" \
      "Key=DirectReadinessVersionIdSha256,Value=$direct_readiness_version_id_sha" \
      "Key=DirectReadinessSha256,Value=$direct_readiness_sha" \
      Key=PromotionReceiptSchemaVersion,Value=2 >/dev/null
fi
aws --region "$region" rds wait db-snapshot-available --db-snapshot-identifier "$snapshot_id"
snapshot_json=$(aws --region "$region" rds describe-db-snapshots --db-snapshot-identifier "$snapshot_id")
jq -e \
  --arg snapshot "$snapshot_id" --arg release "$dataset_release" --arg runId "$dataset_run_id" \
  --arg dumpSha "$dump_sha" --arg flyway "$flyway_version" --arg manifestSha "$manifest_sha" \
  --arg sourceInstance "$rds_instance_id" --arg sourceResourceId "$source_rds_resource_id" \
  --arg sourceRunId "$source_lab_run_id" --arg engineVersion "$(jq -r '.rdsEngineVersion' "$data_receipt")" \
  --arg dataKey "$data_receipt_key" --arg dataVersionIdSha "$data_receipt_version_id_sha" \
  --arg dataSha "$data_receipt_sha" --arg readinessKey "$direct_readiness_key" \
  --arg readinessVersionIdSha "$direct_readiness_version_id_sha" \
  --arg readinessSha "$direct_readiness_sha" '
  .DBSnapshots as $snapshots |
  $snapshots[0] as $candidate |
  ($candidate.TagList | map({key: .Key, value: .Value}) | from_entries) as $tags |
  ($snapshots | length == 1) and
  $candidate.DBSnapshotIdentifier == $snapshot and
  $candidate.Status == "available" and
  $candidate.Engine == "mysql" and
  $candidate.EngineVersion == $engineVersion and
  $candidate.DBInstanceIdentifier == $sourceInstance and
  $candidate.DbiResourceId == $sourceResourceId and
  $candidate.AllocatedStorage == 100 and
  $candidate.StorageType == "gp3" and
  $candidate.Iops == 3000 and
  $candidate.StorageThroughput == 125 and
  $candidate.Encrypted == true and
  $tags.DatasetRelease == $release and
  $tags.DatasetRunId == $runId and
  $tags.DumpSha256 == $dumpSha and
  $tags.FlywayVersion == $flyway and
  $tags.ManifestSha256 == $manifestSha and
  $tags.SourceLabRunId == $sourceRunId and
  $tags.SourceRdsResourceId == $sourceResourceId and
  $tags.DataBootstrapKey == $dataKey and
  $tags.DataBootstrapVersionIdSha256 == $dataVersionIdSha and
  $tags.DataBootstrapSha256 == $dataSha and
  $tags.DirectReadinessKey == $readinessKey and
  $tags.DirectReadinessVersionIdSha256 == $readinessVersionIdSha and
  $tags.DirectReadinessSha256 == $readinessSha and
  $tags.PromotionReceiptSchemaVersion == "2" and
  $tags.Project == "airbob" and
  $tags.Environment == "performance-lab" and
  $tags.Stack == "dataset" and
  $tags.ManagedBy == "dataset-publisher" and
  $tags.Persistence == "persistent"
' <<<"$snapshot_json" >/dev/null || { printf '%s\n' 'RDS snapshot source identity or tags do not match the release tuple' >&2; exit 1; }

created_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
receipt_temp=$(mktemp "$output_parent/.${output_name}.XXXXXX") \
  || { printf '%s\n' 'cannot create temporary snapshot promotion receipt' >&2; exit 1; }
cleanup() {
  rm -f "$receipt_temp"
  cleanup_verification
}
trap cleanup EXIT HUP INT TERM
jq -n \
  --arg snapshotIdentifier "$snapshot_id" --arg datasetRelease "$dataset_release" \
  --arg datasetRunId "$dataset_run_id" --arg dumpSha256 "$dump_sha" \
  --arg flywayVersion "$flyway_version" --arg manifestSha256 "$manifest_sha" \
  --arg sourceLabRunId "$source_lab_run_id" \
  --arg sourceRdsResourceId "$source_rds_resource_id" \
  --arg dataKey "$data_receipt_key" --arg dataVersionId "$data_receipt_version_id" \
  --arg dataVersionIdSha256 "$data_receipt_version_id_sha" --arg dataSha256 "$data_receipt_sha" \
  --arg readinessKey "$direct_readiness_key" --arg readinessVersionId "$direct_readiness_version_id" \
  --arg readinessVersionIdSha256 "$direct_readiness_version_id_sha" \
  --arg readinessSha256 "$direct_readiness_sha" --arg createdAt "$created_at" '
  {
    schemaVersion: 2,
    snapshotIdentifier: $snapshotIdentifier,
    datasetRelease: $datasetRelease,
    datasetRunId: $datasetRunId,
    dumpSha256: $dumpSha256,
    flywayVersion: $flywayVersion,
    manifestSha256: $manifestSha256,
    sourceLabRunId: $sourceLabRunId,
    sourceRdsResourceId: $sourceRdsResourceId,
    sourceDataBootstrapReceipt: {
      key: $dataKey,
      versionId: $dataVersionId,
      versionIdSha256: $dataVersionIdSha256,
      sha256: $dataSha256
    },
    sourceDirectReadinessReceipt: {
      key: $readinessKey,
      versionId: $readinessVersionId,
      versionIdSha256: $readinessVersionIdSha256,
      sha256: $readinessSha256
    },
    persistence: "persistent",
    createdAt: $createdAt
  }
' > "$receipt_temp"
chmod 600 "$receipt_temp"
ln "$receipt_temp" "$output_json" 2>/dev/null \
  || { printf '%s\n' 'snapshot promotion receipt output appeared before publication' >&2; exit 1; }
rm -f "$receipt_temp"
cleanup_verification
trap - EXIT HUP INT TERM

printf '%s\n' 'RDS snapshot promotion candidate verified'
