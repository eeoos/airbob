#!/usr/bin/env bash
set -euo pipefail

# The production operator permits Terraform only with an explicit, non-refreshing
# STS environment tuple. Hermetic fake AWS calls use inert values.
export AWS_ACCESS_KEY_ID=ASIAEXPLICITFIXTURE
export AWS_SECRET_ACCESS_KEY=explicit-fixture-secret
export AWS_SESSION_TOKEN=explicit-fixture-session

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
operator="$repo_root/infra/aws/scripts/aws-lab.sh"
lease_script="$repo_root/infra/aws/scripts/orchestration-lease.sh"
orphan_scanner="$repo_root/infra/aws/scripts/scan-lab-orphans.sh"
expiry_cleanup="$repo_root/infra/aws/scripts/cleanup-expired-lab.sh"
policy_verifier="$repo_root/infra/aws/scripts/enforce-measurement-policy.sh"
comparison_projection_filter="$repo_root/infra/aws/scripts/readiness-comparison-projection.jq"
workflow="$repo_root/.github/workflows/aws-performance-lab.yml"
ssm_contract="$repo_root/infra/aws/lab/ssm.tf"
run_identity_contract="$repo_root/infra/aws/lab/run-identity.tf"
foundation_iam="$repo_root/infra/aws/foundation/iam.tf"
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

for executable in "$operator" "$lease_script" "$orphan_scanner" "$expiry_cleanup" "$policy_verifier"; do
  [[ -x "$executable" && ! -L "$executable" ]] || fail "$executable is missing or unsafe"
done
[[ -f "$workflow" && ! -L "$workflow" ]] || fail "AWS lab workflow is missing or unsafe"
[[ -f "$makefile" && ! -L "$makefile" ]] || fail "root Makefile is missing or unsafe"
for contract_file in "$ssm_contract" "$run_identity_contract" "$foundation_iam"; do
  [[ -f "$contract_file" && ! -L "$contract_file" ]] || fail "$contract_file is missing or unsafe"
done
[[ -f "$comparison_projection_filter" && ! -L "$comparison_projection_filter" ]] \
  || fail "readiness comparison projection is missing or unsafe"

assert_contains "$operator" 'case "$action" in up|status|switch|down)'
assert_contains "$operator" 'AWS_LAB_OPERATOR_SCOPE must be direct or cutover'
assert_contains "$operator" 'airbob-lab-cutover-operator'
assert_contains "$operator" 'active Lab credentials do not match AWS_LAB_OPERATOR_SCOPE'
assert_contains "$operator" '"$lease_script" acquire'
assert_contains "$operator" '"$lease_script" heartbeat'
assert_contains "$operator" '"$lease_script" assert'
assert_contains "$operator" '"$lease_script" release'
assert_contains "$operator" 'DEFAULT_COMMAND_DEADLINE_SECONDS=5400'
assert_contains "$operator" 'DUMP_UP_COMMAND_DEADLINE_SECONDS=14400'
assert_contains "$operator" 'DEFAULT_CREDENTIAL_SESSION_SECONDS=7200'
assert_contains "$operator" 'DUMP_UP_CREDENTIAL_SESSION_SECONDS=18000'
assert_contains "$operator" 'DUMP_UP_PRE_BOOTSTRAP_ALLOWANCE_SECONDS=3600'
assert_contains "$operator" 'DUMP_UP_POST_BOOTSTRAP_ALLOWANCE_SECONDS=2400'
assert_contains "$operator" 'LAB_ROLE_MAX_SESSION_SECONDS=18000'
assert_contains "$operator" 'TERRAFORM_LOCK_CREDENTIAL_EXPIRY_MARGIN_SECONDS=300'
assert_contains "$operator" 'TERRAFORM_LOCK_CREDENTIAL_EXPIRY_BARRIER_SECONDS=$(('
assert_contains "$operator" 'active Lab role must use one explicit static STS environment credential tuple'
assert_contains "$operator" 'assumed Lab role returned an incomplete static STS environment credential tuple'
assert_contains "$operator" 'run_supervised_mutation'
assert_contains "$operator" 'kill -TERM -- "-$child_pgid"'
assert_contains "$operator" 'kill -KILL -- "-$child_pgid"'
assert_contains "$operator" 'terraform_lock_key="${lab_state_key}.tflock"'
assert_contains "$operator" 'terraform_lock_path="$state_bucket/$lab_state_key"'
assert_contains "$operator" 'recover_prior_terraform_lock'
assert_contains "$operator" 'force-unlock -force "$lock_id"'
assert_contains "$operator" 'lock_created_epoch < lease_acquired_epoch'
assert_contains "$operator" 'Terraform native lock was created at or after the current orchestration lease'
assert_contains "$operator" 'for role_name in airbob-lab-operator airbob-lab-cutover-operator'
assert_contains "$operator" 'iam get-role --role-name "$role_name"'
assert_contains "$operator" 'lock_last_modified_epoch + TERRAFORM_LOCK_CREDENTIAL_EXPIRY_BARRIER_SECONDS'
assert_contains "$operator" 'clock_server_epoch >= credential_expiry_epoch'
assert_contains "$operator" 'Terraform native lock is younger than the AWS-authoritative static STS credential-expiry barrier'
assert_contains "$operator" 'teardown-terraform-lock-clock-$fencing_token.json'
assert_contains "$operator" 'Terraform native lock S3 identity changed during fenced recovery'
if grep -Eq 'aws s3api delete-object .*tflock|aws s3 rm .*tflock' "$operator"; then
  fail "operator directly deletes a Terraform native lock instead of using force-unlock"
fi
assert_contains "$operator" 'AIRBOB_OPERATOR_TEST_HARNESS'
assert_contains "$operator" 'operator test timing overrides are outside the hermetic fake harness'
assert_contains "$operator" 'dump bootstrap requires TTL_HOURS of at least 5'
assert_contains "$operator" 'snapshot bootstrap requires explicit TTL_HOURS=2'
assert_contains "$operator" 'dump bootstrap forbids every RDS snapshot source identity'
assert_contains "$operator" 'RDS_SNAPSHOT_SOURCE_RUN_ID'
assert_contains "$operator" 'RDS_SNAPSHOT_SOURCE_RESOURCE_ID'
assert_contains "$operator" 'local existing_found=false'
assert_contains "$operator" 'existing_found=true'
assert_contains "$operator" '[[ "$existing_found" == false ]] || receipt=$existing'
if grep -Fq '[[ -f "$existing" ]] && receipt=$existing' "$operator"; then
  fail "teardown finalize readback selection still depends on a failed GET leaving no file"
fi
assert_contains "$operator" '-target=terraform_data.run_identity'
assert_contains "$operator" 'output -json run_identity'
assert_contains "$operator" '.values.root_module.resources[]?'
assert_contains "$operator" 'Terraform state resource fencing token differs from the run manifest'
assert_contains "$run_identity_contract" 'resource "terraform_data" "run_identity"'
assert_contains "$run_identity_contract" 'resource_fencing_token'
assert_contains "$foundation_iam" 'max_session_duration = 18000'
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
assert_contains "$operator" 'DNS_MODE must be direct-only or cutover'
assert_contains "$operator" 'direct-only runs cannot switch DNS'
assert_contains "$operator" 'verify_oci_authority'
assert_contains "$operator" 'curl -4 --fail'
assert_contains "$operator" '[.ResourceRecordSets[] | select(.Name == $fqdn)]'
assert_contains "$operator" '--start-record-name api.airbob.cloud'
if grep -Fq -- '--start-record-type A' "$operator"; then
  fail "OCI authority verification still reads only Route 53 A records"
fi
assert_contains "$operator" 'actual ALB ingress is not exactly TCP/443 from the requested IPv4 CIDR'
assert_contains "$operator" 'live Auto Scaling capacity differs from the Phase 4 contract'
assert_contains "$operator" 'measurements/$run_id/direct-readiness.json'
assert_contains "$operator" 'measurements/$run_id/teardown-start.json'
assert_contains "$operator" 'measurements/$run_id/teardown-finalize.json'
assert_contains "$operator" 'measurements/state-clean/$state_version_hash.json'
assert_contains "$operator" 'plan -destroy -refresh=false'
assert_contains "$operator" 'state rm -lock-timeout=5m'
assert_contains "$operator" 'empty Terraform state is not the direct successor of teardown finalize'
assert_contains "$operator" "--if-none-match '*'"
assert_contains "$operator" 'application digest does not match the runtime commit tag'
assert_contains "$operator" 'state list 2>/dev/null'
assert_contains "$operator" 'comparisonProjectionSha256'
assert_contains "$operator" 'RUN_ID has already been used'
assert_contains "$operator" 'AIRBOB_SCAN_SCOPE=global'
assert_contains "$operator" 'DATASET_MANIFEST_VERSION_ID is required'
assert_contains "$operator" 'BUNDLE_MANIFEST_VERSION_ID is required'
assert_contains "$operator" '--version-id "$dataset_manifest_version_id"'
assert_contains "$operator" '--version-id "$bundle_manifest_version_id"'
assert_contains "$operator" '--version-id "$network_clearance_version_id"'
assert_contains "$operator" 'network clearance receipt has no version identity'
assert_contains "$operator" 'network clearance receipt is invalid'
assert_contains "$operator" '--arg dns_mode "$dns_mode"'
assert_contains "$operator" '--arg alb_ingress_cidr "$alb_ingress_cidr"'
if grep -Fq 'ensure_teardown_start >/dev/null 2>&1 || true' "$operator"; then
  fail "failure cleanup must not destroy after a best-effort teardown-start journal"
fi

assert_contains "$orphan_scanner" 'resourcegroupstaggingapi get-resources'
assert_contains "$orphan_scanner" 'RunId'
assert_contains "$orphan_scanner" 'Persistence'
assert_contains "$orphan_scanner" 'AIRBOB_SCAN_SCOPE'
assert_contains "$orphan_scanner" 'bounded_prefix="airbob-${run_id:0:12}-${run_hash:0:6}"'
assert_contains "$orphan_scanner" "LoadBalancerName=='\$bounded_prefix-alb'"
assert_contains "$orphan_scanner" "AutoScalingGroupName=='airbob-\$run_id-app'"
assert_contains "$orphan_scanner" 'describe-vpcs'
assert_contains "$orphan_scanner" 'describe-network-interfaces'
assert_contains "$orphan_scanner" 'describe-launch-templates'
assert_contains "$orphan_scanner" 'describe-vpc-endpoints'
assert_contains "$orphan_scanner" 'describe-target-groups'
assert_contains "$orphan_scanner" 'describe-db-subnet-groups'
assert_contains "$orphan_scanner" 'describe-db-parameter-groups'
assert_contains "$orphan_scanner" 'autoscaling describe-policies'
assert_contains "$orphan_scanner" 'iam list-roles'
assert_contains "$orphan_scanner" 'iam list-instance-profiles'
assert_contains "$orphan_scanner" 'list-secrets'
assert_contains "$orphan_scanner" 'list-dashboards'
assert_contains "$orphan_scanner" 'describe-instance-information'
assert_contains "$orphan_scanner" "starts_with(Name, 'airbob-\$run_id')"
assert_contains "$orphan_scanner" '/airbob/performance-lab/foundation/lab-contract'
assert_contains "$orphan_scanner" 'private_dns_zone_id'
assert_contains "$orphan_scanner" 'redis-general.lab.airbob.internal.'
assert_contains "$orphan_scanner" 'monitoring.lab.airbob.internal.'
assert_contains "$orphan_scanner" 'assert_aws_empty'
if grep -Eq 'assert_empty .*\$\(aws ' "$orphan_scanner"; then
  fail "orphan scanner must fail closed before passing AWS output to assert_empty"
fi
if grep -Fq -- '--filters "Key=Name,Values=airbob-$run_id"' "$orphan_scanner"; then
  fail "SSM document scan must list owned documents then enforce the exact run prefix"
fi
assert_contains "$policy_verifier" 'airbob-outbox-connector/pause'
assert_contains "$policy_verifier" 'AIRBOB_ISOLATED_DB_OK'
assert_contains "$policy_verifier" 'AIRBOB_ISOLATED_KAFKA_OK'
assert_contains "$policy_verifier" 'kafka-get-offsets.sh'

for target in aws-up aws-status aws-switch aws-down; do
  assert_contains "$makefile" "$target:"
done

assert_contains "$workflow" 'workflow_dispatch:'
assert_contains "$workflow" 'options: [up, status, switch, down]'
assert_contains "$workflow" 'schedule:'
assert_contains "$workflow" "cron: '17,47 * * * *'"
assert_contains "$workflow" 'group: aws-performance-lab'
assert_contains "$workflow" 'cancel-in-progress: false'
assert_contains "$workflow" 'id-token: write'
assert_contains "$workflow" "environment: \${{ (github.event_name == 'schedule' || inputs.action == 'switch' || inputs.dns_mode == 'cutover') && 'aws-performance-lab-cutover' || 'aws-performance-lab' }}"
assert_contains "$workflow" 'aws-actions/configure-aws-credentials@e3dd6a429d7300a6a4c196c26e071d42e0343502'
assert_contains "$workflow" 'infra/aws/scripts/aws-lab.sh'
assert_contains "$workflow" 'infra/aws/scripts/cleanup-expired-lab.sh'
assert_contains "$workflow" 'options: [performance, scaling]'
assert_contains "$workflow" 'options: [direct-only, cutover]'
assert_contains "$workflow" "default: '5'"
assert_contains "$workflow" "timeout-minutes: \${{ inputs.action == 'up' && inputs.database_bootstrap == 'dump' && 270 || 120 }}"
assert_contains "$workflow" "inputs.database_bootstrap == 'dump' && 18000 || 7200"
assert_contains "$workflow" 'https://checkip.amazonaws.com'
assert_contains "$workflow" "printf 'ALB_INGRESS_CIDR=%s/32"
assert_contains "$workflow" 'live_address=$(curl --fail --silent --show-error --max-time 10 https://checkip.amazonaws.com)'
assert_contains "$workflow" 'requested_address=${BASH_REMATCH[1]}'
assert_contains "$workflow" '[[ "$requested_address" == "$live_address" ]]'
workflow_dispatch_input_count=$(awk '
  /^  workflow_dispatch:/ { in_dispatch=1; next }
  in_dispatch && /^concurrency:/ { exit }
  in_dispatch && /^      [[:alnum:]_]+:$/ { count++ }
  END { print count + 0 }
' "$workflow")
((workflow_dispatch_input_count <= 25)) \
  || fail "AWS lab workflow_dispatch exposes $workflow_dispatch_input_count inputs; GitHub permits at most 25"
for removed_measurement_input in \
  benchmark_target rate duration warmup_duration minimum_completed_samples \
  round run_order app_commit expected_sql_calls_per_request; do
  if grep -Eq "^      ${removed_measurement_input}:$|inputs\\.${removed_measurement_input}([^a-zA-Z0-9_]|$)" "$workflow"; then
    fail "AWS lab qualification workflow still exposes performance input: $removed_measurement_input"
  fi
done
if grep -Eq "inputs\\.action (==|!=) 'measure'|Run shared AWS discovery harness|run-aws-discovery\\.sh" "$workflow"; then
  fail "AWS lab qualification workflow still exposes the performance measurement harness"
fi
if grep -Fq 'env.AIRBOB_ALB_INGRESS_CIDR' "$workflow"; then
  fail "workflow must consume the ingress CIDR from GITHUB_ENV, not a static expression context"
fi
awk '
  /- name: Resolve direct-only operator ingress \/32/ { found=1 }
  found && /^        run: \|/ { body=1; next }
  body && /^      - name:/ { exit }
  body { sub(/^          /, ""); print }
' "$workflow" > "$temp_dir/resolve-operator-cidr.sh"
bash -n "$temp_dir/resolve-operator-cidr.sh"
mkdir -p "$temp_dir/cidr-bin"
cat > "$temp_dir/cidr-bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "${FAKE_LIVE_IP:-8.8.8.8}"
EOF
chmod 700 "$temp_dir/cidr-bin/curl"
: > "$temp_dir/github-env"
env PATH="$temp_dir/cidr-bin:$PATH" REQUESTED_CIDR=8.8.8.8/32 \
  GITHUB_ENV="$temp_dir/github-env" bash "$temp_dir/resolve-operator-cidr.sh"
grep -Fqx 'ALB_INGRESS_CIDR=8.8.8.8/32' "$temp_dir/github-env" \
  || fail "workflow did not export the live hosted-runner /32"
if env PATH="$temp_dir/cidr-bin:$PATH" REQUESTED_CIDR=1.1.1.1/32 \
  GITHUB_ENV="$temp_dir/github-env" bash "$temp_dir/resolve-operator-cidr.sh" >/dev/null 2>&1; then
  fail "workflow accepted a CIDR override that differs from the live hosted-runner address"
fi
if env PATH="$temp_dir/cidr-bin:$PATH" FAKE_LIVE_IP=192.88.99.1 \
  REQUESTED_CIDR=192.88.99.1/32 GITHUB_ENV="$temp_dir/github-env" \
  bash "$temp_dir/resolve-operator-cidr.sh" >/dev/null 2>&1; then
  fail "workflow accepted the reserved 192.88.99.0/24 relay anycast range"
fi
assert_contains "$workflow" 'DATASET_MANIFEST_VERSION_ID:'
assert_contains "$workflow" 'BUNDLE_MANIFEST_VERSION_ID:'
assert_contains "$makefile" 'DNS_MODE="$(or $(DNS_MODE),direct-only)"'
assert_contains "$makefile" 'LOAD_GENERATOR_ENABLED="$(or $(LOAD_GENERATOR_ENABLED),false)"'
assert_contains "$expiry_cleanup" '"$now_epoch" -lt "$expires_at"'
if grep -Eq 'AWS_(ACCESS_KEY_ID|SECRET_ACCESS_KEY)' "$workflow"; then
  fail "AWS lab workflow must not use static credentials"
fi

default_deadline=$(awk -F= '$1 == "DEFAULT_COMMAND_DEADLINE_SECONDS" { print $2 }' "$operator")
dump_deadline=$(awk -F= '$1 == "DUMP_UP_COMMAND_DEADLINE_SECONDS" { print $2 }' "$operator")
default_session=$(awk -F= '$1 == "DEFAULT_CREDENTIAL_SESSION_SECONDS" { print $2 }' "$operator")
dump_session=$(awk -F= '$1 == "DUMP_UP_CREDENTIAL_SESSION_SECONDS" { print $2 }' "$operator")
workflow_seconds=$((120 * 60))
dump_workflow_seconds=$((270 * 60))
ssm_seconds=7200
pre_bootstrap_seconds=$(awk -F= '$1 == "DUMP_UP_PRE_BOOTSTRAP_ALLOWANCE_SECONDS" { print $2 }' "$operator")
post_bootstrap_seconds=$(awk -F= '$1 == "DUMP_UP_POST_BOOTSTRAP_ALLOWANCE_SECONDS" { print $2 }' "$operator")
dump_ttl_seconds=$((5 * 3600))
[[ "$(grep -Fc 'timeoutSeconds = "7200"' "$ssm_contract")" -eq 1 ]] \
  || fail "data bootstrap SSM document timeout is not exactly 7200 seconds"
assert_contains "$ssm_contract" 'wait_for_success_timeout_seconds = 7200'
((default_deadline == 5400 && default_deadline < default_session && default_session == workflow_seconds)) \
  || fail "snapshot/down deadline, credential, and workflow timeout hierarchy is invalid"
((ssm_seconds + pre_bootstrap_seconds + post_bootstrap_seconds < dump_deadline &&
  dump_deadline < dump_workflow_seconds && dump_workflow_seconds < dump_session &&
  dump_session == dump_ttl_seconds)) \
  || fail "dump pre/bootstrap/post, operator, workflow, credential, and TTL hierarchy is invalid"
((dump_deadline < 5 * 3600)) \
  || fail "dump operator deadline must remain inside the minimum ephemeral TTL"
if env AIRBOB_OPERATOR_TEST_HARNESS=hermetic-fake-v1 \
  AIRBOB_TEST_COMMAND_DEADLINE_SECONDS=2 AIRBOB_TEST_HEARTBEAT_INTERVAL_SECONDS=1 \
  AIRBOB_TEST_TERMINATION_GRACE_SECONDS=1 "$operator" status >/dev/null 2>&1; then
  fail "tracked operator checkout accepted hermetic-only timing overrides"
fi

# The canonical projection deliberately excludes run/mode-source coordinates
# while retaining immutable lineage, fixed shape, and every smoke outcome.
cat > "$temp_dir/readiness-a.json" <<'JSON'
{
  "schemaVersion": 1,
  "runId": "lab-dump",
  "fencingToken": 41,
  "executionCode": {"commit":"dddddddddddddddddddddddddddddddddddddddd","operatorTreeSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
  "dataset": {"release":"fixture-v20","manifestVersionId":"dataset-v1","manifestSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
  "bundle": {"commit":"cccccccccccccccccccccccccccccccccccccccc","archiveSha256":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","checksumVersionId":"checksum-v1","manifestVersionId":"bundle-v1","manifestSha256":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"},
  "images": {"app":"repo@sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","infra":{"REDIS_IMAGE":"redis@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}},
  "bootstrap": {"mode":"dump","rdsSnapshotIdentifier":null,"rdsSnapshotSourceRunId":null,"rdsSnapshotSourceResourceId":null,"dataProjectionSha256":"1111111111111111111111111111111111111111111111111111111111111111","receipt":{"key":"data-bootstrap/lab-dump/fixture-v20.json","versionId":"data-v1","sha256":"2222222222222222222222222222222222222222222222222222222222222222","lastModified":"2026-09-01T00:00:00Z"}},
  "networkClearance": {"key":"network-clearance/lab-dump/i-old.json","versionId":"network-v1","sha256":"5555555555555555555555555555555555555555555555555555555555555555","lastModified":"2026-09-01T00:01:00Z","projectionSha256":"6666666666666666666666666666666666666666666666666666666666666666"},
  "actual": {
    "ami":{"id":"ami-0123456789abcdef0","shape":{"imageId":"ami-0123456789abcdef0","architecture":"x86_64"}},
    "rds":{"identifier":"airbob-lab-dump","resourceId":"db-OLD","class":"db.t3.micro","engine":"mysql","engineVersion":"8.0.42","allocatedStorageGiB":100,"storageType":"gp3","multiAz":false,"storageEncrypted":true,"availabilityZone":"ap-northeast-2a","parameterGroups":["airbob-lab-dump"]},
    "rdsParameterGroupFamily":"mysql8.0",
    "alb":{"arn":"arn:old","dnsName":"old.elb.amazonaws.com","targetGroupArn":"tg-old","autoScalingGroupName":"asg-old","securityGroupId":"sg-old","shape":{"arn":"arn:old","dnsName":"old.elb.amazonaws.com","scheme":"internet-facing","type":"application","ipAddressType":"ipv4","availabilityZones":["ap-northeast-2a","ap-northeast-2c"],"securityGroups":["sg-old"]},"observedIngress":[{"ruleId":"sgr-old","groupId":"sg-old","isEgress":false,"ipProtocol":"tcp","fromPort":443,"toPort":443,"cidrIpv4":"198.51.100.10/32","cidrIpv6":null,"prefixListId":null,"referencedGroupId":null}]},
    "autoScalingGroup":{"name":"asg-old","min":1,"desired":1,"max":1}
  },
  "topology":{"mode":"performance","policy":"integrated-smoke","dnsMode":"direct-only","albIngressCidr":"198.51.100.10/32","cacheEnabled":true,"loadGeneratorEnabled":false},
  "ociAuthority":{"status":"verified","observedAt":"2026-09-01T00:03:00Z","recordSetSha256":"3333333333333333333333333333333333333333333333333333333333333333"},
  "smoke":{"health":{"passed":true},"accommodationDetail":{"id":200001,"passed":true},"search":{"enabled":true,"querySha256":"4444444444444444444444444444444444444444444444444444444444444444","passed":true}},
  "timing":{"resourceStartedAt":"2026-09-01T00:00:00Z","dataReadyAt":"2026-09-01T00:10:00Z","directReadyAt":"2026-09-01T00:12:00Z","resourceToDataReadySeconds":600,"resourceToDirectReadySeconds":720}
}
JSON
jq '
  .runId="lab-snapshot" | .fencingToken=99 |
  .bootstrap.mode="snapshot" | .bootstrap.rdsSnapshotIdentifier="airbob-dataset-fixture" |
  .bootstrap.rdsSnapshotSourceRunId="lab-dump" |
  .bootstrap.rdsSnapshotSourceResourceId="db-ABCDEFGHIJKLMNOPQRSTUVWX" |
  .bootstrap.receipt.key="data-bootstrap/lab-snapshot/fixture-v20.json" |
  .bootstrap.receipt.versionId="data-v2" | .bootstrap.receipt.lastModified="2026-09-01T01:00:00Z" |
  .networkClearance.key="network-clearance/lab-snapshot/i-new.json" |
  .networkClearance.versionId="network-v2" |
  .networkClearance.sha256="7777777777777777777777777777777777777777777777777777777777777777" |
  .networkClearance.lastModified="2026-09-01T01:01:00Z" |
  .actual.rds.identifier="airbob-lab-snapshot" | .actual.rds.resourceId="db-NEW" |
  .actual.rds.parameterGroups=["airbob-lab-snapshot"] |
  .actual.alb.arn="arn:new" | .actual.alb.dnsName="new.elb.amazonaws.com" |
  .actual.alb.targetGroupArn="tg-new" | .actual.alb.autoScalingGroupName="asg-new" |
  .actual.alb.securityGroupId="sg-new" | .actual.alb.shape.securityGroups=["sg-new"] |
  .actual.alb.observedIngress[0].ruleId="sgr-new" | .actual.alb.observedIngress[0].groupId="sg-new" |
  .actual.alb.observedIngress[0].cidrIpv4="203.0.113.20/32" |
  .actual.alb.shape.arn="arn:new" | .actual.alb.shape.dnsName="new.elb.amazonaws.com" |
  .actual.alb.shape.availabilityZones=["ap-northeast-2c","ap-northeast-2a"] |
  .actual.autoScalingGroup.name="asg-new" |
  .topology.albIngressCidr="203.0.113.20/32" |
  .ociAuthority.observedAt="2026-09-01T01:03:00Z" |
  .timing={resourceStartedAt:"2026-09-01T01:00:00Z",dataReadyAt:"2026-09-01T01:05:00Z",directReadyAt:"2026-09-01T01:07:00Z",resourceToDataReadySeconds:300,resourceToDirectReadySeconds:420}
' "$temp_dir/readiness-a.json" > "$temp_dir/readiness-b.json"
jq -Sf "$comparison_projection_filter" "$temp_dir/readiness-a.json" > "$temp_dir/projection-a.json"
jq -Sf "$comparison_projection_filter" "$temp_dir/readiness-b.json" > "$temp_dir/projection-b.json"
cmp -s "$temp_dir/projection-a.json" "$temp_dir/projection-b.json" \
  || fail "run/fence/source/CIDR/generated-coordinate/timing differences changed the comparison projection"
jq -e '.topology.alb.availabilityZones == ["ap-northeast-2a", "ap-northeast-2c"]' \
  "$temp_dir/projection-a.json" "$temp_dir/projection-b.json" >/dev/null \
  || fail "comparison projection did not canonically sort reversed ALB availability zones"
for drift in lineage shape clearance outcome; do
  case "$drift" in
    lineage) jq '.dataset.manifestSha256="9999999999999999999999999999999999999999999999999999999999999999"' "$temp_dir/readiness-b.json" ;;
    shape) jq '.actual.rds.class="db.t3.small"' "$temp_dir/readiness-b.json" ;;
    clearance) jq '.networkClearance.projectionSha256="9999999999999999999999999999999999999999999999999999999999999999"' "$temp_dir/readiness-b.json" ;;
    outcome) jq '.smoke.search.passed=false' "$temp_dir/readiness-b.json" ;;
  esac > "$temp_dir/readiness-drift.json"
  jq -Sf "$comparison_projection_filter" "$temp_dir/readiness-drift.json" > "$temp_dir/projection-drift.json"
  ! cmp -s "$temp_dir/projection-a.json" "$temp_dir/projection-drift.json" \
    || fail "$drift drift was excluded from the comparison projection"
done

mkdir -p "$temp_dir/bin"
cp "$repo_root/infra/aws/lab/tests/fixtures/lab-contract.json" "$temp_dir/lab-contract.json"
cat > "$temp_dir/bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >> "${FAKE_CALL_LOG:?}"
case " $* " in
  *' sts get-caller-identity '*Account*) printf '%s\n' 942632789808 ;;
  *' sts get-caller-identity '*)
    role_name=airbob-lab-operator
    [[ "${AWS_LAB_OPERATOR_SCOPE:-direct}" != cutover ]] || role_name=airbob-lab-cutover-operator
    printf 'arn:aws:sts::942632789808:assumed-role/%s/test\n' "$role_name"
    ;;
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

if env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
  PATH="$temp_dir/bin:/usr/bin:/bin" AWS_REGION=ap-northeast-2 \
  FAKE_CALL_LOG="$temp_dir/aws.log" FAKE_TERRAFORM_LOG="$temp_dir/terraform.log" \
  FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" "$operator" status \
  >"$temp_dir/non-static-credentials.out" 2>"$temp_dir/non-static-credentials.err"; then
  fail "already-assumed Lab role accepted a refreshable credential source"
fi
grep -Fq 'active Lab role must use one explicit static STS environment credential tuple' \
  "$temp_dir/non-static-credentials.err" \
  || fail "missing static STS tuple did not fail with the credential-source invariant"

# Execute the full operator against hermetic fake CLIs/helpers. This verifies
# orchestration order and post-cutover rollback without touching AWS.
export AWS_LAB_OPERATOR_SCOPE=cutover
fixture_repo="$temp_dir/operator-repo"
fixture_scripts="$fixture_repo/infra/aws/scripts"
mkdir -p "$fixture_scripts" "$fixture_repo/infra/aws/lab/modules/security" \
  "$fixture_repo/.github/workflows" "$temp_dir/operator-bin" "$temp_dir/fake-s3"
export FAKE_S3_STORE="$temp_dir/fake-s3"
cp "$operator" "$fixture_scripts/aws-lab.sh"
cp "$repo_root/infra/aws/toolchain.env" "$fixture_repo/infra/aws/toolchain.env"
cp "$repo_root/Makefile" "$fixture_repo/Makefile"
cp "$workflow" "$fixture_repo/.github/workflows/aws-performance-lab.yml"
cp "$repo_root/infra/aws/scripts/cleanup-expired-lab.sh" "$fixture_scripts/cleanup-expired-lab.sh"
cp "$repo_root/infra/aws/scripts/readiness-comparison-projection.jq" "$fixture_scripts/readiness-comparison-projection.jq"
cp "$orphan_scanner" "$fixture_scripts/scan-lab-orphans.sh"
cp "$repo_root/infra/aws/lab/variables.tf" "$fixture_repo/infra/aws/lab/variables.tf"
cp "$repo_root/infra/aws/lab/security.tf" "$fixture_repo/infra/aws/lab/security.tf"
cp "$repo_root/infra/aws/lab/modules/security/main.tf" "$fixture_repo/infra/aws/lab/modules/security/main.tf"
cp "$repo_root/infra/aws/lab/modules/security/variables.tf" "$fixture_repo/infra/aws/lab/modules/security/variables.tf"

cat > "$fixture_scripts/orchestration-lease.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'lease %s\n' "$*" >> "${FAKE_OPERATOR_LOG:?}"
case "$1" in
  acquire) printf '%s\n' 'fencing_token=42' ;;
  heartbeat)
    [[ "${FAKE_HEARTBEAT_FAILURE:-false}" != true ]] || exit 70
    ;;
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
printf 'orphans %s scope=%s\n' "$*" "${AIRBOB_SCAN_SCOPE:-run}" >> "${FAKE_OPERATOR_LOG:?}"
[[ "${FAKE_ORPHAN_FAILURE:-false}" != true ]]
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
key=''
body=''
destination=''
requested_version=''
prefix=''
previous=''
for argument in "$@"; do
  case "$previous" in
    --key) key=$argument ;;
    --body) body=$argument ;;
    --version-id) requested_version=$argument ;;
    --prefix) prefix=$argument ;;
  esac
  case "$argument" in "${FAKE_OPERATOR_TEMP_PREFIX:?}"*) destination=$argument ;; esac
  previous=$argument
done
store_path="${FAKE_S3_STORE:?}/${key//\//__}"

state_phase() {
  local run=${FAKE_STATE_RUN_ID:-lab-partial-down}
  if [[ -f "$FAKE_S3_STORE/destroyed-$run" ]]; then
    printf '%s\n' empty
  elif [[ -f "$FAKE_S3_STORE/identity-only-$run" || "${FAKE_INITIAL_IDENTITY_ONLY:-false}" == true ]]; then
    printf '%s\n' identity
  else
    printf '%s\n' active
  fi
}

state_version_for_phase() {
  local phase=$1 active=${FAKE_STATE_VERSION_ID:-state-version-fixture}
  case "$phase" in
    active) printf '%s\n' "$active" ;;
    identity) printf '%s\n' "${FAKE_IDENTITY_STATE_VERSION_ID:-$active-identity}" ;;
    empty) printf '%s\n' "${FAKE_EMPTY_STATE_VERSION_ID:-$active-empty}" ;;
  esac
}

write_state_for_phase() {
  local phase=$1 destination_file=$2
  local run=${FAKE_STATE_RUN_ID:-lab-partial-down}
  local fence=${FAKE_STATE_FENCING_TOKEN:-41}
  local lineage=${FAKE_STATE_LINEAGE:-11111111-2222-3333-4444-555555555555}
  local active_serial=${FAKE_STATE_SERIAL:-10}
  local identity_serial=${FAKE_IDENTITY_STATE_SERIAL:-$((active_serial + 2))}
  local empty_serial=${FAKE_EMPTY_STATE_SERIAL:-$((identity_serial + 1))}
  if [[ "$phase" == active && -n "${FAKE_STATE_CONTENT:-}" ]]; then
    printf '%s\n' "$FAKE_STATE_CONTENT" > "$destination_file"
    return
  fi
  if [[ "$phase" == identity && -n "${FAKE_IDENTITY_STATE_CONTENT:-}" ]]; then
    printf '%s\n' "$FAKE_IDENTITY_STATE_CONTENT" > "$destination_file"
    return
  fi
  if [[ "$phase" == empty && -n "${FAKE_EMPTY_STATE_CONTENT:-}" ]]; then
    printf '%s\n' "$FAKE_EMPTY_STATE_CONTENT" > "$destination_file"
    return
  fi
  case "$phase" in
    active)
      jq -n --arg run "$run" --argjson fence "$fence" --arg lineage "$lineage" \
        --argjson serial "$active_serial" \
        '{version:4,terraform_version:"1.15.5",serial:$serial,lineage:$lineage,outputs:{},resources:[{mode:"data",type:"aws_caller_identity",name:"current",provider:"provider[\"registry.terraform.io/hashicorp/aws\"]",instances:[{schema_version:0,attributes:{account_id:"942632789808"},sensitive_attributes:[]}]},{module:"module.network",mode:"managed",type:"aws_vpc",name:"this",provider:"provider[\"registry.terraform.io/hashicorp/aws\"]",instances:[{schema_version:1,attributes:{id:"vpc-0123456789abcdef0"},sensitive_attributes:[]}]},{mode:"managed",type:"terraform_data",name:"run_identity",provider:"provider[\"terraform.io/builtin/terraform\"]",instances:[{schema_version:0,identity_schema_version:0,attributes:{id:"fixture-id",input:{value:{run_id:$run,resource_fencing_token:$fence},type:["object",{run_id:"string",resource_fencing_token:"number"}]},output:{value:{run_id:$run,resource_fencing_token:$fence},type:["object",{run_id:"string",resource_fencing_token:"number"}]},triggers_replace:null},sensitive_attributes:[]}] }]}' \
        > "$destination_file"
      ;;
    identity)
      jq -n --arg run "$run" --argjson fence "$fence" --arg lineage "$lineage" \
        --argjson serial "$identity_serial" \
        '{version:4,terraform_version:"1.15.5",serial:$serial,lineage:$lineage,outputs:{},resources:[{mode:"managed",type:"terraform_data",name:"run_identity",provider:"provider[\"terraform.io/builtin/terraform\"]",instances:[{schema_version:0,identity_schema_version:0,attributes:{id:"fixture-id",input:{value:{run_id:$run,resource_fencing_token:$fence},type:["object",{run_id:"string",resource_fencing_token:"number"}]},output:{value:{run_id:$run,resource_fencing_token:$fence},type:["object",{run_id:"string",resource_fencing_token:"number"}]},triggers_replace:null},sensitive_attributes:[]}] }]}' \
        > "$destination_file"
      ;;
    empty)
      jq -n --arg lineage "$lineage" --argjson serial "$empty_serial" \
        '{version:4,terraform_version:"1.15.5",serial:$serial,lineage:$lineage,outputs:{},resources:[]}' \
        > "$destination_file"
      ;;
  esac
}
case " $* " in
  *' sts get-caller-identity '*Account*) printf '%s\n' 942632789808 ;;
  *' sts get-caller-identity '*)
    role_name=${FAKE_ACTIVE_ROLE_NAME:-airbob-lab-operator}
    if [[ -z "${FAKE_ACTIVE_ROLE_NAME:-}" && "${AWS_LAB_OPERATOR_SCOPE:-direct}" == cutover ]]; then
      role_name=airbob-lab-cutover-operator
    fi
    printf 'arn:aws:sts::942632789808:assumed-role/%s/test\n' "$role_name"
    ;;
  *' ssm get-parameter '*'dns-contract'*) printf '%s\n' '{"schemaVersion":1,"zone_id":"Z0123456789EXAMPLE","zone_name":"airbob.cloud","api_fqdn":"api.airbob.cloud"}' ;;
  *' ssm get-parameter '*) cat "${FAKE_LAB_CONTRACT:?}" ;;
  *' s3api list-objects-v2 '*)
    if [[ "$prefix" == 'airbob/lab/terraform.tfstate.tflock' ]]; then
      if [[ -f "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock" ]]; then
        printf '%s\n' 'airbob/lab/terraform.tfstate.tflock'
      else
        printf '%s\n' None
      fi
    elif [[ "${FAKE_STATE_AFTER_FIRST_LOOKUP:-false}" == true ]]; then
      state_lookup_count=0
      [[ ! -f "${FAKE_STATE_LOOKUP_COUNTER:?}" ]] || state_lookup_count=$(cat "$FAKE_STATE_LOOKUP_COUNTER")
      state_lookup_count=$((state_lookup_count + 1))
      printf '%s\n' "$state_lookup_count" > "$FAKE_STATE_LOOKUP_COUNTER"
      if [[ "$state_lookup_count" -gt 1 ]]; then
        printf '%s\n' 'airbob/lab/terraform.tfstate'
      else
        printf '%s\n' None
      fi
    elif [[ "${FAKE_STATE_EXISTS:-false}" == true ]]; then
      printf '%s\n' 'airbob/lab/terraform.tfstate'
    else
      printf '%s\n' None
    fi
    ;;
  *' s3api head-object '*)
    if [[ "$key" == 'airbob/lab/terraform.tfstate.tflock' ]]; then
      lock_last_modified=${FAKE_TFLOCK_LAST_MODIFIED:-}
      [[ -n "$lock_last_modified" ]] \
        || lock_last_modified=$(jq -er '.Created' "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock")
      lock_version_id=lock-version-fixture
      if [[ -n "${FAKE_TFLOCK_HEAD_VERSION_COUNTER:-}" ]]; then
        head_count=0
        [[ ! -f "$FAKE_TFLOCK_HEAD_VERSION_COUNTER" ]] \
          || head_count=$(cat "$FAKE_TFLOCK_HEAD_VERSION_COUNTER")
        head_count=$((head_count + 1))
        printf '%s\n' "$head_count" > "$FAKE_TFLOCK_HEAD_VERSION_COUNTER"
        [[ "$head_count" -lt 2 ]] || lock_version_id=lock-version-aba
      fi
      jq -nc \
        --argjson contentLength "$(wc -c < "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock" | tr -d ' ')" \
        --arg lastModified "$lock_last_modified" --arg versionId "$lock_version_id" \
        '{contentLength:$contentLength,lastModified:$lastModified,versionId:$versionId}'
    elif [[ "$key" == measurements/*/teardown-terraform-lock-clock-*.json ]]; then
      jq -nc --arg lastModified "${FAKE_LOCK_RECOVERY_SERVER_TIME:-$(date -u '+%Y-%m-%dT%H:%M:%SZ')}" \
        '{lastModified:$lastModified,versionId:"clock-version-fixture"}'
    elif [[ "$*" == *"--query VersionId"* ]]; then
      printf '%s\n' version-fixture
    elif [[ "$key" == 'airbob/lab/terraform.tfstate' ]]; then
      printf '{"versionId":"%s","contentLength":64}\n' "$(state_version_for_phase "$(state_phase)")"
    elif [[ "$key" == network-clearance/* && "${FAKE_NETWORK_VERSION_EMPTY:-false}" == true ]]; then
      printf '%s\n' '{"versionId":"","lastModified":"2026-09-01T00:00:00Z"}'
    else
      printf '%s\n' '{"versionId":"version-fixture","lastModified":"2026-09-01T00:00:00Z"}'
    fi
    ;;
  *' s3api get-object '*)
    [[ -n "$destination" ]] || exit 1
    if [[ -n "${FAKE_IMMUTABLE_UNREADABLE_KEY:-}" \
      && "$key" == "$FAKE_IMMUTABLE_UNREADABLE_KEY" && -f "$store_path" ]]; then
      exit 70
    fi
    if [[ "$key" == 'airbob/lab/terraform.tfstate.tflock' ]]; then
      cp "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock" "$destination"
    elif [[ "$*" == *'runs/'*'/operator.json'* ]]; then
      if [[ -f "$store_path" ]]; then
        cp "$store_path" "$destination"
      else
        cat "${FAKE_RUN_MANIFEST:?}" > "$destination"
      fi
    elif [[ "$key" == data-bootstrap/* ]]; then
      receipt_run=${key#data-bootstrap/}
      receipt_run=${receipt_run%%/*}
      manifest_sha=$(shasum -a 256 "${FAKE_DATASET_MANIFEST:?}" | awk '{print $1}')
      jq -n --arg run "$receipt_run" --arg manifestSha "$manifest_sha" \
        --arg bootstrap "${FAKE_DATABASE_BOOTSTRAP:-dump}" \
        '{schemaVersion:2,runId:$run,datasetRelease:"fixture-v20",databaseBootstrap:$bootstrap,datasetManifestSha256:$manifestSha,rdsResourceId:"db-ABCDEFGHIJKL01234",rdsEngineVersion:"8.0.42",outboxState:"empty",redisState:"empty",connectorState:"RUNNING",searchState:"restored",verifiedAt:"2026-09-01T00:00:00Z"}' \
        > "$destination"
    elif [[ "$key" == network-clearance/* ]]; then
      clearance_run=${key#network-clearance/}
      clearance_run=${clearance_run%%/*}
      clearance_probe=${key##*/}
      clearance_probe=${clearance_probe%.json}
      clearance_state=terminated
      [[ "${FAKE_NETWORK_RECEIPT_INVALID:-false}" != true ]] || clearance_state=running
      jq -n --arg run "$clearance_run" --arg probe "$clearance_probe" --arg state "$clearance_state" \
        '{schemaVersion:1,runId:$run,vpcId:"vpc-0123456789abcdef0",probeInstanceId:$probe,instanceState:$state,clearedAt:"2026-09-01T00:00:00Z"}' \
        > "$destination"
    elif [[ "$key" == 'airbob/lab/terraform.tfstate' ]]; then
      requested_phase=$(state_phase)
      active_version=$(state_version_for_phase active)
      identity_version=$(state_version_for_phase identity)
      empty_version=$(state_version_for_phase empty)
      case "$requested_version" in
        "" ) ;;
        "$active_version") requested_phase=active ;;
        "$identity_version") requested_phase=identity ;;
        "$empty_version") requested_phase=empty ;;
        *) exit 1 ;;
      esac
      write_state_for_phase "$requested_phase" "$destination"
    elif [[ "$key" == measurements/* ]]; then
      [[ -f "$store_path" ]] || exit 1
      cp "$store_path" "$destination"
    elif [[ "$*" == *'/benchmark/dataset-manifest.json'* ]]; then
      cat "${FAKE_BENCHMARK_DATASET_MANIFEST:?}" > "$destination"
    elif [[ "$*" == *'/benchmark/manifest.json'* ]]; then
      cat "${FAKE_BENCHMARK_MANIFEST:?}" > "$destination"
    elif [[ "$*" == *'datasets/'*'/manifest.json'* ]]; then
      cat "${FAKE_DATASET_MANIFEST:?}" > "$destination"
    elif [[ "$key" == *'.manifest.json' ]]; then
      jq -n '{schemaVersion:1,commit:"cccccccccccccccccccccccccccccccccccccccc",archive:"airbob-service-bundles-cccccccccccccccccccccccccccccccccccccccc.tar.gz",sha256:"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}' > "$destination"
    elif [[ "$*" == *'.sha256'* ]]; then
      printf '%s  %s\n' 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' bundle.tar.gz > "$destination"
    else
      printf '%s\n' '{}' > "$destination"
    fi
    ;;
  *' s3api put-object '*)
    if [[ "${FAKE_TEARDOWN_START_PUT_FAILURE:-false}" == true && "$key" == measurements/*/teardown-start.json ]]; then
      exit 70
    fi
    if [[ "${FAKE_TEARDOWN_FINALIZE_PUT_FAILURE:-false}" == true && "$key" == measurements/*/teardown-finalize.json ]]; then
      exit 70
    fi
    if [[ "$*" == *"--if-none-match *"* && -f "$store_path" ]]; then
      exit 1
    fi
    cp "$body" "$store_path"
    ;;
  *' dynamodb get-item '*)
    lease_now=$(/bin/date +%s)
    lease_acquired=${FAKE_LEASE_ACQUIRED_AT:-$((lease_now - 60))}
    jq -nc --arg lock 'airbob-performance-lab' \
      --arg owner "${FAKE_LEASE_OWNER_OVERRIDE:-${LEASE_OWNER:-fixture/operator:1}}" \
      --arg token '42' --arg run "${RUN_ID:?}" --arg command "${FAKE_LEASE_COMMAND:-up}" \
      --arg acquired "$lease_acquired" --arg heartbeat "${FAKE_LEASE_HEARTBEAT_AT:-$lease_now}" \
      --arg expires "${FAKE_LEASE_EXPIRES_AT:-$((lease_now + 180))}" \
      --arg deadline "${FAKE_LEASE_DEADLINE_AT:-$((lease_now + 5400))}" \
      '{lockName:$lock,owner:$owner,fencingToken:$token,runId:$run,command:$command,acquiredAt:$acquired,heartbeatAt:$heartbeat,expiresAt:$expires,commandDeadline:$deadline}'
    ;;
  *' iam get-role '*)
    printf '%s\n' "${FAKE_LAB_ROLE_MAX_SESSION_SECONDS:-18000}"
    ;;
  *' ecr describe-images '*imageTag=*)
    if [[ "$*" == *'--repository-name airbob-repo'* ]]; then
      printf '%s\n' "${FAKE_APP_TAGGED_DIGEST:-sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}"
    else
      printf '%s\n' 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    fi
    ;;
  *' ecr describe-images '*) ;;
  *' route53 list-resource-record-sets '*)
    [[ " $* " == *' --start-record-name api.airbob.cloud '* ]] || exit 252
    [[ " $* " != *' --starting-record-name '* ]] || exit 252
    oci_verify_count=0
    if [[ -n "${FAKE_OCI_COUNTER_FILE:-}" ]]; then
      [[ ! -f "$FAKE_OCI_COUNTER_FILE" ]] || oci_verify_count=$(cat "$FAKE_OCI_COUNTER_FILE")
      oci_verify_count=$((oci_verify_count + 1))
      printf '%s\n' "$oci_verify_count" > "$FAKE_OCI_COUNTER_FILE"
    fi
    if [[ "${FAKE_OCI_DNS_INVALID:-false}" == true \
      || ( -n "${FAKE_OCI_FAIL_AT:-}" && "$oci_verify_count" == "$FAKE_OCI_FAIL_AT" ) ]]; then
      printf '%s\n' '{"ResourceRecordSets":[{"Name":"api.airbob.cloud.","Type":"A","SetIdentifier":"aws","Weight":100,"AliasTarget":{"DNSName":"example"}}]}'
    else
      case "${FAKE_OCI_RECORD_DRIFT:-none}" in
        none)
          printf '%s\n' '{"ResourceRecordSets":[{"Name":"api.airbob.cloud.","Type":"A","SetIdentifier":"oci","Weight":100,"TTL":60,"ResourceRecords":[{"Value":"203.0.113.10"}]}]}'
          ;;
        aaaa)
          printf '%s\n' '{"ResourceRecordSets":[{"Name":"api.airbob.cloud.","Type":"A","SetIdentifier":"oci","Weight":100,"TTL":60,"ResourceRecords":[{"Value":"203.0.113.10"}]},{"Name":"api.airbob.cloud.","Type":"AAAA","TTL":60,"ResourceRecords":[{"Value":"2001:db8::1"}]}]}'
          ;;
        cname)
          printf '%s\n' '{"ResourceRecordSets":[{"Name":"api.airbob.cloud.","Type":"CNAME","TTL":60,"ResourceRecords":[{"Value":"alternate.example.com."}]}]}'
          ;;
        alternate)
          printf '%s\n' '{"ResourceRecordSets":[{"Name":"api.airbob.cloud.","Type":"A","SetIdentifier":"oci","Weight":100,"TTL":60,"ResourceRecords":[{"Value":"203.0.113.10"}]},{"Name":"api.airbob.cloud.","Type":"A","SetIdentifier":"alternate","Weight":0,"TTL":60,"ResourceRecords":[{"Value":"8.8.8.8"}]}]}'
          ;;
        alias)
          printf '%s\n' '{"ResourceRecordSets":[{"Name":"api.airbob.cloud.","Type":"A","SetIdentifier":"oci","Weight":100,"AliasTarget":{"HostedZoneId":"ZEXAMPLE","DNSName":"example.elb.amazonaws.com.","EvaluateTargetHealth":false}}]}'
          ;;
        *) exit 70 ;;
      esac
    fi
    ;;
  *' ec2 describe-images '*) printf '%s\n' '{"imageId":"ami-0123456789abcdef0","creationDate":"2026-08-31T00:00:00Z","architecture":"x86_64","rootDeviceType":"ebs","virtualizationType":"hvm"}' ;;
  *' rds describe-db-instances '*'MasterUserSecret.SecretArn'*) printf '%s\n' 'arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-test' ;;
  *' rds describe-db-instances '*) printf '%s\n' '{"identifier":"airbob-fake","resourceId":"db-ABCDEFGHIJKL01234","class":"db.t3.micro","engine":"mysql","engineVersion":"8.0.42","allocatedStorageGiB":100,"storageType":"gp3","multiAz":false,"storageEncrypted":true,"availabilityZone":"ap-northeast-2a","parameterGroups":["airbob-fake"]}' ;;
  *' rds describe-db-parameter-groups '*) printf '%s\n' mysql8.0 ;;
  *' elbv2 describe-load-balancers '*)
    if [[ "${FAKE_ALB_SECURITY_GROUP_DRIFT:-false}" == true ]]; then
      printf '%s\n' '{"arn":"arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-fake/0123456789abcdef","dnsName":"airbob-fake.ap-northeast-2.elb.amazonaws.com","scheme":"internet-facing","type":"application","ipAddressType":"ipv4","availabilityZones":["ap-northeast-2a"],"securityGroups":["sg-0123456789abcdef0","sg-fedcba98765432100"]}'
    else
      printf '%s\n' '{"arn":"arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-fake/0123456789abcdef","dnsName":"airbob-fake.ap-northeast-2.elb.amazonaws.com","scheme":"internet-facing","type":"application","ipAddressType":"ipv4","availabilityZones":["ap-northeast-2a"],"securityGroups":["sg-0123456789abcdef0"]}'
    fi
    ;;
  *' ec2 describe-security-group-rules '*)
    case "${FAKE_ALB_INGRESS_DRIFT:-none}" in
      none)
        jq -nc --arg cidr "${FAKE_EXPECTED_ALB_INGRESS_CIDR:-0.0.0.0/0}" \
          '[{ruleId:"sgr-0123456789abcdef0",groupId:"sg-0123456789abcdef0",isEgress:false,ipProtocol:"tcp",fromPort:443,toPort:443,cidrIpv4:$cidr,cidrIpv6:null,prefixListId:null,referencedGroupId:null}]'
        ;;
      cidr)
        printf '%s\n' '[{"ruleId":"sgr-0123456789abcdef0","groupId":"sg-0123456789abcdef0","isEgress":false,"ipProtocol":"tcp","fromPort":443,"toPort":443,"cidrIpv4":"0.0.0.0/0","cidrIpv6":null,"prefixListId":null,"referencedGroupId":null}]'
        ;;
      ipv6)
        printf '%s\n' '[{"ruleId":"sgr-0123456789abcdef0","groupId":"sg-0123456789abcdef0","isEgress":false,"ipProtocol":"tcp","fromPort":443,"toPort":443,"cidrIpv4":null,"cidrIpv6":"::/0","prefixListId":null,"referencedGroupId":null}]'
        ;;
      extra)
        jq -nc --arg cidr "${FAKE_EXPECTED_ALB_INGRESS_CIDR:-0.0.0.0/0}" \
          '[{ruleId:"sgr-0123456789abcdef0",groupId:"sg-0123456789abcdef0",isEgress:false,ipProtocol:"tcp",fromPort:443,toPort:443,cidrIpv4:$cidr,cidrIpv6:null,prefixListId:null,referencedGroupId:null},{ruleId:"sgr-11111111111111111",groupId:"sg-0123456789abcdef0",isEgress:false,ipProtocol:"tcp",fromPort:80,toPort:80,cidrIpv4:$cidr,cidrIpv6:null,prefixListId:null,referencedGroupId:null}]'
        ;;
      *) exit 70 ;;
    esac
    ;;
  *' autoscaling describe-instance-refreshes '*) printf '%s\n' Successful ;;
  *' elbv2 describe-target-health '*'State!='*) printf '%s\n' 0 ;;
  *' elbv2 describe-target-health '*'State=='*) printf '%s\n' 1 ;;
  *' autoscaling describe-auto-scaling-groups '*'AutoScalingGroups[0].{name:'*)
    if [[ "${FAKE_ASG_CAPACITY_DRIFT:-false}" == true ]]; then
      printf '%s\n' '{"name":"airbob-fake-asg","min":1,"desired":1,"max":4}'
    else
      printf '%s\n' '{"name":"airbob-fake-asg","min":1,"desired":1,"max":1}'
    fi
    ;;
  *' autoscaling describe-auto-scaling-groups '*) printf '%s\n' "${FAKE_DESIRED_CAPACITY:-1}" ;;
  *) printf 'unexpected fake operator AWS call: %s\n' "$*" >&2; exit 1 ;;
esac
EOF
cat > "$temp_dir/operator-bin/terraform" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'terraform %s\n' "$*" >> "${FAKE_OPERATOR_LOG:?}"
fake_run=${FAKE_STATE_RUN_ID:-lab-partial-down}
identity_marker="${FAKE_S3_STORE:?}/identity-only-$fake_run"
destroyed_marker="$FAKE_S3_STORE/destroyed-$fake_run"
data_removed_marker="$FAKE_S3_STORE/data-removed-$fake_run"
data_retained_marker="$FAKE_S3_STORE/data-retained-$fake_run"
state_phase=active
[[ ! -f "$identity_marker" && "${FAKE_INITIAL_IDENTITY_ONLY:-false}" != true ]] || state_phase=identity
[[ ! -f "$destroyed_marker" ]] || state_phase=empty
if [[ "${FAKE_BLOCK_TERRAFORM_APPLY:-}" == run-identity && \
  " $* " == *' apply '*'/run-identity.tfplan '* ]]; then
  if [[ "${FAKE_CREATE_TFLOCK:-false}" == true ]]; then
    jq -nc --arg created "${FAKE_TFLOCK_CREATED:?}" \
      '{ID:"11111111-2222-3333-4444-555555555555",Operation:"OperationTypeApply",Info:"",Who:"runner@fake-host",Version:"1.15.5",Created:$created,Path:"airbob-performance-lab-tfstate-942632789808/airbob/lab/terraform.tfstate"}' \
      > "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock"
  fi
  (
    trap '' HUP INT TERM
    while :; do
      printf '%s\n' descendant-mutation >> "${FAKE_BLOCKING_MUTATION_LOG:?}"
      sleep 0.2
    done
  ) &
  blocking_descendant=$!
  printf 'blocking-leader=%s descendant=%s\n' "$$" "$blocking_descendant" \
    >> "${FAKE_BLOCKING_MUTATION_LOG:?}"
  trap 'wait "$blocking_descendant"' TERM
  wait "$blocking_descendant"
fi
for argument in "$@"; do
  case "$argument" in
    -var-file=*)
      tfvars=${argument#-var-file=}
      printf 'phase %s app=%s dns=%s cidr=%s\n' \
        "$(jq -r .deployment_phase "$tfvars")" "$(jq -r .app_enabled "$tfvars")" \
        "$(jq -r .dns_mode "$tfvars")" "$(jq -r .alb_ingress_cidr "$tfvars")" \
        >> "${FAKE_OPERATOR_LOG:?}"
      ;;
  esac
done
case " $* " in
  *' force-unlock -force '*)
    requested_id=${!#}
    lock_file="$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock"
    [[ -f "$lock_file" && "$(jq -er .ID "$lock_file")" == "$requested_id" ]] || exit 70
    rm -f "$lock_file"
    ;;
  *' state list '*)
    if [[ ( "${FAKE_STATE_EXISTS:-false}" == true || "${FAKE_STATE_AFTER_FIRST_LOOKUP:-false}" == true ) \
      && "$state_phase" != empty ]]; then
      [[ ( "$state_phase" != active && ! -f "$data_retained_marker" ) || -f "$data_removed_marker" ]] \
        || printf '%s\n' 'data.aws_caller_identity.current'
      printf '%s\n' 'terraform_data.run_identity'
      [[ "$state_phase" != active ]] || printf '%s\n' 'module.network.aws_vpc.this'
    fi
    ;;
  *' state rm '*)
    [[ "$state_phase" != empty ]] || exit 70
    state_rm_target=''
    for argument in "$@"; do
      case "$argument" in
        -chdir=*|state|rm|-lock-timeout=*) ;;
        data.aws_caller_identity.current|terraform_data.run_identity)
          [[ -z "$state_rm_target" ]] || { printf '%s\n' 'fake state-rm accepts one modeled target' >&2; exit 70; }
          state_rm_target=$argument
          ;;
        *) printf 'unsafe fake state-rm target: %s\n' "$argument" >&2; exit 70 ;;
      esac
    done
    case "$state_rm_target" in
      data.aws_caller_identity.current) touch "$data_removed_marker" ;;
      terraform_data.run_identity)
        [[ "${FAKE_IDENTITY_STATE_RM_FAILURE:-false}" != true ]] || exit 70
        touch "$destroyed_marker"
        [[ "${FAKE_IDENTITY_STATE_RM_RESPONSE_LOSS:-false}" != true ]] || exit 70
        ;;
      *) exit 70 ;;
    esac
    ;;
  *' apply '*'/run-identity.tfplan '*)
    if [[ "${FAKE_IDENTITY_APPLY_FAILURE:-false}" == true ]]; then
      touch "$identity_marker" "$data_removed_marker"
      exit 70
    fi
    ;;
  *' apply '*'/destroy-resources.tfplan '*)
    [[ "${FAKE_DESTROY_FAILURE:-false}" != true ]] || exit 70
    touch "$identity_marker" "$data_retained_marker"
    ;;
  *' show -json '*'/run-identity.tfplan '*)
    printf '%s\n' '{"resource_changes":[{"address":"terraform_data.run_identity","mode":"managed","type":"terraform_data","name":"run_identity","provider_name":"terraform.io/builtin/terraform","change":{"actions":["create"],"before":null,"after":{"input":{"run_id":"fixture","resource_fencing_token":42}},"after_unknown":{},"before_sensitive":false,"after_sensitive":{}}}]}'
    ;;
  *' show -json ')
    if [[ "$state_phase" == empty ]]; then
      printf '%s\n' '{"values":{"root_module":{"resources":[]}}}'
    elif [[ "$state_phase" == identity ]]; then
      jq -nc --arg run_id "${FAKE_STATE_RUN_ID:-lab-partial-down}" \
        --argjson resource_fencing_token "${FAKE_STATE_FENCING_TOKEN:-41}" \
        --argjson include_data "$( [[ ! -f "$data_retained_marker" || -f "$data_removed_marker" ]] && printf false || printf true )" '
          {values:{root_module:{resources:((if $include_data then [{address:"data.aws_caller_identity.current",mode:"data",type:"aws_caller_identity",name:"current",values:{account_id:"942632789808"}}] else [] end) +
            [{address:"terraform_data.run_identity",mode:"managed",type:"terraform_data",name:"run_identity",values:{output:{run_id:$run_id,resource_fencing_token:$resource_fencing_token}}}])}}}'
    else
      jq -nc --arg run_id "${FAKE_STATE_RUN_ID:-lab-partial-down}" \
        --argjson resource_fencing_token "${FAKE_STATE_FENCING_TOKEN:-41}" \
        --argjson include_data "$( [[ -f "$data_removed_marker" ]] && printf false || printf true )" '
          {values:{root_module:{
            resources:((if $include_data then [{address:"data.aws_caller_identity.current",mode:"data",type:"aws_caller_identity",name:"current",values:{account_id:"942632789808"}}] else [] end) +
              [{address:"terraform_data.run_identity",mode:"managed",type:"terraform_data",name:"run_identity",values:{output:{run_id:$run_id,resource_fencing_token:$resource_fencing_token}}}]),
            child_modules:[{address:"module.network",resources:[{address:"module.network.aws_vpc.this",mode:"managed",type:"aws_vpc",name:"this",values:{id:"vpc-0123456789abcdef0"}}]}]
          }}}'
    fi
    ;;
  *' show -json '*)
    if [[ "$*" == *'/destroy-resources.tfplan'* ]]; then
      if [[ "${FAKE_FIRST_PHASE_IDENTITY_DELETE:-false}" == true ]]; then
        printf '%s\n' '{"resource_changes":[{"address":"module.network.aws_vpc.this","mode":"managed","type":"aws_vpc","name":"this","change":{"actions":["delete"]}},{"address":"terraform_data.run_identity","mode":"managed","type":"terraform_data","name":"run_identity","change":{"actions":["delete"]}}]}'
      elif [[ "${FAKE_FIRST_PHASE_PERSISTENT_DELETE:-false}" == true ]]; then
        printf '%s\n' '{"resource_changes":[{"address":"module.network.aws_vpc.this","mode":"managed","type":"aws_vpc","name":"this","change":{"actions":["delete"],"before":{"tags":{"Persistence":"persistent"}}}}]}'
      else
        printf '%s\n' '{"resource_changes":[{"address":"module.network.aws_vpc.this","mode":"managed","type":"aws_vpc","name":"this","change":{"actions":["delete"]}}]}'
      fi
    elif [[ "$*" == *'/destroy-run-identity.tfplan'* ]]; then
      printf '%s\n' '{"resource_changes":[{"address":"terraform_data.run_identity","mode":"managed","type":"terraform_data","name":"run_identity","change":{"actions":["delete"]}}]}'
    elif [[ "${FAKE_PERSISTENT_DELETE:-false}" == true ]]; then
      printf '%s\n' '{"resource_changes":[{"change":{"actions":["delete"],"before":{"tags":{"Persistence":"persistent"}}}}]}'
    else
      printf '%s\n' '{"resource_changes":[]}'
    fi
    ;;
  *' output -json run_identity '*)
    [[ "${FAKE_RUN_IDENTITY_OUTPUT_MISSING:-false}" != true ]] || exit 1
    jq -nc --arg run_id "${FAKE_STATE_RUN_ID:-lab-partial-down}" \
      --argjson resource_fencing_token "${FAKE_STATE_FENCING_TOKEN:-41}" \
      '{run_id:$run_id,resource_fencing_token:$resource_fencing_token}'
    ;;
  *' output -json phase2_contract '*)
    [[ "${FAKE_PHASE2_OUTPUT_MISSING:-false}" != true ]] || exit 1
    jq -nc --arg run_id "${FAKE_STATE_RUN_ID:-lab-partial-down}" '{run_id:$run_id,vpc_id:"vpc-0123456789abcdef0",primary_private_route_table:"rtb-0123456789abcdef0",probe_instance_id:"i-0123456789abcdef0",services:{debezium:"i-11111111111111111",kafka:"i-22222222222222222"}}'
    ;;
  *' output -json phase3_contract '*)
    printf '%s\n' '{"rds_instance_id":"airbob-fake","rds_resource_id":"db-ABCDEFGHIJKL01234","rds_endpoint":"fake.abcdefghijkl.ap-northeast-2.rds.amazonaws.com"}'
    ;;
  *' output -json phase4_contract '*)
    if [[ "${FAKE_NO_ALB:-false}" == true ]]; then
      printf '%s\n' '{}'
    else
      printf '%s\n' '{"alb_arn":"arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-fake/0123456789abcdef","alb_dns_name":"airbob-fake.ap-northeast-2.elb.amazonaws.com","alb_security_group_id":"sg-0123456789abcdef0","target_group_arn":"arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:targetgroup/airbob-fake/0123456789abcdef","auto_scaling_group_name":"airbob-fake-asg","capacity":{"min":1,"desired":1,"max":1},"app_availability_zones":["ap-northeast-2a","ap-northeast-2c"]}'
    fi
    ;;
  *' output -json '*)
    printf '%s\n' '{"persistent_resource_contract":{"sensitive":false,"type":"object","value":{}},"run_identity":{"sensitive":false,"type":"object","value":{"run_id":"fixture","resource_fencing_token":42}},"state_boundaries":{"sensitive":false,"type":"object","value":{}},"phase2_contract":{"sensitive":false,"type":"object","value":{}},"phase3_contract":{"sensitive":false,"type":"object","value":{}},"phase4_contract":{"sensitive":false,"type":"object","value":{}}}'
    ;;
esac
EOF
cat > "$temp_dir/operator-bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'curl %s\n' "$*" >> "${FAKE_OPERATOR_LOG:?}"
if [[ "${FAKE_PUBLIC_SMOKE_FAILURE:-false}" == true && "$*" == *'/actuator/health'* && " $* " != *' --connect-to '* ]]; then
  exit 22
fi
if [[ "${FAKE_DIRECT_SMOKE_FAILURE:-false}" == true && "$*" == *'/actuator/health'* && " $* " == *' --connect-to '* ]]; then
  exit 22
fi
case "$*" in
  *'/actuator/health'*) printf '%s\n' '{"status":"UP"}' ;;
  *'/health'*) printf '%s\n' 'healthy' ;;
  *'/api/v1/accommodations/200001'*) printf '%s\n' '{"success":true,"data":{"id":200001}}' ;;
  *'/api/v1/search/accommodations'*)
    printf '%s\n' '{"success":true,"data":{"stay_search_result_listing":[{"id":101706}],"page_info":{"current_page":0,"total_elements":1,"total_pages":1}}}'
    ;;
  *) printf 'unexpected fake smoke request: %s\n' "$*" >&2; exit 22 ;;
esac
EOF
cat > "$temp_dir/operator-bin/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case " $* " in
  *' rev-parse HEAD '*) printf '%s\n' 'dddddddddddddddddddddddddddddddddddddddd' ;;
  *' status --porcelain --untracked-files=all '*) ;;
  *) printf 'unexpected fake git call: %s\n' "$*" >&2; exit 1 ;;
esac
EOF
cat > "$temp_dir/operator-bin/date" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ -z "${FAKE_TIME_STEP_SECONDS:-}" ]]; then
  exec /bin/date "$@"
fi
if [[ "$*" == '+%s' ]]; then
  count=0
  [[ ! -f "${FAKE_TIME_COUNTER:?}" ]] || count=$(cat "$FAKE_TIME_COUNTER")
  count=$((count + 1))
  printf '%s\n' "$count" > "$FAKE_TIME_COUNTER"
  printf '%s\n' "$((1900000000 + count * FAKE_TIME_STEP_SECONDS))"
elif [[ "$*" == '-u +%Y-%m-%dT%H:%M:%SZ' ]]; then
  printf '2030-03-17T17:%02d:00Z\n' "$((FAKE_TIME_STEP_SECONDS / 60))"
else
  exec /bin/date "$@"
fi
EOF
chmod 700 "$temp_dir/operator-bin/aws" "$temp_dir/operator-bin/terraform" \
  "$temp_dir/operator-bin/curl" "$temp_dir/operator-bin/git" "$temp_dir/operator-bin/date"

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
  local bootstrap=${FAKE_DATABASE_BOOTSTRAP:-dump}
  local ttl_hours=${FAKE_TTL_HOURS:-} fake_dns_mode=${FAKE_DNS_MODE:-cutover}
  local snapshot_source_run_id snapshot_source_resource_id expected_alb_ingress_cidr fake_operator_scope
  [[ -n "$ttl_hours" ]] || {
    ttl_hours=2
    [[ "$bootstrap" != dump ]] || ttl_hours=5
  }
  if [[ "$bootstrap" == snapshot ]]; then
    snapshot_source_run_id=${FAKE_RDS_SNAPSHOT_SOURCE_RUN_ID-lab-repeat-dump}
    snapshot_source_resource_id=${FAKE_RDS_SNAPSHOT_SOURCE_RESOURCE_ID-db-ABCDEFGHIJKLMNOPQRSTUVWX}
  else
    snapshot_source_run_id=${FAKE_RDS_SNAPSHOT_SOURCE_RUN_ID-}
    snapshot_source_resource_id=${FAKE_RDS_SNAPSHOT_SOURCE_RESOURCE_ID-}
  fi
  expected_alb_ingress_cidr=0.0.0.0/0
  [[ "$fake_dns_mode" != direct-only ]] \
    || expected_alb_ingress_cidr=${FAKE_ALB_INGRESS_CIDR:-}
  fake_operator_scope=${FAKE_AWS_LAB_OPERATOR_SCOPE:-}
  if [[ -z "$fake_operator_scope" ]]; then
    fake_operator_scope=direct
    [[ "$fake_dns_mode" != cutover ]] || fake_operator_scope=cutover
  fi
  env PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
    FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
    FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
    FAKE_RUN_MANIFEST="$temp_dir/run-manifest.json" \
    FAKE_DATASET_MANIFEST="${FAKE_DATASET_MANIFEST:-$temp_dir/dataset-manifest.json}" \
    FAKE_BENCHMARK_MANIFEST="${FAKE_BENCHMARK_MANIFEST:-$temp_dir/benchmark-manifest.json}" \
    FAKE_BENCHMARK_DATASET_MANIFEST="${FAKE_BENCHMARK_DATASET_MANIFEST:-$temp_dir/benchmark-dataset-manifest.json}" \
    FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
    FAKE_STATE_EXISTS="${FAKE_STATE_EXISTS:-false}" FAKE_STATE_RUN_ID="${FAKE_STATE_RUN_ID:-$1}" \
    FAKE_STATE_FENCING_TOKEN="${FAKE_STATE_FENCING_TOKEN:-42}" \
    FAKE_STATE_AFTER_FIRST_LOOKUP="${FAKE_STATE_AFTER_FIRST_LOOKUP:-false}" \
    FAKE_STATE_LOOKUP_COUNTER="${FAKE_STATE_LOOKUP_COUNTER:-$temp_dir/state-lookup-unused}" \
    FAKE_OCI_COUNTER_FILE="${FAKE_OCI_COUNTER_FILE:-}" FAKE_OCI_FAIL_AT="${FAKE_OCI_FAIL_AT:-}" \
    FAKE_OCI_RECORD_DRIFT="${FAKE_OCI_RECORD_DRIFT:-none}" \
    FAKE_DIRECT_SMOKE_FAILURE="${FAKE_DIRECT_SMOKE_FAILURE:-false}" \
    FAKE_HEARTBEAT_FAILURE="${FAKE_HEARTBEAT_FAILURE:-false}" \
    FAKE_BLOCK_TERRAFORM_APPLY="${FAKE_BLOCK_TERRAFORM_APPLY:-}" \
    FAKE_BLOCKING_MUTATION_LOG="${FAKE_BLOCKING_MUTATION_LOG:-$temp_dir/blocking-mutation-unused.log}" \
    FAKE_ALB_SECURITY_GROUP_DRIFT="${FAKE_ALB_SECURITY_GROUP_DRIFT:-false}" \
    FAKE_ALB_INGRESS_DRIFT="${FAKE_ALB_INGRESS_DRIFT:-none}" \
    FAKE_ASG_CAPACITY_DRIFT="${FAKE_ASG_CAPACITY_DRIFT:-false}" \
    FAKE_EXPECTED_ALB_INGRESS_CIDR="$expected_alb_ingress_cidr" \
    FAKE_ACTIVE_ROLE_NAME="${FAKE_ACTIVE_ROLE_NAME:-}" \
    FAKE_ORPHAN_FAILURE="${FAKE_ORPHAN_FAILURE:-false}" \
    FAKE_NETWORK_VERSION_EMPTY="${FAKE_NETWORK_VERSION_EMPTY:-false}" \
    FAKE_NETWORK_RECEIPT_INVALID="${FAKE_NETWORK_RECEIPT_INVALID:-false}" \
    FAKE_TEARDOWN_START_PUT_FAILURE="${FAKE_TEARDOWN_START_PUT_FAILURE:-false}" \
    FAKE_TEARDOWN_FINALIZE_PUT_FAILURE="${FAKE_TEARDOWN_FINALIZE_PUT_FAILURE:-false}" \
    FAKE_DESTROY_FAILURE="${FAKE_DESTROY_FAILURE:-false}" \
    FAKE_IDENTITY_STATE_RM_FAILURE="${FAKE_IDENTITY_STATE_RM_FAILURE:-false}" \
    FAKE_IDENTITY_STATE_RM_RESPONSE_LOSS="${FAKE_IDENTITY_STATE_RM_RESPONSE_LOSS:-false}" \
    FAKE_FIRST_PHASE_IDENTITY_DELETE="${FAKE_FIRST_PHASE_IDENTITY_DELETE:-false}" \
    FAKE_FIRST_PHASE_PERSISTENT_DELETE="${FAKE_FIRST_PHASE_PERSISTENT_DELETE:-false}" \
    FAKE_INITIAL_IDENTITY_ONLY="${FAKE_INITIAL_IDENTITY_ONLY:-false}" \
    FAKE_IDENTITY_APPLY_FAILURE="${FAKE_IDENTITY_APPLY_FAILURE:-false}" \
    FAKE_RUN_IDENTITY_OUTPUT_MISSING="${FAKE_RUN_IDENTITY_OUTPUT_MISSING:-false}" \
    FAKE_PHASE2_OUTPUT_MISSING="${FAKE_PHASE2_OUTPUT_MISSING:-false}" \
    FAKE_APP_TAGGED_DIGEST="${FAKE_APP_TAGGED_DIGEST:-}" \
    FAKE_DATABASE_BOOTSTRAP="${FAKE_DATABASE_BOOTSTRAP:-dump}" \
    FAKE_TIME_STEP_SECONDS="${FAKE_TIME_STEP_SECONDS:-}" \
    FAKE_TIME_COUNTER="${FAKE_TIME_COUNTER:-$temp_dir/time-counter-unused}" \
    MODE="$capacity_mode" POLICY="$measurement_policy" REQUEST_TARGET="$request_target" \
    DNS_MODE="$fake_dns_mode" ALB_INGRESS_CIDR="${FAKE_ALB_INGRESS_CIDR:-}" \
    IMAGE_DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    DATASET_RELEASE=fixture-v20 DATASET_MANIFEST_VERSION_ID="${FAKE_DATASET_VERSION-dataset-version-fixture}" \
    BUNDLE_COMMIT=cccccccccccccccccccccccccccccccccccccccc BUNDLE_MANIFEST_VERSION_ID="${FAKE_BUNDLE_VERSION-bundle-version-fixture}" \
    AMI_ID=ami-0123456789abcdef0 OCI_ORIGIN_IPV4="$oci_origin" \
    DATABASE_BOOTSTRAP="$bootstrap" \
    RDS_SNAPSHOT_IDENTIFIER="${FAKE_RDS_SNAPSHOT_IDENTIFIER:-}" \
    RDS_SNAPSHOT_SOURCE_RUN_ID="$snapshot_source_run_id" \
    RDS_SNAPSHOT_SOURCE_RESOURCE_ID="$snapshot_source_resource_id" \
    RDS_ENGINE_VERSION=8.0.42 LOAD_GENERATOR_ENABLED=false TTL_HOURS="$ttl_hours" \
    AWS_LAB_OPERATOR_SCOPE="$fake_operator_scope" \
    AIRBOB_OPERATOR_TEST_HARNESS="${AIRBOB_OPERATOR_TEST_HARNESS:-}" \
    AIRBOB_TEST_COMMAND_DEADLINE_SECONDS="${AIRBOB_TEST_COMMAND_DEADLINE_SECONDS:-}" \
    AIRBOB_TEST_HEARTBEAT_INTERVAL_SECONDS="${AIRBOB_TEST_HEARTBEAT_INTERVAL_SECONDS:-}" \
    AIRBOB_TEST_TERMINATION_GRACE_SECONDS="${AIRBOB_TEST_TERMINATION_GRACE_SECONDS:-}" \
    LEASE_OWNER=fixture/operator:1 FAKE_LEASE_COMMAND=up \
    FAKE_LEASE_ACQUIRED_AT="${FAKE_LEASE_ACQUIRED_AT:-}" \
    FAKE_LEASE_HEARTBEAT_AT="${FAKE_LEASE_HEARTBEAT_AT:-}" \
    FAKE_LEASE_EXPIRES_AT="${FAKE_LEASE_EXPIRES_AT:-}" \
    FAKE_LEASE_DEADLINE_AT="${FAKE_LEASE_DEADLINE_AT:-}" \
    FAKE_LEASE_OWNER_OVERRIDE="${FAKE_LEASE_OWNER_OVERRIDE:-}" \
    FAKE_LAB_ROLE_MAX_SESSION_SECONDS="${FAKE_LAB_ROLE_MAX_SESSION_SECONDS:-18000}" \
    FAKE_TFLOCK_LAST_MODIFIED="${FAKE_TFLOCK_LAST_MODIFIED:-}" \
    FAKE_LOCK_RECOVERY_SERVER_TIME="${FAKE_LOCK_RECOVERY_SERVER_TIME:-}" \
    FAKE_TFLOCK_HEAD_VERSION_COUNTER="${FAKE_TFLOCK_HEAD_VERSION_COUNTER:-}" \
    FAKE_CREATE_TFLOCK="${FAKE_CREATE_TFLOCK:-false}" \
    FAKE_TFLOCK_CREATED="${FAKE_TFLOCK_CREATED:-}" \
    RUN_ID="$1" FAKE_PUBLIC_SMOKE_FAILURE="${2:-false}" \
    "$fixture_scripts/aws-lab.sh" up
}

run_fake_down() {
  local selected_run=$1
  env PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
    FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
    FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
    FAKE_RUN_MANIFEST="${FAKE_RUN_MANIFEST_PATH:-$temp_dir/run-manifest.json}" \
    FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
    FAKE_STATE_EXISTS="${FAKE_STATE_EXISTS:-true}" \
    FAKE_STATE_RUN_ID="${FAKE_STATE_RUN_ID:-$selected_run}" \
    FAKE_STATE_FENCING_TOKEN="${FAKE_STATE_FENCING_TOKEN:-41}" \
    FAKE_STATE_VERSION_ID="${FAKE_STATE_VERSION_ID:-state-version-fixture}" \
    FAKE_IDENTITY_STATE_VERSION_ID="${FAKE_IDENTITY_STATE_VERSION_ID:-}" \
    FAKE_EMPTY_STATE_VERSION_ID="${FAKE_EMPTY_STATE_VERSION_ID:-}" \
    FAKE_STATE_CONTENT="${FAKE_STATE_CONTENT:-}" \
    FAKE_IDENTITY_STATE_CONTENT="${FAKE_IDENTITY_STATE_CONTENT:-}" \
    FAKE_EMPTY_STATE_CONTENT="${FAKE_EMPTY_STATE_CONTENT:-}" \
    FAKE_STATE_LINEAGE="${FAKE_STATE_LINEAGE:-11111111-2222-3333-4444-555555555555}" \
    FAKE_STATE_SERIAL="${FAKE_STATE_SERIAL:-10}" \
    FAKE_IDENTITY_STATE_SERIAL="${FAKE_IDENTITY_STATE_SERIAL:-12}" \
    FAKE_EMPTY_STATE_SERIAL="${FAKE_EMPTY_STATE_SERIAL:-13}" \
    FAKE_DESTROY_FAILURE="${FAKE_DESTROY_FAILURE:-false}" \
    FAKE_IDENTITY_STATE_RM_FAILURE="${FAKE_IDENTITY_STATE_RM_FAILURE:-false}" \
    FAKE_IDENTITY_STATE_RM_RESPONSE_LOSS="${FAKE_IDENTITY_STATE_RM_RESPONSE_LOSS:-false}" \
    FAKE_FIRST_PHASE_IDENTITY_DELETE="${FAKE_FIRST_PHASE_IDENTITY_DELETE:-false}" \
    FAKE_FIRST_PHASE_PERSISTENT_DELETE="${FAKE_FIRST_PHASE_PERSISTENT_DELETE:-false}" \
    FAKE_INITIAL_IDENTITY_ONLY="${FAKE_INITIAL_IDENTITY_ONLY:-false}" \
    FAKE_NO_ALB="${FAKE_NO_ALB:-true}" \
    FAKE_IMMUTABLE_UNREADABLE_KEY="${FAKE_IMMUTABLE_UNREADABLE_KEY:-}" \
    FAKE_TEARDOWN_FINALIZE_PUT_FAILURE="${FAKE_TEARDOWN_FINALIZE_PUT_FAILURE:-false}" \
    FAKE_RUN_IDENTITY_OUTPUT_MISSING="${FAKE_RUN_IDENTITY_OUTPUT_MISSING:-false}" \
    FAKE_PHASE2_OUTPUT_MISSING="${FAKE_PHASE2_OUTPUT_MISSING:-false}" \
    AWS_LAB_OPERATOR_SCOPE="${FAKE_AWS_LAB_OPERATOR_SCOPE:-cutover}" \
    FAKE_ACTIVE_ROLE_NAME="${FAKE_ACTIVE_ROLE_NAME:-}" \
    LEASE_OWNER=fixture/operator:1 FAKE_LEASE_COMMAND=down \
    FAKE_LEASE_ACQUIRED_AT="${FAKE_LEASE_ACQUIRED_AT:-}" \
    FAKE_LEASE_HEARTBEAT_AT="${FAKE_LEASE_HEARTBEAT_AT:-}" \
    FAKE_LEASE_EXPIRES_AT="${FAKE_LEASE_EXPIRES_AT:-}" \
    FAKE_LEASE_DEADLINE_AT="${FAKE_LEASE_DEADLINE_AT:-}" \
    FAKE_LEASE_OWNER_OVERRIDE="${FAKE_LEASE_OWNER_OVERRIDE:-}" \
    FAKE_LAB_ROLE_MAX_SESSION_SECONDS="${FAKE_LAB_ROLE_MAX_SESSION_SECONDS:-18000}" \
    FAKE_TFLOCK_LAST_MODIFIED="${FAKE_TFLOCK_LAST_MODIFIED:-}" \
    FAKE_LOCK_RECOVERY_SERVER_TIME="${FAKE_LOCK_RECOVERY_SERVER_TIME:-}" \
    FAKE_TFLOCK_HEAD_VERSION_COUNTER="${FAKE_TFLOCK_HEAD_VERSION_COUNTER:-}" \
    FORCE="${FAKE_FORCE:-false}" \
    RUN_ID="$selected_run" "$fixture_scripts/aws-lab.sh" down
}

epoch_to_utc() {
  local epoch=$1
  if date -u -r "$epoch" '+%Y-%m-%dT%H:%M:%SZ' >/dev/null 2>&1; then
    date -u -r "$epoch" '+%Y-%m-%dT%H:%M:%SZ'
  else
    date -u -d "@$epoch" '+%Y-%m-%dT%H:%M:%SZ'
  fi
}

write_fake_tflock() {
  local created=$1 path=${2:-airbob-performance-lab-tfstate-942632789808/airbob/lab/terraform.tfstate}
  jq -nc --arg created "$created" --arg path "$path" \
    '{ID:"11111111-2222-3333-4444-555555555555",Operation:"OperationTypeApply",Info:"",Who:"runner@fake-host",Version:"1.15.5",Created:$created,Path:$path}' \
    > "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock"
}

# A supervised Terraform apply creates the native lock, ignores TERM, and is
# KILLed with its process group. The same lease must preserve that new lock;
# only the next lease can recover it and continue forced teardown.
lock_now=$(/bin/date +%s)
prior_lock_created=$(epoch_to_utc "$((lock_now - 18400))")
: > "$temp_dir/stale-lock-state-lookups"
: > "$temp_dir/stale-lock-mutation.log"
: > "$temp_dir/operator-execution.log"
if AIRBOB_OPERATOR_TEST_HARNESS=hermetic-fake-v1 \
  AIRBOB_TEST_COMMAND_DEADLINE_SECONDS=2 AIRBOB_TEST_HEARTBEAT_INTERVAL_SECONDS=1 \
  AIRBOB_TEST_TERMINATION_GRACE_SECONDS=1 \
  FAKE_BLOCK_TERRAFORM_APPLY=run-identity \
  FAKE_BLOCKING_MUTATION_LOG="$temp_dir/stale-lock-mutation.log" \
  FAKE_CREATE_TFLOCK=true FAKE_TFLOCK_CREATED="$prior_lock_created" \
  FAKE_STATE_AFTER_FIRST_LOOKUP=true \
  FAKE_STATE_LOOKUP_COUNTER="$temp_dir/stale-lock-state-lookups" \
  FAKE_LEASE_ACQUIRED_AT="$((lock_now - 18500))" \
  FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
  run_fake_up lab-stale-lock >"$temp_dir/stale-lock-up.out" 2>"$temp_dir/stale-lock-up.err"; then
  fail "SIGKILL stale-lock fixture unexpectedly completed"
fi
[[ -f "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock" ]] \
  || fail "SIGKILL stale-lock fixture did not preserve Terraform's native lock"
if grep -Fq 'force-unlock' "$temp_dir/operator-execution.log"; then
  fail "same orchestration lease force-unlocked its own new Terraform lock"
fi
stale_lock_lines=$(awk 'END {print NR}' "$temp_dir/stale-lock-mutation.log")
sleep 1
[[ "$(awk 'END {print NR}' "$temp_dir/stale-lock-mutation.log")" == "$stale_lock_lines" ]] \
  || fail "SIGKILL stale-lock fixture left a mutating descendant alive"
stale_manifest_store="$FAKE_S3_STORE/runs__lab-stale-lock__operator.json"
jq '.expiresAt=1700000000' "$stale_manifest_store" > "$temp_dir/stale-lock-expired.json"
mv "$temp_dir/stale-lock-expired.json" "$stale_manifest_store"
: > "$temp_dir/operator-execution.log"
prior_lock_s3_time="${prior_lock_created%Z}.123456+00:00"
recovery_server_time="$(epoch_to_utc "$lock_now")"
recovery_server_time="${recovery_server_time%Z}.654321+00:00"
FAKE_STATE_RUN_ID=lab-stale-lock FAKE_STATE_FENCING_TOKEN=42 \
  FAKE_STATE_VERSION_ID=state-version-stale-lock FAKE_LEASE_ACQUIRED_AT="$lock_now" \
  FAKE_TFLOCK_LAST_MODIFIED="$prior_lock_s3_time" \
  FAKE_LOCK_RECOVERY_SERVER_TIME="$recovery_server_time" \
  FAKE_FORCE=true run_fake_down lab-stale-lock >/dev/null
[[ ! -f "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock" ]] \
  || fail "prior Terraform native lock remained after validated force-unlock"
grep -Fq 'terraform -chdir=' "$temp_dir/operator-execution.log" \
  || fail "prior Terraform lock recovery never invoked Terraform"
grep -Fq 'force-unlock -force 11111111-2222-3333-4444-555555555555' \
  "$temp_dir/operator-execution.log" \
  || fail "prior Terraform lock recovery did not bind the exact lock ID"
for role_name in airbob-lab-operator airbob-lab-cutover-operator; do
  grep -Fq "iam get-role --role-name $role_name" "$temp_dir/operator-execution.log" \
    || fail "prior Terraform lock recovery did not verify $role_name MaxSessionDuration"
done
role_boundary_line=$(grep -n -m1 'iam get-role --role-name airbob-lab-cutover-operator' \
  "$temp_dir/operator-execution.log" | cut -d: -f1)
force_unlock_line=$(grep -n -m1 'force-unlock -force 11111111-2222-3333-4444-555555555555' \
  "$temp_dir/operator-execution.log" | cut -d: -f1)
[[ "$role_boundary_line" -lt "$force_unlock_line" ]] \
  || fail "Terraform force-unlock ran before both role expiry boundaries were verified"
grep -Eq 'apply .*destroy-resources.tfplan' "$temp_dir/operator-execution.log" \
  || fail "prior Terraform lock recovery did not continue through teardown"
if grep -Eq '^aws s3api delete-object .*tflock' "$temp_dir/operator-execution.log"; then
  fail "prior Terraform lock recovery directly deleted the S3 lock"
fi

# Even when it predates the new lease, a lock younger than the maximum static
# STS session plus clock margin is preserved because the old child could still
# hold usable inherited credentials.
jq '.runId="lab-lock-young"' "$temp_dir/run-manifest.json" \
  > "$temp_dir/run-manifest.young-lock.json"
write_fake_tflock "$(epoch_to_utc "$((lock_now - 120))")"
young_lock_sha=$(sha256_file "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock")
: > "$temp_dir/operator-execution.log"
if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.young-lock.json" \
  FAKE_STATE_RUN_ID=lab-lock-young FAKE_LEASE_ACQUIRED_AT="$((lock_now - 60))" \
  run_fake_down lab-lock-young >"$temp_dir/young-lock.out" 2>"$temp_dir/young-lock.err"; then
  fail "young prior Terraform lock passed the static STS credential-expiry barrier"
fi
grep -Fq 'younger than the AWS-authoritative static STS credential-expiry barrier' "$temp_dir/young-lock.err" \
  || fail "young prior Terraform lock did not report the credential-expiry barrier"
[[ "$(sha256_file "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock")" == "$young_lock_sha" ]] \
  || fail "young prior Terraform lock bytes changed after rejection"
for role_name in airbob-lab-operator airbob-lab-cutover-operator; do
  grep -Fq "iam get-role --role-name $role_name" "$temp_dir/operator-execution.log" \
    || fail "young prior lock did not verify $role_name MaxSessionDuration"
done
if grep -Eq 'force-unlock|apply .*destroy-resources.tfplan' "$temp_dir/operator-execution.log"; then
  fail "young prior Terraform lock reached force-unlock or teardown"
fi
rm -f "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock"

# A severely lagging prior-host clock cannot age a fresh S3 lock. The server
# LastModified and server-stamped recovery receipt remain the only age basis.
jq '.runId="lab-lock-clock-skew"' "$temp_dir/run-manifest.json" \
  > "$temp_dir/run-manifest.clock-skew.json"
write_fake_tflock "$(epoch_to_utc "$((lock_now - 18400))")"
clock_skew_lock_sha=$(sha256_file "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock")
: > "$temp_dir/operator-execution.log"
if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.clock-skew.json" \
  FAKE_STATE_RUN_ID=lab-lock-clock-skew FAKE_LEASE_ACQUIRED_AT="$((lock_now - 60))" \
  FAKE_TFLOCK_LAST_MODIFIED="$(epoch_to_utc "$((lock_now - 120))")" \
  FAKE_LOCK_RECOVERY_SERVER_TIME="$(epoch_to_utc "$lock_now")" \
  run_fake_down lab-lock-clock-skew \
  >"$temp_dir/clock-skew-lock.out" 2>"$temp_dir/clock-skew-lock.err"; then
  fail "prior-host clock lag bypassed the AWS-authoritative lock-age barrier"
fi
grep -Fq 'younger than the AWS-authoritative static STS credential-expiry barrier' \
  "$temp_dir/clock-skew-lock.err" \
  || fail "clock-skewed prior lock did not report the AWS-authoritative barrier"
[[ "$(sha256_file "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock")" == "$clock_skew_lock_sha" ]] \
  || fail "clock-skewed prior lock bytes changed after rejection"
if grep -Eq 'force-unlock|apply .*destroy-resources.tfplan' "$temp_dir/operator-execution.log"; then
  fail "clock-skewed prior Terraform lock reached force-unlock or teardown"
fi
rm -f "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock"

# A role whose live maximum no longer matches the closed five-hour contract
# blocks recovery before force-unlock, even when the S3 age itself is old.
jq '.runId="lab-lock-role-drift"' "$temp_dir/run-manifest.json" \
  > "$temp_dir/run-manifest.role-drift-lock.json"
write_fake_tflock "$(epoch_to_utc "$((lock_now - 18400))")"
role_drift_lock_sha=$(sha256_file "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock")
: > "$temp_dir/operator-execution.log"
if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.role-drift-lock.json" \
  FAKE_STATE_RUN_ID=lab-lock-role-drift FAKE_LEASE_ACQUIRED_AT="$((lock_now - 30))" \
  FAKE_LAB_ROLE_MAX_SESSION_SECONDS=43200 \
  run_fake_down lab-lock-role-drift \
  >"$temp_dir/role-drift-lock.out" 2>"$temp_dir/role-drift-lock.err"; then
  fail "drifted Lab role MaxSessionDuration passed Terraform lock recovery"
fi
grep -Fq 'Lab role MaxSessionDuration differs from the Terraform lock recovery boundary' \
  "$temp_dir/role-drift-lock.err" \
  || fail "role-duration drift did not report the credential-expiry boundary"
[[ "$(sha256_file "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock")" == "$role_drift_lock_sha" ]] \
  || fail "role-duration drift changed the preserved Terraform lock"
if grep -Eq 'force-unlock|apply .*destroy-resources.tfplan' "$temp_dir/operator-execution.log"; then
  fail "role-duration drift reached force-unlock or teardown"
fi
rm -f "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock"

# Byte-identical ABA replacement under a different S3 VersionId is rejected at
# the final identity read even though LockInfo content remains unchanged.
jq '.runId="lab-lock-aba"' "$temp_dir/run-manifest.json" \
  > "$temp_dir/run-manifest.aba-lock.json"
write_fake_tflock "$(epoch_to_utc "$((lock_now - 18400))")"
: > "$temp_dir/aba-lock-head-count"
: > "$temp_dir/operator-execution.log"
if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.aba-lock.json" \
  FAKE_STATE_RUN_ID=lab-lock-aba FAKE_LEASE_ACQUIRED_AT="$((lock_now - 30))" \
  FAKE_LOCK_RECOVERY_SERVER_TIME="$(epoch_to_utc "$lock_now")" \
  FAKE_TFLOCK_HEAD_VERSION_COUNTER="$temp_dir/aba-lock-head-count" \
  run_fake_down lab-lock-aba >"$temp_dir/aba-lock.out" 2>"$temp_dir/aba-lock.err"; then
  fail "byte-identical Terraform lock ABA replacement passed final S3 identity validation"
fi
grep -Fq 'Terraform native lock S3 identity changed during fenced recovery' \
  "$temp_dir/aba-lock.err" \
  || fail "Terraform lock ABA replacement did not report S3 identity drift"
if grep -Eq 'force-unlock|apply .*destroy-resources.tfplan' "$temp_dir/operator-execution.log"; then
  fail "Terraform lock ABA replacement reached force-unlock or teardown"
fi
rm -f "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock"

# A current/new lock cannot be stolen by this lease. The lock bytes and Lab
# state remain untouched before any destroy mutation.
for freshness in current new; do
  reject_run="lab-lock-$freshness"
  jq --arg run "$reject_run" '.runId=$run' "$temp_dir/run-manifest.json" \
    > "$temp_dir/run-manifest.$freshness-lock.json"
  created_epoch=$lock_now
  [[ "$freshness" != new ]] || created_epoch=$((lock_now + 1))
  write_fake_tflock "$(epoch_to_utc "$created_epoch")"
  rejected_lock_sha=$(sha256_file "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock")
  : > "$temp_dir/operator-execution.log"
  if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.$freshness-lock.json" \
    FAKE_STATE_RUN_ID="$reject_run" FAKE_LEASE_ACQUIRED_AT="$lock_now" \
    run_fake_down "$reject_run" >"$temp_dir/$freshness-lock.out" 2>"$temp_dir/$freshness-lock.err"; then
    fail "$freshness Terraform lock was force-unlocked by its current lease"
  fi
  grep -Fq 'created at or after the current orchestration lease' "$temp_dir/$freshness-lock.err" \
    || fail "$freshness Terraform lock rejection did not report its fencing reason"
  [[ "$(sha256_file "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock")" == "$rejected_lock_sha" ]] \
    || fail "$freshness Terraform lock bytes changed after rejection"
  if grep -Eq 'force-unlock|apply .*destroy-resources.tfplan' "$temp_dir/operator-execution.log"; then
    fail "$freshness Terraform lock rejection reached a teardown mutation"
  fi
done

# Malformed and wrong-backend LockInfo are fail-closed and preserved.
for invalid_lock in malformed wrong-path; do
  reject_run="lab-lock-$invalid_lock"
  jq --arg run "$reject_run" '.runId=$run' "$temp_dir/run-manifest.json" \
    > "$temp_dir/run-manifest.$invalid_lock-lock.json"
  if [[ "$invalid_lock" == malformed ]]; then
    printf '%s\n' '{"ID":"not-a-uuid","password":"must-not-log"}' \
      > "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock"
  else
    write_fake_tflock "$prior_lock_created" \
      'airbob-performance-lab-tfstate-942632789808/airbob/dns/terraform.tfstate'
  fi
  rejected_lock_sha=$(sha256_file "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock")
  : > "$temp_dir/operator-execution.log"
  if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.$invalid_lock-lock.json" \
    FAKE_STATE_RUN_ID="$reject_run" FAKE_LEASE_ACQUIRED_AT="$((lock_now - 60))" \
    run_fake_down "$reject_run" >"$temp_dir/$invalid_lock-lock.out" \
      2>"$temp_dir/$invalid_lock-lock.err"; then
    fail "$invalid_lock Terraform native lock passed the closed LockInfo gate"
  fi
  [[ "$(sha256_file "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock")" == "$rejected_lock_sha" ]] \
    || fail "$invalid_lock Terraform lock bytes changed after rejection"
  if grep -Eq 'force-unlock|apply .*destroy-resources.tfplan' "$temp_dir/operator-execution.log"; then
    fail "$invalid_lock Terraform lock rejection reached a teardown mutation"
  fi
done
rm -f "$FAKE_S3_STORE/airbob__lab__terraform.tfstate.tflock"

# Exercise the shared create-only receipt helper itself. A byte-identical
# conflict is an idempotent success; drift or an unreadable incumbent is a
# hard failure and the incumbent bytes remain untouched.
immutable_harness="$temp_dir/publish-immutable-harness.sh"
{
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
    'fail() { printf '\''%s\\n'\'' "$1" >&2; exit 1; }' \
    'AWS_REGION=ap-northeast-2' 'evidence_bucket=fixture-evidence' \
    'temp_dir=${IMMUTABLE_TEMP_DIR:?}'
  sed -n '/^sha256_text() {$/,/^}$/p' "$operator"
  sed -n '/^publish_immutable_json() {$/,/^}$/p' "$operator"
  printf '%s\n' 'publish_immutable_json "$IMMUTABLE_KEY" "$IMMUTABLE_SOURCE"'
} > "$immutable_harness"
chmod 700 "$immutable_harness"
mkdir -p "$temp_dir/immutable-temp"
printf '%s\n' '{"schemaVersion":1,"status":"ready"}' > "$temp_dir/immutable-a.json"
printf '%s\n' '{"schemaVersion":1,"status":"drifted"}' > "$temp_dir/immutable-b.json"
immutable_key=measurements/create-only-fixture.json
immutable_store="$FAKE_S3_STORE/measurements__create-only-fixture.json"
for attempt in 1 2; do
  env PATH="$temp_dir/operator-bin:$PATH" \
    FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
    FAKE_OPERATOR_TEMP_PREFIX="$temp_dir/immutable-temp/" \
    IMMUTABLE_TEMP_DIR="$temp_dir/immutable-temp" IMMUTABLE_KEY="$immutable_key" \
    IMMUTABLE_SOURCE="$temp_dir/immutable-a.json" "$immutable_harness"
done
cmp -s "$temp_dir/immutable-a.json" "$immutable_store" \
  || fail "byte-identical immutable publication did not preserve the incumbent receipt"
if env PATH="$temp_dir/operator-bin:$PATH" \
  FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
  FAKE_OPERATOR_TEMP_PREFIX="$temp_dir/immutable-temp/" \
  IMMUTABLE_TEMP_DIR="$temp_dir/immutable-temp" IMMUTABLE_KEY="$immutable_key" \
  IMMUTABLE_SOURCE="$temp_dir/immutable-b.json" "$immutable_harness" >/dev/null 2>&1; then
  fail "create-only immutable publication accepted drifted bytes"
fi
cmp -s "$temp_dir/immutable-a.json" "$immutable_store" \
  || fail "drifted immutable publication changed the incumbent receipt"
if env PATH="$temp_dir/operator-bin:$PATH" \
  FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
  FAKE_OPERATOR_TEMP_PREFIX="$temp_dir/immutable-temp/" \
  FAKE_IMMUTABLE_UNREADABLE_KEY="$immutable_key" \
  IMMUTABLE_TEMP_DIR="$temp_dir/immutable-temp" IMMUTABLE_KEY="$immutable_key" \
  IMMUTABLE_SOURCE="$temp_dir/immutable-a.json" "$immutable_harness" >/dev/null 2>&1; then
  fail "create-only immutable publication accepted an unreadable incumbent"
fi
cmp -s "$temp_dir/immutable-a.json" "$immutable_store" \
  || fail "unreadable immutable publication changed the incumbent receipt"

: > "$temp_dir/operator-execution.log"
if run_fake_up lab-invalid-ip false 999.0.0.1 >/dev/null 2>&1; then
  fail "operator accepted a non-canonical OCI IPv4 address"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "invalid OCI input acquired the orchestration lease"
fi

: > "$temp_dir/operator-execution.log"
if FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=198.51.100.25/32 \
  run_fake_up lab-nonpublic-ingress >/dev/null 2>&1; then
  fail "direct-only accepted a non-public documentation CIDR"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "non-public direct-only CIDR acquired the orchestration lease"
fi

: > "$temp_dir/operator-execution.log"
if FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=192.88.99.1/32 \
  run_fake_up lab-relay-anycast-ingress >/dev/null 2>&1; then
  fail "operator accepted the reserved 192.88.99.0/24 relay anycast range"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "reserved relay-anycast CIDR acquired the orchestration lease"
fi

: > "$temp_dir/operator-execution.log"
if FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
  FAKE_AWS_LAB_OPERATOR_SCOPE=cutover run_fake_up lab-direct-cutover-scope >/dev/null 2>&1; then
  fail "direct-only up accepted cutover-scoped credentials"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "direct-only/cutover-scope mismatch acquired the orchestration lease"
fi

: > "$temp_dir/operator-execution.log"
if FAKE_DNS_MODE=cutover FAKE_AWS_LAB_OPERATOR_SCOPE=direct \
  run_fake_up lab-cutover-direct-scope >/dev/null 2>&1; then
  fail "cutover up accepted direct-scoped credentials"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "cutover/direct-scope mismatch acquired the orchestration lease"
fi

: > "$temp_dir/operator-execution.log"
if FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
  FAKE_AWS_LAB_OPERATOR_SCOPE=direct FAKE_ACTIVE_ROLE_NAME=airbob-lab-cutover-operator \
  run_fake_up lab-wrong-active-role >/dev/null 2>&1; then
  fail "operator accepted active credentials from the wrong Lab role"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "wrong active Lab role reached lease acquisition"
fi

: > "$temp_dir/operator-execution.log"
if FAKE_TTL_HOURS=4 run_fake_up lab-short-dump-ttl >/dev/null 2>&1; then
  fail "dump up accepted a TTL shorter than its five-hour safety window"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "short dump TTL acquired the orchestration lease"
fi

for dump_snapshot_input in identifier source-run source-resource; do
  : > "$temp_dir/operator-execution.log"
  case "$dump_snapshot_input" in
    identifier)
      FAKE_RDS_SNAPSHOT_IDENTIFIER=airbob-dataset-fixture \
        run_fake_up "lab-dump-$dump_snapshot_input" >/dev/null 2>&1 && accepted=true || accepted=false
      ;;
    source-run)
      FAKE_RDS_SNAPSHOT_SOURCE_RUN_ID=lab-repeat-dump \
        run_fake_up "lab-dump-$dump_snapshot_input" >/dev/null 2>&1 && accepted=true || accepted=false
      ;;
    source-resource)
      FAKE_RDS_SNAPSHOT_SOURCE_RESOURCE_ID=db-ABCDEFGHIJKLMNOPQRSTUVWX \
        run_fake_up "lab-dump-$dump_snapshot_input" >/dev/null 2>&1 && accepted=true || accepted=false
      ;;
  esac
  [[ "$accepted" == false ]] || fail "dump up accepted snapshot input: $dump_snapshot_input"
  if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
    fail "dump snapshot-input rejection acquired the orchestration lease: $dump_snapshot_input"
  fi
done

: > "$temp_dir/operator-execution.log"
if FAKE_DATABASE_BOOTSTRAP=snapshot FAKE_TTL_HOURS=3 \
  FAKE_RDS_SNAPSHOT_IDENTIFIER=airbob-dataset-fixture \
  run_fake_up lab-long-snapshot-ttl >/dev/null 2>&1; then
  fail "snapshot up accepted a TTL other than its explicit two-hour window"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "invalid snapshot TTL acquired the orchestration lease"
fi

for missing_snapshot_input in identifier source-run source-resource; do
  : > "$temp_dir/operator-execution.log"
  case "$missing_snapshot_input" in
    identifier)
      FAKE_DATABASE_BOOTSTRAP=snapshot FAKE_RDS_SNAPSHOT_IDENTIFIER= \
        run_fake_up "lab-snap-no-$missing_snapshot_input" >/dev/null 2>&1 && accepted=true || accepted=false
      ;;
    source-run)
      FAKE_DATABASE_BOOTSTRAP=snapshot FAKE_RDS_SNAPSHOT_IDENTIFIER=airbob-dataset-fixture \
        FAKE_RDS_SNAPSHOT_SOURCE_RUN_ID= \
        run_fake_up "lab-snap-no-$missing_snapshot_input" >/dev/null 2>&1 && accepted=true || accepted=false
      ;;
    source-resource)
      FAKE_DATABASE_BOOTSTRAP=snapshot FAKE_RDS_SNAPSHOT_IDENTIFIER=airbob-dataset-fixture \
        FAKE_RDS_SNAPSHOT_SOURCE_RESOURCE_ID= \
        run_fake_up "lab-snap-no-$missing_snapshot_input" >/dev/null 2>&1 && accepted=true || accepted=false
      ;;
  esac
  [[ "$accepted" == false ]] || fail "snapshot up accepted missing source input: $missing_snapshot_input"
  if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
    fail "missing snapshot source acquired the orchestration lease: $missing_snapshot_input"
  fi
done

for invalid_snapshot_input in identifier source-run source-resource; do
  : > "$temp_dir/operator-execution.log"
  invalid_identifier=airbob-snapshot-fixture
  invalid_source_run=lab-Invalid
  invalid_source_resource=db-ABCDEFGHIJKLMNOPQRSTUVW
  FAKE_DATABASE_BOOTSTRAP=snapshot \
    FAKE_RDS_SNAPSHOT_IDENTIFIER="$([[ "$invalid_snapshot_input" == identifier ]] && printf '%s' "$invalid_identifier" || printf '%s' airbob-dataset-fixture)" \
    FAKE_RDS_SNAPSHOT_SOURCE_RUN_ID="$([[ "$invalid_snapshot_input" == source-run ]] && printf '%s' "$invalid_source_run" || printf '%s' lab-repeat-dump)" \
    FAKE_RDS_SNAPSHOT_SOURCE_RESOURCE_ID="$([[ "$invalid_snapshot_input" == source-resource ]] && printf '%s' "$invalid_source_resource" || printf '%s' db-ABCDEFGHIJKLMNOPQRSTUVWX)" \
    run_fake_up "lab-snap-bad-$invalid_snapshot_input" >/dev/null 2>&1 && accepted=true || accepted=false
  [[ "$accepted" == false ]] || fail "snapshot up accepted non-canonical input: $invalid_snapshot_input"
  if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
    fail "non-canonical snapshot source acquired the orchestration lease: $invalid_snapshot_input"
  fi
done

: > "$temp_dir/operator-execution.log"
if FAKE_DATASET_VERSION= run_fake_up lab-missing-dataset-version >/dev/null 2>&1; then
  fail "operator accepted an absent audited dataset manifest VersionId"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "absent dataset VersionId acquired the orchestration lease"
fi

: > "$temp_dir/operator-execution.log"
if FAKE_BUNDLE_VERSION= run_fake_up lab-missing-bundle-version >/dev/null 2>&1; then
  fail "operator accepted an absent audited bundle manifest VersionId"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "absent bundle VersionId acquired the orchestration lease"
fi

: > "$temp_dir/operator-execution.log"
if FAKE_APP_TAGGED_DIGEST=sha256:9999999999999999999999999999999999999999999999999999999999999999 \
  run_fake_up lab-app-tag-drift >/dev/null 2>&1; then
  fail "operator accepted an app digest that differs from the runtime commit tag"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "app runtime-tag drift acquired the orchestration lease"
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

for oci_record_drift in aaaa cname alternate alias; do
  : > "$temp_dir/operator-execution.log"
  if FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
    FAKE_OCI_RECORD_DRIFT="$oci_record_drift" \
    run_fake_up "lab-oci-$oci_record_drift" >/dev/null 2>&1; then
    fail "OCI authority accepted exact-name Route 53 drift: $oci_record_drift"
  fi
  if grep -Eq '^terraform .* (plan|apply)|^network |^dns ' "$temp_dir/operator-execution.log"; then
    fail "OCI exact-name drift reached an infrastructure mutation: $oci_record_drift"
  fi
done

run_blocking_guard_case() {
  local guard_case=$1 guard_run=$2 deadline=20 heartbeat_failure=false
  local before_lines after_lines guard_log="$temp_dir/$guard_case-blocking-mutation.log"
  [[ "$guard_case" != deadline ]] || deadline=2
  [[ "$guard_case" != heartbeat ]] || heartbeat_failure=true
  : > "$temp_dir/operator-execution.log"
  : > "$guard_log"
  if AIRBOB_OPERATOR_TEST_HARNESS=hermetic-fake-v1 \
    AIRBOB_TEST_COMMAND_DEADLINE_SECONDS="$deadline" \
    AIRBOB_TEST_HEARTBEAT_INTERVAL_SECONDS=1 \
    AIRBOB_TEST_TERMINATION_GRACE_SECONDS=1 \
    FAKE_HEARTBEAT_FAILURE="$heartbeat_failure" \
    FAKE_BLOCK_TERRAFORM_APPLY=run-identity FAKE_BLOCKING_MUTATION_LOG="$guard_log" \
    FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
    run_fake_up "$guard_run" >"$temp_dir/$guard_case.out" 2>"$temp_dir/$guard_case.err"; then
    fail "$guard_case guard did not stop a blocking mutation"
  fi
  grep -Fq 'blocking-leader=' "$guard_log" \
    || fail "$guard_case guard fixture never reached the blocking mutation"
  grep -Fq 'stopping supervised mutation' "$temp_dir/$guard_case.err" \
    || fail "$guard_case guard did not report process-group termination"
  case "$guard_case" in
    deadline) grep -Fq 'operator command deadline exceeded' "$temp_dir/$guard_case.err" ;;
    heartbeat) grep -Fq 'orchestration heartbeat failed' "$temp_dir/$guard_case.err" ;;
  esac || fail "$guard_case guard did not report its exact abort reason"
  if grep -Eq '^terraform .* plan .*\/lab\.tfplan|^network |^policy |^dns ' \
    "$temp_dir/operator-execution.log"; then
    fail "$guard_case guard allowed a later infrastructure mutation"
  fi
  grep -Fq 'lease release ' "$temp_dir/operator-execution.log" \
    || fail "$guard_case guard did not release the orchestration lease"
  before_lines=$(awk 'END {print NR}' "$guard_log")
  sleep 2
  after_lines=$(awk 'END {print NR}' "$guard_log")
  [[ "$after_lines" == "$before_lines" ]] \
    || fail "$guard_case guard left a mutating descendant alive after operator cleanup"
}

run_blocking_guard_case deadline lab-deadline-stop
run_blocking_guard_case heartbeat lab-heartbeat-stop

: > "$temp_dir/operator-execution.log"
if FAKE_ORPHAN_FAILURE=true \
  run_fake_up lab-absent-orphans >"$temp_dir/absent-orphans.out" 2>"$temp_dir/absent-orphans.err"; then
  fail "absent Terraform state bypassed a failed global orphan scan"
fi
grep -Fq 'absent-state reuse failed a fresh zero-orphan scan' "$temp_dir/absent-orphans.err" \
  || fail "absent-state orphan failure did not report its reuse gate"
if grep -Eq 'runs/lab-absent-orphans/operator.json|terraform .* (plan|apply)|^phase ' \
  "$temp_dir/operator-execution.log"; then
  fail "absent-state orphan failure reached run publication or Terraform mutation"
fi

: > "$temp_dir/operator-execution.log"
run_fake_up lab-fake-success >/dev/null
grep -Fqx 'orphans lab-fake-success scope=global' "$temp_dir/operator-execution.log" \
  || fail "first absent-state up did not require a global zero-orphan scan"
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
oci_after_direct_line=$(grep -n 'route53 list-resource-record-sets' "$temp_dir/operator-execution.log" | sed -n '2p' | cut -d: -f1)
direct_readiness_line=$(grep -n -m1 's3api put-object .*measurements/lab-fake-success/direct-readiness.json' \
  "$temp_dir/operator-execution.log" | cut -d: -f1)
dns_stage_line=$(grep -n -m1 '^dns stage oci' "$temp_dir/operator-execution.log" | cut -d: -f1)
dns_switch_line=$(grep -n -m1 '^dns switch aws' "$temp_dir/operator-execution.log" | cut -d: -f1)
public_detail_line=$(grep -n 'api/v1/accommodations/200001' "$temp_dir/operator-execution.log" | tail -1 | cut -d: -f1)
[[ "$direct_detail_line" -lt "$dns_stage_line" && "$dns_switch_line" -lt "$public_detail_line" ]] \
  || fail "representative MySQL smoke did not bracket the AWS DNS switch"
[[ -n "$oci_after_direct_line" && -n "$direct_readiness_line" && \
  "$direct_detail_line" -lt "$oci_after_direct_line" && \
  "$oci_after_direct_line" -lt "$direct_readiness_line" && \
  "$direct_readiness_line" -lt "$dns_stage_line" ]] \
  || fail "cutover up did not freshly verify OCI and publish direct readiness before optional DNS staging"

: > "$temp_dir/operator-execution.log"
FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
  run_fake_up lab-direct-ready false 203.0.113.10 performance integrated-smoke >/dev/null
if grep -Eq '^dns ' "$temp_dir/operator-execution.log"; then
  fail "direct-only up invoked the DNS controller"
fi
[[ "$(grep -c 'route53 list-resource-record-sets' "$temp_dir/operator-execution.log")" == 2 ]] \
  || fail "direct-only up did not verify OCI before create and after direct smoke"
grep -Fq 'phase data-ready app=true dns=direct-only cidr=8.8.4.4/32' \
  "$temp_dir/operator-execution.log" || fail "direct-only tfvars did not retain the exact operator /32"
grep -Fq -- '--version-id dataset-version-fixture' "$temp_dir/operator-execution.log" \
  || fail "up did not fetch the exact audited dataset completion marker version"
grep -Fq -- '--version-id bundle-version-fixture' "$temp_dir/operator-execution.log" \
  || fail "up did not fetch the exact audited bundle completion manifest version"
direct_receipt_store="$FAKE_S3_STORE/measurements__lab-direct-ready__direct-readiness.json"
[[ -f "$direct_receipt_store" ]] || fail "direct-only up did not publish direct-readiness evidence"
jq -e '
  .status == "ready" and .topology.dnsMode == "direct-only" and
  .topology.albIngressCidr == "8.8.4.4/32" and
  .comparisonProjection.topology.ingress == {publicRestricted:true,prefixLength:32} and
  (.comparisonProjection.topology | has("albIngressCidr") | not) and
  (.networkClearance.versionId | length > 0) and
  (.networkClearance.sha256 | test("^[0-9a-f]{64}$")) and
  (.networkClearance.projectionSha256 | test("^[0-9a-f]{64}$")) and
  .comparisonProjection.networkClearanceProjectionSha256 == .networkClearance.projectionSha256 and
  .bootstrap.rdsSnapshotIdentifier == null and
  .bootstrap.rdsSnapshotSourceRunId == null and
  .bootstrap.rdsSnapshotSourceResourceId == null and
  .actual.alb.securityGroupId == "sg-0123456789abcdef0" and
  .actual.alb.shape.securityGroups == ["sg-0123456789abcdef0"] and
  (.actual.alb.observedIngress | length) == 1 and
  .actual.alb.observedIngress[0].cidrIpv4 == "8.8.4.4/32" and
  .actual.alb.observedIngress[0].cidrIpv6 == null and
  .actual.autoScalingGroup == {name:"airbob-fake-asg",min:1,desired:1,max:1} and
  .ociAuthority.route53 == "oci-only"
' "$direct_receipt_store" >/dev/null || fail "direct-readiness evidence has an invalid direct-only contract"
grep -Fq 'curl -4 --fail --silent --show-error --max-time 10' "$temp_dir/operator-execution.log" \
  || fail "OCI authority verification did not force IPv4 on the exact A path"
grep -Fq -- '--key network-clearance/lab-direct-ready/i-0123456789abcdef0.json --version-id version-fixture' \
  "$temp_dir/operator-execution.log" \
  || fail "direct readiness did not fetch the exact network-clearance receipt version"

for live_shape_drift in extra-security-group ingress-cidr ingress-ipv6 ingress-extra asg-capacity; do
  : > "$temp_dir/operator-execution.log"
  case "$live_shape_drift" in
    extra-security-group)
      FAKE_ALB_SECURITY_GROUP_DRIFT=true FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
        run_fake_up "lab-drift-$live_shape_drift" >/dev/null 2>&1 && accepted=true || accepted=false
      ;;
    ingress-cidr)
      FAKE_ALB_INGRESS_DRIFT=cidr FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
        run_fake_up "lab-drift-$live_shape_drift" >/dev/null 2>&1 && accepted=true || accepted=false
      ;;
    ingress-ipv6)
      FAKE_ALB_INGRESS_DRIFT=ipv6 FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
        run_fake_up "lab-drift-$live_shape_drift" >/dev/null 2>&1 && accepted=true || accepted=false
      ;;
    ingress-extra)
      FAKE_ALB_INGRESS_DRIFT=extra FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
        run_fake_up "lab-drift-$live_shape_drift" >/dev/null 2>&1 && accepted=true || accepted=false
      ;;
    asg-capacity)
      FAKE_ASG_CAPACITY_DRIFT=true FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
        run_fake_up "lab-drift-$live_shape_drift" >/dev/null 2>&1 && accepted=true || accepted=false
      ;;
  esac
  [[ "$accepted" == false ]] || fail "direct readiness accepted live shape drift: $live_shape_drift"
  [[ ! -f "$FAKE_S3_STORE/measurements__lab-drift-${live_shape_drift}__direct-readiness.json" ]] \
    || fail "live shape drift reached immutable readiness publication: $live_shape_drift"
done

# RUN_ID is globally single-use even when the caller supplies byte-identical
# inputs. The immutable operator manifest rejects the second attempt before
# Terraform or DNS can mutate anything.
: > "$temp_dir/operator-execution.log"
if FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
  run_fake_up lab-direct-ready >"$temp_dir/reused-run.out" 2>"$temp_dir/reused-run.err"; then
  fail "operator accepted reuse of an immutable RUN_ID"
fi
grep -Fq 'RUN_ID has already been used' "$temp_dir/reused-run.err" \
  || fail "reused RUN_ID did not report the immutable manifest conflict"
if grep -Eq '^dns |^terraform ' "$temp_dir/operator-execution.log"; then
  fail "reused RUN_ID reached Terraform or DNS mutation"
fi

# Execute both supported database bootstrap sources through the same
# direct-only operator path. Source identity and deterministic preparation
# timing differ, while the canonical readiness projections stay identical.
: > "$temp_dir/operator-execution.log"
printf '%s\n' 0 > "$temp_dir/dump-time-counter"
FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
  FAKE_DATABASE_BOOTSTRAP=dump FAKE_TIME_STEP_SECONDS=120 \
  FAKE_TIME_COUNTER="$temp_dir/dump-time-counter" \
  run_fake_up lab-repeat-dump false 203.0.113.10 performance integrated-smoke >/dev/null
printf '%s\n' 0 > "$temp_dir/snapshot-time-counter"
FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
  FAKE_DATABASE_BOOTSTRAP=snapshot FAKE_RDS_SNAPSHOT_IDENTIFIER=airbob-dataset-fixture \
  FAKE_TIME_STEP_SECONDS=60 FAKE_TIME_COUNTER="$temp_dir/snapshot-time-counter" \
  run_fake_up lab-repeat-snapshot false 203.0.113.10 performance integrated-smoke >/dev/null
grep -Eq '^lease acquire .* lab-repeat-dump up 180 14400$' "$temp_dir/operator-execution.log" \
  || fail "dump up did not acquire the 14400-second lease deadline"
grep -Eq '^lease acquire .* lab-repeat-snapshot up 180 5400$' "$temp_dir/operator-execution.log" \
  || fail "snapshot up did not retain the 5400-second lease deadline"
identity_apply_line=$(grep -n -m1 'terraform .* apply .*run-identity.tfplan' "$temp_dir/operator-execution.log" | cut -d: -f1)
network_plan_line=$(grep -n -m1 'terraform .* plan .*lab.tfplan' "$temp_dir/operator-execution.log" | cut -d: -f1)
network_probe_line=$(grep -n -m1 '^network egress ' "$temp_dir/operator-execution.log" | cut -d: -f1)
[[ -n "$identity_apply_line" && -n "$network_plan_line" && -n "$network_probe_line" && \
  "$identity_apply_line" -lt "$network_plan_line" && "$identity_apply_line" -lt "$network_probe_line" ]] \
  || fail "no-cost run identity was not persisted before the first network mutation and probe"
dump_readiness="$FAKE_S3_STORE/measurements__lab-repeat-dump__direct-readiness.json"
snapshot_readiness="$FAKE_S3_STORE/measurements__lab-repeat-snapshot__direct-readiness.json"
jq -e '
  .bootstrap.mode == "dump" and .bootstrap.rdsSnapshotIdentifier == null and
  .bootstrap.rdsSnapshotSourceRunId == null and .bootstrap.rdsSnapshotSourceResourceId == null and
  (.timing.resourceToDataReadySeconds | type == "number" and . >= 0) and
  (.timing.resourceToDirectReadySeconds | type == "number" and . >= 0)
' "$dump_readiness" >/dev/null || fail "dump-mode fake readiness source/timing is invalid"
jq -e '
  .bootstrap.mode == "snapshot" and .bootstrap.rdsSnapshotIdentifier == "airbob-dataset-fixture" and
  .bootstrap.rdsSnapshotSourceRunId == "lab-repeat-dump" and
  .bootstrap.rdsSnapshotSourceResourceId == "db-ABCDEFGHIJKLMNOPQRSTUVWX" and
  (.timing.resourceToDataReadySeconds | type == "number" and . >= 0) and
  (.timing.resourceToDirectReadySeconds | type == "number" and . >= 0)
' "$snapshot_readiness" >/dev/null || fail "snapshot-mode fake readiness source/timing is invalid"
jq -e --slurpfile snapshot "$snapshot_readiness" \
  '.timing != $snapshot[0].timing' "$dump_readiness" >/dev/null \
  || fail "dump and snapshot fake paths did not retain distinct preparation timings"
dump_projection_sha=$(jq -r '.comparisonProjectionSha256' "$dump_readiness")
snapshot_projection_sha=$(jq -r '.comparisonProjectionSha256' "$snapshot_readiness")
[[ "$dump_projection_sha" == "$snapshot_projection_sha" ]] \
  || fail "dump and snapshot paths produced different canonical readiness projection hashes"
jq -S '.comparisonProjection' "$dump_readiness" > "$temp_dir/dump-projection.json"
jq -S '.comparisonProjection' "$snapshot_readiness" > "$temp_dir/snapshot-projection.json"
cmp -s "$temp_dir/dump-projection.json" "$temp_dir/snapshot-projection.json" \
  || fail "dump and snapshot paths produced different canonical readiness projections"

: > "$temp_dir/operator-execution.log"
if FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
  FAKE_NETWORK_VERSION_EMPTY=true run_fake_up lab-network-versionless >/dev/null 2>&1; then
  fail "direct readiness accepted a network-clearance receipt without a VersionId"
fi
[[ ! -f "$FAKE_S3_STORE/measurements__lab-network-versionless__direct-readiness.json" ]] \
  || fail "versionless network clearance reached immutable readiness publication"

: > "$temp_dir/operator-execution.log"
if FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.4.4/32 \
  FAKE_NETWORK_RECEIPT_INVALID=true run_fake_up lab-network-not-cleared >/dev/null 2>&1; then
  fail "direct readiness accepted a probe that was not terminated"
fi
[[ ! -f "$FAKE_S3_STORE/measurements__lab-network-not-cleared__direct-readiness.json" ]] \
  || fail "invalid network clearance reached immutable readiness publication"

jq '.schemaVersion=2 | .dnsMode="direct-only" | .albIngressCidr="8.8.4.4/32"' \
  "$temp_dir/run-manifest.json" > "$temp_dir/run-manifest.direct.json"
: > "$temp_dir/operator-execution.log"
if env PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
  FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
  FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
  FAKE_RUN_MANIFEST="$temp_dir/run-manifest.direct.json" \
  FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
  FAKE_STATE_EXISTS=true FAKE_STATE_RUN_ID=lab-partial-down TARGET=aws RUN_ID=lab-partial-down \
  "$fixture_scripts/aws-lab.sh" switch >/dev/null 2>&1; then
  fail "direct-only run accepted a DNS switch"
fi
if grep -Eq '^dns ' "$temp_dir/operator-execution.log"; then
  fail "direct-only switch rejection invoked the DNS controller"
fi

: > "$temp_dir/operator-execution.log"
if env PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
  AWS_LAB_OPERATOR_SCOPE=cutover \
  FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
  FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
  FAKE_RUN_MANIFEST="$temp_dir/run-manifest.direct.json" \
  FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
  FAKE_STATE_EXISTS=false RUN_ID=lab-partial-down \
  "$fixture_scripts/aws-lab.sh" down >/dev/null 2>&1; then
  fail "direct-only down accepted cutover credentials without the scheduled FORCE gate"
fi
if grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log"; then
  fail "direct-only/cutover down mismatch acquired the orchestration lease"
fi

: > "$temp_dir/operator-execution.log"
if env PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
  AWS_LAB_OPERATOR_SCOPE=direct \
  FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
  FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
  FAKE_RUN_MANIFEST="$temp_dir/run-manifest.direct.json" \
  FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
  FAKE_STATE_EXISTS=false RUN_ID=lab-partial-down \
  "$fixture_scripts/aws-lab.sh" down >/dev/null 2>&1; then
  fail "missing-state direct-only down unexpectedly succeeded"
fi
grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log" \
  || fail "direct-only down did not accept direct-scoped credentials"
grep -Fq 'lease release ' "$temp_dir/operator-execution.log" \
  || fail "failed direct-only down did not release its lease"

: > "$temp_dir/operator-execution.log"
if env PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
  AWS_LAB_OPERATOR_SCOPE=cutover FORCE=true \
  FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
  FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
  FAKE_RUN_MANIFEST="$temp_dir/run-manifest.direct.json" \
  FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
  FAKE_STATE_EXISTS=false RUN_ID=lab-partial-down \
  "$fixture_scripts/aws-lab.sh" down >/dev/null 2>&1; then
  fail "missing-state scheduled direct-only cleanup unexpectedly succeeded"
fi
grep -Fq 'lease acquire ' "$temp_dir/operator-execution.log" \
  || fail "scheduled FORCE cleanup did not accept cutover-scoped credentials for a direct-only run"
grep -Fq 'lease release ' "$temp_dir/operator-execution.log" \
  || fail "failed scheduled direct-only cleanup did not release its lease"

# If the dedicated identity apply reports failure after the backend becomes
# non-empty, automatic cleanup owns that state before any network phase runs.
: > "$temp_dir/operator-execution.log"
: > "$temp_dir/state-lookup-count"
if FAKE_IDENTITY_APPLY_FAILURE=true FAKE_STATE_AFTER_FIRST_LOOKUP=true \
  FAKE_STATE_LOOKUP_COUNTER="$temp_dir/state-lookup-count" \
  FAKE_STATE_RUN_ID=lab-identity-failure FAKE_STATE_VERSION_ID=state-version-identity-failure \
  run_fake_up lab-identity-failure >"$temp_dir/identity-failure.out" 2>"$temp_dir/identity-failure.err"; then
  fail "operator hid the targeted identity apply failure"
fi
grep -Eq '^terraform .* apply .*run-identity.tfplan' "$temp_dir/operator-execution.log" \
  || fail "identity failure fixture did not reach the targeted apply"
if grep -Eq '^terraform .* plan .*\/lab.tfplan|^network ' "$temp_dir/operator-execution.log"; then
  fail "identity apply failure reached a network plan or probe"
fi
grep -Eq '^terraform .* state rm .*terraform_data.run_identity' "$temp_dir/operator-execution.log" \
  || { cat "$temp_dir/identity-failure.err" >&2; tail -80 "$temp_dir/operator-execution.log" >&2; fail "identity apply failure did not clean the newly non-empty backend"; }
grep -Fqx 'orphans lab-identity-failure scope=global' "$temp_dir/operator-execution.log" \
  || fail "identity apply failure cleanup skipped the global orphan scan"

# A recoverable up failure must complete the entire automatic cleanup: an
# actual destroy, a global orphan scan, a global-scope clean receipt, and
# lease release while preserving the original non-zero result.
: > "$temp_dir/operator-execution.log"
: > "$temp_dir/state-lookup-count"
auto_cleanup_version=state-version-auto-clean
if FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.8.8/32 \
  FAKE_DIRECT_SMOKE_FAILURE=true FAKE_STATE_AFTER_FIRST_LOOKUP=true \
  FAKE_STATE_LOOKUP_COUNTER="$temp_dir/state-lookup-count" \
  FAKE_STATE_RUN_ID=lab-auto-clean FAKE_STATE_VERSION_ID="$auto_cleanup_version" \
  run_fake_up lab-auto-clean >"$temp_dir/auto-clean.out" 2>"$temp_dir/auto-clean.err"; then
  fail "operator hid the application failure used for automatic cleanup"
fi
grep -Eq '^terraform .* apply .*destroy-resources.tfplan' "$temp_dir/operator-execution.log" \
  || fail "successful automatic failure cleanup did not apply the resource destroy plan"
grep -Fqx 'orphans lab-auto-clean scope=global' "$temp_dir/operator-execution.log" \
  || fail "successful automatic failure cleanup did not run the global orphan scan"
printf '%s' "$auto_cleanup_version-empty" > "$temp_dir/auto-clean-version.txt"
auto_cleanup_hash=$(sha256_file "$temp_dir/auto-clean-version.txt")
auto_cleanup_receipt="$FAKE_S3_STORE/measurements__state-clean__${auto_cleanup_hash}.json"
[[ -f "$auto_cleanup_receipt" ]] \
  || { cat "$temp_dir/auto-clean.err" >&2; tail -100 "$temp_dir/operator-execution.log" >&2; fail "successful automatic failure cleanup did not publish a global clean-state receipt"; }
jq -e '
  .status == "clean" and .runId == "lab-auto-clean" and
  .terraformState.resourceCount == 0 and
  .orphanScan == {status:"clean",scope:"global",runId:"lab-auto-clean"}
' "$auto_cleanup_receipt" >/dev/null \
  || fail "successful automatic failure cleanup did not publish a global clean-state receipt"
grep -Fq 'lease release ' "$temp_dir/operator-execution.log" \
  || fail "successful automatic failure cleanup skipped lease release"

: > "$temp_dir/operator-execution.log"
: > "$temp_dir/oci-verify-count"
: > "$temp_dir/state-lookup-count"
stale_cleanup_version=state-version-stale-oci
if FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.8.8/32 \
  FAKE_DIRECT_SMOKE_FAILURE=true FAKE_STATE_AFTER_FIRST_LOOKUP=true \
  FAKE_STATE_LOOKUP_COUNTER="$temp_dir/state-lookup-count" \
  FAKE_OCI_COUNTER_FILE="$temp_dir/oci-verify-count" FAKE_OCI_FAIL_AT=3 \
  FAKE_STATE_RUN_ID=lab-stale-oci-cleanup FAKE_STATE_VERSION_ID="$stale_cleanup_version" \
  run_fake_up lab-stale-oci-cleanup >/dev/null 2>&1; then
  fail "operator hid a direct application-smoke failure"
fi
if [[ ! -f "$FAKE_S3_STORE/measurements__lab-stale-oci-cleanup__teardown-start.json" ]]; then
  tail -80 "$temp_dir/operator-execution.log" >&2
  printf 'state-lookups=%s oci-verifications=%s\n' \
    "$(cat "$temp_dir/state-lookup-count")" "$(cat "$temp_dir/oci-verify-count")" >&2
  fail "failure cleanup did not journal the active pre-destroy state"
fi
printf '%s' "$stale_cleanup_version-empty" > "$temp_dir/stale-cleanup-version.txt"
stale_cleanup_hash=$(sha256_file "$temp_dir/stale-cleanup-version.txt")
if [[ -f "$FAKE_S3_STORE/measurements__state-clean__${stale_cleanup_hash}.json" ]]; then
  fail "failure cleanup published a clean-state receipt after post-destroy OCI verification failed"
fi

: > "$temp_dir/operator-execution.log"
: > "$temp_dir/oci-verify-count"
: > "$temp_dir/state-lookup-count"
if FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.8.8/32 \
  FAKE_DIRECT_SMOKE_FAILURE=true FAKE_STATE_AFTER_FIRST_LOOKUP=true \
  FAKE_STATE_LOOKUP_COUNTER="$temp_dir/state-lookup-count" \
  FAKE_OCI_COUNTER_FILE="$temp_dir/oci-verify-count" FAKE_OCI_FAIL_AT=2 \
  run_fake_up lab-precleanup-oci-failure >"$temp_dir/precleanup.out" 2>"$temp_dir/precleanup.err"; then
  fail "operator hid the application-smoke failure used for cleanup preservation"
fi
if grep -Eq 'terraform .* (plan|apply) .*destroy-(resources|run-identity)' "$temp_dir/operator-execution.log"; then
  fail "failure cleanup destroyed resources without a fresh pre-destroy OCI observation"
fi
grep -Fq 'lease release ' "$temp_dir/operator-execution.log" \
  || fail "failed pre-destroy OCI gate skipped lease release"
grep -Fq 'failure cleanup preserved resources because OCI authority or teardown-start could not be verified' \
  "$temp_dir/precleanup.err" || fail "failed pre-destroy OCI gate did not explain resource preservation"

: > "$temp_dir/operator-execution.log"
: > "$temp_dir/state-lookup-count"
if FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.8.8/32 \
  FAKE_DIRECT_SMOKE_FAILURE=true FAKE_STATE_AFTER_FIRST_LOOKUP=true \
  FAKE_STATE_LOOKUP_COUNTER="$temp_dir/state-lookup-count" \
  FAKE_TEARDOWN_START_PUT_FAILURE=true \
  run_fake_up lab-teardown-journal-failure >"$temp_dir/journal.out" 2>"$temp_dir/journal.err"; then
  fail "operator hid the application-smoke failure used for journal preservation"
fi
if grep -Eq 'terraform .* (plan|apply) .*destroy-(resources|run-identity)' "$temp_dir/operator-execution.log"; then
  fail "failure cleanup destroyed resources without a create-only/read-back teardown-start journal"
fi
grep -Fq 'lease release ' "$temp_dir/operator-execution.log" \
  || fail "failed teardown-start gate skipped lease release"
grep -Fq 'failure cleanup preserved resources because OCI authority or teardown-start could not be verified' \
  "$temp_dir/journal.err" || fail "failed teardown-start gate did not explain resource preservation"
assert_contains "$operator" 'failure cleanup preserved resources because OCI authority or teardown-start could not be verified'

: > "$temp_dir/operator-execution.log"
: > "$temp_dir/state-lookup-count"
if FAKE_DNS_MODE=direct-only FAKE_ALB_INGRESS_CIDR=8.8.8.8/32 \
  FAKE_DIRECT_SMOKE_FAILURE=true FAKE_STATE_AFTER_FIRST_LOOKUP=true \
  FAKE_STATE_LOOKUP_COUNTER="$temp_dir/state-lookup-count" FAKE_DESTROY_FAILURE=true \
  run_fake_up lab-destroy-failure >"$temp_dir/destroy.out" 2>"$temp_dir/destroy.err"; then
  fail "operator hid the application-smoke failure used for destroy-failure cleanup"
fi
grep -Fq 'lease release ' "$temp_dir/operator-execution.log" \
  || { cat "$temp_dir/destroy.err" >&2; tail -120 "$temp_dir/operator-execution.log" >&2; fail "failed automatic destroy skipped lease release"; }
grep -Fq 'failure cleanup could not prove an empty Terraform state; resources remain blocked' \
  "$temp_dir/destroy.err" || fail "failed automatic destroy did not explain the blocked state"

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
  DNS_MODE=cutover \
  IMAGE_DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  DATASET_RELEASE=fixture-v20 DATASET_MANIFEST_VERSION_ID=dataset-version-fixture \
  BUNDLE_COMMIT=cccccccccccccccccccccccccccccccccccccccc BUNDLE_MANIFEST_VERSION_ID=bundle-version-fixture \
  AMI_ID=ami-0123456789abcdef0 OCI_ORIGIN_IPV4=203.0.113.10 \
  RDS_ENGINE_VERSION=8.0.42 LOAD_GENERATOR_ENABLED=false TTL_HOURS=5 \
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
grep -Fq 'dns remove oci' "$temp_dir/operator-execution.log" || fail "post-cutover failure did not remove the AWS alias after restoring OCI"
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
grep -Fq 'dns remove oci' "$temp_dir/operator-execution.log" \
  || fail "manual AWS switch smoke failure did not remove the AWS alias after restoring OCI"

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
grep -Eq '^terraform .* apply .*destroy-resources.tfplan' "$temp_dir/operator-execution.log" \
  || fail "partial down did not apply the exact managed-resource destroy plan"
grep -Eq '^terraform .* state rm .*data\.aws_caller_identity\.current' "$temp_dir/operator-execution.log" \
  || fail "partial down did not remove the validated data-source state allowlist"
grep -Eq '^terraform .* plan .*destroy-run-identity.tfplan' "$temp_dir/operator-execution.log" \
  || fail "partial down did not validate the identity-only destroy plan"
if grep -Eq '^terraform .* apply .*destroy-run-identity.tfplan' "$temp_dir/operator-execution.log"; then
  fail "partial down applied the identity plan instead of using exact state removal"
fi
grep -Eq '^terraform .* state rm .*terraform_data.run_identity' "$temp_dir/operator-execution.log" \
  || fail "partial down did not remove the exact literal run-identity state address"
grep -Fqx 'orphans lab-partial-down scope=global' "$temp_dir/operator-execution.log" \
  || fail "partial down did not perform the required global orphan scan"
grep -Fq 'runs/lab-partial-down/terraform-outputs.redacted.json' "$temp_dir/operator-execution.log" \
  || fail "down did not save redacted Terraform outputs"
printf '%s' state-version-fixture-empty > "$temp_dir/state-version.txt"
state_version_hash=$(sha256_file "$temp_dir/state-version.txt")
state_clean_store="$FAKE_S3_STORE/measurements__state-clean__${state_version_hash}.json"
[[ -f "$FAKE_S3_STORE/measurements__lab-partial-down__teardown-start.json" ]] \
  || fail "down did not publish the immutable teardown-start journal"
[[ -f "$state_clean_store" ]] || fail "down did not publish the state-version-addressed clean receipt"
jq -e --arg versionHash "$state_version_hash" '
  .status == "clean" and .terraformState.versionIdSha256 == $versionHash and
  .terraformState.resourceCount == 0 and .ociAuthority.status == "verified" and
  .orphanScan.status == "clean" and .orphanScan.scope == "global" and
  .orphanScan.runId == "lab-partial-down"
' "$state_clean_store" >/dev/null || fail "clean-state receipt is invalid"
jq -e '
  .teardownFinalize.key == "measurements/lab-partial-down/teardown-finalize.json" and
  (.teardownFinalize.versionId | type == "string" and length > 0)
' "$state_clean_store" >/dev/null || fail "clean-state receipt does not bind teardown finalize"

# The first network apply may fail before phase2_contract exists. An explicit
# RUN_ID must recover from the separately persisted terraform_data identity;
# even a missing output is reconstructed from the exact current-state JSON.
jq '.runId="lab-first-apply"' "$temp_dir/run-manifest.json" \
  > "$temp_dir/run-manifest.first-apply.json"
: > "$temp_dir/operator-execution.log"
first_apply_state='{"version":4,"serial":2,"resources":[{"type":"terraform_data","name":"run_identity"},{"type":"aws_vpc","name":"partial"}]}'
FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.first-apply.json" \
  FAKE_STATE_RUN_ID=lab-first-apply FAKE_STATE_FENCING_TOKEN=41 \
  FAKE_STATE_VERSION_ID=state-version-first-apply FAKE_STATE_CONTENT="$first_apply_state" \
  FAKE_RUN_IDENTITY_OUTPUT_MISSING=true FAKE_PHASE2_OUTPUT_MISSING=true \
  run_fake_down lab-first-apply > "$temp_dir/first-apply-down.out"
grep -Eq '^terraform .* show -json$' "$temp_dir/operator-execution.log" \
  || fail "first-apply recovery did not read the durable identity from current-state JSON"
if grep -Fq 'output -json phase2_contract' "$temp_dir/operator-execution.log"; then
  fail "first-apply recovery still depended on the unavailable phase2 contract"
fi
grep -Eq '^terraform .* apply .*destroy-resources.tfplan' "$temp_dir/operator-execution.log" \
  || fail "first-apply recovery did not destroy the partial non-empty state"
grep -Fqx 'orphans lab-first-apply scope=global' "$temp_dir/operator-execution.log" \
  || fail "first-apply recovery skipped the global orphan scan"
printf '%s' state-version-first-apply-empty > "$temp_dir/first-apply-version.txt"
first_apply_hash=$(sha256_file "$temp_dir/first-apply-version.txt")
first_apply_clean="$FAKE_S3_STORE/measurements__state-clean__${first_apply_hash}.json"
jq -e '
  .status == "clean" and .runId == "lab-first-apply" and
  .resourceFencingToken == 41 and .terraformState.resourceCount == 0 and
  .orphanScan == {status:"clean",scope:"global",runId:"lab-first-apply"}
' "$first_apply_clean" >/dev/null \
  || fail "first-apply recovery did not publish the exact clean-state receipt"

# Simulate a response/publication loss after destroy. The explicit old RUN_ID
# must finalize from its journal without another apply or destroy.
mv "$state_clean_store" "$state_clean_store.failed-publication"
: > "$temp_dir/operator-execution.log"
env PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
  FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
  FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
  FAKE_RUN_MANIFEST="$temp_dir/run-manifest.json" \
  FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
  FAKE_STATE_EXISTS=true FAKE_STATE_RUN_ID=lab-partial-down RUN_ID=lab-partial-down \
  "$fixture_scripts/aws-lab.sh" down >/dev/null
[[ -f "$state_clean_store" ]] || fail "empty-state teardown recovery did not finalize the clean receipt"
grep -Fq 'teardown_recovered=true' <(
  env PATH="$temp_dir/operator-bin:$PATH" AWS_REGION=ap-northeast-2 \
    FAKE_OPERATOR_LOG="$temp_dir/operator-execution.log" \
    FAKE_LAB_CONTRACT="$temp_dir/lab-contract.json" \
    FAKE_RUN_MANIFEST="$temp_dir/run-manifest.json" \
    FAKE_OPERATOR_TEMP_PREFIX="${TMPDIR:-/tmp}/airbob-lab." \
    FAKE_STATE_EXISTS=true FAKE_STATE_RUN_ID=lab-partial-down RUN_ID=lab-partial-down \
    "$fixture_scripts/aws-lab.sh" down
) || fail "teardown recovery did not report its recovery path"
if grep -Eq 'terraform .* (plan|apply|destroy)' "$temp_dir/operator-execution.log"; then
  fail "empty-state teardown recovery repeated an infrastructure mutation"
fi

# A destroy retry remains safe when Terraform has written a newer partial
# state version after the immutable teardown-start journal. The old journal
# remains the authority for intent; the final clean receipt binds the exact
# newer empty state version and bytes.
jq '.runId="lab-partial-retry"' "$temp_dir/run-manifest.json" \
  > "$temp_dir/run-manifest.partial-retry.json"
old_partial_state='{"version":4,"serial":10,"resources":[{"type":"aws_vpc"}]}'
new_partial_state='{"version":4,"serial":11,"resources":[{"type":"aws_subnet"}]}'
: > "$temp_dir/operator-execution.log"
if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.partial-retry.json" \
  FAKE_STATE_RUN_ID=lab-partial-retry FAKE_STATE_VERSION_ID=state-version-before-retry \
  FAKE_STATE_CONTENT="$old_partial_state" FAKE_DESTROY_FAILURE=true \
  run_fake_down lab-partial-retry >"$temp_dir/partial-retry-first.out" 2>"$temp_dir/partial-retry-first.err"; then
  fail "partial-destroy fixture unexpectedly succeeded before retry"
fi
if [[ -f "$FAKE_S3_STORE/destroyed-lab-partial-retry" ]] ||
  grep -Eq 'state rm .*terraform_data.run_identity' "$temp_dir/operator-execution.log"; then
  fail "failed resource destroy removed the durable run identity"
fi
[[ ! -f "$FAKE_S3_STORE/measurements__lab-partial-retry__teardown-finalize.json" ]] \
  || fail "failed resource destroy finalized identity removal prematurely"
partial_retry_journal="$FAKE_S3_STORE/measurements__lab-partial-retry__teardown-start.json"
jq -e '
  .runId == "lab-partial-retry" and
  .terraformState.versionId == "state-version-before-retry"
' "$partial_retry_journal" >/dev/null \
  || fail "partial-destroy retry did not preserve the original teardown-start state identity"
: > "$temp_dir/operator-execution.log"
FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.partial-retry.json" \
  FAKE_STATE_RUN_ID=lab-partial-retry FAKE_STATE_VERSION_ID=state-version-after-retry \
  FAKE_STATE_CONTENT="$new_partial_state" run_fake_down lab-partial-retry >/dev/null
grep -Eq '^terraform .* apply .*destroy-resources.tfplan' "$temp_dir/operator-execution.log" \
  || fail "partial-destroy retry did not resume Terraform destroy"
grep -Fqx 'orphans lab-partial-retry scope=global' "$temp_dir/operator-execution.log" \
  || fail "partial-destroy retry skipped the global orphan scan"
printf '%s' state-version-after-retry-empty > "$temp_dir/partial-retry-version.txt"
partial_retry_hash=$(sha256_file "$temp_dir/partial-retry-version.txt")
partial_retry_clean="$FAKE_S3_STORE/measurements__state-clean__${partial_retry_hash}.json"
jq -e '
  .runId == "lab-partial-retry" and
  .teardownStart.key == "measurements/lab-partial-retry/teardown-start.json" and
  .teardownFinalize.key == "measurements/lab-partial-retry/teardown-finalize.json" and
  .terraformState.versionId == "state-version-after-retry-empty" and
  .terraformState.resourceCount == 0 and .orphanScan.scope == "global"
' "$partial_retry_clean" >/dev/null \
  || fail "partial-destroy retry clean receipt did not bind the newer empty state identity"
jq -e '.terraformState.versionId == "state-version-before-retry"' "$partial_retry_journal" >/dev/null \
  || fail "partial-destroy retry rewrote the immutable teardown-start journal"

# A retry after a pre-mutation identity state-rm failure must reuse the exact
# create-only finalize bytes even though the new down command has a new lease.
jq '.runId="lab-finalize-reuse"' "$temp_dir/run-manifest.json" \
  > "$temp_dir/run-manifest.finalize-reuse.json"
: > "$temp_dir/operator-execution.log"
if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.finalize-reuse.json" \
  FAKE_STATE_RUN_ID=lab-finalize-reuse FAKE_STATE_VERSION_ID=state-version-finalize-reuse \
  FAKE_IDENTITY_STATE_RM_FAILURE=true \
  run_fake_down lab-finalize-reuse >"$temp_dir/finalize-reuse-first.out" 2>"$temp_dir/finalize-reuse-first.err"; then
  fail "identity state-removal failure fixture unexpectedly succeeded"
fi
finalize_reuse="$FAKE_S3_STORE/measurements__lab-finalize-reuse__teardown-finalize.json"
[[ -f "$finalize_reuse" && -f "$FAKE_S3_STORE/identity-only-lab-finalize-reuse" && \
  ! -f "$FAKE_S3_STORE/destroyed-lab-finalize-reuse" ]] \
  || fail "identity state-removal failure did not preserve finalize authority and identity-only state"
finalize_reuse_sha=$(sha256_file "$finalize_reuse")
jq -e '.runId == "lab-finalize-reuse" and (has("fencingToken") | not)' \
  "$finalize_reuse" >/dev/null \
  || fail "retryable teardown finalize journal contains command-lease identity"
: > "$temp_dir/operator-execution.log"
FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.finalize-reuse.json" \
  FAKE_STATE_RUN_ID=lab-finalize-reuse FAKE_STATE_VERSION_ID=state-version-finalize-reuse \
  run_fake_down lab-finalize-reuse >/dev/null
[[ "$(sha256_file "$finalize_reuse")" == "$finalize_reuse_sha" ]] \
  || fail "identity-only retry changed the create-only finalize journal bytes"
grep -Eq '^terraform .* state rm .*terraform_data.run_identity' "$temp_dir/operator-execution.log" \
  || fail "identity-only retry did not complete exact identity state removal"

# If the exact identity state removal reaches the backend but its response is lost,
# the create-only finalize journal must let the same explicit RUN_ID prove the
# exact identity predecessor and publish clean state without another mutation.
jq '.runId="lab-final-response-loss"' "$temp_dir/run-manifest.json" \
  > "$temp_dir/run-manifest.final-response-loss.json"
: > "$temp_dir/operator-execution.log"
if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.final-response-loss.json" \
  FAKE_STATE_RUN_ID=lab-final-response-loss FAKE_STATE_VERSION_ID=state-version-final-response \
  FAKE_IDENTITY_STATE_RM_RESPONSE_LOSS=true \
  run_fake_down lab-final-response-loss >"$temp_dir/final-response-first.out" 2>"$temp_dir/final-response-first.err"; then
  fail "identity state-removal response-loss fixture unexpectedly succeeded"
fi
final_response_finalize="$FAKE_S3_STORE/measurements__lab-final-response-loss__teardown-finalize.json"
[[ -f "$final_response_finalize" && -f "$FAKE_S3_STORE/destroyed-lab-final-response-loss" ]] \
  || fail "identity state-removal response loss did not preserve its finalize authority and empty state"
jq -e '
  .runId == "lab-final-response-loss" and
  .terraformState.versionId == "state-version-final-response-identity" and
  .terraformState.serial == 12 and (has("fencingToken") | not)
' "$final_response_finalize" >/dev/null \
  || fail "identity state-removal response-loss finalize journal is invalid"
: > "$temp_dir/operator-execution.log"
FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.final-response-loss.json" \
  FAKE_STATE_RUN_ID=lab-final-response-loss FAKE_STATE_VERSION_ID=state-version-final-response \
  run_fake_down lab-final-response-loss > "$temp_dir/final-response-retry.out"
grep -Fq 'teardown_recovered=true' "$temp_dir/final-response-retry.out" \
  || fail "correct RUN_ID did not recover after identity state-removal response loss"
if grep -Eq 'terraform .* (plan|apply) .*destroy-(resources|run-identity)|terraform .* state rm .*terraform_data.run_identity' "$temp_dir/operator-execution.log"; then
  fail "identity state-removal response-loss recovery repeated a Terraform mutation"
fi
printf '%s' state-version-final-response-empty > "$temp_dir/final-response-empty-version.txt"
final_response_hash=$(sha256_file "$temp_dir/final-response-empty-version.txt")
[[ -f "$FAKE_S3_STORE/measurements__state-clean__${final_response_hash}.json" ]] \
  || fail "identity state-removal response-loss recovery did not publish clean state"

# An old RUN_ID cannot claim a newer empty state with a finalize journal whose
# recorded predecessor serial differs from the exact versioned identity state.
jq '.runId="lab-old-finalize"' "$temp_dir/run-manifest.json" \
  > "$temp_dir/run-manifest.old-finalize.json"
wrong_lineage=11111111-2222-3333-4444-555555555555
wrong_identity_state="$temp_dir/wrong-old-identity.tfstate"
jq -n --arg run lab-old-finalize --argjson fence 41 --arg lineage "$wrong_lineage" --argjson serial 12 \
  '{version:4,terraform_version:"1.15.5",serial:$serial,lineage:$lineage,outputs:{},resources:[{mode:"managed",type:"terraform_data",name:"run_identity",provider:"provider[\"terraform.io/builtin/terraform\"]",instances:[{schema_version:0,identity_schema_version:0,attributes:{id:"fixture-id",input:{value:{run_id:$run,resource_fencing_token:$fence},type:["object",{run_id:"string",resource_fencing_token:"number"}]},output:{value:{run_id:$run,resource_fencing_token:$fence},type:["object",{run_id:"string",resource_fencing_token:"number"}]},triggers_replace:null},sensitive_attributes:[]}]}]}' \
  > "$wrong_identity_state"
wrong_identity_sha=$(sha256_file "$wrong_identity_state")
printf '%s' state-version-old-finalize-identity > "$temp_dir/wrong-old-version.txt"
wrong_identity_version_hash=$(sha256_file "$temp_dir/wrong-old-version.txt")
jq -nS --arg runId lab-old-finalize --argjson resourceFencingToken 41 --arg dnsMode cutover \
  '{schemaVersion:1,status:"started",runId:$runId,resourceFencingToken:$resourceFencingToken,dnsMode:$dnsMode,terraformState:{versionId:"state-version-old-finalize",objectSha256:"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}' \
  > "$FAKE_S3_STORE/measurements__lab-old-finalize__teardown-start.json"
jq -nS --arg runId lab-old-finalize --argjson resourceFencingToken 41 --arg dnsMode cutover \
  --arg stateSha "$wrong_identity_sha" --arg versionHash "$wrong_identity_version_hash" \
  --arg lineage "$wrong_lineage" \
  '{schemaVersion:1,status:"ready",runId:$runId,resourceFencingToken:$resourceFencingToken,dnsMode:$dnsMode,teardownStart:{key:"measurements/lab-old-finalize/teardown-start.json",versionId:"version-fixture"},terraformState:{key:"airbob/lab/terraform.tfstate",versionId:"state-version-old-finalize-identity",versionIdSha256:$versionHash,objectSha256:$stateSha,lineage:$lineage,serial:99,resourceCount:1,identityAddress:"terraform_data.run_identity"}}' \
  > "$FAKE_S3_STORE/measurements__lab-old-finalize__teardown-finalize.json"
touch "$FAKE_S3_STORE/destroyed-lab-old-finalize"
: > "$temp_dir/operator-execution.log"
if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.old-finalize.json" \
  FAKE_STATE_RUN_ID=lab-old-finalize FAKE_STATE_VERSION_ID=state-version-old-finalize \
  run_fake_down lab-old-finalize >"$temp_dir/wrong-old.out" 2>"$temp_dir/wrong-old.err"; then
  fail "old RUN_ID claimed empty state with a mismatched finalize predecessor serial"
fi
if grep -Fq 'route53 list-resource-record-sets' "$temp_dir/operator-execution.log"; then
  fail "mismatched finalize predecessor reached OCI or orphan verification"
fi

# The first destroy plan is rejected before apply if Terraform attempts to
# include the durable run identity among the managed resource deletions.
jq '.runId="lab-plan-identity-delete"' "$temp_dir/run-manifest.json" \
  > "$temp_dir/run-manifest.plan-identity-delete.json"
: > "$temp_dir/operator-execution.log"
if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.plan-identity-delete.json" \
  FAKE_STATE_RUN_ID=lab-plan-identity-delete FAKE_STATE_VERSION_ID=state-version-plan-identity \
  FAKE_FIRST_PHASE_IDENTITY_DELETE=true \
  run_fake_down lab-plan-identity-delete >"$temp_dir/plan-identity.out" 2>"$temp_dir/plan-identity.err"; then
  fail "first destroy plan was allowed to delete the durable run identity"
fi
grep -Eq 'show -json .*destroy-resources.tfplan' "$temp_dir/operator-execution.log" \
  || fail "identity-delete rejection did not inspect the first destroy plan JSON"
if grep -Eq 'apply .*destroy-(resources|run-identity).tfplan' "$temp_dir/operator-execution.log"; then
  fail "identity-delete first plan reached Terraform apply"
fi
[[ ! -f "$FAKE_S3_STORE/measurements__lab-plan-identity-delete__teardown-finalize.json" ]] \
  || fail "identity-delete first plan published teardown finalize"

# The same first-plan gate preserves any resource that has crossed into the
# persistent boundary, even if that address appears in the Lab backend.
jq '.runId="lab-plan-persistent-delete"' "$temp_dir/run-manifest.json" \
  > "$temp_dir/run-manifest.plan-persistent-delete.json"
: > "$temp_dir/operator-execution.log"
if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.plan-persistent-delete.json" \
  FAKE_STATE_RUN_ID=lab-plan-persistent-delete FAKE_STATE_VERSION_ID=state-version-plan-persistent \
  FAKE_FIRST_PHASE_PERSISTENT_DELETE=true \
  run_fake_down lab-plan-persistent-delete >"$temp_dir/plan-persistent.out" 2>"$temp_dir/plan-persistent.err"; then
  fail "first destroy plan was allowed to delete a persistent resource"
fi
if grep -Eq 'apply .*destroy-(resources|run-identity).tfplan|state rm .*terraform_data.run_identity' \
  "$temp_dir/operator-execution.log"; then
  fail "persistent-delete first plan reached Terraform mutation"
fi
[[ ! -f "$FAKE_S3_STORE/measurements__lab-plan-persistent-delete__teardown-finalize.json" ]] \
  || fail "persistent-delete first plan published teardown finalize"

# A failed create-only finalize publication stops before the identity plan, so
# the explicit down path can retry from the durable identity-only state.
jq '.runId="lab-finalize-publish-fail"' "$temp_dir/run-manifest.json" \
  > "$temp_dir/run-manifest.finalize-publish-fail.json"
: > "$temp_dir/operator-execution.log"
if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.finalize-publish-fail.json" \
  FAKE_STATE_RUN_ID=lab-finalize-publish-fail FAKE_STATE_VERSION_ID=state-version-finalize-fail \
  FAKE_TEARDOWN_FINALIZE_PUT_FAILURE=true \
  run_fake_down lab-finalize-publish-fail >"$temp_dir/finalize-fail.out" 2>"$temp_dir/finalize-fail.err"; then
  fail "teardown succeeded after finalize publication failure"
fi
[[ -f "$FAKE_S3_STORE/identity-only-lab-finalize-publish-fail" && \
  ! -f "$FAKE_S3_STORE/destroyed-lab-finalize-publish-fail" ]] \
  || fail "finalize publication failure did not preserve identity-only state"
if grep -Eq 'plan .*destroy-run-identity.tfplan|state rm .*terraform_data.run_identity' "$temp_dir/operator-execution.log"; then
  fail "finalize publication failure reached identity deletion"
fi

: > "$temp_dir/operator-execution.log"
if FAKE_STATE_EXISTS=true FAKE_STATE_RUN_ID=lab-partial-down FAKE_STATE_FENCING_TOKEN=41 FAKE_OCI_DNS_INVALID=true \
  run_fake_up lab-clean-state-reuse >/dev/null 2>&1; then
  fail "state-reuse probe unexpectedly passed an intentionally invalid OCI gate"
fi
grep -Fq "measurements/state-clean/$state_version_hash.json" "$temp_dir/operator-execution.log" \
  || fail "new up did not validate the exact state-version-addressed clean receipt"
grep -Fq 'route53 list-resource-record-sets' "$temp_dir/operator-execution.log" \
  || fail "valid clean-state receipt did not permit progress to the OCI pre-create gate"

cp "$state_clean_store" "$temp_dir/state-clean.valid.json"
jq '.orphanScan.runId="lab-other-run"' "$temp_dir/state-clean.valid.json" \
  > "$temp_dir/state-clean.orphan-drift.json"
mv "$temp_dir/state-clean.orphan-drift.json" "$state_clean_store"
: > "$temp_dir/operator-execution.log"
if FAKE_STATE_EXISTS=true FAKE_STATE_RUN_ID=lab-partial-down FAKE_STATE_FENCING_TOKEN=41 FAKE_OCI_DNS_INVALID=true \
  run_fake_up lab-orphan-binding-drift >/dev/null 2>&1; then
  fail "new up accepted a clean-state receipt whose orphan scan belongs to another run"
fi
if grep -Fq 'route53 list-resource-record-sets' "$temp_dir/operator-execution.log"; then
  fail "incoherent clean-state receipt reached the OCI or infrastructure gates"
fi
cp "$temp_dir/state-clean.valid.json" "$state_clean_store"

jq '.teardownStart.key="measurements/lab-other-run/teardown-start.json"' \
  "$temp_dir/state-clean.valid.json" > "$temp_dir/state-clean.start-drift.json"
mv "$temp_dir/state-clean.start-drift.json" "$state_clean_store"
: > "$temp_dir/operator-execution.log"
if FAKE_STATE_EXISTS=true FAKE_STATE_RUN_ID=lab-partial-down FAKE_STATE_FENCING_TOKEN=41 FAKE_OCI_DNS_INVALID=true \
  run_fake_up lab-start-binding-drift >/dev/null 2>&1; then
  fail "new up accepted a clean-state receipt bound to another run's teardown journal"
fi
if grep -Fq 'route53 list-resource-record-sets' "$temp_dir/operator-execution.log"; then
  fail "incoherent teardown-start binding reached the OCI or infrastructure gates"
fi
cp "$temp_dir/state-clean.valid.json" "$state_clean_store"

jq '.terraformState.objectSha256="9999999999999999999999999999999999999999999999999999999999999999"' \
  "$state_clean_store" > "$temp_dir/state-clean.drift.json"
mv "$temp_dir/state-clean.drift.json" "$state_clean_store"
: > "$temp_dir/operator-execution.log"
if FAKE_STATE_EXISTS=true FAKE_STATE_RUN_ID=lab-partial-down FAKE_STATE_FENCING_TOKEN=41 \
  run_fake_up lab-drifted-state-reuse >/dev/null 2>&1; then
  fail "new up accepted a drifted clean-state receipt"
fi
if grep -Fq 'route53 list-resource-record-sets' "$temp_dir/operator-execution.log"; then
  fail "drifted clean-state receipt reached the OCI or infrastructure gates"
fi
mv "$temp_dir/state-clean.valid.json" "$state_clean_store"

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

jq '.runId="lab-fence-drift"' "$temp_dir/run-manifest.json" \
  > "$temp_dir/run-manifest.fence-drift.json"
: > "$temp_dir/operator-execution.log"
if FAKE_RUN_MANIFEST_PATH="$temp_dir/run-manifest.fence-drift.json" \
  FAKE_STATE_RUN_ID=lab-fence-drift FAKE_STATE_FENCING_TOKEN=99 \
  run_fake_down lab-fence-drift >/dev/null 2>&1; then
  fail "down accepted a state identity fenced by another creation lease"
fi
if grep -Eq 'dns |terraform .* (plan|apply|destroy)' "$temp_dir/operator-execution.log"; then
  fail "state/manifest fencing drift reached an infrastructure mutation"
fi

# Run the expanded zero-orphan inventory against a hermetic AWS CLI and prove
# every run-bound service family is queried. Then surface one SSM document.
mkdir -p "$temp_dir/scanner-bin"
cat > "$temp_dir/scanner-bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >> "${FAKE_SCANNER_LOG:?}"
if [[ "${FAKE_SCANNER_FAILURE:-}" == vpc && " $* " == *' ec2 describe-vpcs '* ]]; then
  exit 70
fi
case " $* " in
  *' sts get-caller-identity '*) printf '%s\n' 942632789808 ;;
  *' ssm get-parameter '*)
    printf '%s\n' '{"schemaVersion":1,"private_dns_zone_id":"Z0987654321PRIVATE","private_dns_zone_name":"lab.airbob.internal"}'
    ;;
  *' route53 list-resource-record-sets '*)
    if [[ "${FAKE_SCANNER_ORPHAN:-}" == private-dns ]]; then
      printf '%s\n' '{"ResourceRecordSets":[{"Name":"kafka.lab.airbob.internal.","Type":"A","TTL":30,"ResourceRecords":[{"Value":"10.0.1.5"}]}]}'
    else
      printf '%s\n' '{"ResourceRecordSets":[]}'
    fi
    ;;
  *' elbv2 describe-load-balancers '*)
    if [[ "${FAKE_SCANNER_ORPHAN:-}" == alb ]]; then
      printf '%s\n' arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/fixture/1
    else
      printf '%s\n' None
    fi
    ;;
  *' ssm list-documents '*)
    if [[ "${FAKE_SCANNER_ORPHAN:-}" == ssm-document ]]; then
      printf '%s\n' airbob-lab-scan-start-app
    else
      printf '%s\n' None
    fi
    ;;
  *) printf '%s\n' None ;;
esac
EOF
chmod 700 "$temp_dir/scanner-bin/aws"
: > "$temp_dir/scanner.log"
env PATH="$temp_dir/scanner-bin:/usr/bin:/bin" AWS_REGION=ap-northeast-2 \
  FAKE_SCANNER_LOG="$temp_dir/scanner.log" "$orphan_scanner" lab-scan >/dev/null
for command in \
  'ec2 describe-vpcs' 'ec2 describe-subnets' 'ec2 describe-route-tables' \
  'ec2 describe-internet-gateways' 'ec2 describe-network-interfaces' \
  'ec2 describe-security-groups' 'ec2 describe-launch-templates' 'ec2 describe-vpc-endpoints' \
  'elbv2 describe-load-balancers' 'elbv2 describe-target-groups' \
  'rds describe-db-instances' 'rds describe-db-subnet-groups' 'rds describe-db-parameter-groups' \
  'autoscaling describe-auto-scaling-groups' 'autoscaling describe-policies' \
  'secretsmanager list-secrets' 'cloudwatch list-dashboards' 'cloudwatch describe-alarms' \
  'iam list-roles' 'iam list-instance-profiles' \
  'ssm get-parameter' 'ssm list-associations' 'ssm list-documents' 'ssm describe-instance-information' \
  'route53 list-resource-record-sets'; do
  grep -Fq "$command" "$temp_dir/scanner.log" || fail "orphan scanner skipped: $command"
done
: > "$temp_dir/scanner-global.log"
env PATH="$temp_dir/scanner-bin:/usr/bin:/bin" AWS_REGION=ap-northeast-2 \
  AIRBOB_SCAN_SCOPE=global FAKE_SCANNER_LOG="$temp_dir/scanner-global.log" \
  "$orphan_scanner" lab-scan > "$temp_dir/scanner-global.out"
grep -Fq 'orphan_scan=clean run_id=lab-scan scope=global' "$temp_dir/scanner-global.out" \
  || fail "global orphan scan did not report its authoritative scope"
global_tag_query=$(grep -m1 'resourcegroupstaggingapi get-resources' "$temp_dir/scanner-global.log")
[[ "$global_tag_query" == *'Key=Project,Values=airbob'* \
  && "$global_tag_query" == *'Key=Persistence,Values=ephemeral'* ]] \
  || fail "global orphan scan omitted the lab-wide ephemeral tag boundary"
[[ "$global_tag_query" != *'Key=RunId,Values='* ]] \
  || fail "global orphan scan incorrectly narrowed the tagged-resource query by RunId"
if env PATH="$temp_dir/scanner-bin:/usr/bin:/bin" AWS_REGION=ap-northeast-2 \
  FAKE_SCANNER_LOG="$temp_dir/scanner.log" FAKE_SCANNER_ORPHAN=ssm-document \
  "$orphan_scanner" lab-scan >/dev/null 2>&1; then
  fail "orphan scanner accepted a run-prefixed SSM document"
fi
: > "$temp_dir/scanner.log"
if env PATH="$temp_dir/scanner-bin:/usr/bin:/bin" AWS_REGION=ap-northeast-2 \
  FAKE_SCANNER_LOG="$temp_dir/scanner.log" FAKE_SCANNER_ORPHAN=alb \
  "$orphan_scanner" lab-scan >/dev/null 2>&1; then
  fail "orphan scanner accepted a run-bound ALB"
fi
grep -Fq 'elbv2 describe-listeners' "$temp_dir/scanner.log" \
  || fail "orphan scanner did not inspect listeners on a surviving run-bound ALB"

: > "$temp_dir/scanner.log"
if env PATH="$temp_dir/scanner-bin:/usr/bin:/bin" AWS_REGION=ap-northeast-2 \
  FAKE_SCANNER_LOG="$temp_dir/scanner.log" FAKE_SCANNER_ORPHAN=private-dns \
  "$orphan_scanner" lab-scan >/dev/null 2>&1; then
  fail "orphan scanner accepted one of the six private lab A records"
fi

: > "$temp_dir/scanner.log"
if env PATH="$temp_dir/scanner-bin:/usr/bin:/bin" AWS_REGION=ap-northeast-2 \
  FAKE_SCANNER_LOG="$temp_dir/scanner.log" FAKE_SCANNER_FAILURE=vpc \
  "$orphan_scanner" lab-scan >/dev/null 2>&1; then
  fail "orphan scanner treated a failed AWS query as an empty result"
fi

expiry_fixture="$temp_dir/expiry-fixture"
mkdir -p "$expiry_fixture"
cp "$expiry_cleanup" "$expiry_fixture/cleanup-expired-lab.sh"
cat > "$expiry_fixture/aws-lab.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'operator %s run=%s force=%s\n' "$*" "${RUN_ID:-}" "${FORCE:-}" >> "${FAKE_EXPIRY_LOG:?}"
EOF
cat > "$temp_dir/scanner-bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' '{"ResourceTagMappingList":[{"Tags":[{"Key":"Persistence","Value":"ephemeral"},{"Key":"RunId","Value":"lab-expired"},{"Key":"ExpiresAt","Value":"2000000000"}]}]}'
EOF
chmod 700 "$expiry_fixture"/*.sh "$temp_dir/scanner-bin/aws"
: > "$temp_dir/expiry.log"
env PATH="$temp_dir/scanner-bin:/usr/bin:/bin" AWS_REGION=ap-northeast-2 \
  AIRBOB_NOW_EPOCH=1999999999 FAKE_EXPIRY_LOG="$temp_dir/expiry.log" \
  "$expiry_fixture/cleanup-expired-lab.sh" > "$temp_dir/expiry-before.out"
grep -Fq 'cleanup_due=false run_id=lab-expired' "$temp_dir/expiry-before.out" \
  || fail "scheduled cleanup became eligible before expiresAt"
[[ ! -s "$temp_dir/expiry.log" ]] || fail "scheduled cleanup invoked down before expiresAt"
env PATH="$temp_dir/scanner-bin:/usr/bin:/bin" AWS_REGION=ap-northeast-2 \
  AIRBOB_NOW_EPOCH=2000000000 FAKE_EXPIRY_LOG="$temp_dir/expiry.log" \
  "$expiry_fixture/cleanup-expired-lab.sh" > "$temp_dir/expiry-at.out"
grep -Fq 'cleanup_due=true run_id=lab-expired' "$temp_dir/expiry-at.out" \
  || fail "scheduled cleanup was not eligible at expiresAt"
grep -Fq 'operator down run=lab-expired force=true' "$temp_dir/expiry.log" \
  || fail "scheduled cleanup did not force the expired run down"

printf '%s\n' 'AWS lab operator contract tests passed'
