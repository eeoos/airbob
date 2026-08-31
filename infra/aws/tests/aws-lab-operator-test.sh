#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
operator="$repo_root/infra/aws/scripts/aws-lab.sh"
orphan_scanner="$repo_root/infra/aws/scripts/scan-lab-orphans.sh"
expiry_cleanup="$repo_root/infra/aws/scripts/cleanup-expired-lab.sh"
policy_verifier="$repo_root/infra/aws/scripts/enforce-measurement-policy.sh"
workflow="$repo_root/.github/workflows/aws-performance-lab.yml"
makefile="$repo_root/Makefile"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-operator-test.XXXXXX")

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  rm -rf "$temp_dir"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

fail() { printf '%s\n' "$1" >&2; exit 1; }
assert_contains() { grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"; }
sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

for executable in "$operator" "$orphan_scanner" "$expiry_cleanup" "$policy_verifier"; do
  [[ -x "$executable" && ! -L "$executable" ]] || fail "$executable is missing or unsafe"
done
[[ -f "$workflow" && ! -L "$workflow" ]] || fail "AWS lab workflow is missing or unsafe"
[[ -f "$makefile" && ! -L "$makefile" ]] || fail "root Makefile is missing or unsafe"

assert_contains "$operator" 'case "$action" in up|status|switch|down)'
assert_contains "$operator" '"$lease_script" acquire'
assert_contains "$operator" '"$lease_script" heartbeat'
assert_contains "$operator" '"$lease_script" assert'
assert_contains "$operator" '"$lease_script" release'
assert_contains "$operator" 'COMMAND_DEADLINE_SECONDS=5400'
assert_contains "$operator" 'INSTANCE_REFRESH_TIMEOUT_SECONDS=900'
assert_contains "$operator" 'autoscaling rollback-instance-refresh'
assert_contains "$operator" 'autoscaling cancel-instance-refresh'
assert_contains "$operator" 'keep_on_failure'
assert_contains "$operator" 'deployment_phase=network'
assert_contains "$operator" 'deployment_phase=probe-cleared'
assert_contains "$operator" 'deployment_phase=services'
assert_contains "$operator" 'deployment_phase=data-ready'
assert_contains "$operator" 'aws-dns-controller.sh'
assert_contains "$operator" 'enforce-measurement-policy.sh'
assert_contains "$operator" 'airbob-performance-lab-expiry-observer'
assert_contains "$operator" 'airbob-performance-lab-expiry-action-required'
assert_contains "$operator" 'airbob-performance-lab-expiry-heartbeat-missing'
assert_contains "$operator" 'airbob-performance-lab-expiry-lambda-errors'
assert_contains "$operator" 'write_failure_evidence'
assert_contains "$operator" 'failures/$run_id/operator-$fencing_token.json'
assert_contains "$operator" 'switch requires an active lab ALB'
assert_contains "$operator" 'persistent resource deletion is outside the lab-state boundary'
assert_contains "$operator" 'terraform-outputs.redacted.json'
assert_contains "$operator" 'run manifest expiry is invalid'
assert_contains "$operator" 'dataset completion manifest is unavailable'
assert_contains "$operator" 'dataset completion manifest is invalid'
assert_contains "$operator" 'verify_public_aws_smoke'
assert_contains "$operator" 'verify_direct_aws_smoke'
assert_contains "$operator" 'legacyBenchmarkManifestSha256'
assert_contains "$operator" 'search-narrow'
assert_contains "$operator" 'for attempt in 1 2 3'
assert_contains "$operator" 'Terraform state run changed before lease acquisition'
assert_contains "$operator" 'refusing to replace an active lab; run status or down first'

assert_contains "$orphan_scanner" 'resourcegroupstaggingapi get-resources'
assert_contains "$orphan_scanner" 'RunId'
assert_contains "$orphan_scanner" 'Persistence'
assert_contains "$orphan_scanner" 'bounded_prefix="airbob-${run_id:0:12}-${run_hash:0:6}"'
assert_contains "$orphan_scanner" "LoadBalancerName=='\$bounded_prefix-alb'"
assert_contains "$orphan_scanner" "AutoScalingGroupName=='airbob-\$run_id-app'"
assert_contains "$policy_verifier" 'airbob-outbox-connector/pause'
assert_contains "$policy_verifier" 'AIRBOB_ISOLATED_DB_OK'
assert_contains "$policy_verifier" 'AIRBOB_ISOLATED_KAFKA_OK'
assert_contains "$policy_verifier" 'kafka-get-offsets.sh'

for target in aws-up aws-status aws-switch aws-down; do
  assert_contains "$makefile" "$target:"
done

assert_contains "$workflow" 'workflow_dispatch:'
assert_contains "$workflow" 'schedule:'
assert_contains "$workflow" 'group: aws-performance-lab'
assert_contains "$workflow" 'cancel-in-progress: false'
assert_contains "$workflow" 'id-token: write'
assert_contains "$workflow" 'environment: aws-performance-lab'
assert_contains "$workflow" 'aws-actions/configure-aws-credentials@e3dd6a429d7300a6a4c196c26e071d42e0343502'
assert_contains "$workflow" 'infra/aws/scripts/aws-lab.sh'
assert_contains "$workflow" 'infra/aws/scripts/cleanup-expired-lab.sh'
assert_contains "$workflow" 'options: [performance, scaling]'
if grep -Eq 'AWS_(ACCESS_KEY_ID|SECRET_ACCESS_KEY)' "$workflow"; then
  fail "AWS lab workflow must not use static credentials"
fi

mkdir -p "$temp_dir/bin"
cp "$repo_root/infra/aws/lab/tests/fixtures/lab-contract.json" "$temp_dir/lab-contract.json"
cat > "$temp_dir/bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >> "${FAKE_CALL_LOG:?}"
case " $* " in
  *' sts get-caller-identity '*Account*) printf '%s\n' 942632789808 ;;
  *' sts get-caller-identity '*) printf '%s\n' 'arn:aws:sts::942632789808:assumed-role/airbob-lab-operator/test' ;;
  *' ssm get-parameter '*) cat "${FAKE_LAB_CONTRACT:?}" ;;
  *' s3api list-objects-v2 '*)
    if [[ "${FAKE_STATE_EXISTS:-false}" == true ]]; then
      printf '%s\n' 'airbob/lab/terraform.tfstate'
    else
      printf '%s\n' None
    fi
    ;;
  *' dynamodb get-item '*) printf '%s\n' 'released 7 run-a up 0 0' ;;
  *' events describe-rule '*) printf '%s\n' '{}';;
  *' cloudwatch describe-alarms '*) printf '%s\n' '{}';;
  *' resourcegroupstaggingapi get-resources '*) printf '%s\n' '{}';;
  *) printf 'unexpected fake AWS call: %s\n' "$*" >&2; exit 1 ;;
esac
EOF
cat > "$temp_dir/bin/terraform" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'terraform %s\n' "$*" >> "${FAKE_TERRAFORM_LOG:?}"
exit 1
EOF
chmod 700 "$temp_dir/bin/aws" "$temp_dir/bin/terraform"
: > "$temp_dir/aws.log"
: > "$temp_dir/terraform.log"
env PATH="$temp_dir/bin:/usr/bin:/bin" AWS_REGION=ap-northeast-2 \
  FAKE_CALL_LOG="$temp_dir/aws.log" FAKE_TERRAFORM_LOG="$temp_dir/terraform.log" \
  FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" "$operator" status >/dev/null
grep -Fq 'dynamodb get-item' "$temp_dir/aws.log" || fail "status did not read the lease"
grep -Fq 'events describe-rule' "$temp_dir/aws.log" || fail "status did not read the expiry observer"
grep -Fq 'cloudwatch describe-alarms' "$temp_dir/aws.log" || fail "status did not read the three alarms"
if grep -Eq 'dynamodb (put-item|update-item|delete-item)' "$temp_dir/aws.log"; then
  fail "status mutated the orchestration lease"
fi
[[ ! -s "$temp_dir/terraform.log" ]] || fail "status invoked Terraform"

# Execute the full operator against hermetic fake CLIs/helpers. This verifies
# orchestration order and post-cutover rollback without touching AWS.
fixture_repo="$temp_dir/operator-repo"
fixture_scripts="$fixture_repo/infra/aws/scripts"
mkdir -p "$fixture_scripts" "$fixture_repo/infra/aws/lab" "$temp_dir/operator-bin"
cp "$operator" "$fixture_scripts/aws-lab.sh"
cp "$repo_root/infra/aws/toolchain.env" "$fixture_repo/infra/aws/toolchain.env"

cat > "$fixture_scripts/orchestration-lease.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'lease %s\n' "$*" >> "${FAKE_OPERATOR_LOG:?}"
case "$1" in
  acquire) printf '%s\n' 'fencing_token=42' ;;
  status) printf '%s\n' 'released 41 old up 0 0' ;;
esac
EOF
cat > "$fixture_scripts/aws-dns-controller.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'dns %s\n' "$*" >> "${FAKE_OPERATOR_LOG:?}"
printf 'dns-fence resource=%s current=%s\n' "${ALB_FENCING_TOKEN:?}" "${FENCING_TOKEN:?}" >> "${FAKE_OPERATOR_LOG:?}"
EOF
cat > "$fixture_scripts/scan-lab-orphans.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'orphans %s\n' "$*" >> "${FAKE_OPERATOR_LOG:?}"
EOF
cat > "$fixture_scripts/prepare-terraform-backend.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'backend %s\n' "$*" >> "${FAKE_OPERATOR_LOG:?}"
EOF
cat > "$fixture_scripts/verify-network-egress.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'network %s\n' "$*" >> "${FAKE_OPERATOR_LOG:?}"
EOF
cat > "$fixture_scripts/enforce-measurement-policy.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'policy %s\n' "$*" >> "${FAKE_OPERATOR_LOG:?}"
EOF
chmod 700 "$fixture_scripts"/*.sh

cat > "$temp_dir/operator-bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >> "${FAKE_OPERATOR_LOG:?}"
case " $* " in
  *' sts get-caller-identity '*Account*) printf '%s\n' 942632789808 ;;
  *' sts get-caller-identity '*) printf '%s\n' 'arn:aws:sts::942632789808:assumed-role/airbob-lab-operator/test' ;;
  *' ssm get-parameter '*) cat "${FAKE_LAB_CONTRACT:?}" ;;
  *' s3api list-objects-v2 '*)
    if [[ "${FAKE_STATE_EXISTS:-false}" == true ]]; then
      printf '%s\n' 'airbob/lab/terraform.tfstate'
    else
      printf '%s\n' None
    fi
    ;;
  *' s3api get-object '*)
    destination=''
    for argument in "$@"; do
      case "$argument" in "${FAKE_OPERATOR_TEMP_PREFIX:?}"*) destination=$argument ;; esac
    done
    [[ -n "$destination" ]] || exit 1
    if [[ "$*" == *'runs/'*'/operator.json'* ]]; then
      cat "${FAKE_RUN_MANIFEST:?}" > "$destination"
    elif [[ "$*" == *'/benchmark/dataset-manifest.json'* ]]; then
      cat "${FAKE_BENCHMARK_DATASET_MANIFEST:?}" > "$destination"
    elif [[ "$*" == *'/benchmark/manifest.json'* ]]; then
      cat "${FAKE_BENCHMARK_MANIFEST:?}" > "$destination"
    elif [[ "$*" == *'datasets/'*'/manifest.json'* ]]; then
      cat "${FAKE_DATASET_MANIFEST:?}" > "$destination"
    elif [[ "$*" == *'.sha256'* ]]; then
      printf '%s  %s\n' 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' bundle.tar.gz > "$destination"
    else
      printf '%s\n' '{}' > "$destination"
    fi
    ;;
  *' s3api put-object '*) ;;
  *' ecr describe-images '*imageTag=*) printf '%s\n' 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' ;;
  *' ecr describe-images '*) ;;
  *' rds describe-db-instances '*) printf '%s\n' 'arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-test' ;;
  *' autoscaling describe-instance-refreshes '*) printf '%s\n' Successful ;;
  *' elbv2 describe-target-health '*'State!='*) printf '%s\n' 0 ;;
  *' elbv2 describe-target-health '*'State=='*) printf '%s\n' 1 ;;
  *' autoscaling describe-auto-scaling-groups '*) printf '%s\n' "${FAKE_DESIRED_CAPACITY:-1}" ;;
  *) printf 'unexpected fake operator AWS call: %s\n' "$*" >&2; exit 1 ;;
esac
EOF
cat > "$temp_dir/operator-bin/terraform" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'terraform %s\n' "$*" >> "${FAKE_OPERATOR_LOG:?}"
for argument in "$@"; do
  case "$argument" in
    -var-file=*)
      tfvars=${argument#-var-file=}
      printf 'phase %s app=%s\n' "$(jq -r .deployment_phase "$tfvars")" "$(jq -r .app_enabled "$tfvars")" >> "${FAKE_OPERATOR_LOG:?}"
      ;;
  esac
done
case " $* " in
  *' show -json '*)
    if [[ "${FAKE_PERSISTENT_DELETE:-false}" == true ]]; then
      printf '%s\n' '{"resource_changes":[{"change":{"actions":["delete"],"before":{"tags":{"Persistence":"persistent"}}}}]}'
    else
      printf '%s\n' '{"resource_changes":[]}'
    fi
    ;;
  *' output -json phase2_contract '*)
    jq -nc --arg run_id "${FAKE_STATE_RUN_ID:-lab-partial-down}" '{run_id:$run_id,vpc_id:"vpc-0123456789abcdef0",primary_private_route_table:"rtb-0123456789abcdef0",probe_instance_id:"i-0123456789abcdef0",services:{debezium:"i-11111111111111111",kafka:"i-22222222222222222"}}'
    ;;
  *' output -json phase3_contract '*)
    printf '%s\n' '{"rds_instance_id":"airbob-fake","rds_endpoint":"fake.abcdefghijkl.ap-northeast-2.rds.amazonaws.com"}'
    ;;
  *' output -json phase4_contract '*)
    if [[ "${FAKE_NO_ALB:-false}" == true ]]; then
      printf '%s\n' '{}'
    else
      printf '%s\n' '{"alb_arn":"arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-fake/0123456789abcdef","alb_dns_name":"airbob-fake.ap-northeast-2.elb.amazonaws.com","target_group_arn":"arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:targetgroup/airbob-fake/0123456789abcdef","auto_scaling_group_name":"airbob-fake-asg","app_availability_zones":["ap-northeast-2a","ap-northeast-2c"]}'
    fi
    ;;
  *' output -json '*)
    printf '%s\n' '{"persistent_resource_contract":{"sensitive":false,"type":"object","value":{}},"state_boundaries":{"sensitive":false,"type":"object","value":{}},"phase2_contract":{"sensitive":false,"type":"object","value":{}},"phase3_contract":{"sensitive":false,"type":"object","value":{}},"phase4_contract":{"sensitive":false,"type":"object","value":{}}}'
    ;;
esac
EOF
cat > "$temp_dir/operator-bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'curl %s\n' "$*" >> "${FAKE_OPERATOR_LOG:?}"
if [[ "${FAKE_PUBLIC_SMOKE_FAILURE:-false}" == true && " $* " != *' --connect-to '* ]]; then
  exit 22
fi
case "$*" in
  *'/actuator/health'*) printf '%s\n' '{"status":"UP"}' ;;
  *'/api/v1/accommodations/200001'*) printf '%s\n' '{"success":true,"data":{"id":200001}}' ;;
  *'/api/v1/search/accommodations'*)
    printf '%s\n' '{"success":true,"data":{"stay_search_result_listing":[{"id":101706}],"page_info":{"current_page":0,"total_elements":1,"total_pages":1}}}'
    ;;
  *) printf 'unexpected fake smoke request: %s\n' "$*" >&2; exit 22 ;;
esac
EOF
chmod 700 "$temp_dir/operator-bin/aws" "$temp_dir/operator-bin/terraform" \
  "$temp_dir/operator-bin/curl"

cat > "$temp_dir/benchmark-manifest.json" <<'JSON'
{
  "datasetVersion": "nplus1-v1",
  "hostAccommodations": { "detailAccommodationId": 200001 }
}
JSON
cat > "$temp_dir/benchmark-dataset-manifest.json" <<'JSON'
{
  "schemaVersion": 2,
  "datasetVersion": "benchmark-dataset-v2",
  "world": { "version": "world-v2" },
  "capsules": [{
    "capsuleId": "index-query-v1",
    "mutability": "READ_ONLY",
    "targets": [{
      "id": "search-narrow",
      "expectedRows": 1,
      "resourceIds": [101706],
      "query": {
        "kind": "ACCOMMODATION_SEARCH_V1",
        "destination": "",
        "minPrice": 0,
        "maxPrice": 4383,
        "adultOccupancy": 1,
        "childOccupancy": 0,
        "infantOccupancy": 0,
        "petOccupancy": 0,
        "topLeftLat": 60.03054680000001,
        "topLeftLng": -124.42150910000001,
        "bottomRightLat": -43.440580000000004,
        "bottomRightLng": 153.63548000000003,
        "page": 0
      },
      "expectedResultHash": "41672a3dd4e44ee6138dba8c0dea8ab83cfd8717dd61555a13eac357af676489"
    }]
  }]
}
JSON
legacy_manifest_sha=$(sha256_file "$temp_dir/benchmark-manifest.json")
benchmark_dataset_manifest_sha=$(sha256_file "$temp_dir/benchmark-dataset-manifest.json")

cat > "$temp_dir/dataset-manifest.json" <<'JSON'
{
  "schemaVersion": 2,
  "releaseKind": "pipeline-rehearsal",
  "datasetRelease": "fixture-v20",
  "mysql": {
    "flywayVersion": "27",
    "expectedTableRows": {
      "flyway_schema_history": 27,
      "outbox": 0,
      "accommodation": 1,
      "accommodation_inventory_day": 0,
      "reservation": 0
    }
  },
  "source": {
    "legacyBenchmarkManifestKey": "benchmark/manifest.json",
    "legacyBenchmarkManifestSha256": "placeholder",
    "benchmarkDatasetManifestKey": "benchmark/dataset-manifest.json",
    "benchmarkDatasetManifestSha256": "placeholder"
  },
  "releaseTuple": { "manifestSha256": "placeholder" },
  "search": { "enabled": false }
}
JSON
jq --arg legacySha "$legacy_manifest_sha" --arg compositeSha "$benchmark_dataset_manifest_sha" '
  .source.legacyBenchmarkManifestSha256 = $legacySha |
  .source.benchmarkDatasetManifestSha256 = $compositeSha |
  .releaseTuple.manifestSha256 = $compositeSha
' "$temp_dir/dataset-manifest.json" > "$temp_dir/dataset-manifest.next"
mv "$temp_dir/dataset-manifest.next" "$temp_dir/dataset-manifest.json"
dataset_wrapper_sha=$(sha256_file "$temp_dir/dataset-manifest.json")

jq -n \
  --arg runId lab-partial-down \
  --argjson expiresAt 2000000000 \
  --arg datasetManifestSha256 "$dataset_wrapper_sha" \
  '{schemaVersion:1,runId:$runId,expiresAt:$expiresAt,fencingToken:41,mode:"performance",policy:"isolated-read",cacheEnabled:false,requestTarget:"",loadGeneratorEnabled:false,amiId:"ami-0123456789abcdef0",ociOriginIpv4:"203.0.113.10",databaseBootstrap:"dump",rdsSnapshotIdentifier:"",rdsEngineVersion:"8.0.42",bundleCommit:"cccccccccccccccccccccccccccccccccccccccc",bundleSha256:"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",datasetRelease:"fixture-v20",datasetManifestSha256:$datasetManifestSha256,appImageReference:"942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-repo@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",infraImageReferences:{},verifiedProbeInstanceId:"i-0123456789abcdef0"}' \
  > "$temp_dir/run-manifest.json"

run_fake_up() {
  local oci_origin=${3:-203.0.113.10}
  local capacity_mode=${4:-performance}
  local measurement_policy=${5:-isolated-read}
  local request_target=${6:-}
  env PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
    FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
    FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
    FAKE_RUN_MANIFEST="$temp_dir/run-manifest.json" \
    FAKE_DATASET_MANIFEST="${FAKE_DATASET_MANIFEST:-$temp_dir/dataset-manifest.json}" \
    FAKE_BENCHMARK_MANIFEST="${FAKE_BENCHMARK_MANIFEST:-$temp_dir/benchmark-manifest.json}" \
    FAKE_BENCHMARK_DATASET_MANIFEST="${FAKE_BENCHMARK_DATASET_MANIFEST:-$temp_dir/benchmark-dataset-manifest.json}" \
    FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
    FAKE_STATE_EXISTS="${FAKE_STATE_EXISTS:-false}" FAKE_STATE_RUN_ID="${FAKE_STATE_RUN_ID:-lab-partial-down}" \
    MODE="$capacity_mode" POLICY="$measurement_policy" REQUEST_TARGET="$request_target" \
    IMAGE_DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    DATASET_RELEASE=fixture-v20 BUNDLE_COMMIT=cccccccccccccccccccccccccccccccccccccccc \
    AMI_ID=ami-0123456789abcdef0 OCI_ORIGIN_IPV4="$oci_origin" \
    RDS_ENGINE_VERSION=8.0.42 LOAD_GENERATOR_ENABLED=false TTL_HOURS=1 \
    RUN_ID="$1" FAKE_PUBLIC_SMOKE_FAILURE="${2:-false}" \
    "$fixture_scripts/aws-lab.sh" up
}

: > "$temp_dir/operator-execution.log"
if run_fake_up lab-invalid-ip false 999.0.0.1 >/dev/null 2>&1; then
  fail "operator accepted a non-canonical OCI IPv4 address"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "invalid OCI input acquired the orchestration lease"
fi

: > "$temp_dir/operator-execution.log"
if run_fake_up lab-scaling-no-target false 203.0.113.10 scaling isolated-read >/dev/null 2>&1; then
  fail "operator accepted scaling mode without a request target"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "scaling without a request target acquired the orchestration lease"
fi

: > "$temp_dir/operator-execution.log"
if run_fake_up lab-performance-request false 203.0.113.10 performance isolated-read 1200 >/dev/null 2>&1; then
  fail "operator accepted a request target outside scaling mode"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "non-scaling request target acquired the orchestration lease"
fi

: > "$temp_dir/operator-execution.log"
if FAKE_STATE_EXISTS=true FAKE_STATE_RUN_ID=lab-existing-run \
  run_fake_up lab-new-run >/dev/null 2>&1; then
  fail "up accepted replacement of an active lab from another run"
fi
if grep -Eq 'dns |terraform .* (plan|apply|destroy)' "$temp_dir/operator-execution.log"; then
  fail "active-run replacement rejection reached an infrastructure mutation"
fi

jq '.mysql.flywayVersion = "19"' "$temp_dir/dataset-manifest.json" > "$temp_dir/dataset-manifest-v19.json"
: > "$temp_dir/operator-execution.log"
if FAKE_DATASET_MANIFEST="$temp_dir/dataset-manifest-v19.json" \
  run_fake_up lab-v19-dataset >"$temp_dir/v19.out" 2>"$temp_dir/v19.err"; then
  fail "operator accepted a V19 dataset completion manifest"
fi
if grep -Eq 'terraform .* (plan|apply)|^network ' "$temp_dir/operator-execution.log"; then
  fail "V19 dataset rejection reached Terraform plan/apply or network verification"
fi
if grep -Eq '^lease acquire |s3api put-object|dynamodb (put-item|update-item|delete-item)' "$temp_dir/operator-execution.log"; then
  fail "V19 dataset rejection reached an AWS mutation"
fi

jq '.schemaVersion = 1' "$temp_dir/dataset-manifest.json" > "$temp_dir/dataset-manifest-v1.json"
: > "$temp_dir/operator-execution.log"
if FAKE_DATASET_MANIFEST="$temp_dir/dataset-manifest-v1.json" \
  run_fake_up lab-v1-dataset >"$temp_dir/v1.out" 2>"$temp_dir/v1.err"; then
  fail "operator accepted a legacy V1 dataset completion manifest"
fi
if grep -Eq 'terraform .* (plan|apply)|^network ' "$temp_dir/operator-execution.log"; then
  fail "legacy V1 dataset rejection reached Terraform plan/apply or network verification"
fi
if grep -Eq '^lease acquire |s3api put-object|dynamodb (put-item|update-item|delete-item)' "$temp_dir/operator-execution.log"; then
  fail "legacy V1 dataset rejection reached an AWS mutation"
fi

jq '.releaseKind = "evidence"' "$temp_dir/dataset-manifest.json" > "$temp_dir/dataset-manifest-evidence.json"
: > "$temp_dir/operator-execution.log"
if FAKE_DATASET_MANIFEST="$temp_dir/dataset-manifest-evidence.json" \
  run_fake_up lab-evidence-dataset >"$temp_dir/evidence.out" 2>"$temp_dir/evidence.err"; then
  fail "operator accepted a non-deployable evidence release"
fi
if grep -Eq 'terraform .* (plan|apply)|^network |^lease acquire |s3api put-object|dynamodb (put-item|update-item|delete-item)' \
  "$temp_dir/operator-execution.log"; then
  fail "evidence release rejection reached an AWS mutation"
fi

jq '.mysql.expectedTableRows.flyway_schema_history = 19' "$temp_dir/dataset-manifest.json" \
  > "$temp_dir/dataset-manifest-history-v19.json"
: > "$temp_dir/operator-execution.log"
if FAKE_DATASET_MANIFEST="$temp_dir/dataset-manifest-history-v19.json" \
  run_fake_up lab-v19-history >"$temp_dir/v19-history.out" 2>"$temp_dir/v19-history.err"; then
  fail "operator accepted a V27 manifest with a V19 Flyway history row count"
fi
if grep -Eq 'terraform .* (plan|apply)|^network ' "$temp_dir/operator-execution.log"; then
  fail "Flyway history rejection reached Terraform plan/apply or network verification"
fi
if grep -Eq '^lease acquire |s3api put-object|dynamodb (put-item|update-item|delete-item)' "$temp_dir/operator-execution.log"; then
  fail "Flyway history rejection reached an AWS mutation"
fi

: > "$temp_dir/operator-execution.log"
if FAKE_DATASET_MANIFEST="$temp_dir/missing-dataset-manifest.json" \
  run_fake_up lab-missing-dataset >"$temp_dir/missing.out" 2>"$temp_dir/missing.err"; then
  fail "operator accepted an absent dataset completion manifest"
fi
if grep -Eq 'terraform .* (plan|apply)|^network ' "$temp_dir/operator-execution.log"; then
  fail "absent dataset rejection reached Terraform plan/apply or network verification"
fi

jq '.mysql.password = "must-not-be-replayed"' "$temp_dir/dataset-manifest.json" > "$temp_dir/dataset-manifest-secret.json"
: > "$temp_dir/operator-execution.log"
if FAKE_DATASET_MANIFEST="$temp_dir/dataset-manifest-secret.json" \
  run_fake_up lab-secret-dataset >"$temp_dir/secret.out" 2>"$temp_dir/secret.err"; then
  fail "operator accepted a secret-bearing dataset completion manifest"
fi
if grep -Fq 'must-not-be-replayed' "$temp_dir/secret.out" "$temp_dir/secret.err" "$temp_dir/operator-execution.log"; then
  fail "operator replayed a rejected dataset manifest value"
fi
if grep -Eq 'terraform .* (plan|apply)|^network ' "$temp_dir/operator-execution.log"; then
  fail "secret-bearing dataset rejection reached Terraform plan/apply or network verification"
fi

jq '.hostAccommodations.detailAccommodationId = 200002' "$temp_dir/benchmark-manifest.json" \
  > "$temp_dir/benchmark-manifest-tampered.json"
: > "$temp_dir/operator-execution.log"
if FAKE_BENCHMARK_MANIFEST="$temp_dir/benchmark-manifest-tampered.json" \
  run_fake_up lab-tampered-smoke-input >"$temp_dir/tampered-smoke.out" 2>"$temp_dir/tampered-smoke.err"; then
  fail "operator accepted a representative smoke input outside the release digest"
fi
if grep -Eq '^lease acquire |terraform .* (plan|apply)|^network |^dns ' "$temp_dir/operator-execution.log"; then
  fail "tampered smoke input rejection reached an infrastructure mutation"
fi

: > "$temp_dir/operator-execution.log"
run_fake_up lab-fake-success >/dev/null
expected_order='phase network app=false
network egress
phase probe-cleared app=false
network cleared
phase services app=false
policy isolated-read
phase data-ready app=true
dns stage oci
dns switch aws'
previous_line=0
while IFS= read -r expected; do
  line=$(grep -n -m1 -F "$expected" "$temp_dir/operator-execution.log" | cut -d: -f1)
  [[ -n "$line" && "$line" -gt "$previous_line" ]] || fail "fake up order is incorrect at: $expected"
  previous_line=$line
done <<EOF
$expected_order
EOF
grep -Fq 'lease release ' "$temp_dir/operator-execution.log" || fail "successful up did not release its lease"
grep -Fq 'runs/lab-fake-success/terraform-outputs.redacted.json' "$temp_dir/operator-execution.log" \
  || fail "successful up did not save redacted Terraform outputs"
direct_detail_line=$(grep -n -m1 'api/v1/accommodations/200001' "$temp_dir/operator-execution.log" | cut -d: -f1)
dns_stage_line=$(grep -n -m1 '^dns stage oci' "$temp_dir/operator-execution.log" | cut -d: -f1)
dns_switch_line=$(grep -n -m1 '^dns switch aws' "$temp_dir/operator-execution.log" | cut -d: -f1)
public_detail_line=$(grep -n 'api/v1/accommodations/200001' "$temp_dir/operator-execution.log" | tail -1 | cut -d: -f1)
[[ "$direct_detail_line" -lt "$dns_stage_line" && "$dns_switch_line" -lt "$public_detail_line" ]] \
  || fail "representative MySQL smoke did not bracket the AWS DNS switch"

jq '.search.enabled = true' "$temp_dir/dataset-manifest.json" > "$temp_dir/dataset-manifest-search.json"
: > "$temp_dir/operator-execution.log"
FAKE_DATASET_MANIFEST="$temp_dir/dataset-manifest-search.json" \
  run_fake_up lab-search-smoke >/dev/null
[[ "$(grep -c 'api/v1/search/accommodations' "$temp_dir/operator-execution.log")" == 4 ]] \
  || fail "search-enabled up did not run one direct and three public Elasticsearch API smokes"
grep -Fq -- '--data-urlencode maxPrice=4383' "$temp_dir/operator-execution.log" \
  || fail "search smoke did not use the manifest-bound typed query"

: > "$temp_dir/operator-execution.log"
if env FAKE_PERSISTENT_DELETE=true \
  PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
  FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
  FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
  FAKE_RUN_MANIFEST="$temp_dir/run-manifest.json" \
  FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
  MODE=performance POLICY=isolated-read \
  IMAGE_DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  DATASET_RELEASE=fixture-v20 BUNDLE_COMMIT=cccccccccccccccccccccccccccccccccccccccc \
  AMI_ID=ami-0123456789abcdef0 OCI_ORIGIN_IPV4=203.0.113.10 \
  RDS_ENGINE_VERSION=8.0.42 LOAD_GENERATOR_ENABLED=false TTL_HOURS=1 \
  RUN_ID=lab-persistent-delete "$fixture_scripts/aws-lab.sh" up >/dev/null 2>&1; then
  fail "operator accepted a plan that deletes a persistent resource"
fi
if grep -Fq 'dns ' "$temp_dir/operator-execution.log"; then
  fail "persistent-delete rejection reached DNS mutation"
fi

: > "$temp_dir/operator-execution.log"
if run_fake_up lab-fake-failure true >/dev/null 2>&1; then
  fail "post-cutover public smoke failure was hidden"
fi
grep -Fq 'dns switch oci' "$temp_dir/operator-execution.log" || fail "post-cutover failure did not roll DNS back to OCI"
grep -Fq 'terraform -chdir=' "$temp_dir/operator-execution.log" || fail "failure path did not invoke Terraform cleanup"
grep -Fq 'failures/lab-fake-failure/operator-42.json' "$temp_dir/operator-execution.log" \
  || fail "failure path did not save bounded operator evidence"
grep -Fq 'runs/lab-fake-failure/terraform-outputs.redacted.json' "$temp_dir/operator-execution.log" \
  || fail "failure path did not save bounded Terraform output evidence before cleanup"

: > "$temp_dir/operator-execution.log"
if env PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
  FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
  FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
  FAKE_RUN_MANIFEST="$temp_dir/run-manifest.json" \
  FAKE_DATASET_MANIFEST="$temp_dir/dataset-manifest.json" \
  FAKE_BENCHMARK_MANIFEST="$temp_dir/benchmark-manifest.json" \
  FAKE_BENCHMARK_DATASET_MANIFEST="$temp_dir/benchmark-dataset-manifest.json" \
  FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
  FAKE_STATE_EXISTS=true FAKE_STATE_RUN_ID=lab-partial-down \
  FAKE_PUBLIC_SMOKE_FAILURE=true TARGET=aws RUN_ID=lab-partial-down \
  "$fixture_scripts/aws-lab.sh" switch >/dev/null 2>&1; then
  fail "manual AWS switch hid a post-cutover public smoke failure"
fi
grep -Fq 'dns switch aws' "$temp_dir/operator-execution.log" \
  || fail "manual AWS switch did not reach the requested target"
grep -Fq 'dns switch oci' "$temp_dir/operator-execution.log" \
  || fail "manual AWS switch smoke failure did not roll DNS back to OCI"

: > "$temp_dir/operator-execution.log"
if ! env PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
  FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
  FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
  FAKE_RUN_MANIFEST="$temp_dir/run-manifest.json" \
  FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
  FAKE_NO_ALB=true FAKE_STATE_EXISTS=true RUN_ID=lab-partial-down \
  "$fixture_scripts/aws-lab.sh" down >"$temp_dir/down.out" 2>"$temp_dir/down.err"; then
  sed -n '1,80p' "$temp_dir/down.err" >&2
  tail -40 "$temp_dir/operator-execution.log" >&2
  fail "partial down failed in the fake operator environment"
fi
grep -Fq 'dns remove oci' "$temp_dir/operator-execution.log" || fail "partial down did not restore the OCI DNS contract"
grep -Fqx 'dns-fence resource=41 current=42' "$temp_dir/operator-execution.log" \
  || fail "down did not preserve the ALB creation fence separately from the current command fence"
grep -Fq 'terraform -chdir=' "$temp_dir/operator-execution.log" || fail "partial down did not destroy the lab state"
grep -Fqx 'orphans lab-partial-down' "$temp_dir/operator-execution.log" || fail "partial down did not scan for orphans"
grep -Fq 'runs/lab-partial-down/terraform-outputs.redacted.json' "$temp_dir/operator-execution.log" \
  || fail "down did not save redacted Terraform outputs"

jq '.expiresAt="not-an-epoch"' "$temp_dir/run-manifest.json" > "$temp_dir/run-manifest.invalid.json"
: > "$temp_dir/operator-execution.log"
if env PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
  FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
  FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
  FAKE_RUN_MANIFEST="$temp_dir/run-manifest.invalid.json" \
  FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
  FAKE_STATE_EXISTS=true RUN_ID=lab-partial-down "$fixture_scripts/aws-lab.sh" down >/dev/null 2>&1; then
  fail "down accepted a non-canonical manifest expiry"
fi
if grep -Eq 'dns |terraform .* (plan|apply|destroy)' "$temp_dir/operator-execution.log"; then
  fail "invalid manifest expiry reached an infrastructure mutation"
fi

: > "$temp_dir/operator-execution.log"
if env PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
  FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
  FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
  FAKE_RUN_MANIFEST="$temp_dir/run-manifest.json" \
  FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
  FAKE_STATE_EXISTS=true FAKE_STATE_RUN_ID=lab-newer-run RUN_ID=lab-partial-down \
  "$fixture_scripts/aws-lab.sh" down >/dev/null 2>&1; then
  fail "down accepted a stale manifest after the Terraform state run changed"
fi
if grep -Eq 'dns |terraform .* (plan|apply|destroy)' "$temp_dir/operator-execution.log"; then
  fail "stale state identity reached an infrastructure mutation"
fi

printf '%s\n' 'AWS lab operator contract tests passed'
