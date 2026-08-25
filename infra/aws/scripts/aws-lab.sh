#!/usr/bin/env bash
set -euo pipefail
umask 077

COMMAND_DEADLINE_SECONDS=5400
INSTANCE_REFRESH_TIMEOUT_SECONDS=900
HEARTBEAT_TTL_SECONDS=180
HEARTBEAT_INTERVAL_SECONDS=60

fail() { printf '%s\n' "$1" >&2; exit 1; }

valid_ipv4() {
  local address=$1 octet
  local -a octets
  [[ "$address" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 1
  IFS=. read -r -a octets <<<"$address"
  [[ "${#octets[@]}" -eq 4 ]] || return 1
  for octet in "${octets[@]}"; do
    [[ "$octet" == 0 || "$octet" != 0* ]] || return 1
    ((10#$octet <= 255)) || return 1
  done
}

[[ "$#" -eq 1 ]] || fail "usage: aws-lab.sh up|status|switch|down"
action=$1
case "$action" in up|status|switch|down) ;; *) fail "unsupported AWS lab action" ;; esac

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
lab_root="$repo_root/infra/aws/lab"
lease_script="$script_dir/orchestration-lease.sh"
dns_controller="$script_dir/aws-dns-controller.sh"
orphan_scanner="$script_dir/scan-lab-orphans.sh"
backend_helper="$script_dir/prepare-terraform-backend.sh"
network_verifier="$script_dir/verify-network-egress.sh"
policy_verifier="$script_dir/enforce-measurement-policy.sh"
toolchain_contract="$repo_root/infra/aws/toolchain.env"

for executable in "$lease_script" "$dns_controller" "$orphan_scanner" "$backend_helper" "$network_verifier" "$policy_verifier"; do
  [[ -x "$executable" && ! -L "$executable" ]] || fail "required AWS lab helper is missing or unsafe"
done
[[ -f "$toolchain_contract" && ! -L "$toolchain_contract" ]] || fail "toolchain contract is missing or unsafe"
# shellcheck disable=SC1090
. "$toolchain_contract"

export AWS_REGION=${AWS_REGION:-$AIRBOB_AWS_REGION}
[[ "$AWS_REGION" == "$AIRBOB_AWS_REGION" ]] || fail "AWS_REGION must equal $AIRBOB_AWS_REGION"
command -v aws >/dev/null 2>&1 || fail "AWS CLI is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"
command -v terraform >/dev/null 2>&1 || fail "Terraform is required"

lab_role_arn=${AWS_LAB_OPERATOR_ROLE_ARN:-arn:aws:iam::$AIRBOB_AWS_ACCOUNT_ID:role/airbob-lab-operator}
[[ "$lab_role_arn" == "arn:aws:iam::$AIRBOB_AWS_ACCOUNT_ID:role/airbob-lab-operator" ]] \
  || fail "lab operator role ARN is outside the foundation boundary"

ensure_lab_role() {
  local caller_arn credentials
  caller_arn=$(aws sts get-caller-identity --query Arn --output text --region "$AWS_REGION")
  case "$caller_arn" in
    arn:aws:sts::$AIRBOB_AWS_ACCOUNT_ID:assumed-role/airbob-lab-operator/*) return ;;
  esac
  credentials=$(aws sts assume-role \
    --role-arn "$lab_role_arn" \
    --role-session-name "airbob-lab-${GITHUB_RUN_ID:-local}-$(date +%s)" \
    --duration-seconds "$COMMAND_DEADLINE_SECONDS" \
    --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' \
    --output text --region "$AWS_REGION") || fail "cannot assume the lab operator role"
  read -r AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN <<EOF
$credentials
EOF
  export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
  caller_arn=$(aws sts get-caller-identity --query Arn --output text --region "$AWS_REGION")
  [[ "$caller_arn" == arn:aws:sts::$AIRBOB_AWS_ACCOUNT_ID:assumed-role/airbob-lab-operator/* ]] \
    || fail "lab operations require assumed-role/airbob-lab-operator credentials"
}
ensure_lab_role

account_id=$(aws sts get-caller-identity --query Account --output text --region "$AWS_REGION")
[[ "$account_id" == "$AIRBOB_AWS_ACCOUNT_ID" ]] || fail "active AWS account is outside the lab boundary"
lab_contract=$(aws ssm get-parameter \
  --name /airbob/performance-lab/foundation/lab-contract \
  --query 'Parameter.Value' --output text --region "$AWS_REGION")
jq -e '.schemaVersion == 1 and .account_id == "942632789808" and .region == "ap-northeast-2"' \
  <<<"$lab_contract" >/dev/null || fail "foundation lab contract is invalid"

lease_table=$(jq -er '.lease_table_name' <<<"$lab_contract")
lease_lock_id=$(jq -er '.lease_lock_id' <<<"$lab_contract")
evidence_bucket=$(jq -er '.evidence_bucket_name' <<<"$lab_contract")
bundle_bucket=$(jq -er '.bundle_bucket_name' <<<"$lab_contract")
dataset_bucket=$(jq -er '.dataset_bucket_name' <<<"$lab_contract")
state_bucket=$(jq -er '.state_bucket_name' <<<"$lab_contract")
lab_state_key=$(jq -er '.lab_state_key' <<<"$lab_contract")
[[ "$state_bucket" == "$AIRBOB_STATE_BUCKET_NAME" && "$lab_state_key" == "$AIRBOB_STATE_KEY_LAB" ]] \
  || fail "foundation lab-state boundary is invalid"
dns_controller_role_arn="arn:aws:iam::$AIRBOB_AWS_ACCOUNT_ID:role/airbob-dns-controller"

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-lab.XXXXXX")
heartbeat_pid=''
watchdog_pid=''
lease_acquired=false
dns_switched=false
current_tfvars=''
up_in_progress=false
current_stage=not-started
lab_backend_prepared=false
keep_on_failure=${KEEP_ON_FAILURE:-false}
[[ "$keep_on_failure" == true || "$keep_on_failure" == false ]] || fail "keep_on_failure must be true or false"

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ "$status" -ne 0 && "$up_in_progress" == true ]]; then
    write_failure_evidence "$status" "$current_stage" >/dev/null 2>&1 || true
    if [[ "$dns_switched" == true ]]; then
      invoke_dns_controller switch oci >/dev/null 2>&1 || true
    fi
    if [[ "$keep_on_failure" == false && -n "$current_tfvars" && -f "$current_tfvars" ]]; then
      write_terraform_output_evidence best-effort >/dev/null 2>&1 || true
      write_tfvars network false "" >/dev/null 2>&1 || true
      destroy_lab >/dev/null 2>&1 || true
      "$orphan_scanner" "$run_id" >/dev/null 2>&1 || true
    fi
  fi
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
      "$fencing_token" "$run_id" "$action" >/dev/null 2>&1 || true
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

prepare_lab_backend() {
  [[ "$lab_backend_prepared" == false ]] || return 0
  "$backend_helper" lab >/dev/null
  terraform -chdir="$lab_root" init -input=false -lockfile=readonly \
    -backend-config=backend.generated.hcl >/dev/null
  lab_backend_prepared=true
}

current_run_id() {
  prepare_lab_backend
  terraform -chdir="$lab_root" output -json phase2_contract 2>/dev/null | jq -er '.run_id'
}

assert_state_run_identity() {
  local requirement=${1:-required}
  local state_object state_run_id
  [[ "$requirement" == required || "$requirement" == allow-absent ]] \
    || fail "state identity requirement is invalid"
  state_object=$(aws s3api list-objects-v2 --bucket "$state_bucket" \
    --prefix "$lab_state_key" --max-items 1 \
    --query "Contents[?Key=='$lab_state_key'].Key | [0]" --output text \
    --region "$AWS_REGION" --no-cli-pager) || fail "cannot inspect the lab state identity"
  if [[ -z "$state_object" || "$state_object" == None ]]; then
    [[ "$requirement" == allow-absent ]] || fail "Terraform state run identity is unavailable"
    return 0
  fi
  [[ "$state_object" == "$lab_state_key" ]] || fail "lab state lookup returned an unexpected object"
  [[ "$requirement" == required ]] \
    || fail "refusing to replace an active lab; run status or down first"
  state_run_id=$(current_run_id) || fail "Terraform state run identity is unavailable"
  if [[ "$state_run_id" != "$run_id" ]]; then
    fail "Terraform state run changed before lease acquisition"
  fi
}

read_run_manifest() {
  local selected_run=$1 destination=$2
  aws s3api get-object --bucket "$evidence_bucket" \
    --key "runs/$selected_run/operator.json" "$destination" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "run manifest is missing"
  jq -e --arg run "$selected_run" '.schemaVersion == 1 and .runId == $run' "$destination" >/dev/null \
    || fail "run manifest is invalid"
}

write_run_manifest() {
  local manifest=$1
  aws s3api put-object --bucket "$evidence_bucket" \
    --key "runs/$run_id/operator.json" --body "$manifest" \
    --tagging Retention=summary --server-side-encryption AES256 \
    --content-type application/json --region "$AWS_REGION" --no-cli-pager >/dev/null
}

start_mutation_guard() {
  lease_owner=${LEASE_OWNER:-${GITHUB_REPOSITORY:-local}/${GITHUB_RUN_ID:-$(id -un)}:${GITHUB_RUN_ATTEMPT:-1}}
  [[ "$lease_owner" =~ ^[A-Za-z0-9._:@/-]{3,128}$ ]] || fail "lease owner is not canonical"
  token_output=$("$lease_script" acquire "$lease_table" "$lease_lock_id" "$lease_owner" \
    "$run_id" "$action" "$HEARTBEAT_TTL_SECONDS" "$COMMAND_DEADLINE_SECONDS")
  fencing_token=${token_output#fencing_token=}
  [[ "$fencing_token" =~ ^[1-9][0-9]*$ ]] || fail "lease did not issue a fencing token"
  lease_acquired=true
  parent_pid=$$
  (
    while sleep "$HEARTBEAT_INTERVAL_SECONDS"; do
      "$lease_script" heartbeat "$lease_table" "$lease_lock_id" "$lease_owner" \
        "$fencing_token" "$run_id" "$action" "$HEARTBEAT_TTL_SECONDS" >/dev/null \
        || { kill -TERM "$parent_pid" 2>/dev/null || true; exit 1; }
    done
  ) &
  heartbeat_pid=$!
  (
    sleep "$COMMAND_DEADLINE_SECONDS"
    kill -TERM "$parent_pid" 2>/dev/null || true
  ) &
  watchdog_pid=$!
}

assert_lease() {
  "$lease_script" assert "$lease_table" "$lease_lock_id" "$lease_owner" \
    "$fencing_token" "$run_id" "$action" >/dev/null
}

resolve_release_inputs() {
  local checksum_file="$temp_dir/bundle.sha256" dataset_manifest="$temp_dir/dataset-manifest.json"
  app_digest=${IMAGE_DIGEST:-}
  [[ "$app_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "IMAGE_DIGEST must be one canonical sha256 digest"
  bundle_commit=${BUNDLE_COMMIT:-$(git -C "$repo_root" rev-parse HEAD)}
  [[ "$bundle_commit" =~ ^[0-9a-f]{40}$ ]] || fail "BUNDLE_COMMIT must be one full Git commit"
  dataset_release=${DATASET_RELEASE:-}
  [[ "$dataset_release" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ ]] || fail "DATASET_RELEASE is required and must be canonical"

  bundle_archive="airbob-service-bundles-$bundle_commit.tar.gz"
  aws s3api get-object --bucket "$bundle_bucket" \
    --key "service-bundles/$bundle_commit/$bundle_archive.sha256" "$checksum_file" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "immutable service bundle checksum is unavailable"
  bundle_sha256=$(awk 'NR == 1 {print $1}' "$checksum_file")
  [[ "$bundle_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "bundle checksum object is invalid"

  aws s3api get-object --bucket "$dataset_bucket" \
    --key "datasets/$dataset_release/manifest.json" "$dataset_manifest" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "dataset completion manifest is unavailable"
  jq -e --arg expectedRelease "$dataset_release" '
    .schemaVersion == 1 and
    .datasetRelease == $expectedRelease and
    (.releaseKind == "pipeline-rehearsal" or .releaseKind == "evidence") and
    .mysql.flywayVersion == "27" and
    .mysql.expectedTableRows.flyway_schema_history == 27 and
    ([.. | objects | keys[]] |
      all(test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not))
  ' "$dataset_manifest" >/dev/null || fail "dataset completion manifest is invalid"
  dataset_manifest_sha256=$(sha256_file "$dataset_manifest")

  app_repository=$(jq -er '.ecr_repositories.APP_IMAGE.url' <<<"$lab_contract")
  app_image_reference="$app_repository@$app_digest"
  app_repository_name=${app_repository#*/}
  aws ecr describe-images --repository-name "$app_repository_name" \
    --image-ids "imageDigest=$app_digest" --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "application digest is not readable from the approved ECR repository"

  infra_image_references='{}'
  for image_variable in REDIS_IMAGE REDIS_EXPORTER_IMAGE NODE_EXPORTER_IMAGE KAFKA_IMAGE DEBEZIUM_IMAGE ELASTICSEARCH_IMAGE ELASTICSEARCH_EXPORTER_IMAGE PROMETHEUS_IMAGE GRAFANA_IMAGE; do
    repository_url=$(jq -er --arg key "$image_variable" '.ecr_repositories[$key].url' <<<"$lab_contract")
    repository_name=${repository_url#*/}
    digest=$(aws ecr describe-images --repository-name "$repository_name" \
      --image-ids "imageTag=$bundle_commit" --query 'imageDetails[0].imageDigest' \
      --output text --region "$AWS_REGION" --no-cli-pager) \
      || fail "immutable infrastructure image is unavailable"
    [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "infrastructure image digest is invalid"
    infra_image_references=$(jq -c --arg key "$image_variable" --arg ref "$repository_url@$digest" \
      '. + {($key): $ref}' <<<"$infra_image_references")
  done
}

write_tfvars() {
  local deployment_phase=$1 app_enabled=$2 verified_probe_id=${3:-}
  local infra_images_json=${infra_image_references:-}
  local effective_load_generator=false
  [[ -n "$infra_images_json" ]] || infra_images_json='{}'
  [[ "$app_enabled" == true ]] && effective_load_generator=$load_generator_enabled
  current_tfvars="$temp_dir/$deployment_phase.tfvars.json"
  jq -n \
    --arg run_id "$run_id" --arg expires_at "$expires_at" --argjson fencing_token "$fencing_token" \
    --arg deployment_phase "$deployment_phase" --arg ami_id "$ami_id" --arg verified_probe_instance_id "$verified_probe_id" \
    --arg bundle_commit "${bundle_commit:-}" --arg bundle_sha256 "${bundle_sha256:-}" \
    --argjson infra_image_references "$infra_images_json" --arg app_image_reference "${app_image_reference:-}" \
    --argjson app_enabled "$app_enabled" --arg mode "$mode" --arg measurement_policy "$policy" \
    --argjson cache_enabled "$cache_enabled" --arg request_target "$request_target" \
    --argjson load_generator_enabled "$effective_load_generator" --arg dataset_release "${dataset_release:-}" \
    --arg dataset_manifest_sha256 "${dataset_manifest_sha256:-}" --arg database_bootstrap "$database_bootstrap" \
    --arg rds_snapshot_identifier "$rds_snapshot_identifier" --arg rds_engine_version "$rds_engine_version" \
    '{run_id:$run_id,expires_at:$expires_at,fencing_token:$fencing_token,deployment_phase:$deployment_phase,ami_id:$ami_id,verified_probe_instance_id:$verified_probe_instance_id,bundle_commit:$bundle_commit,bundle_sha256:$bundle_sha256,infra_image_references:$infra_image_references,app_image_reference:$app_image_reference,app_enabled:$app_enabled,mode:$mode,measurement_policy:$measurement_policy,accommodation_detail_cache_enabled:$cache_enabled,request_count_per_target_per_minute:(if $request_target == "" then null else ($request_target|tonumber) end),load_generator_enabled:$load_generator_enabled,dataset_release:$dataset_release,dataset_manifest_sha256:$dataset_manifest_sha256,database_bootstrap:$database_bootstrap,rds_snapshot_identifier:$rds_snapshot_identifier,rds_engine_version:$rds_engine_version}' \
    > "$current_tfvars"
}

apply_lab() {
  assert_lease
  prepare_lab_backend
  local plan_file="$temp_dir/lab.tfplan" plan_json="$temp_dir/lab-plan.json"
  terraform -chdir="$lab_root" plan -input=false -lock-timeout=5m \
    -var-file="$current_tfvars" -out="$plan_file" >/dev/null
  terraform -chdir="$lab_root" show -json "$plan_file" > "$plan_json"
  jq -e '
    [.resource_changes[]? |
      select(.change.actions | index("delete")) |
      select(
        (.change.before.tags.Persistence? // "") == "persistent" or
        (.change.before.tags_all.Persistence? // "") == "persistent"
      )
    ] | length == 0
  ' "$plan_json" >/dev/null \
    || fail "persistent resource deletion is outside the lab-state boundary"
  assert_lease
  terraform -chdir="$lab_root" apply -input=false -lock-timeout=5m -auto-approve "$plan_file" >/dev/null
}

destroy_lab() {
  [[ -n "$current_tfvars" && -f "$current_tfvars" ]] || return 1
  assert_lease
  prepare_lab_backend
  terraform -chdir="$lab_root" destroy -input=false -lock-timeout=5m -auto-approve \
    -var-file="$current_tfvars" >/dev/null
}

invoke_dns_controller() {
  local dns_action=$1 target=$2
  assert_lease
  AWS_DNS_CONTROLLER_ROLE_ARN="$dns_controller_role_arn" \
  OCI_ORIGIN_IPV4="$oci_origin_ipv4" \
  AWS_ALB_ARN="$aws_alb_arn" \
  AWS_ALB_DNS_NAME="$aws_alb_dns_name" \
  ALB_FENCING_TOKEN="$resource_fencing_token" \
  LEASE_TABLE="$lease_table" LEASE_LOCK_ID="$lease_lock_id" LEASE_OWNER="$lease_owner" \
  FENCING_TOKEN="$fencing_token" RUN_ID="$run_id" LEASE_COMMAND="$action" \
  KEEP_ON_FAILURE="$keep_on_failure" FORCE_DOWN="${FORCE:-false}" \
    "$dns_controller" "$dns_action" "$target"
}

write_failure_evidence() {
  local exit_status=$1 stage=$2 destination="$temp_dir/operator-failure.json"
  jq -n --arg runId "$run_id" --arg stage "$stage" \
    --argjson fencingToken "$fencing_token" --argjson exitStatus "$exit_status" \
    --argjson observedAt "$(date +%s)" \
    '{schemaVersion:1,runId:$runId,fencingToken:$fencingToken,stage:$stage,exitStatus:$exitStatus,observedAt:$observedAt}' \
    > "$destination"
  aws s3api put-object --bucket "$evidence_bucket" \
    --key "failures/$run_id/operator-$fencing_token.json" --body "$destination" \
    --tagging Retention=summary --server-side-encryption AES256 \
    --content-type application/json --region "$AWS_REGION" --no-cli-pager >/dev/null
}

write_terraform_output_evidence() {
  local requirement=${1:-required}
  local raw_outputs="$temp_dir/terraform-outputs.json"
  local evidence="$temp_dir/terraform-outputs.redacted.json"
  local output_status=available
  [[ "$requirement" == required || "$requirement" == best-effort ]] \
    || fail "Terraform output evidence requirement is invalid"
  assert_lease
  prepare_lab_backend
  if ! terraform -chdir="$lab_root" output -json > "$raw_outputs" 2>/dev/null ||
    ! jq -e '
      (keys | sort) == [
        "persistent_resource_contract",
        "phase2_contract",
        "phase3_contract",
        "phase4_contract",
        "state_boundaries"
      ] and
      all(.[]; .sensitive == false and has("value"))
    ' "$raw_outputs" >/dev/null; then
    output_status=unavailable
  fi
  if [[ "$output_status" == available ]]; then
    jq --arg runId "$run_id" --argjson fencingToken "$fencing_token" \
      --argjson recordedAt "$(date +%s)" \
      '{schemaVersion:1,runId:$runId,fencingToken:$fencingToken,recordedAt:$recordedAt,status:"available",outputs:with_entries(.value=.value.value)}' \
      "$raw_outputs" > "$evidence"
  else
    jq -n --arg runId "$run_id" --argjson fencingToken "$fencing_token" \
      --argjson recordedAt "$(date +%s)" \
      '{schemaVersion:1,runId:$runId,fencingToken:$fencingToken,recordedAt:$recordedAt,status:"unavailable",outputs:null}' \
      > "$evidence"
  fi
  aws s3api put-object --bucket "$evidence_bucket" \
    --key "runs/$run_id/terraform-outputs.redacted.json" --body "$evidence" \
    --tagging Retention=summary --server-side-encryption AES256 \
    --content-type application/json --region "$AWS_REGION" --no-cli-pager >/dev/null
  [[ "$output_status" == available || "$requirement" == best-effort ]]
}

wait_for_application() {
  local deadline status unhealthy healthy desired
  deadline=$(($(date +%s) + INSTANCE_REFRESH_TIMEOUT_SECONDS))
  while [[ $(date +%s) -le "$deadline" ]]; do
    assert_lease
    status=$(aws autoscaling describe-instance-refreshes --auto-scaling-group-name "$asg_name" \
      --max-records 1 --query 'InstanceRefreshes[0].Status' --output text \
      --region "$AWS_REGION" --no-cli-pager)
    case "$status" in Failed|Cancelled|RollbackFailed) fail "application instance refresh failed" ;; esac
    unhealthy=$(aws elbv2 describe-target-health --target-group-arn "$target_group_arn" \
      --query 'TargetHealthDescriptions[?TargetHealth.State!=`healthy`] | length(@)' \
      --output text --region "$AWS_REGION" --no-cli-pager)
    healthy=$(aws elbv2 describe-target-health --target-group-arn "$target_group_arn" \
      --query 'TargetHealthDescriptions[?TargetHealth.State==`healthy`] | length(@)' \
      --output text --region "$AWS_REGION" --no-cli-pager)
    desired=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$asg_name" \
      --query 'AutoScalingGroups[0].DesiredCapacity' --output text --region "$AWS_REGION" --no-cli-pager)
    [[ "$unhealthy" == 0 && "$healthy" == "$desired" && "$desired" -ge 1 && ( "$status" == Successful || "$status" == None ) ]] \
      && return 0
    sleep 10
  done
  aws autoscaling rollback-instance-refresh --auto-scaling-group-name "$asg_name" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null 2>&1 || \
    aws autoscaling cancel-instance-refresh --auto-scaling-group-name "$asg_name" \
      --region "$AWS_REGION" --no-cli-pager >/dev/null 2>&1 || true
  fail "application refresh/target-health gate exceeded 15 minutes"
}

verify_public_aws_smoke() {
  local attempt
  for attempt in 1 2 3; do
    curl --fail --silent --show-error --max-time 15 \
      "https://api.airbob.cloud/actuator/health" \
      | jq -e '.status == "UP"' >/dev/null \
      || fail "public AWS smoke check failed"
    [[ "$attempt" -eq 3 ]] || sleep 5
  done
}

show_status() {
  printf '%s\n' 'lease:'
  "$lease_script" status "$lease_table" "$lease_lock_id" || true
  printf '%s\n' 'expiry observer:'
  aws events describe-rule --name airbob-performance-lab-expiry-observer \
    --query '[State,LastModifiedTime]' --output table --region "$AWS_REGION" --no-cli-pager
  aws cloudwatch describe-alarms --alarm-names \
    airbob-performance-lab-expiry-action-required \
    airbob-performance-lab-expiry-heartbeat-missing \
    airbob-performance-lab-expiry-lambda-errors \
    --query 'MetricAlarms[].[AlarmName,StateValue,StateUpdatedTimestamp,ActionsEnabled]' \
    --output table --region "$AWS_REGION" --no-cli-pager
  aws resourcegroupstaggingapi get-resources --tag-filters \
    Key=Project,Values=airbob Key=Environment,Values=performance-lab Key=Stack,Values=lab \
    --query 'ResourceTagMappingList[].ResourceARN' --output table --region "$AWS_REGION" --no-cli-pager
}

case "$action" in
  status)
    show_status
    exit 0
    ;;
  up)
    mode=${MODE:-performance}
    policy=${POLICY:-isolated-read}
    cache_enabled=${CACHE_ENABLED:-true}
    request_target=${REQUEST_TARGET:-}
    ttl_hours=${TTL_HOURS:-6}
    load_generator_enabled=${LOAD_GENERATOR_ENABLED:-true}
    ami_id=${AMI_ID:-}
    oci_origin_ipv4=${OCI_ORIGIN_IPV4:-}
    database_bootstrap=${DATABASE_BOOTSTRAP:-dump}
    rds_snapshot_identifier=${RDS_SNAPSHOT_IDENTIFIER:-}
    rds_engine_version=${RDS_ENGINE_VERSION:-}
    [[ "$mode" == performance || "$mode" == scaling ]] \
      || fail "MODE must be performance or scaling"
    [[ "$policy" == integrated-smoke || "$policy" == isolated-read ]] || fail "POLICY is invalid"
    [[ "$mode:$policy" != scaling:integrated-smoke ]] || fail "scaling requires isolated-read"
    [[ "$cache_enabled" == true || "$cache_enabled" == false ]] || fail "CACHE_ENABLED must be true or false"
    [[ "$load_generator_enabled" == true || "$load_generator_enabled" == false ]] || fail "LOAD_GENERATOR_ENABLED must be true or false"
    [[ "$ttl_hours" =~ ^[1-9][0-9]?$ && "$ttl_hours" -le 24 ]] || fail "TTL_HOURS must be 1-24"
    [[ "$mode" != scaling || "$request_target" =~ ^[1-9][0-9]*$ ]] || fail "scaling requires REQUEST_TARGET"
    [[ "$mode" == scaling || -z "$request_target" ]] || fail "REQUEST_TARGET is valid only for scaling"
    [[ "$ami_id" =~ ^ami-[0-9a-f]{8,17}$ ]] || fail "AMI_ID is required and must be reviewed"
    valid_ipv4 "$oci_origin_ipv4" || fail "OCI_ORIGIN_IPV4 must be one canonical IPv4 address"
    [[ "$rds_engine_version" =~ ^8\.0\.[0-9]+$ ]] || fail "RDS_ENGINE_VERSION is required and must be exact"
    [[ "$database_bootstrap" == dump || "$database_bootstrap" == snapshot ]] || fail "DATABASE_BOOTSTRAP must be dump or snapshot"
    now_epoch=$(date +%s)
    expires_at=$((now_epoch + ttl_hours * 3600))
    run_id=${RUN_ID:-lab-$(date -u +%Y%m%d%H%M%S)-${GITHUB_RUN_ID:-local}}
    run_id=$(printf '%.32s' "$run_id" | sed 's/-$//')
    [[ "$run_id" =~ ^[a-z0-9][a-z0-9-]{2,31}$ ]] || fail "generated RUN_ID is not canonical"
    current_stage=release-validation
    resolve_release_inputs
    start_mutation_guard
    resource_fencing_token=$fencing_token
    assert_state_run_identity allow-absent
    up_in_progress=true

    manifest="$temp_dir/operator.json"
    jq -n --arg runId "$run_id" --arg expiresAt "$expires_at" --argjson fencingToken "$resource_fencing_token" \
      --arg mode "$mode" --arg policy "$policy" --arg imageDigest "$app_digest" --arg datasetRelease "$dataset_release" \
      --arg bundleCommit "$bundle_commit" --arg bundleSha256 "$bundle_sha256" --arg datasetManifestSha256 "$dataset_manifest_sha256" \
      --arg amiId "$ami_id" --arg ociOriginIpv4 "$oci_origin_ipv4" --arg rdsEngineVersion "$rds_engine_version" \
      --arg databaseBootstrap "$database_bootstrap" --arg rdsSnapshotIdentifier "$rds_snapshot_identifier" \
      --argjson cacheEnabled "$cache_enabled" --arg requestTarget "$request_target" --argjson loadGeneratorEnabled "$load_generator_enabled" \
      --arg appImageReference "$app_image_reference" --argjson infraImageReferences "$infra_image_references" \
      '{schemaVersion:1,runId:$runId,expiresAt:$expiresAt,fencingToken:$fencingToken,mode:$mode,policy:$policy,imageDigest:$imageDigest,datasetRelease:$datasetRelease,bundleCommit:$bundleCommit,bundleSha256:$bundleSha256,datasetManifestSha256:$datasetManifestSha256,amiId:$amiId,ociOriginIpv4:$ociOriginIpv4,rdsEngineVersion:$rdsEngineVersion,databaseBootstrap:$databaseBootstrap,rdsSnapshotIdentifier:$rdsSnapshotIdentifier,cacheEnabled:$cacheEnabled,requestTarget:$requestTarget,loadGeneratorEnabled:$loadGeneratorEnabled,appImageReference:$appImageReference,infraImageReferences:$infraImageReferences,verifiedProbeInstanceId:""}' \
      > "$manifest"
    write_run_manifest "$manifest"

    current_stage=release-validated
    current_stage=network
    write_tfvars network false ""
    apply_lab # deployment_phase=network
    phase2=$(terraform -chdir="$lab_root" output -json phase2_contract)
    vpc_id=$(jq -er '.vpc_id' <<<"$phase2")
    route_table_id=$(jq -er '.primary_private_route_table' <<<"$phase2")
    probe_instance_id=$(jq -er '.probe_instance_id' <<<"$phase2")
    "$network_verifier" egress "$run_id" "$vpc_id" "$route_table_id" "$probe_instance_id" "$ami_id" "$evidence_bucket" >/dev/null

    current_stage=probe-cleared
    write_tfvars probe-cleared false "$probe_instance_id"
    apply_lab # deployment_phase=probe-cleared
    "$network_verifier" cleared "$run_id" "$vpc_id" "$probe_instance_id" "$evidence_bucket" >/dev/null
    jq --arg probe "$probe_instance_id" '.verifiedProbeInstanceId=$probe' "$manifest" > "$manifest.next"
    mv "$manifest.next" "$manifest"
    write_run_manifest "$manifest"

    current_stage=services-and-data-bootstrap
    write_tfvars services false "$probe_instance_id"
    apply_lab # deployment_phase=services
    phase2=$(terraform -chdir="$lab_root" output -json phase2_contract)
    phase3=$(terraform -chdir="$lab_root" output -json phase3_contract)
    debezium_instance_id=$(jq -er '.services.debezium' <<<"$phase2")
    kafka_instance_id=$(jq -er '.services.kafka' <<<"$phase2")
    rds_instance_id=$(jq -er '.rds_instance_id' <<<"$phase3")
    rds_endpoint=$(jq -er '.rds_endpoint' <<<"$phase3")
    rds_secret_arn=$(aws rds describe-db-instances --db-instance-identifier "$rds_instance_id" \
      --query 'DBInstances[0].MasterUserSecret.SecretArn' --output text --region "$AWS_REGION" --no-cli-pager)
    assert_lease
    "$policy_verifier" "$policy" "$run_id" "$debezium_instance_id" "$kafka_instance_id" \
      "$rds_endpoint" "$rds_secret_arn" "$evidence_bucket" "$fencing_token" >/dev/null
    current_stage=data-ready-and-app
    write_tfvars data-ready true "$probe_instance_id"
    apply_lab # deployment_phase=data-ready
    phase4=$(terraform -chdir="$lab_root" output -json phase4_contract)
    aws_alb_arn=$(jq -er '.alb_arn' <<<"$phase4")
    aws_alb_dns_name=$(jq -er '.alb_dns_name' <<<"$phase4")
    target_group_arn=$(jq -er '.target_group_arn' <<<"$phase4")
    asg_name=$(jq -er '.auto_scaling_group_name' <<<"$phase4")
    wait_for_application
    current_stage=dns-stage
    invoke_dns_controller stage oci >/dev/null
    current_stage=dns-switch
    invoke_dns_controller switch aws >/dev/null
    dns_switched=true
    verify_public_aws_smoke
    current_stage=evidence
    write_terraform_output_evidence required
    up_in_progress=false
    printf 'run_id=%s\nfencing_token=%s\nexpires_at=%s\ndns_target=aws\n' "$run_id" "$fencing_token" "$expires_at"
    ;;
  switch|down)
    run_id=${RUN_ID:-$(current_run_id)}
    [[ "$run_id" =~ ^[a-z0-9][a-z0-9-]{2,31}$ ]] || fail "RUN_ID is unavailable or invalid"
    manifest="$temp_dir/operator.json"
    read_run_manifest "$run_id" "$manifest"
    resource_fencing_token=$(jq -er '.fencingToken' "$manifest")
    [[ "$resource_fencing_token" =~ ^[1-9][0-9]*$ ]] || fail "run manifest fencing token is invalid"
    start_mutation_guard
    assert_state_run_identity required
    expires_at=$(jq -er '.expiresAt' "$manifest")
    [[ "$expires_at" =~ ^[1-9][0-9]{9}$ ]] || fail "run manifest expiry is invalid"
    mode=$(jq -er '.mode' "$manifest")
    policy=$(jq -er '.policy' "$manifest")
    cache_enabled=$(jq -r '.cacheEnabled' "$manifest")
    request_target=$(jq -r '.requestTarget' "$manifest")
    load_generator_enabled=$(jq -r '.loadGeneratorEnabled' "$manifest")
    [[ "$cache_enabled" == true || "$cache_enabled" == false ]] || fail "run manifest cache toggle is invalid"
    [[ "$load_generator_enabled" == true || "$load_generator_enabled" == false ]] \
      || fail "run manifest load-generator toggle is invalid"
    ami_id=$(jq -er '.amiId' "$manifest")
    oci_origin_ipv4=$(jq -er '.ociOriginIpv4' "$manifest")
    database_bootstrap=$(jq -er '.databaseBootstrap' "$manifest")
    rds_snapshot_identifier=$(jq -r '.rdsSnapshotIdentifier' "$manifest")
    rds_engine_version=$(jq -er '.rdsEngineVersion' "$manifest")
    bundle_commit=$(jq -er '.bundleCommit' "$manifest")
    bundle_sha256=$(jq -er '.bundleSha256' "$manifest")
    dataset_release=$(jq -er '.datasetRelease' "$manifest")
    dataset_manifest_sha256=$(jq -er '.datasetManifestSha256' "$manifest")
    app_image_reference=$(jq -er '.appImageReference' "$manifest")
    infra_image_references=$(jq -c '.infraImageReferences' "$manifest")
    probe_instance_id=$(jq -er '.verifiedProbeInstanceId' "$manifest")
    prepare_lab_backend
    phase4=$(terraform -chdir="$lab_root" output -json phase4_contract 2>/dev/null || printf '{}')
    aws_alb_arn=$(jq -r '.alb_arn // empty' <<<"$phase4")
    aws_alb_dns_name=$(jq -r '.alb_dns_name // empty' <<<"$phase4")
    if [[ "$action" == switch ]]; then
      [[ -n "$aws_alb_arn" && -n "$aws_alb_dns_name" ]] || fail "switch requires an active lab ALB"
      target=${TARGET:-}
      [[ "$target" == aws || "$target" == oci ]] || fail "TARGET must be aws or oci"
      invoke_dns_controller switch "$target"
      exit 0
    fi
    force=${FORCE:-false}
    [[ "$force" == true || "$force" == false ]] || fail "FORCE must be true or false"
    if [[ "$force" == true && $(date +%s) -lt $((expires_at + 7200)) ]]; then
      fail "FORCE teardown is permitted only after the expiry grace period"
    fi
    if [[ "$force" == true ]]; then
      cleanup_evidence="$temp_dir/forced-cleanup.json"
      jq -n --arg runId "$run_id" --argjson fencingToken "$fencing_token" \
        --argjson observedAt "$(date +%s)" \
        '{schemaVersion:1,runId:$runId,fencingToken:$fencingToken,reason:"expired-after-grace",observedAt:$observedAt}' \
        > "$cleanup_evidence"
      aws s3api put-object --bucket "$evidence_bucket" \
        --key "cleanup/$run_id/forced-$fencing_token.json" --body "$cleanup_evidence" \
        --tagging Retention=summary --server-side-encryption AES256 \
        --content-type application/json --region "$AWS_REGION" --no-cli-pager >/dev/null
    fi
    invoke_dns_controller remove oci
    if [[ "$force" == true ]]; then
      write_terraform_output_evidence best-effort || true
    else
      write_terraform_output_evidence required
    fi
    write_tfvars network false ""
    destroy_lab
    "$orphan_scanner" "$run_id"
    printf 'destroyed_run_id=%s\ndns_target=oci\n' "$run_id"
    ;;
esac
