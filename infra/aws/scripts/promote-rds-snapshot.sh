#!/usr/bin/env bash
set -euo pipefail
umask 077
fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ $# == 6 ]] || fail 'usage: promote-rds-snapshot.sh MANIFEST QUALIFICATION QUALIFICATION_VERSION_ID RDS_INSTANCE_ID SNAPSHOT_ID OUTPUT_JSON'
manifest=$1 qualification=$2 version=$3 instance=$4 snapshot=$5 output=$6
region=${AIRBOB_REGION:-ap-northeast-2}
script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
source "$script_dir/dataset-qualification.sh"
[[ "$region" == ap-northeast-2 ]] || fail 'snapshot promotion is pinned to Seoul'
for input in "$manifest" "$qualification"; do
  [[ -f "$input" && ! -L "$input" ]] || fail 'unsafe snapshot promotion input'
done
[[ ! -e "$output" && ! -L "$output" ]] || fail 'snapshot promotion output already exists'
[[ -d "$(dirname -- "$output")" ]] || fail 'snapshot promotion output directory is missing'
[[ "$instance" =~ ^airbob-[a-z0-9][a-z0-9-]{1,54}[a-z0-9]$ && "$instance" != *--* ]] || fail 'unsafe RDS instance identifier'
[[ "$snapshot" =~ ^airbob-dataset-[a-z0-9][a-z0-9-]{1,46}[a-z0-9]$ && "$snapshot" != *--* ]] || fail 'unsafe snapshot identifier'
[[ "$version" =~ ^[A-Za-z0-9._~+/=-]+$ && "$version" != null && "$version" != None && ${#version} -le 1024 ]] || fail 'missing immutable qualification version'
run=$(jq -er '.runId' "$qualification") resource=$(jq -er '.rdsResourceId' "$qualification")
[[ "$instance" == "airbob-$run" ]] || fail 'source instance must belong to the exact preparation run'
engine=$(jq -er '.rdsEngineVersion' "$qualification")
verify_dataset_qualification "$qualification" "$manifest" "$run" "$resource" "$engine" \
  || fail 'only a full initial dataset qualification can promote a snapshot'
release=$(jq -er '.datasetRelease' "$manifest")
[[ "$release" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ ]] || fail 'unsafe dataset release'
key="data-bootstrap/$run/dataset-qualification.json"
bucket=airbob-performance-lab-evidence-942632789808
version_sha=$(printf '%s' "$version" | qualification_sha_text)
content_sha=$(qualification_sha_file "$qualification")
manifest_sha=$(qualification_sha_file "$manifest")
work=$(mktemp -d "${TMPDIR:-/tmp}/airbob-promote.XXXXXX")
trap 'rm -rf "$work"' EXIT
fetch_dataset_qualification "$bucket" "$key" "$version_sha" "$content_sha" "$work/qualification.json" "$region" \
  || fail 'local qualification differs from the current immutable S3 version'
cmp -s "$qualification" "$work/qualification.json" || fail 'qualification bytes differ'

# A preparation run must never host application writers. Normal up receipts cannot qualify.
aws --region "$region" autoscaling describe-auto-scaling-groups --output json > "$work/asgs.json"
jq -e --arg run "$run" '[.AutoScalingGroups[] | select(any(.Tags[]?; .Key == "RunId" and .Value == $run))] | length == 0' \
  "$work/asgs.json" >/dev/null || fail 'source run contains an application ASG; use a dedicated preparation run'
aws --region "$region" rds describe-db-instances --db-instance-identifier "$instance" > "$work/instance.json"
jq -e --arg id "$instance" --arg resource "$resource" --arg engine "$engine" --arg run "$run" '
  .DBInstances | length == 1 and (.[0] |
    .DBInstanceIdentifier == $id and .DbiResourceId == $resource and .DBInstanceStatus == "available" and
    .Engine == "mysql" and .EngineVersion == $engine and .StorageEncrypted == true and
    .PubliclyAccessible == false and .StorageType == "gp3" and .Iops == 3000 and
    (.AllocatedStorage | type == "number" and . >= 20) and
    any(.TagList[]; .Key == "RunId" and .Value == $run))
' "$work/instance.json" >/dev/null || fail 'live source RDS differs from the qualified preparation run'
storage=$(jq -r '.DBInstances[0].AllocatedStorage' "$work/instance.json")
# Snapshot tags contain hashes, never raw VersionIds or credentials. The foundation
# allowlist is a separate registration step; this command creates a candidate only.
jq -n --slurpfile m "$manifest" --arg release "$release" --arg run "$run" --arg resource "$resource" \
  --arg key "$key" --arg versionSha "$version_sha" --arg sha "$content_sha" --arg manifestSha "$manifest_sha" '
  {Project:"airbob",Environment:"performance-lab",Stack:"dataset",ManagedBy:"dataset-publisher",
   Persistence:"persistent",PromotionReceiptSchemaVersion:"3",DatasetRelease:$release,
   DatasetRunId:$m[0].datasetRunId,DumpSha256:$m[0].mysql.dumpSha256,FlywayVersion:$m[0].mysql.flywayVersion,
   ManifestSha256:$manifestSha,SourceLabRunId:$run,SourceRdsResourceId:$resource,
   DataBootstrapKey:$key,DataBootstrapVersionIdSha256:$versionSha,DataBootstrapSha256:$sha}
  | to_entries | map({Key:.key,Value:.value})
' > "$work/tags.json"
if ! aws --region "$region" rds describe-db-snapshots --db-snapshot-identifier "$snapshot" \
    > "$work/snapshot.json" 2> "$work/snapshot-error"; then
  # Permission/network failures must not be treated as absence.
  grep -q 'DBSnapshotNotFound' "$work/snapshot-error" || fail 'cannot determine whether the candidate snapshot exists'
  aws --region "$region" rds create-db-snapshot --db-instance-identifier "$instance" \
    --db-snapshot-identifier "$snapshot" --tags "file://$work/tags.json" >/dev/null
fi
aws --region "$region" rds wait db-snapshot-available --db-snapshot-identifier "$snapshot"
aws --region "$region" rds describe-db-snapshots --db-snapshot-identifier "$snapshot" > "$work/snapshot.json"
jq -e --arg id "$snapshot" --arg instance "$instance" --arg resource "$resource" --arg engine "$engine" \
  --argjson storage "$storage" --slurpfile expected "$work/tags.json" '
  .DBSnapshots | length == 1 and (.[0] |
    (.TagList | map({key:.Key,value:.Value}) | from_entries) as $tags |
    .DBSnapshotIdentifier == $id and .DBInstanceIdentifier == $instance and .DbiResourceId == $resource and
    .Engine == "mysql" and .EngineVersion == $engine and .Status == "available" and .Encrypted == true and
    .SnapshotType == "manual" and .AllocatedStorage == $storage and .StorageType == "gp3" and .Iops == 3000 and
    (.TagList | (map(.Key) | unique | length) == length) and
    all($expected[0][]; $tags[.Key] == .Value))
' "$work/snapshot.json" >/dev/null || fail 'candidate snapshot identity or approval binding differs'
jq -n --arg snapshot "$snapshot" --arg release "$release" --arg run "$run" --arg resource "$resource" \
  --arg key "$key" --arg version "$version" --arg versionSha "$version_sha" --arg sha "$content_sha" \
  --arg manifestSha "$manifest_sha" --arg now "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" '
  {schemaVersion:3,snapshotIdentifier:$snapshot,datasetRelease:$release,manifestSha256:$manifestSha,
   sourceLabRunId:$run,sourceRdsResourceId:$resource,sourceQualification:{key:$key,versionId:$version,
   versionIdSha256:$versionSha,sha256:$sha},persistence:"persistent",createdAt:$now}
' > "$work/promotion.json"
# Atomic no-overwrite output works across filesystems too.
output_temp=$(mktemp "$(dirname -- "$output")/.airbob-promotion.XXXXXX")
cp "$work/promotion.json" "$output_temp"
if ! ln "$output_temp" "$output"; then rm -f "$output_temp"; fail 'promotion output appeared during publication'; fi
rm -f "$output_temp"
printf '%s\n' 'RDS snapshot candidate verified; register its identifier in the foundation allowlist before reuse'
