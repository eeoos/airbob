#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
script="$repo_root/infra/aws/scripts/promote-rds-snapshot.sh"
bootstrap="$repo_root/infra/aws/scripts/bootstrap-data.sh"
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
  "schemaVersion": 2,
  "runId": "phase3-test",
  "datasetRelease": "rehearsal-v20",
  "datasetRunId": "20260816T001530Z-12345678",
  "releaseKind": "pipeline-rehearsal",
  "databaseBootstrap": "dump",
  "dumpSha256": "94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2",
  "flywayVersion": "27",
  "migrationChecksumSha256": "4444444444444444444444444444444444444444444444444444444444444444",
  "schemaFingerprintSha256": "5555555555555555555555555555555555555555555555555555555555555555",
  "validatorSha256": "7777777777777777777777777777777777777777777777777777777777777777",
  "benchmarkDatasetManifestSha256": "6666666666666666666666666666666666666666666666666666666666666666",
  "calibrationSha256": "8888888888888888888888888888888888888888888888888888888888888888",
  "productionSpecSha256": "bbba284a93ff00637928f5cfcf046cce1aab1f848bc31fd467f809d01d73fcdd",
  "qualificationSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "databaseFingerprintSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "restoreAttestationSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "finalWorldFingerprintSha256": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
  "baseWorldFingerprintSha256": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
  "distributionFingerprintSha256": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
  "targetFingerprintSha256": "0000000000000000000000000000000000000000000000000000000000000000",
  "inventoryFingerprintSha256": "1111111111111111111111111111111111111111111111111111111111111111",
  "semanticAttestationSha256": "2222222222222222222222222222222222222222222222222222222222222222",
  "rdsResourceId": "db-ABCDEFGHIJKLMNOPQRSTUVWX",
  "rdsEngineVersion": "8.0.40",
  "outboxState": "empty",
  "redisState": "empty",
  "kafkaTopics": [
    { "name": "PAYMENT_OPERATION.events", "partitions": 3, "retentionMs": 86400000 },
    { "name": "PAYMENT_OPERATION.events.RETRY", "partitions": 3, "retentionMs": 86400000 },
    { "name": "PAYMENT_OPERATION.events.DLT", "partitions": 3, "retentionMs": 86400000 },
    { "name": "ACCOMMODATION_INDEX.events", "partitions": 3, "retentionMs": 86400000 },
    { "name": "ACCOMMODATION_INDEX.events.RETRY", "partitions": 3, "retentionMs": 86400000 },
    { "name": "ACCOMMODATION_INDEX.events.DLT", "partitions": 3, "retentionMs": 86400000 },
    { "name": "ACCOMMODATION_CACHE.events", "partitions": 3, "retentionMs": 86400000 },
    { "name": "ACCOMMODATION_CACHE.events.RETRY", "partitions": 3, "retentionMs": 86400000 },
    { "name": "ACCOMMODATION_CACHE.events.DLT", "partitions": 3, "retentionMs": 86400000 },
    { "name": "OPERATOR_ALERT.events", "partitions": 3, "retentionMs": 86400000 },
    { "name": "OPERATOR_ALERT.events.RETRY", "partitions": 3, "retentionMs": 86400000 },
    { "name": "OPERATOR_ALERT.events.DLT", "partitions": 3, "retentionMs": 86400000 }
  ],
  "connectorState": "RUNNING",
  "searchState": "skipped",
  "verifiedAt": "2030-01-01T00:30:00Z"
}
JSON
jq --arg manifestSha "$manifest_sha" '.datasetManifestSha256 = $manifestSha' \
  "$tmp_dir/receipt.json" > "$tmp_dir/receipt.next"
mv "$tmp_dir/receipt.next" "$tmp_dir/receipt.json"

producer_receipt_keys=$(
  awk '
    /receipt="\$work_root\/data-bootstrap-receipt.json"/ { in_receipt = 1 }
    in_receipt && /schemaVersion: 2,/ { capture = 1 }
    capture { print }
    capture && /verifiedAt: \$verifiedAt/ { exit }
  ' "$bootstrap" \
    | sed -nE 's/^[[:space:]]*([A-Za-z][A-Za-z0-9]*):.*/\1/p' \
    | sort
)
fixture_receipt_keys=$(jq -r 'keys[]' "$tmp_dir/receipt.json" | sort)
[[ "$producer_receipt_keys" == "$fixture_receipt_keys" ]] || {
  printf '%s\n' 'data bootstrap producer and snapshot promotion fixture receipt keys differ' >&2
  exit 1
}

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
    jq -n --arg manifestSha "$FAKE_MANIFEST_SHA" \
      --arg sourceInstance "${FAKE_SNAPSHOT_INSTANCE_ID:-airbob-phase3-test}" \
      --arg sourceResourceId "${FAKE_SNAPSHOT_RESOURCE_ID:-db-ABCDEFGHIJKLMNOPQRSTUVWX}" \
      --arg engineVersion "${FAKE_SNAPSHOT_ENGINE_VERSION:-8.0.40}" '{DBSnapshots:[{
      DBSnapshotIdentifier:"airbob-dataset-rehearsal-v20",Status:"available",Engine:"mysql",
      EngineVersion:$engineVersion,DBInstanceIdentifier:$sourceInstance,DbiResourceId:$sourceResourceId,Encrypted:true,
      TagList:[
        {Key:"DatasetRelease",Value:"rehearsal-v20"},
        {Key:"DatasetRunId",Value:"20260816T001530Z-12345678"},
        {Key:"DumpSha256",Value:"94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2"},
        {Key:"FlywayVersion",Value:"27"},
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
  airbob-phase3-test airbob-dataset-rehearsal-v20 "$tmp_dir/promotion.json" >/dev/null

jq -e --arg manifestSha "$manifest_sha" '
  .schemaVersion == 1 and
  .snapshotIdentifier == "airbob-dataset-rehearsal-v20" and
  .datasetRelease == "rehearsal-v20" and
  .manifestSha256 == $manifestSha and
  .persistence == "persistent"
' "$tmp_dir/promotion.json" >/dev/null
grep -Fq 'Key=Persistence,Value=persistent' "$tmp_dir/aws.log"
grep -Fq 'Key=DatasetRelease,Value=rehearsal-v20' "$tmp_dir/aws.log"

cp "$tmp_dir/receipt.json" "$tmp_dir/bad-receipt.json"
jq '.connectorState = "PAUSED"' "$tmp_dir/bad-receipt.json" > "$tmp_dir/bad.next"
mv "$tmp_dir/bad.next" "$tmp_dir/bad-receipt.json"
if PATH="$fake_bin:$PATH" FAKE_AWS_LOG="$tmp_dir/aws.log" FAKE_SNAPSHOT_CREATED="$tmp_dir/snapshot-created" \
  FAKE_MANIFEST_SHA="$manifest_sha" AIRBOB_REGION=ap-northeast-2 \
  "$script" "$manifest" "$tmp_dir/bad-receipt.json" \
  airbob-phase3-test airbob-dataset-rehearsal-v20 "$tmp_dir/rejected.json" >/dev/null 2>&1; then
  printf '%s\n' 'snapshot promotion accepted an invalid data receipt' >&2
  exit 1
fi
[[ ! -e "$tmp_dir/rejected.json" ]]

for rejected_receipt_case in legacy-schema missing-semantic extra-field tuple-drift; do
  case "$rejected_receipt_case" in
    legacy-schema)
      jq '.schemaVersion = 1' "$tmp_dir/receipt.json" > "$tmp_dir/rejected-receipt.json"
      ;;
    missing-semantic)
      jq 'del(.semanticAttestationSha256)' "$tmp_dir/receipt.json" > "$tmp_dir/rejected-receipt.json"
      ;;
    extra-field)
      jq '.unexpected = true' "$tmp_dir/receipt.json" > "$tmp_dir/rejected-receipt.json"
      ;;
    tuple-drift)
      jq '.validatorSha256 = ("9" * 64)' "$tmp_dir/receipt.json" > "$tmp_dir/rejected-receipt.json"
      ;;
  esac
  : > "$tmp_dir/aws.log"
  if PATH="$fake_bin:$PATH" FAKE_AWS_LOG="$tmp_dir/aws.log" FAKE_SNAPSHOT_CREATED="$tmp_dir/snapshot-created" \
    FAKE_MANIFEST_SHA="$manifest_sha" AIRBOB_REGION=ap-northeast-2 \
    "$script" "$manifest" "$tmp_dir/rejected-receipt.json" \
    airbob-phase3-test airbob-dataset-rehearsal-v20 "$tmp_dir/rejected-$rejected_receipt_case.json" \
    >/dev/null 2>&1; then
    printf 'snapshot promotion accepted rejected receipt case: %s\n' "$rejected_receipt_case" >&2
    exit 1
  fi
  [[ ! -s "$tmp_dir/aws.log" ]] \
    || { printf 'snapshot promotion contacted AWS for rejected receipt case: %s\n' "$rejected_receipt_case" >&2; exit 1; }
  [[ ! -e "$tmp_dir/rejected-$rejected_receipt_case.json" ]]
done

cp "$manifest" "$tmp_dir/tampered-manifest.json"
jq '.source.seed = "different-seed"' "$tmp_dir/tampered-manifest.json" > "$tmp_dir/tampered.next"
mv "$tmp_dir/tampered.next" "$tmp_dir/tampered-manifest.json"
: > "$tmp_dir/aws.log"
if PATH="$fake_bin:$PATH" FAKE_AWS_LOG="$tmp_dir/aws.log" FAKE_SNAPSHOT_CREATED="$tmp_dir/snapshot-created" \
  FAKE_MANIFEST_SHA="$manifest_sha" AIRBOB_REGION=ap-northeast-2 \
  "$script" "$tmp_dir/tampered-manifest.json" "$tmp_dir/receipt.json" \
  airbob-phase3-test airbob-dataset-rehearsal-v20 "$tmp_dir/tampered.json" >/dev/null 2>&1; then
  printf '%s\n' 'snapshot promotion accepted a manifest outside the bootstrap receipt tuple' >&2
  exit 1
fi
[[ ! -e "$tmp_dir/tampered.json" ]]
[[ ! -s "$tmp_dir/aws.log" ]] || { printf '%s\n' 'snapshot promotion contacted AWS before validating the manifest tuple' >&2; exit 1; }

for snapshot_source_case in resource-id instance-id engine-version; do
  snapshot_environment=(
    FAKE_SNAPSHOT_RESOURCE_ID=db-ABCDEFGHIJKLMNOPQRSTUVWX
    FAKE_SNAPSHOT_INSTANCE_ID=airbob-phase3-test
    FAKE_SNAPSHOT_ENGINE_VERSION=8.0.40
  )
  case "$snapshot_source_case" in
    resource-id) snapshot_environment[0]=FAKE_SNAPSHOT_RESOURCE_ID=db-ZYXWVUTSRQPONMLKJIHGFEDC ;;
    instance-id) snapshot_environment[1]=FAKE_SNAPSHOT_INSTANCE_ID=airbob-other-source ;;
    engine-version) snapshot_environment[2]=FAKE_SNAPSHOT_ENGINE_VERSION=8.0.41 ;;
  esac
  if env PATH="$fake_bin:$PATH" FAKE_AWS_LOG="$tmp_dir/aws.log" \
    FAKE_SNAPSHOT_CREATED="$tmp_dir/snapshot-created" FAKE_MANIFEST_SHA="$manifest_sha" \
    AIRBOB_REGION=ap-northeast-2 "${snapshot_environment[@]}" \
    "$script" "$manifest" "$tmp_dir/receipt.json" \
    airbob-phase3-test airbob-dataset-rehearsal-v20 \
    "$tmp_dir/source-drift-$snapshot_source_case.json" >/dev/null 2>&1; then
    printf 'snapshot promotion accepted source identity drift: %s\n' "$snapshot_source_case" >&2
    exit 1
  fi
  [[ ! -e "$tmp_dir/source-drift-$snapshot_source_case.json" ]]
done

for unsafe_identifier_pair in \
  'airbob-phase3-test-|airbob-dataset-rehearsal-v20' \
  'airbob-phase3--test|airbob-dataset-rehearsal-v20' \
  'airbob-phase3-test|airbob-dataset-rehearsal-v20-' \
  'airbob-phase3-test|airbob-dataset-rehearsal--v27'; do
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
