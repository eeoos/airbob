#!/usr/bin/env bash
set -euo pipefail
umask 077

COMMAND_DEADLINE_SECONDS=5400
HEARTBEAT_TTL_SECONDS=180
HEARTBEAT_INTERVAL_SECONDS=60

fail() { printf '%s\n' "$1" >&2; exit 1; }

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
lab_root="$repo_root/infra/aws/lab"
lease_script="$repo_root/infra/aws/scripts/orchestration-lease.sh"
backend_helper="$repo_root/infra/aws/scripts/prepare-terraform-backend.sh"
policy_verifier="$repo_root/infra/aws/scripts/enforce-measurement-policy.sh"
dataset_verifier="$repo_root/infra/aws/scripts/verify-dataset-release.sh"
dns_controller="$repo_root/infra/aws/scripts/aws-dns-controller.sh"
aggregator="$script_dir/aggregate-traffic-results.mjs"
toolchain_contract="$repo_root/infra/aws/toolchain.env"

for executable in "$lease_script" "$backend_helper" "$policy_verifier" "$dataset_verifier" "$dns_controller" "$aggregator"; do
  [[ -x "$executable" && ! -L "$executable" ]] || fail "required AWS discovery helper is missing or unsafe"
done
[[ -f "$toolchain_contract" && ! -L "$toolchain_contract" ]] || fail "toolchain contract is missing or unsafe"
# shellcheck disable=SC1090
. "$toolchain_contract"

export AWS_REGION=${AWS_REGION:-$AIRBOB_AWS_REGION}
[[ "$AWS_REGION" == "$AIRBOB_AWS_REGION" ]] || fail "AWS_REGION must equal $AIRBOB_AWS_REGION"
[[ "$AIRBOB_K6_VERSION" == 1.5.0 ]] || fail "k6 version is outside the reviewed discovery contract"
[[ "$AIRBOB_K6_LINUX_AMD64_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail "k6 checksum is invalid"
for command_name in aws curl git jq node tar terraform; do
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
done

harness_commit=$(git -C "$repo_root" rev-parse HEAD)
consumed_paths=(
  infra/aws/scripts/orchestration-lease.sh
  infra/aws/scripts/prepare-terraform-backend.sh
  infra/aws/scripts/enforce-measurement-policy.sh
  infra/aws/scripts/verify-dataset-release.sh
  infra/aws/scripts/aws-dns-controller.sh
  infra/aws/toolchain.env
  load-test/k6/traffic/run-aws-discovery.sh
  load-test/k6/traffic/aggregate-traffic-results.mjs
  load-test/k6/traffic/guest-read.js
  load-test/k6/lib/traffic-benchmark.js
  load-test/k6/lib/benchmark-fixture.js
  load-test/k6/lib/benchmark-manifest.js
  load-test/k6/lib/read-model-benchmark.js
  load-test/mysql/capture-statement-digests.sql
)
for consumed_path in "${consumed_paths[@]}"; do
  git -C "$repo_root" cat-file -e "$harness_commit:$consumed_path" \
    || fail "discovery source is absent from the harness commit"
  git -C "$repo_root" show "$harness_commit:$consumed_path" | cmp -s - "$repo_root/$consumed_path" \
    || fail "discovery source differs from the harness commit"
done

run_id=${RUN_ID:-}
target=${TARGET:-}
rate=${RATE:-}
duration=${DURATION:-}
minimum_samples=${MIN_COMPLETED_SAMPLES:-}
round=${ROUND:-}
run_order=${RUN_ORDER:-}
app_commit=${APP_COMMIT:-}
expected_sql_calls=${EXPECTED_SQL_CALLS_PER_REQUEST:-}
warmup_duration=${WARMUP_DURATION:-10s}
run_label=${RUN_LABEL:-${target}-r${round}-o${run_order}}

[[ "$run_id" =~ ^[a-z0-9][a-z0-9-]{2,31}$ && "$run_id" != *--* && "$run_id" != *- ]] \
  || fail "RUN_ID is required and must be canonical"
[[ "$target" == accommodation-detail ]] \
  || fail "TARGET must equal accommodation-detail for the first AWS discovery slice"
for value_name in rate minimum_samples round run_order expected_sql_calls; do
  value=${!value_name}
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || fail "$value_name must be a positive integer"
  [[ ${#value} -le 9 ]] || fail "$value_name exceeds the bounded discovery contract"
done
[[ "$duration" =~ ^[1-9][0-9]{0,3}s$ ]] || fail "DURATION must be 1-9999 whole seconds for discovery"
[[ "$warmup_duration" =~ ^[1-9][0-9]{0,2}s$ ]] || fail "WARMUP_DURATION must be 1-999 whole seconds"
[[ "$run_label" =~ ^[a-z0-9][a-z0-9-]{2,79}$ ]] || fail "RUN_LABEL is not canonical"
[[ "$app_commit" =~ ^[0-9a-f]{40}$ ]] || fail "APP_COMMIT must be one full Git commit"
duration_seconds=${duration%s}
warmup_seconds=${warmup_duration%s}
[[ "$duration_seconds" -le 1800 ]] || fail "DURATION must be at most 1800s within the fenced command deadline"
[[ "$warmup_seconds" -le 300 ]] || fail "WARMUP_DURATION must be at most 300s"

lab_role_arn=${AWS_LAB_OPERATOR_ROLE_ARN:-arn:aws:iam::$AIRBOB_AWS_ACCOUNT_ID:role/airbob-lab-operator}
[[ "$lab_role_arn" == "arn:aws:iam::$AIRBOB_AWS_ACCOUNT_ID:role/airbob-lab-operator" ]] \
  || fail "lab operator role ARN is outside the foundation boundary"

ensure_lab_role() {
  local caller_arn credentials
  caller_arn=$(aws sts get-caller-identity --query Arn --output text --region "$AWS_REGION")
  case "$caller_arn" in
    arn:aws:sts::$AIRBOB_AWS_ACCOUNT_ID:assumed-role/airbob-lab-operator/*) return ;;
  esac
  credentials=$(aws sts assume-role --role-arn "$lab_role_arn" \
    --role-session-name "airbob-measure-${GITHUB_RUN_ID:-local}-$(date +%s)" \
    --duration-seconds "$COMMAND_DEADLINE_SECONDS" \
    --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' \
    --output text --region "$AWS_REGION") || fail "cannot assume the lab operator role"
  read -r AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN <<EOF
$credentials
EOF
  export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
  caller_arn=$(aws sts get-caller-identity --query Arn --output text --region "$AWS_REGION")
  [[ "$caller_arn" == arn:aws:sts::$AIRBOB_AWS_ACCOUNT_ID:assumed-role/airbob-lab-operator/* ]] \
    || fail "AWS discovery requires assumed-role/airbob-lab-operator credentials"
}
ensure_lab_role

account_id=$(aws sts get-caller-identity --query Account --output text --region "$AWS_REGION")
[[ "$account_id" == "$AIRBOB_AWS_ACCOUNT_ID" ]] || fail "active AWS account is outside the lab boundary"
lab_contract=$(aws ssm get-parameter --name /airbob/performance-lab/foundation/lab-contract \
  --query 'Parameter.Value' --output text --region "$AWS_REGION")
jq -e '.schemaVersion == 1 and .account_id == "942632789808" and .region == "ap-northeast-2"' \
  <<<"$lab_contract" >/dev/null || fail "foundation lab contract is invalid"

lease_table=$(jq -er '.lease_table_name' <<<"$lab_contract")
lease_lock_id=$(jq -er '.lease_lock_id' <<<"$lab_contract")
evidence_bucket=$(jq -er '.evidence_bucket_name' <<<"$lab_contract")
dataset_bucket=$(jq -er '.dataset_bucket_name' <<<"$lab_contract")
state_bucket=$(jq -er '.state_bucket_name' <<<"$lab_contract")
lab_state_key=$(jq -er '.lab_state_key' <<<"$lab_contract")
[[ "$lease_table" == airbob-performance-lab-orchestration-lease \
  && "$lease_lock_id" == airbob-performance-lab \
  && "$evidence_bucket" == "airbob-performance-lab-evidence-$AIRBOB_AWS_ACCOUNT_ID" \
  && "$dataset_bucket" == "airbob-performance-lab-dataset-$AIRBOB_AWS_ACCOUNT_ID" \
  && "$state_bucket" == "$AIRBOB_STATE_BUCKET_NAME" \
  && "$lab_state_key" == "$AIRBOB_STATE_KEY_LAB" ]] \
  || fail "foundation lab-state boundary is invalid"

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-discovery.XXXXXX")
heartbeat_pid=''
watchdog_pid=''
lease_acquired=false

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ -n "$heartbeat_pid" ]]; then
    kill "$heartbeat_pid" 2>/dev/null || true
    wait "$heartbeat_pid" 2>/dev/null || true
  fi
  if [[ -n "$watchdog_pid" ]]; then
    kill "$watchdog_pid" 2>/dev/null || true
    wait "$watchdog_pid" 2>/dev/null || true
  fi
  if [[ "$lease_acquired" == true ]]; then
    "$lease_script" release "$lease_table" "$lease_lock_id" "$lease_owner" \
      "$fencing_token" "$run_id" measurement >/dev/null 2>&1 || true
  fi
  rm -rf "$temp_dir"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

lease_owner=${LEASE_OWNER:-${GITHUB_REPOSITORY:-local}/${GITHUB_RUN_ID:-$(id -un)}:${GITHUB_RUN_ATTEMPT:-1}}
[[ "$lease_owner" =~ ^[A-Za-z0-9._:@/-]{3,128}$ ]] || fail "lease owner is not canonical"
token_output=$("$lease_script" acquire "$lease_table" "$lease_lock_id" "$lease_owner" \
  "$run_id" measurement "$HEARTBEAT_TTL_SECONDS" "$COMMAND_DEADLINE_SECONDS")
fencing_token=${token_output#fencing_token=}
[[ "$fencing_token" =~ ^[1-9][0-9]*$ ]] || fail "lease did not issue a fencing token"
lease_acquired=true
parent_pid=$$
(
  while sleep "$HEARTBEAT_INTERVAL_SECONDS"; do
    "$lease_script" heartbeat "$lease_table" "$lease_lock_id" "$lease_owner" \
      "$fencing_token" "$run_id" measurement "$HEARTBEAT_TTL_SECONDS" >/dev/null \
      || { kill -TERM "$parent_pid" 2>/dev/null || true; exit 1; }
  done
) &
heartbeat_pid=$!
(
  sleep "$COMMAND_DEADLINE_SECONDS"
  kill -TERM "$parent_pid" 2>/dev/null || true
) &
watchdog_pid=$!

assert_lease() {
  "$lease_script" assert "$lease_table" "$lease_lock_id" "$lease_owner" \
    "$fencing_token" "$run_id" measurement >/dev/null
}

assert_lease
"$backend_helper" lab >/dev/null
terraform -chdir="$lab_root" init -input=false -lockfile=readonly \
  -backend-config=backend.generated.hcl >/dev/null
phase2=$(terraform -chdir="$lab_root" output -json phase2_contract)
phase3=$(terraform -chdir="$lab_root" output -json phase3_contract)
phase4=$(terraform -chdir="$lab_root" output -json phase4_contract)

jq -e --arg run "$run_id" '.run_id == $run and .deployment_phase == "data-ready"' \
  <<<"$phase2" >/dev/null || fail "active Terraform state is not the requested data-ready run"
jq -e '.release_kind == "pipeline-rehearsal" and .data_ready == true' \
  <<<"$phase3" >/dev/null || fail "Phase 3 is not a data-ready pipeline rehearsal"
jq -e '.app_enabled == true and .measurement_policy == "isolated-read" and .load_generator_enabled == true' \
  <<<"$phase4" >/dev/null || fail "Phase 4 is not an isolated-read load-generator run"

debezium_instance_id=$(jq -er '.services.debezium' <<<"$phase2")
kafka_instance_id=$(jq -er '.services.kafka' <<<"$phase2")
monitoring_instance_id=$(jq -er '.services.monitoring' <<<"$phase2")
loadgen_instance_id=$(jq -er '.load_generator_instance_id' <<<"$phase4")
rds_instance_id=$(jq -er '.rds_instance_id' <<<"$phase3")
rds_endpoint=$(jq -er '.rds_endpoint' <<<"$phase3")
app_instance_count=$(jq -er '.capacity.desired' <<<"$phase4")
asg_name=$(jq -er '.auto_scaling_group_name' <<<"$phase4")
alb_arn=$(jq -er '.alb_arn' <<<"$phase4")
alb_dns_name=$(jq -er '.alb_dns_name' <<<"$phase4")
dataset_release=$(jq -er '.dataset_release' <<<"$phase3")
for instance_id in "$debezium_instance_id" "$kafka_instance_id" "$monitoring_instance_id" "$loadgen_instance_id"; do
  [[ "$instance_id" =~ ^i-[0-9a-f]{8,17}$ ]] || fail "Terraform returned an invalid measurement host"
done
[[ "$rds_endpoint" =~ ^[A-Za-z0-9.-]+\.rds\.amazonaws\.com$ ]] || fail "Terraform returned an invalid RDS endpoint"
[[ "$rds_instance_id" == "airbob-$run_id" ]] || fail "Terraform returned an invalid RDS instance"
[[ "$app_instance_count" =~ ^[1-9][0-9]*$ ]] || fail "Terraform returned an invalid application instance count"
[[ "$asg_name" =~ ^airbob-[a-z0-9-]+-app$ ]] || fail "Terraform returned an invalid application ASG"
[[ "$alb_arn" =~ ^arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/ ]] \
  || fail "Terraform returned an invalid application ALB"
[[ "$alb_dns_name" =~ ^[A-Za-z0-9.-]+\.elb\.amazonaws\.com$ ]] \
  || fail "Terraform returned an invalid application ALB DNS name"

current_app_instance_count() {
  aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$asg_name" \
    --query "AutoScalingGroups[0].Instances[?LifecycleState=='InService' && HealthStatus=='Healthy'] | length(@)" \
    --output text --region "$AWS_REGION" --no-cli-pager
}

observed_app_instance_count=$(current_app_instance_count)
[[ "$observed_app_instance_count" == "$app_instance_count" ]] \
  || fail "actual application capacity does not match the Terraform contract"

operator_manifest="$temp_dir/operator.json"
aws s3api get-object --bucket "$evidence_bucket" --key "runs/$run_id/operator.json" \
  "$operator_manifest" --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "run manifest is missing"
jq -e --arg run "$run_id" --arg release "$dataset_release" '
  .schemaVersion == 1 and .runId == $run and .datasetRelease == $release and
  .policy == "isolated-read" and .loadGeneratorEnabled == true and
  (.bundleCommit | test("^[0-9a-f]{40}$")) and
  (.imageDigest | test("^sha256:[0-9a-f]{64}$"))
' "$operator_manifest" >/dev/null || fail "run manifest is invalid for discovery"

bundle_commit=$(jq -er '.bundleCommit' "$operator_manifest")
[[ "$harness_commit" == "$bundle_commit" ]] || fail "discovery harness HEAD must equal the deployed bundle commit"
image_digest=$(jq -er '.imageDigest' "$operator_manifest")
resource_fencing_token=$(jq -er '.fencingToken' "$operator_manifest")
[[ "$resource_fencing_token" =~ ^[1-9][0-9]*$ ]] || fail "run manifest fencing token is invalid"
app_repository=$(jq -er '.ecr_repositories.APP_IMAGE.url' <<<"$lab_contract")
[[ "$app_repository" == "$AIRBOB_AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/airbob-repo" ]] \
  || fail "application repository is outside the foundation boundary"
app_repository_name=${app_repository#*/}
tagged_digest=$(aws ecr describe-images --repository-name "$app_repository_name" \
  --image-ids "imageTag=$app_commit" --query 'imageDetails[0].imageDigest' \
  --output text --region "$AWS_REGION" --no-cli-pager) || fail "application commit tag is unavailable"
[[ "$tagged_digest" == "$image_digest" ]] || fail "APP_COMMIT does not resolve to the deployed IMAGE_DIGEST"

dataset_root="$temp_dir/dataset-release"
mkdir -p "$dataset_root/benchmark" "$dataset_root/mysql"
dataset_manifest="$dataset_root/manifest.json"
benchmark_manifest="$dataset_root/benchmark/manifest.json"
aws s3api get-object --bucket "$dataset_bucket" --key "datasets/$dataset_release/manifest.json" \
  "$dataset_manifest" --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "dataset manifest is unavailable"
aws s3api get-object --bucket "$dataset_bucket" --key "datasets/$dataset_release/benchmark/manifest.json" \
  "$benchmark_manifest" --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "benchmark manifest is unavailable"
aws s3api get-object --bucket "$dataset_bucket" --key "datasets/$dataset_release/mysql/sha256.txt" \
  "$dataset_root/mysql/sha256.txt" --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "dataset checksum is unavailable"
"$dataset_verifier" "$dataset_root" "$dataset_release" pipeline-rehearsal --metadata-only >/dev/null
dataset_manifest_sha256=$(sha256_file "$dataset_manifest")
benchmark_manifest_sha256=$(sha256_file "$benchmark_manifest")
[[ "$dataset_manifest_sha256" == "$(jq -er '.datasetManifestSha256' "$operator_manifest")" ]] \
  || fail "dataset manifest changed after lab creation"
[[ "$benchmark_manifest_sha256" == "$(jq -er '.source.benchmarkManifestSha256' "$dataset_manifest")" ]] \
  || fail "benchmark manifest is not bound to the selected dataset"

bootstrap_receipt="$temp_dir/data-bootstrap-receipt.json"
aws s3api get-object --bucket "$evidence_bucket" \
  --key "data-bootstrap/$run_id/$dataset_release.json" "$bootstrap_receipt" \
  --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "data bootstrap receipt is unavailable"
jq -e --arg run "$run_id" --arg release "$dataset_release" --arg sha "$dataset_manifest_sha256" '
  .schemaVersion == 1 and .runId == $run and .datasetRelease == $release and
  .releaseKind == "pipeline-rehearsal" and .datasetManifestSha256 == $sha and
  .flywayVersion == "27" and .outboxState == "empty"
' "$bootstrap_receipt" >/dev/null || fail "data bootstrap receipt does not attest the discovery dataset"
expected_flyway_version=$(jq -er '.flywayVersion' "$bootstrap_receipt")
[[ "$expected_flyway_version" =~ ^[1-9][0-9]*$ ]] \
  || fail "data bootstrap receipt Flyway version is not canonical"

remote_source_paths=(
  load-test/k6/traffic/run-aws-discovery.sh
  load-test/k6/traffic/aggregate-traffic-results.mjs
  load-test/k6/traffic/guest-read.js
  load-test/k6/lib/traffic-benchmark.js
  load-test/k6/lib/benchmark-fixture.js
  load-test/k6/lib/benchmark-manifest.js
  load-test/k6/lib/read-model-benchmark.js
  load-test/mysql/capture-statement-digests.sql
)
source_archive="$temp_dir/discovery-source.tar"
git -C "$repo_root" archive --format=tar --output="$source_archive" \
  "$harness_commit" -- "${remote_source_paths[@]}"
source_sha256=$(sha256_file "$source_archive")

k6_archive_name="k6-v$AIRBOB_K6_VERSION-linux-amd64.tar.gz"
k6_archive="$temp_dir/$k6_archive_name"
curl --fail --silent --show-error --location --retry 5 \
  --output "$k6_archive" \
  "https://github.com/grafana/k6/releases/download/v$AIRBOB_K6_VERSION/$k6_archive_name"
[[ "$(sha256_file "$k6_archive")" == "$AIRBOB_K6_LINUX_AMD64_SHA256" ]] \
  || fail "downloaded k6 archive failed its pinned checksum"
[[ "$(tar -tzf "$k6_archive")" == $'k6-v1.5.0-linux-amd64/\nk6-v1.5.0-linux-amd64/k6' ]] \
  || fail "downloaded k6 archive has an unexpected member set"

input_prefix="measurement-inputs/$run_id/$harness_commit/$run_label"
measurement_prefix="measurements/$run_id/$run_label"
for input in "$source_archive" "$k6_archive"; do
  assert_lease
  aws s3api put-object --bucket "$evidence_bucket" \
    --key "$input_prefix/${input##*/}" --body "$input" --tagging Retention=raw \
    --server-side-encryption AES256 --if-none-match '*' \
    --region "$AWS_REGION" --no-cli-pager >/dev/null
done

rds_secret_arn=$(aws rds describe-db-instances --db-instance-identifier "$rds_instance_id" \
  --query 'DBInstances[0].MasterUserSecret.SecretArn' --output text \
  --region "$AWS_REGION" --no-cli-pager)
[[ "$rds_secret_arn" =~ ^arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db- ]] \
  || fail "RDS secret ARN is outside the lab boundary"
assert_lease
"$policy_verifier" isolated-read "$run_id" "$debezium_instance_id" "$kafka_instance_id" \
  "$rds_endpoint" "$rds_secret_arn" "$evidence_bucket" "$fencing_token" >/dev/null
AWS_DNS_CONTROLLER_ROLE_ARN="arn:aws:iam::$AIRBOB_AWS_ACCOUNT_ID:role/airbob-dns-controller" \
OCI_ORIGIN_IPV4="${OCI_ORIGIN_IPV4:-}" AWS_ALB_ARN="$alb_arn" AWS_ALB_DNS_NAME="$alb_dns_name" \
ALB_FENCING_TOKEN="$resource_fencing_token" LEASE_TABLE="$lease_table" LEASE_LOCK_ID="$lease_lock_id" \
LEASE_OWNER="$lease_owner" FENCING_TOKEN="$fencing_token" RUN_ID="$run_id" LEASE_COMMAND=measurement \
  "$dns_controller" verify aws >/dev/null

send_and_wait() {
  local instance_id=$1 comment=$2 marker=$3 command_body=$4
  local parameters command_id invocation
  assert_lease
  parameters=$(jq -nc --arg command "$command_body" '{commands:[$command]}')
  command_id=$(aws ssm send-command --instance-ids "$instance_id" \
    --document-name AWS-RunShellScript --comment "$comment" --parameters "$parameters" \
    --query 'Command.CommandId' --output text --region "$AWS_REGION" --no-cli-pager)
  [[ "$command_id" =~ ^[0-9a-f-]{36}$ ]] || fail "SSM did not return a command id"
  aws ssm wait command-executed --command-id "$command_id" --instance-id "$instance_id" \
    --region "$AWS_REGION" --no-cli-pager
  invocation=$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" \
    --output json --region "$AWS_REGION" --no-cli-pager)
  jq -e '.Status == "Success"' <<<"$invocation" >/dev/null || fail "AWS discovery SSM command failed"
  jq -er '.StandardOutputContent' <<<"$invocation" | grep -Fq "$marker" \
    || fail "AWS discovery SSM completion marker is missing"
}

download_measurement() {
  local name=$1 destination=$2
  aws s3api get-object --bucket "$evidence_bucket" --key "$measurement_prefix/$name" \
    "$destination" --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "measurement artifact is unavailable"
}

capture_flyway() {
  local phase=$1 marker=$2
  local command_body
  command_body=$(cat <<EOF
set -euo pipefail
umask 077
work='$remote_root/provenance'
install -d -m 700 "\$work"
secret_file=\$(mktemp /run/airbob-discovery-secret.XXXXXX)
cleanup_secret() { rm -f "\$secret_file"; }
trap cleanup_secret EXIT HUP INT TERM
aws --region '$AWS_REGION' secretsmanager get-secret-value --secret-id '$rds_secret_arn' --query SecretString --output text > "\$secret_file"
chmod 600 "\$secret_file"
username=\$(jq -er '.username' "\$secret_file")
password=\$(jq -er '.password' "\$secret_file")
flyway_version=\$(MYSQL_PWD="\$password" mysql --protocol=TCP --host='$rds_endpoint' --port=3306 --user="\$username" --ssl --batch --raw --skip-column-names --execute='SELECT version FROM airbobdb.flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1')
jq -n --arg flywayVersion "\$flyway_version" '{schemaVersion:1,flywayVersion:\$flywayVersion}' > "\$work/flyway-$phase.json"
aws --region '$AWS_REGION' s3api put-object --bucket '$evidence_bucket' --key '$measurement_prefix/flyway-$phase.json' --body "\$work/flyway-$phase.json" --tagging Retention=raw --server-side-encryption AES256 --content-type application/json --if-none-match '*' >/dev/null
printf '%s\n' '$marker'
EOF
)
  send_and_wait "$debezium_instance_id" "Airbob discovery Flyway $phase" "$marker" "$command_body"
}

remote_root="/var/lib/airbob/measurements/$run_id/$run_label"
stage_command=$(cat <<EOF
set -euo pipefail
umask 077
test -f /var/lib/airbob/load-generator-ready
test ! -e '$remote_root'
install -d -m 700 '$remote_root'
aws --region '$AWS_REGION' s3api get-object --bucket '$evidence_bucket' --key '$input_prefix/discovery-source.tar' '$remote_root/discovery-source.tar' >/dev/null
aws --region '$AWS_REGION' s3api get-object --bucket '$evidence_bucket' --key '$input_prefix/$k6_archive_name' '$remote_root/$k6_archive_name' >/dev/null
aws --region '$AWS_REGION' s3api get-object --bucket '$dataset_bucket' --key 'datasets/$dataset_release/benchmark/manifest.json' '$remote_root/benchmark-manifest.json' >/dev/null
printf '%s  %s\n' '$source_sha256' '$remote_root/discovery-source.tar' | sha256sum --check --status
printf '%s  %s\n' '$AIRBOB_K6_LINUX_AMD64_SHA256' '$remote_root/$k6_archive_name' | sha256sum --check --status
printf '%s  %s\n' '$benchmark_manifest_sha256' '$remote_root/benchmark-manifest.json' | sha256sum --check --status
tar -xf '$remote_root/discovery-source.tar' -C '$remote_root'
tar -xzf '$remote_root/$k6_archive_name' -C '$remote_root'
test "\$('$remote_root/k6-v1.5.0-linux-amd64/k6' version | awk '{print \$2}')" = 'v1.5.0'
install -d -m 700 '$remote_root/build/k6/traffic'
printf '%s\n' AIRBOB_DISCOVERY_STAGE_OK
EOF
)
send_and_wait "$loadgen_instance_id" "Airbob discovery stage" AIRBOB_DISCOVERY_STAGE_OK "$stage_command"

capture_flyway before AIRBOB_DISCOVERY_FLYWAY_BEFORE_OK
download_measurement flyway-before.json "$temp_dir/flyway-before.json"
[[ "$(jq -er '.flywayVersion' "$temp_dir/flyway-before.json")" == "$expected_flyway_version" ]] \
  || fail "Flyway version changed before discovery"

k6_environment=$(cat <<EOF
ROLE=guest TARGET=$target RATE=$rate DURATION=$duration MIN_COMPLETED_SAMPLES=$minimum_samples ROUND=$round RUN_ORDER=$run_order RUN_LABEL=$run_label APP_COMMIT=$app_commit APP_INSTANCE_COUNT=$app_instance_count BASE_URL=https://api.airbob.cloud BENCHMARK_MANIFEST=$remote_root/benchmark-manifest.json
EOF
)
inspect_command=$(cat <<EOF
set -euo pipefail
cd '$remote_root'
env MODE=inspect $k6_environment '$remote_root/k6-v1.5.0-linux-amd64/k6' run load-test/k6/traffic/guest-read.js >/dev/null
printf '%s\n' AIRBOB_DISCOVERY_INSPECT_OK
EOF
)
send_and_wait "$loadgen_instance_id" "Airbob discovery inspect" AIRBOB_DISCOVERY_INSPECT_OK "$inspect_command"

warmup_command=$(cat <<EOF
set -euo pipefail
cd '$remote_root'
env MODE=warmup $k6_environment DURATION='$warmup_duration' '$remote_root/k6-v1.5.0-linux-amd64/k6' run load-test/k6/traffic/guest-read.js >/dev/null
printf '%s\n' AIRBOB_DISCOVERY_WARMUP_OK
EOF
)
send_and_wait "$loadgen_instance_id" "Airbob discovery warmup" AIRBOB_DISCOVERY_WARMUP_OK "$warmup_command"

snapshot_sql() {
  local snapshot_name=$1 marker=$2
  local snapshot_command
  snapshot_command=$(cat <<EOF
set -euo pipefail
umask 077
work='$remote_root/sql'
install -d -m 700 "\$work"
if [[ ! -f "\$work/load-test/mysql/capture-statement-digests.sql" ]]; then
  aws --region '$AWS_REGION' s3api get-object --bucket '$evidence_bucket' --key '$input_prefix/discovery-source.tar' "\$work/discovery-source.tar" >/dev/null
  printf '%s  %s\n' '$source_sha256' "\$work/discovery-source.tar" | sha256sum --check --status
  tar -xf "\$work/discovery-source.tar" -C "\$work" load-test/mysql/capture-statement-digests.sql
fi
secret_file=\$(mktemp /run/airbob-discovery-secret.XXXXXX)
cleanup_secret() { rm -f "\$secret_file"; }
trap cleanup_secret EXIT HUP INT TERM
aws --region '$AWS_REGION' secretsmanager get-secret-value --secret-id '$rds_secret_arn' --query SecretString --output text > "\$secret_file"
chmod 600 "\$secret_file"
username=\$(jq -er '.username' "\$secret_file")
password=\$(jq -er '.password' "\$secret_file")
MYSQL_PWD="\$password" mysql --protocol=TCP --host='$rds_endpoint' --port=3306 --user="\$username" --ssl --batch --raw --skip-column-names < "\$work/load-test/mysql/capture-statement-digests.sql" > "\$work/$snapshot_name.jsonl"
test -s "\$work/$snapshot_name.jsonl"
aws --region '$AWS_REGION' s3api put-object --bucket '$evidence_bucket' --key '$measurement_prefix/$snapshot_name.jsonl' --body "\$work/$snapshot_name.jsonl" --tagging Retention=raw --server-side-encryption AES256 --if-none-match '*' >/dev/null
printf '%s\n' '$marker'
EOF
)
  send_and_wait "$debezium_instance_id" "Airbob discovery SQL $snapshot_name" "$marker" "$snapshot_command"
}

snapshot_sql idle-before AIRBOB_DISCOVERY_IDLE_BEFORE_OK
sleep "$duration_seconds"
snapshot_sql idle-after AIRBOB_DISCOVERY_IDLE_AFTER_OK
download_measurement idle-before.jsonl "$temp_dir/idle-before.jsonl"
download_measurement idle-after.jsonl "$temp_dir/idle-after.jsonl"
node "$aggregator" verify-idle --before "$temp_dir/idle-before.jsonl" \
  --after "$temp_dir/idle-after.jsonl" >/dev/null

snapshot_sql before AIRBOB_DISCOVERY_BASELINE_OK
measure_command=$(cat <<EOF
set -euo pipefail
cd '$remote_root'
start_epoch_ms=\$(date +%s%3N)
env MODE=measure $k6_environment '$remote_root/k6-v1.5.0-linux-amd64/k6' run load-test/k6/traffic/guest-read.js >/dev/null
end_epoch_ms=\$(date +%s%3N)
jq -n --argjson start "\$start_epoch_ms" --argjson end "\$end_epoch_ms" '{schemaVersion:1,startEpochMs:\$start,endEpochMs:\$end}' > window.json
aws --region '$AWS_REGION' s3api put-object --bucket '$evidence_bucket' --key '$measurement_prefix/k6.json' --body 'build/k6/traffic/$run_label.json' --tagging Retention=raw --server-side-encryption AES256 --content-type application/json --if-none-match '*' >/dev/null
aws --region '$AWS_REGION' s3api put-object --bucket '$evidence_bucket' --key '$measurement_prefix/window.json' --body window.json --tagging Retention=raw --server-side-encryption AES256 --content-type application/json --if-none-match '*' >/dev/null
printf '%s\n' AIRBOB_DISCOVERY_MEASURE_OK
EOF
)
send_and_wait "$loadgen_instance_id" "Airbob discovery measure" AIRBOB_DISCOVERY_MEASURE_OK "$measure_command"
snapshot_sql after AIRBOB_DISCOVERY_AFTER_OK
capture_flyway after AIRBOB_DISCOVERY_FLYWAY_AFTER_OK
download_measurement window.json "$temp_dir/window.json"
jq -e '.schemaVersion == 1 and (.startEpochMs | type == "number") and (.endEpochMs | type == "number") and .startEpochMs > 0 and .endEpochMs > .startEpochMs' \
  "$temp_dir/window.json" >/dev/null || fail "measurement window is invalid"
window_start_epoch_ms=$(jq -er '.startEpochMs' "$temp_dir/window.json")
window_end_epoch_ms=$(jq -er '.endEpochMs' "$temp_dir/window.json")
window_start_seconds=$(jq -nr --argjson value "$window_start_epoch_ms" '$value / 1000')
window_end_seconds=$(jq -nr --argjson value "$window_end_epoch_ms" '$value / 1000')

prometheus_command=$(cat <<EOF
set -euo pipefail
umask 077
work='$remote_root/prometheus'
install -d -m 700 "\$work"
start='$window_start_seconds'
end='$window_end_seconds'
query_range() {
  curl --fail --silent --show-error --get 'http://127.0.0.1:9090/api/v1/query_range' --data-urlencode "query=\$1" --data-urlencode "start=\$start" --data-urlencode "end=\$end" --data-urlencode 'step=1'
}
query_range 'http_server_requests_seconds_count{job="airbob",method="GET",uri="/api/v1/accommodations/{accommodationId}"}' > "\$work/request.json"
query_range 'app_query_per_request_queries_sum{job="airbob",http_method="GET",path="/api/v1/accommodations/{accommodationId}",query_type="TOTAL"}' > "\$work/query.json"
query_range 'hikaricp_connections_pending{job="airbob"}' > "\$work/hikari.json"
jq -n --argjson start '$window_start_epoch_ms' --argjson end '$window_end_epoch_ms' --slurpfile request "\$work/request.json" --slurpfile query "\$work/query.json" --slurpfile hikari "\$work/hikari.json" '{schemaVersion:1,startEpochMs:\$start,endEpochMs:\$end,queries:{requestCount:\$request[0],queryCount:\$query[0],hikariPending:\$hikari[0]}}' > "\$work/prometheus.json"
aws --region '$AWS_REGION' s3api put-object --bucket '$evidence_bucket' --key '$measurement_prefix/prometheus.json' --body "\$work/prometheus.json" --tagging Retention=raw --server-side-encryption AES256 --content-type application/json --if-none-match '*' >/dev/null
printf '%s\n' AIRBOB_DISCOVERY_PROMETHEUS_OK
EOF
)
send_and_wait "$monitoring_instance_id" "Airbob discovery Prometheus" AIRBOB_DISCOVERY_PROMETHEUS_OK "$prometheus_command"

for artifact in k6.json before.jsonl after.jsonl prometheus.json; do
  download_measurement "$artifact" "$temp_dir/$artifact"
done

post_dataset_manifest="$temp_dir/post-dataset-manifest.json"
post_benchmark_manifest="$temp_dir/post-benchmark-manifest.json"
aws s3api get-object --bucket "$dataset_bucket" --key "datasets/$dataset_release/manifest.json" \
  "$post_dataset_manifest" --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "post-run dataset manifest is unavailable"
aws s3api get-object --bucket "$dataset_bucket" --key "datasets/$dataset_release/benchmark/manifest.json" \
  "$post_benchmark_manifest" --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "post-run benchmark manifest is unavailable"
post_dataset_manifest_sha256=$(sha256_file "$post_dataset_manifest")
post_benchmark_manifest_sha256=$(sha256_file "$post_benchmark_manifest")
post_image_digest=$(aws ecr describe-images --repository-name "$app_repository_name" \
  --image-ids "imageTag=$app_commit" --query 'imageDetails[0].imageDigest' \
  --output text --region "$AWS_REGION" --no-cli-pager) || fail "post-run application commit tag is unavailable"
post_app_instance_count=$(current_app_instance_count)
download_measurement flyway-after.json "$temp_dir/flyway-after.json"
post_flyway_version=$(jq -er '.flywayVersion' "$temp_dir/flyway-after.json")
[[ "$post_dataset_manifest_sha256" =~ ^[0-9a-f]{64}$ \
  && "$post_benchmark_manifest_sha256" =~ ^[0-9a-f]{64}$ \
  && "$post_image_digest" =~ ^sha256:[0-9a-f]{64}$ \
  && "$post_app_instance_count" =~ ^[1-9][0-9]*$ \
  && "$post_flyway_version" =~ ^[1-9][0-9]*$ ]] || fail "post-run provenance is malformed"

metadata="$temp_dir/metadata.json"
jq -n --arg runId "$run_id" --arg datasetRelease "$dataset_release" \
  --arg datasetManifestSha256 "$dataset_manifest_sha256" \
  --arg benchmarkManifestSha256 "$benchmark_manifest_sha256" --arg appCommit "$app_commit" \
  --arg imageDigest "$image_digest" --arg harnessCommit "$harness_commit" \
  --arg flywayVersion "$expected_flyway_version" \
  --argjson appInstanceCount "$app_instance_count" --argjson expectedSqlCallsPerRequest "$expected_sql_calls" \
  --arg postDatasetManifestSha256 "$post_dataset_manifest_sha256" \
  --arg postBenchmarkManifestSha256 "$post_benchmark_manifest_sha256" \
  --arg postImageDigest "$post_image_digest" --arg postFlywayVersion "$post_flyway_version" \
  --argjson postAppInstanceCount "$post_app_instance_count" \
  --argjson window "$(cat "$temp_dir/window.json")" \
  '{schemaVersion:1,releaseKind:"pipeline-rehearsal",claimScope:"pipeline-only",runId:$runId,datasetRelease:$datasetRelease,datasetManifestSha256:$datasetManifestSha256,benchmarkManifestSha256:$benchmarkManifestSha256,appCommit:$appCommit,imageDigest:$imageDigest,harnessCommit:$harnessCommit,flywayVersion:$flywayVersion,appInstanceCount:$appInstanceCount,target:"accommodation-detail",expectedSqlCallsPerRequest:$expectedSqlCallsPerRequest,window:{startEpochMs:$window.startEpochMs,endEpochMs:$window.endEpochMs},postRun:{datasetManifestSha256:$postDatasetManifestSha256,benchmarkManifestSha256:$postBenchmarkManifestSha256,imageDigest:$postImageDigest,flywayVersion:$postFlywayVersion,appInstanceCount:$postAppInstanceCount}}' \
  > "$metadata"

artifact_dir="$repo_root/build/k6/traffic"
mkdir -p "$artifact_dir"
aggregate_relative="build/k6/traffic/$run_label-aggregate.json"
node "$aggregator" --metadata "$metadata" --k6 "$temp_dir/k6.json" \
  --prometheus "$temp_dir/prometheus.json" --idle-before "$temp_dir/idle-before.jsonl" \
  --idle-after "$temp_dir/idle-after.jsonl" --before "$temp_dir/before.jsonl" \
  --after "$temp_dir/after.jsonl" --output "$aggregate_relative"
aggregate="$repo_root/$aggregate_relative"
assert_lease
aws s3api put-object --bucket "$evidence_bucket" --key "$measurement_prefix/aggregate.json" \
  --body "$aggregate" --tagging Retention=summary --server-side-encryption AES256 \
  --content-type application/json --if-none-match '*' --region "$AWS_REGION" --no-cli-pager >/dev/null

printf 'run_id=%s\nrun_label=%s\nclaim_scope=pipeline-only\nartifact=%s\n' \
  "$run_id" "$run_label" "$aggregate_relative"
