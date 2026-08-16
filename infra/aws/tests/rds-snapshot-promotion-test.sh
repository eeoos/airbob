#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
script="$repo_root/infra/aws/scripts/promote-rds-snapshot.sh"
manifest="$repo_root/infra/aws/lab/tests/fixtures/dataset-manifest.json"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-snapshot-test.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM
fake_bin="$tmp_dir/bin"
mkdir -p "$fake_bin"

if command -v sha256sum >/dev/null 2>&1; then
  manifest_sha=$(sha256sum "$manifest" | awk '{print $1}')
else
  manifest_sha=$(shasum -a 256 "$manifest" | awk '{print $1}')
fi

cat > "$tmp_dir/receipt.json" <<'JSON'
{
  "schemaVersion": 1,
  "runId": "phase3-test",
  "datasetRelease": "rehearsal-v16",
  "datasetRunId": "etl-20260816-001",
  "releaseKind": "pipeline-rehearsal",
  "databaseBootstrap": "dump",
  "dumpSha256": "94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2",
  "flywayVersion": "16",
  "migrationChecksumSha256": "4444444444444444444444444444444444444444444444444444444444444444",
  "schemaFingerprintSha256": "5555555555555555555555555555555555555555555555555555555555555555",
  "rdsResourceId": "db-ABCDEFGHIJKLMNOPQRSTUVWX",
  "rdsEngineVersion": "8.0.40",
  "outboxState": "empty",
  "redisState": "empty",
  "kafkaTopics": [
    { "name": "PAYMENT.events", "partitions": 1, "retentionMs": 86400000 },
    { "name": "RESERVATION.events", "partitions": 1, "retentionMs": 86400000 },
    { "name": "ACCOMMODATIONS.events", "partitions": 1, "retentionMs": 86400000 }
  ],
  "connectorState": "RUNNING",
  "searchState": "skipped",
  "verifiedAt": "2030-01-01T00:30:00Z"
}
JSON
jq --arg manifestSha "$manifest_sha" '.datasetManifestSha256 = $manifestSha' \
  "$tmp_dir/receipt.json" > "$tmp_dir/receipt.next"
mv "$tmp_dir/receipt.next" "$tmp_dir/receipt.json"

cat > "$fake_bin/aws" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_AWS_LOG"
case "$*" in
  *"rds describe-db-instances"*)
    cat <<'JSON'
{"DBInstances":[{"DBInstanceStatus":"available","Engine":"mysql","EngineVersion":"8.0.40","DbiResourceId":"db-ABCDEFGHIJKLMNOPQRSTUVWX","MultiAZ":false,"StorageEncrypted":true}]}
JSON
    ;;
  *"rds describe-db-snapshots"*)
    [[ -f "$FAKE_SNAPSHOT_CREATED" ]] || exit 1
    jq -n --arg manifestSha "$FAKE_MANIFEST_SHA" '{DBSnapshots:[{
      DBSnapshotIdentifier:"airbob-dataset-rehearsal-v16",Status:"available",Engine:"mysql",Encrypted:true,
      TagList:[
        {Key:"DatasetRelease",Value:"rehearsal-v16"},
        {Key:"DatasetRunId",Value:"etl-20260816-001"},
        {Key:"DumpSha256",Value:"94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2"},
        {Key:"FlywayVersion",Value:"16"},
        {Key:"ManifestSha256",Value:$manifestSha},
        {Key:"Persistence",Value:"persistent"}
      ]
    }]}'
    ;;
  *"rds create-db-snapshot"*)
    : > "$FAKE_SNAPSHOT_CREATED"
    printf '%s\n' '{}'
    ;;
  *"rds wait db-snapshot-available"*) ;;
  *) printf 'unexpected fake AWS call: %s\n' "$*" >&2; exit 1 ;;
esac
SH
chmod +x "$fake_bin/aws"

[[ -x "$script" ]] || { printf '%s\n' 'RDS snapshot promotion script is missing or not executable' >&2; exit 1; }
PATH="$fake_bin:$PATH" \
FAKE_AWS_LOG="$tmp_dir/aws.log" \
FAKE_SNAPSHOT_CREATED="$tmp_dir/snapshot-created" \
FAKE_MANIFEST_SHA="$manifest_sha" \
AIRBOB_REGION=ap-northeast-2 \
  "$script" "$manifest" "$tmp_dir/receipt.json" \
  airbob-phase3-test airbob-dataset-rehearsal-v16 "$tmp_dir/promotion.json" >/dev/null

jq -e --arg manifestSha "$manifest_sha" '
  .schemaVersion == 1 and
  .snapshotIdentifier == "airbob-dataset-rehearsal-v16" and
  .datasetRelease == "rehearsal-v16" and
  .manifestSha256 == $manifestSha and
  .persistence == "persistent"
' "$tmp_dir/promotion.json" >/dev/null
grep -Fq 'Key=Persistence,Value=persistent' "$tmp_dir/aws.log"
grep -Fq 'Key=DatasetRelease,Value=rehearsal-v16' "$tmp_dir/aws.log"

cp "$tmp_dir/receipt.json" "$tmp_dir/bad-receipt.json"
jq '.connectorState = "PAUSED"' "$tmp_dir/bad-receipt.json" > "$tmp_dir/bad.next"
mv "$tmp_dir/bad.next" "$tmp_dir/bad-receipt.json"
if PATH="$fake_bin:$PATH" FAKE_AWS_LOG="$tmp_dir/aws.log" FAKE_SNAPSHOT_CREATED="$tmp_dir/snapshot-created" \
  FAKE_MANIFEST_SHA="$manifest_sha" AIRBOB_REGION=ap-northeast-2 \
  "$script" "$manifest" "$tmp_dir/bad-receipt.json" \
  airbob-phase3-test airbob-dataset-rehearsal-v16 "$tmp_dir/rejected.json" >/dev/null 2>&1; then
  printf '%s\n' 'snapshot promotion accepted an invalid data receipt' >&2
  exit 1
fi
[[ ! -e "$tmp_dir/rejected.json" ]]

cp "$manifest" "$tmp_dir/tampered-manifest.json"
jq '.source.seed = "different-seed"' "$tmp_dir/tampered-manifest.json" > "$tmp_dir/tampered.next"
mv "$tmp_dir/tampered.next" "$tmp_dir/tampered-manifest.json"
: > "$tmp_dir/aws.log"
if PATH="$fake_bin:$PATH" FAKE_AWS_LOG="$tmp_dir/aws.log" FAKE_SNAPSHOT_CREATED="$tmp_dir/snapshot-created" \
  FAKE_MANIFEST_SHA="$manifest_sha" AIRBOB_REGION=ap-northeast-2 \
  "$script" "$tmp_dir/tampered-manifest.json" "$tmp_dir/receipt.json" \
  airbob-phase3-test airbob-dataset-rehearsal-v16 "$tmp_dir/tampered.json" >/dev/null 2>&1; then
  printf '%s\n' 'snapshot promotion accepted a manifest outside the bootstrap receipt tuple' >&2
  exit 1
fi
[[ ! -e "$tmp_dir/tampered.json" ]]
[[ ! -s "$tmp_dir/aws.log" ]] || { printf '%s\n' 'snapshot promotion contacted AWS before validating the manifest tuple' >&2; exit 1; }

for unsafe_identifier_pair in \
  'airbob-phase3-test-|airbob-dataset-rehearsal-v16' \
  'airbob-phase3--test|airbob-dataset-rehearsal-v16' \
  'airbob-phase3-test|airbob-dataset-rehearsal-v16-' \
  'airbob-phase3-test|airbob-dataset-rehearsal--v16'; do
  unsafe_instance=${unsafe_identifier_pair%%|*}
  unsafe_snapshot=${unsafe_identifier_pair#*|}
  : > "$tmp_dir/aws.log"
  if PATH="$fake_bin:$PATH" FAKE_AWS_LOG="$tmp_dir/aws.log" FAKE_SNAPSHOT_CREATED="$tmp_dir/snapshot-created" \
    FAKE_MANIFEST_SHA="$manifest_sha" AIRBOB_REGION=ap-northeast-2 \
    "$script" "$manifest" "$tmp_dir/receipt.json" \
    "$unsafe_instance" "$unsafe_snapshot" "$tmp_dir/unsafe.json" >/dev/null 2>&1; then
    printf '%s\n' 'snapshot promotion accepted an unsafe RDS identifier' >&2
    exit 1
  fi
  [[ ! -s "$tmp_dir/aws.log" ]] || { printf '%s\n' 'snapshot promotion contacted AWS before rejecting an unsafe identifier' >&2; exit 1; }
done

printf '%s\n' 'RDS snapshot promotion tests passed'
