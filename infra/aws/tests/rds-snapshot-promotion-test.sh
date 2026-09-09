#!/usr/bin/env bash
set -euo pipefail
repo=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
source "$repo/infra/aws/scripts/dataset-qualification.sh"
work=$(mktemp -d "${TMPDIR:-/tmp}/airbob-qualification-test.XXXXXX")
trap 'rm -rf "$work"' EXIT
fail() { printf '%s\n' "$1" >&2; exit 1; }
jq ' .search.enabled=true ' "$repo/infra/aws/lab/tests/fixtures/dataset-manifest.json" > "$work/manifest.json"
run=lab-prepare resource=db-ABCDEFGHIJKLMNOPQRSTUVWXYZ
write_dataset_qualification "$work/manifest.json" "$work/qualification.json" "$run" "$resource" 8.4.8 "$(printf '%064d' 1)"
for field in '.verification.mode="approved-snapshot"' '.verification.search="disabled"' '.dataset.mysql.dumpSha256=("9"*64)' '.rdsEngineVersion="8.0.42"' '.extra=true'; do
  jq "$field" "$work/qualification.json" > "$work/changed.json"
  if verify_dataset_qualification "$work/changed.json" "$work/manifest.json" "$run" "$resource" 8.4.8; then fail "qualification accepted $field"; fi
done
mkdir "$work/bin"
export QUALIFICATION_TEST_ROOT="$work"
cat > "$work/bin/aws" <<'AWS'
#!/usr/bin/env bash
set -euo pipefail
w=$QUALIFICATION_TEST_ROOT
printf '%s\n' "$*" >> "$w/aws.log"
case " $* " in
  *' s3api head-object '*) printf '%s\n' "${FAKE_VERSION:-qualification-v1}" ;;
  *' s3api get-object '*) cp "$w/qualification.json" "${!#}" ;;
  *' autoscaling describe-auto-scaling-groups '*) cat "$w/asgs.json" ;;
  *' rds describe-db-instances '*) cat "$w/instance.json" ;;
  *' rds describe-db-snapshots '*)
    if [[ -f "$w/snapshot.json" ]]; then cat "$w/snapshot.json"; else printf '%s\n' DBSnapshotNotFound >&2; exit 1; fi ;;
  *' rds create-db-snapshot '*)
    previous=''
    for arg in "$@"; do
      if [[ "$previous" == --tags ]]; then cp "${arg#file://}" "$w/tags.json"; fi
      previous=$arg
    done
    jq --slurpfile tags "$w/tags.json" '.DBInstances[0] | {DBSnapshotIdentifier:"airbob-dataset-fixture",DBInstanceIdentifier,DbiResourceId,Engine,EngineVersion,AllocatedStorage,StorageType,Iops,Encrypted:.StorageEncrypted,Status:"available",SnapshotType:"manual",TagList:$tags[0]} | {DBSnapshots:[.]}' "$w/instance.json" > "$w/snapshot.json" ;;
  *' rds wait '*) : ;;
  *) exit 88 ;;
esac
AWS
chmod +x "$work/bin/aws"
export PATH="$work/bin:$PATH"
printf '%s\n' '{"AutoScalingGroups":[]}' > "$work/asgs.json"
jq -n '{DBInstances:[{DBInstanceIdentifier:"airbob-lab-prepare",DbiResourceId:"db-ABCDEFGHIJKLMNOPQRSTUVWXYZ",Engine:"mysql",EngineVersion:"8.4.8",DBInstanceStatus:"available",StorageEncrypted:true,PubliclyAccessible:false,StorageType:"gp3",Iops:3000,AllocatedStorage:100,TagList:[{Key:"RunId",Value:"lab-prepare"}]}]}' > "$work/instance.json"
promote() { "$repo/infra/aws/scripts/promote-rds-snapshot.sh" "$work/manifest.json" "$work/qualification.json" qualification-v1 airbob-lab-prepare airbob-dataset-fixture "$1"; }
if FAKE_VERSION=changed-v2 promote "$work/wrong-version.json" > /dev/null 2>&1; then fail 'promotion accepted replaced S3 version'; fi
[[ ! -f "$work/snapshot.json" ]] || fail 'invalid version created a snapshot'
promote "$work/first.json"
promote "$work/retry.json"
[[ $(grep -c ' rds create-db-snapshot ' "$work/aws.log") == 1 ]] || fail 'retry created another snapshot'
jq -e '.schemaVersion==3 and .sourceQualification.key=="data-bootstrap/lab-prepare/dataset-qualification.json" and (has("sourceDirectReadinessReceipt")|not)' "$work/first.json" >/dev/null
cp "$work/snapshot.json" "$work/original-snapshot.json"
for drift in '.EngineVersion="8.4.9"' '.DbiResourceId="db-ZYXWVUTSRQPONMLKJIHGFEDCBA"' '.Encrypted=false' '.TagList[0].Value="wrong"'; do
  jq ".DBSnapshots[0] |= ($drift)" "$work/original-snapshot.json" > "$work/snapshot.json"
  if promote "$work/rejected.json" > /dev/null 2>&1; then fail "promotion accepted $drift"; fi
  [[ ! -f "$work/rejected.json" ]] || fail 'failed promotion published a receipt'
done
cp "$work/original-snapshot.json" "$work/snapshot.json"
printf '%s\n' '{"AutoScalingGroups":[{"Tags":[{"Key":"RunId","Value":"lab-prepare"}]}]}' > "$work/asgs.json"
if promote "$work/writer.json" > /dev/null 2>&1; then fail 'promotion accepted a preparation run with an app ASG'; fi
printf '%s\n' 'dataset qualification and RDS promotion tests passed'
