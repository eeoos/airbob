#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
script="$repo_root/infra/aws/scripts/promote-rds-snapshot.sh"
bootstrap="$repo_root/infra/aws/scripts/bootstrap-data.sh"
comparison_filter="$repo_root/infra/aws/scripts/readiness-comparison-projection.jq"
manifest_fixture="$repo_root/infra/aws/lab/tests/fixtures/dataset-manifest.json"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-snapshot-test.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM
fake_bin="$tmp_dir/bin"
mkdir -p "$fake_bin"

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

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

[[ -x "$script" ]] || fail 'RDS snapshot promotion script is missing or not executable'
[[ -f "$comparison_filter" && ! -L "$comparison_filter" ]] \
  || fail 'readiness comparison projection is missing or unsafe'

manifest="$tmp_dir/manifest.json"
jq '.search.enabled = true' "$manifest_fixture" > "$manifest"
manifest_sha=$(sha256_file "$manifest")
data_version_id=data-version-v1
readiness_version_id=readiness-version-v1

cat > "$tmp_dir/data-receipt.json" <<'JSON'
{
  "schemaVersion": 2,
  "runId": "lab-phase3-test",
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
  "searchState": "restored",
  "verifiedAt": "2030-01-01T00:30:00Z"
}
JSON
jq --arg manifestSha "$manifest_sha" '.datasetManifestSha256 = $manifestSha' \
  "$tmp_dir/data-receipt.json" > "$tmp_dir/data-receipt.next"
mv "$tmp_dir/data-receipt.next" "$tmp_dir/data-receipt.json"

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
fixture_receipt_keys=$(jq -r 'keys[]' "$tmp_dir/data-receipt.json" | sort)
[[ "$producer_receipt_keys" == "$fixture_receipt_keys" ]] \
  || fail 'data bootstrap producer and snapshot promotion fixture receipt keys differ'

data_receipt_sha=$(sha256_file "$tmp_dir/data-receipt.json")
data_projection=$(jq -cS 'del(.runId,.databaseBootstrap,.rdsResourceId,.verifiedAt)' \
  "$tmp_dir/data-receipt.json")
data_projection_sha=$(printf '%s' "$data_projection" | sha256_text)

jq -nS \
  --arg manifestSha "$manifest_sha" --arg dataSha "$data_receipt_sha" \
  --arg dataVersionId "$data_version_id" --arg dataProjectionSha "$data_projection_sha" '
  {
    schemaVersion: 1,
    status: "ready",
    runId: "lab-phase3-test",
    fencingToken: 41,
    executionCode: {
      commit: "dddddddddddddddddddddddddddddddddddddddd",
      operatorTreeSha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    },
    dataset: {
      release: "rehearsal-v20",
      manifestVersionId: "dataset-version-v1",
      manifestSha256: $manifestSha
    },
    bundle: {
      commit: "cccccccccccccccccccccccccccccccccccccccc",
      archiveSha256: "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
      checksumVersionId: "checksum-version-v1",
      manifestVersionId: "bundle-version-v1",
      manifestSha256: "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    },
    images: {
      app: "example.invalid/airbob@sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      infra: {
        REDIS_IMAGE: "example.invalid/redis@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        REDIS_EXPORTER_IMAGE: "example.invalid/redis-exporter@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        NODE_EXPORTER_IMAGE: "example.invalid/node-exporter@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        KAFKA_IMAGE: "example.invalid/kafka@sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
        DEBEZIUM_IMAGE: "example.invalid/debezium@sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
        ELASTICSEARCH_IMAGE: "example.invalid/elasticsearch@sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        ELASTICSEARCH_EXPORTER_IMAGE: "example.invalid/elasticsearch-exporter@sha256:1111111111111111111111111111111111111111111111111111111111111111",
        PROMETHEUS_IMAGE: "example.invalid/prometheus@sha256:2222222222222222222222222222222222222222222222222222222222222222",
        GRAFANA_IMAGE: "example.invalid/grafana@sha256:3333333333333333333333333333333333333333333333333333333333333333"
      }
    },
    bootstrap: {
      mode: "dump",
      rdsSnapshotIdentifier: null,
      rdsSnapshotSourceRunId: null,
      rdsSnapshotSourceResourceId: null,
      dataProjectionSha256: $dataProjectionSha,
      receipt: {
        key: "data-bootstrap/lab-phase3-test/rehearsal-v20.json",
        versionId: $dataVersionId,
        sha256: $dataSha,
        lastModified: "2030-01-01T00:31:00Z"
      }
    },
    networkClearance: {
      key: "network-clearance/lab-phase3-test/i-0123456789abcdef0.json",
      versionId: "network-version-v1",
      sha256: "4444444444444444444444444444444444444444444444444444444444444444",
      lastModified: "2030-01-01T00:32:00Z",
      projectionSha256: "5555555555555555555555555555555555555555555555555555555555555555"
    },
    actual: {
      ami: {
        id: "ami-0123456789abcdef0",
        shape: {
          imageId: "ami-0123456789abcdef0",
          creationDate: "2030-01-01T00:00:00.000Z",
          architecture: "x86_64",
          rootDeviceType: "ebs",
          virtualizationType: "hvm"
        }
      },
      rds: {
        identifier: "airbob-lab-phase3-test",
        resourceId: "db-ABCDEFGHIJKLMNOPQRSTUVWX",
        class: "db.t3.small",
        engine: "mysql",
        engineVersion: "8.0.40",
        allocatedStorageGiB: 100,
        storageType: "gp3",
        iops: 3000,
        storageThroughputMiBps: 125,
        multiAz: false,
        storageEncrypted: true,
        publiclyAccessible: false,
        availabilityZone: "ap-northeast-2a",
        parameterGroups: ["airbob-lab-phase3-test"]
      },
      rdsParameterGroupFamily: "mysql8.0",
      alb: {
        arn: "arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob/123",
        dnsName: "airbob.example.elb.amazonaws.com",
        targetGroupArn: "arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:targetgroup/airbob/123",
        autoScalingGroupName: "airbob-lab-phase3-test-app",
        securityGroupId: "sg-0123456789abcdef0",
        shape: {
          arn: "arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob/123",
          dnsName: "airbob.example.elb.amazonaws.com",
          scheme: "internet-facing",
          type: "application",
          ipAddressType: "ipv4",
          availabilityZones: ["ap-northeast-2a", "ap-northeast-2c"],
          securityGroups: ["sg-0123456789abcdef0"]
        },
        observedIngress: [{
          ruleId: "sgr-0123456789abcdef0",
          groupId: "sg-0123456789abcdef0",
          isEgress: false,
          ipProtocol: "tcp",
          fromPort: 443,
          toPort: 443,
          cidrIpv4: "198.51.100.10/32",
          cidrIpv6: null,
          prefixListId: null,
          referencedGroupId: null
        }]
      },
      autoScalingGroup: {name: "airbob-lab-phase3-test-app", min: 1, desired: 1, max: 1}
    },
    topology: {
      mode: "performance",
      policy: "integrated-smoke",
      dnsMode: "direct-only",
      albIngressCidr: "198.51.100.10/32",
      cacheEnabled: false,
      loadGeneratorEnabled: false
    },
    ociAuthority: {
      status: "verified",
      observedAt: "2030-01-01T00:33:00Z",
      zoneId: "Z0123456789ABCDEFG",
      fqdn: "api.airbob.cloud.",
      originIpv4: "203.0.113.10",
      recordSetSha256: "6666666666666666666666666666666666666666666666666666666666666666",
      route53: "oci-only",
      directHealth: "healthy",
      publicHealth: "healthy"
    },
    smoke: {
      health: {passed: true},
      accommodationDetail: {id: 200001, passed: true},
      search: {
        enabled: true,
        querySha256: "7777777777777777777777777777777777777777777777777777777777777777",
        passed: true
      }
    },
    timing: {
      resourceStartedAt: "2030-01-01T00:00:00Z",
      dataReadyAt: "2030-01-01T00:30:00Z",
      directReadyAt: "2030-01-01T00:35:00Z",
      resourceToDataReadySeconds: 1800,
      resourceToDirectReadySeconds: 2100
    }
  }
' > "$tmp_dir/direct-readiness-basis.json"
jq -Sf "$comparison_filter" "$tmp_dir/direct-readiness-basis.json" > "$tmp_dir/comparison.json"
comparison_sha=$(jq -cS . "$tmp_dir/comparison.json" | sha256_text)
jq --arg comparisonSha "$comparison_sha" --slurpfile comparison "$tmp_dir/comparison.json" \
  '. + {comparisonProjection:$comparison[0],comparisonProjectionSha256:$comparisonSha}' \
  "$tmp_dir/direct-readiness-basis.json" > "$tmp_dir/direct-readiness.json"
direct_readiness_sha=$(sha256_file "$tmp_dir/direct-readiness.json")
data_version_id_sha=$(printf '%s' "$data_version_id" | sha256_text)
readiness_version_id_sha=$(printf '%s' "$readiness_version_id" | sha256_text)

cat > "$fake_bin/aws" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_AWS_LOG"
value_after() {
  local wanted=$1 previous='' value
  shift
  for value in "$@"; do
    if [[ "$previous" == "$wanted" ]]; then
      printf '%s' "$value"
      return
    fi
    previous=$value
  done
  return 1
}
case "$*" in
  *"s3api get-object"*)
    key=$(value_after --key "$@")
    version_id=$(value_after --version-id "$@")
    output=${!#}
    case "$key:$version_id" in
      "data-bootstrap/lab-phase3-test/rehearsal-v20.json:$FAKE_DATA_VERSION_ID")
        cp "$FAKE_DATA_OBJECT" "$output"
        ;;
      "measurements/lab-phase3-test/direct-readiness.json:$FAKE_READINESS_VERSION_ID")
        cp "$FAKE_READINESS_OBJECT" "$output"
        ;;
      *) exit 1 ;;
    esac
    printf '%s\n' '{}'
    ;;
  *"rds describe-db-instances"*)
    jq -n \
      --arg class "${FAKE_INSTANCE_CLASS:-db.t3.small}" \
      --arg storageType "${FAKE_INSTANCE_STORAGE_TYPE:-gp3}" \
      --argjson allocatedStorage "${FAKE_INSTANCE_ALLOCATED_STORAGE:-100}" \
      --argjson iops "${FAKE_INSTANCE_IOPS:-3000}" \
      --argjson throughput "${FAKE_INSTANCE_STORAGE_THROUGHPUT:-125}" \
      --argjson publiclyAccessible "${FAKE_INSTANCE_PUBLICLY_ACCESSIBLE:-false}" '
      {DBInstances:[{
        DBInstanceStatus:"available", Engine:"mysql", EngineVersion:"8.0.40",
        DbiResourceId:"db-ABCDEFGHIJKLMNOPQRSTUVWX", DBInstanceClass:$class,
        AllocatedStorage:$allocatedStorage, StorageType:$storageType, Iops:$iops,
        StorageThroughput:$throughput, MultiAZ:false, StorageEncrypted:true,
        PubliclyAccessible:$publiclyAccessible
      }]}'
    ;;
  *"rds describe-db-snapshots"*)
    [[ -f "$FAKE_SNAPSHOT_CREATED" ]] || exit 1
    jq -n --arg manifestSha "$FAKE_MANIFEST_SHA" \
      --arg sourceInstance "${FAKE_SNAPSHOT_INSTANCE_ID:-airbob-lab-phase3-test}" \
      --arg sourceResourceId "${FAKE_SNAPSHOT_RESOURCE_ID:-db-ABCDEFGHIJKLMNOPQRSTUVWX}" \
      --arg engineVersion "${FAKE_SNAPSHOT_ENGINE_VERSION:-8.0.40}" \
      --arg storageType "${FAKE_SNAPSHOT_STORAGE_TYPE:-gp3}" \
      --argjson allocatedStorage "${FAKE_SNAPSHOT_ALLOCATED_STORAGE:-100}" \
      --argjson iops "${FAKE_SNAPSHOT_IOPS:-3000}" \
      --argjson throughput "${FAKE_SNAPSHOT_STORAGE_THROUGHPUT:-125}" \
      --arg dataVersionSha "${FAKE_TAG_DATA_VERSION_SHA:-$FAKE_DATA_VERSION_SHA}" \
      --arg dataSha "${FAKE_TAG_DATA_SHA:-$FAKE_DATA_SHA}" \
      --arg readinessVersionSha "${FAKE_TAG_READINESS_VERSION_SHA:-$FAKE_READINESS_VERSION_SHA}" \
      --arg readinessSha "${FAKE_TAG_READINESS_SHA:-$FAKE_READINESS_SHA}" '{DBSnapshots:[{
      DBSnapshotIdentifier:"airbob-dataset-rehearsal-v20",Status:"available",Engine:"mysql",
      EngineVersion:$engineVersion,DBInstanceIdentifier:$sourceInstance,DbiResourceId:$sourceResourceId,
      AllocatedStorage:$allocatedStorage,StorageType:$storageType,Iops:$iops,
      StorageThroughput:$throughput,Encrypted:true,
      TagList:[
        {Key:"Project",Value:"airbob"},
        {Key:"Environment",Value:"performance-lab"},
        {Key:"Stack",Value:"dataset"},
        {Key:"ManagedBy",Value:"dataset-publisher"},
        {Key:"DatasetRelease",Value:"rehearsal-v20"},
        {Key:"DatasetRunId",Value:"20260816T001530Z-12345678"},
        {Key:"DumpSha256",Value:"94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2"},
        {Key:"FlywayVersion",Value:"27"},
        {Key:"ManifestSha256",Value:$manifestSha},
        {Key:"SourceLabRunId",Value:"lab-phase3-test"},
        {Key:"SourceRdsResourceId",Value:"db-ABCDEFGHIJKLMNOPQRSTUVWX"},
        {Key:"DataBootstrapKey",Value:"data-bootstrap/lab-phase3-test/rehearsal-v20.json"},
        {Key:"DataBootstrapVersionIdSha256",Value:$dataVersionSha},
        {Key:"DataBootstrapSha256",Value:$dataSha},
        {Key:"DirectReadinessKey",Value:"measurements/lab-phase3-test/direct-readiness.json"},
        {Key:"DirectReadinessVersionIdSha256",Value:$readinessVersionSha},
        {Key:"DirectReadinessSha256",Value:$readinessSha},
        {Key:"PromotionReceiptSchemaVersion",Value:"2"},
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

run_promotion() {
  env PATH="$fake_bin:$PATH" \
    FAKE_AWS_LOG="$tmp_dir/aws.log" \
    FAKE_SNAPSHOT_CREATED="$tmp_dir/snapshot-created" \
    FAKE_MANIFEST_SHA="$manifest_sha" \
    FAKE_DATA_OBJECT="${FAKE_DATA_OBJECT:-$tmp_dir/data-receipt.json}" \
    FAKE_READINESS_OBJECT="${FAKE_READINESS_OBJECT:-$tmp_dir/direct-readiness.json}" \
    FAKE_DATA_VERSION_ID="$data_version_id" \
    FAKE_READINESS_VERSION_ID="$readiness_version_id" \
    FAKE_DATA_VERSION_SHA="$data_version_id_sha" \
    FAKE_READINESS_VERSION_SHA="$readiness_version_id_sha" \
    FAKE_DATA_SHA="$data_receipt_sha" \
    FAKE_READINESS_SHA="$direct_readiness_sha" \
    AIRBOB_REGION=ap-northeast-2 \
    "$script" "$@"
}

base_args=(
  "$manifest"
  "$tmp_dir/data-receipt.json"
  "$data_version_id"
  "$tmp_dir/direct-readiness.json"
  "$readiness_version_id"
  airbob-lab-phase3-test
  airbob-dataset-rehearsal-v20
)

: > "$tmp_dir/aws.log"
run_promotion "${base_args[@]}" "$tmp_dir/promotion.json" >/dev/null

jq -e \
  --arg manifestSha "$manifest_sha" --arg dataSha "$data_receipt_sha" \
  --arg dataVersionSha "$data_version_id_sha" --arg readinessSha "$direct_readiness_sha" \
  --arg readinessVersionSha "$readiness_version_id_sha" '
  (keys | sort) == ([
    "schemaVersion", "snapshotIdentifier", "datasetRelease", "datasetRunId",
    "dumpSha256", "flywayVersion", "manifestSha256", "sourceLabRunId",
    "sourceRdsResourceId", "sourceDataBootstrapReceipt",
    "sourceDirectReadinessReceipt", "persistence", "createdAt"
  ] | sort) and
  .schemaVersion == 2 and .snapshotIdentifier == "airbob-dataset-rehearsal-v20" and
  .datasetRelease == "rehearsal-v20" and .manifestSha256 == $manifestSha and
  .sourceLabRunId == "lab-phase3-test" and
  .sourceRdsResourceId == "db-ABCDEFGHIJKLMNOPQRSTUVWX" and
  .sourceDataBootstrapReceipt == {
    key:"data-bootstrap/lab-phase3-test/rehearsal-v20.json",
    versionId:"data-version-v1", versionIdSha256:$dataVersionSha, sha256:$dataSha
  } and
  .sourceDirectReadinessReceipt == {
    key:"measurements/lab-phase3-test/direct-readiness.json",
    versionId:"readiness-version-v1", versionIdSha256:$readinessVersionSha,
    sha256:$readinessSha
  } and .persistence == "persistent"
' "$tmp_dir/promotion.json" >/dev/null || fail 'promotion receipt does not bind both immutable inputs'

for expected_log_fragment in \
  's3api get-object --bucket airbob-performance-lab-evidence-942632789808 --key data-bootstrap/lab-phase3-test/rehearsal-v20.json --version-id data-version-v1' \
  's3api get-object --bucket airbob-performance-lab-evidence-942632789808 --key measurements/lab-phase3-test/direct-readiness.json --version-id readiness-version-v1' \
  'Key=DataBootstrapVersionIdSha256' \
  'Key=DataBootstrapSha256' \
  'Key=DirectReadinessVersionIdSha256' \
  'Key=DirectReadinessSha256' \
  'Key=PromotionReceiptSchemaVersion,Value=2'; do
  grep -Fq "$expected_log_fragment" "$tmp_dir/aws.log" \
    || fail "promotion did not use the immutable binding: $expected_log_fragment"
done
last_s3_line=$(grep -n 's3api get-object' "$tmp_dir/aws.log" | tail -1 | cut -d: -f1)
first_rds_line=$(grep -n 'rds describe-db-instances' "$tmp_dir/aws.log" | head -1 | cut -d: -f1)
((last_s3_line < first_rds_line)) || fail 'RDS was inspected before both immutable S3 objects were verified'

: > "$tmp_dir/aws.log"
if run_promotion "${base_args[@]}" "$tmp_dir/promotion.json" >/dev/null 2>&1; then
  fail 'snapshot promotion overwrote an existing immutable receipt'
fi
[[ ! -s "$tmp_dir/aws.log" ]] \
  || fail 'snapshot promotion contacted AWS before rejecting an existing receipt'

expect_local_reject() {
  local label=$1
  shift
  : > "$tmp_dir/aws.log"
  if run_promotion "$@" >/dev/null 2>&1; then
    fail "snapshot promotion accepted locally rejected case: $label"
  fi
  [[ ! -s "$tmp_dir/aws.log" ]] || fail "snapshot promotion contacted AWS for locally rejected case: $label"
}

expect_local_reject missing-readiness \
  "$manifest" "$tmp_dir/data-receipt.json" "$data_version_id" "$tmp_dir/missing-readiness.json" \
  "$readiness_version_id" airbob-lab-phase3-test airbob-dataset-rehearsal-v20 "$tmp_dir/rejected-missing.json"

for readiness_case in failed-health failed-detail failed-search wrong-data-version wrong-data-sha \
  wrong-run wrong-rds wrong-iops wrong-throughput public-rds snapshot-bootstrap extra-field projection-drift; do
  case "$readiness_case" in
    failed-health) jq '.smoke.health.passed = false' "$tmp_dir/direct-readiness.json" ;;
    failed-detail) jq '.smoke.accommodationDetail.passed = false' "$tmp_dir/direct-readiness.json" ;;
    failed-search) jq '.smoke.search.passed = false' "$tmp_dir/direct-readiness.json" ;;
    wrong-data-version) jq '.bootstrap.receipt.versionId = "data-version-v2"' "$tmp_dir/direct-readiness.json" ;;
    wrong-data-sha) jq '.bootstrap.receipt.sha256 = ("9" * 64)' "$tmp_dir/direct-readiness.json" ;;
    wrong-run) jq '.runId = "lab-other-run"' "$tmp_dir/direct-readiness.json" ;;
    wrong-rds) jq '.actual.rds.resourceId = "db-ZYXWVUTSRQPONMLKJIHGFEDC"' "$tmp_dir/direct-readiness.json" ;;
    wrong-iops) jq '.actual.rds.iops = 3001' "$tmp_dir/direct-readiness.json" ;;
    wrong-throughput) jq '.actual.rds.storageThroughputMiBps = 126' "$tmp_dir/direct-readiness.json" ;;
    public-rds) jq '.actual.rds.publiclyAccessible = true' "$tmp_dir/direct-readiness.json" ;;
    snapshot-bootstrap) jq '.bootstrap.mode = "snapshot"' "$tmp_dir/direct-readiness.json" ;;
    extra-field) jq '.unexpected = true' "$tmp_dir/direct-readiness.json" ;;
    projection-drift) jq '.comparisonProjectionSha256 = ("8" * 64)' "$tmp_dir/direct-readiness.json" ;;
  esac > "$tmp_dir/rejected-readiness.json"
  expect_local_reject "$readiness_case" \
    "$manifest" "$tmp_dir/data-receipt.json" "$data_version_id" "$tmp_dir/rejected-readiness.json" \
    "$readiness_version_id" airbob-lab-phase3-test airbob-dataset-rehearsal-v20 \
    "$tmp_dir/rejected-$readiness_case.json"
done

for data_case in legacy-schema snapshot-bootstrap connector-failed extra-field; do
  case "$data_case" in
    legacy-schema) jq '.schemaVersion = 1' "$tmp_dir/data-receipt.json" ;;
    snapshot-bootstrap) jq '.databaseBootstrap = "snapshot"' "$tmp_dir/data-receipt.json" ;;
    connector-failed) jq '.connectorState = "PAUSED"' "$tmp_dir/data-receipt.json" ;;
    extra-field) jq '.unexpected = true' "$tmp_dir/data-receipt.json" ;;
  esac > "$tmp_dir/rejected-data.json"
  expect_local_reject "data-$data_case" \
    "$manifest" "$tmp_dir/rejected-data.json" "$data_version_id" "$tmp_dir/direct-readiness.json" \
    "$readiness_version_id" airbob-lab-phase3-test airbob-dataset-rehearsal-v20 \
    "$tmp_dir/rejected-data-$data_case.json"
done

expect_local_reject wrong-bootstrap-version-argument \
  "$manifest" "$tmp_dir/data-receipt.json" data-version-v2 "$tmp_dir/direct-readiness.json" \
  "$readiness_version_id" airbob-lab-phase3-test airbob-dataset-rehearsal-v20 \
  "$tmp_dir/rejected-version.json"
expect_local_reject unsafe-readiness-version \
  "$manifest" "$tmp_dir/data-receipt.json" "$data_version_id" "$tmp_dir/direct-readiness.json" \
  'unsafe version' airbob-lab-phase3-test airbob-dataset-rehearsal-v20 "$tmp_dir/rejected-version.json"

: > "$tmp_dir/aws.log"
if run_promotion "$manifest" "$tmp_dir/data-receipt.json" "$data_version_id" \
  "$tmp_dir/direct-readiness.json" readiness-version-v2 airbob-lab-phase3-test \
  airbob-dataset-rehearsal-v20 "$tmp_dir/rejected-readiness-version.json" >/dev/null 2>&1; then
  fail 'snapshot promotion accepted the wrong immutable direct-readiness VersionId'
fi
grep -Fq 's3api get-object' "$tmp_dir/aws.log" \
  || fail 'wrong direct-readiness VersionId was not checked against immutable S3 state'
if grep -Fq 'rds describe-db-instances' "$tmp_dir/aws.log"; then
  fail 'wrong direct-readiness VersionId reached RDS inspection'
fi

cp "$tmp_dir/direct-readiness.json" "$tmp_dir/tampered-s3-readiness.json"
printf '\n' >> "$tmp_dir/tampered-s3-readiness.json"
: > "$tmp_dir/aws.log"
if FAKE_READINESS_OBJECT="$tmp_dir/tampered-s3-readiness.json" \
  run_promotion "${base_args[@]}" "$tmp_dir/rejected-s3-bytes.json" >/dev/null 2>&1; then
  fail 'snapshot promotion accepted local readiness bytes that differ from the exact S3 version'
fi
grep -Fq 's3api get-object' "$tmp_dir/aws.log" || fail 'exact S3 readiness bytes were not fetched'
if grep -Fq 'rds describe-db-instances' "$tmp_dir/aws.log"; then
  fail 'immutable S3 readiness byte drift reached RDS inspection'
fi

for instance_shape_case in class allocated-storage storage-type iops storage-throughput public; do
  : > "$tmp_dir/aws.log"
  case "$instance_shape_case" in
    class) FAKE_INSTANCE_CLASS=db.t3.medium \
      run_promotion "${base_args[@]}" "$tmp_dir/instance-drift-$instance_shape_case.json" >/dev/null 2>&1 && accepted=true || accepted=false ;;
    allocated-storage) FAKE_INSTANCE_ALLOCATED_STORAGE=101 \
      run_promotion "${base_args[@]}" "$tmp_dir/instance-drift-$instance_shape_case.json" >/dev/null 2>&1 && accepted=true || accepted=false ;;
    storage-type) FAKE_INSTANCE_STORAGE_TYPE=gp2 \
      run_promotion "${base_args[@]}" "$tmp_dir/instance-drift-$instance_shape_case.json" >/dev/null 2>&1 && accepted=true || accepted=false ;;
    iops) FAKE_INSTANCE_IOPS=3001 \
      run_promotion "${base_args[@]}" "$tmp_dir/instance-drift-$instance_shape_case.json" >/dev/null 2>&1 && accepted=true || accepted=false ;;
    storage-throughput) FAKE_INSTANCE_STORAGE_THROUGHPUT=126 \
      run_promotion "${base_args[@]}" "$tmp_dir/instance-drift-$instance_shape_case.json" >/dev/null 2>&1 && accepted=true || accepted=false ;;
    public) FAKE_INSTANCE_PUBLICLY_ACCESSIBLE=true \
      run_promotion "${base_args[@]}" "$tmp_dir/instance-drift-$instance_shape_case.json" >/dev/null 2>&1 && accepted=true || accepted=false ;;
  esac
  [[ "$accepted" == false ]] || fail "snapshot promotion accepted source RDS shape drift: $instance_shape_case"
  [[ ! -e "$tmp_dir/instance-drift-$instance_shape_case.json" ]]
done

for snapshot_source_case in resource-id instance-id engine-version allocated-storage storage-type iops storage-throughput; do
  : > "$tmp_dir/aws.log"
  case "$snapshot_source_case" in
    resource-id) FAKE_SNAPSHOT_RESOURCE_ID=db-ZYXWVUTSRQPONMLKJIHGFEDC \
      run_promotion "${base_args[@]}" "$tmp_dir/source-drift-$snapshot_source_case.json" >/dev/null 2>&1 && accepted=true || accepted=false ;;
    instance-id) FAKE_SNAPSHOT_INSTANCE_ID=airbob-other-source \
      run_promotion "${base_args[@]}" "$tmp_dir/source-drift-$snapshot_source_case.json" >/dev/null 2>&1 && accepted=true || accepted=false ;;
    engine-version) FAKE_SNAPSHOT_ENGINE_VERSION=8.0.41 \
      run_promotion "${base_args[@]}" "$tmp_dir/source-drift-$snapshot_source_case.json" >/dev/null 2>&1 && accepted=true || accepted=false ;;
    allocated-storage) FAKE_SNAPSHOT_ALLOCATED_STORAGE=101 \
      run_promotion "${base_args[@]}" "$tmp_dir/source-drift-$snapshot_source_case.json" >/dev/null 2>&1 && accepted=true || accepted=false ;;
    storage-type) FAKE_SNAPSHOT_STORAGE_TYPE=gp2 \
      run_promotion "${base_args[@]}" "$tmp_dir/source-drift-$snapshot_source_case.json" >/dev/null 2>&1 && accepted=true || accepted=false ;;
    iops) FAKE_SNAPSHOT_IOPS=3001 \
      run_promotion "${base_args[@]}" "$tmp_dir/source-drift-$snapshot_source_case.json" >/dev/null 2>&1 && accepted=true || accepted=false ;;
    storage-throughput) FAKE_SNAPSHOT_STORAGE_THROUGHPUT=126 \
      run_promotion "${base_args[@]}" "$tmp_dir/source-drift-$snapshot_source_case.json" >/dev/null 2>&1 && accepted=true || accepted=false ;;
  esac
  [[ "$accepted" == false ]] || fail "snapshot promotion accepted source identity drift: $snapshot_source_case"
  [[ ! -e "$tmp_dir/source-drift-$snapshot_source_case.json" ]]
done

for snapshot_tag_case in data-version data-sha readiness-version readiness-sha; do
  : > "$tmp_dir/aws.log"
  case "$snapshot_tag_case" in
    data-version) FAKE_TAG_DATA_VERSION_SHA=$(printf wrong | sha256_text) ;;
    data-sha) FAKE_TAG_DATA_SHA=$(printf wrong | sha256_text) ;;
    readiness-version) FAKE_TAG_READINESS_VERSION_SHA=$(printf wrong | sha256_text) ;;
    readiness-sha) FAKE_TAG_READINESS_SHA=$(printf wrong | sha256_text) ;;
  esac
  export FAKE_TAG_DATA_VERSION_SHA FAKE_TAG_DATA_SHA FAKE_TAG_READINESS_VERSION_SHA FAKE_TAG_READINESS_SHA
  if run_promotion "${base_args[@]}" "$tmp_dir/tag-drift-$snapshot_tag_case.json" >/dev/null 2>&1; then
    fail "snapshot promotion accepted immutable receipt tag drift: $snapshot_tag_case"
  fi
  unset FAKE_TAG_DATA_VERSION_SHA FAKE_TAG_DATA_SHA FAKE_TAG_READINESS_VERSION_SHA FAKE_TAG_READINESS_SHA
  [[ ! -e "$tmp_dir/tag-drift-$snapshot_tag_case.json" ]]
done

for unsafe_identifier_pair in \
  'airbob-other-run|airbob-dataset-rehearsal-v20' \
  'airbob-lab-phase3-test-|airbob-dataset-rehearsal-v20' \
  'airbob-lab-phase3--test|airbob-dataset-rehearsal-v20' \
  'airbob-lab-phase3-test|airbob-dataset-rehearsal-v20-' \
  'airbob-lab-phase3-test|airbob-dataset-rehearsal--v27'; do
  unsafe_instance=${unsafe_identifier_pair%%|*}
  unsafe_snapshot=${unsafe_identifier_pair#*|}
  expect_local_reject "unsafe-identifier-$unsafe_identifier_pair" \
    "$manifest" "$tmp_dir/data-receipt.json" "$data_version_id" "$tmp_dir/direct-readiness.json" \
    "$readiness_version_id" "$unsafe_instance" "$unsafe_snapshot" "$tmp_dir/unsafe.json"
done

printf '%s\n' 'RDS snapshot promotion tests passed'
