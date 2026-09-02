#!/usr/bin/env bash
set -euo pipefail
umask 077

DEFAULT_COMMAND_DEADLINE_SECONDS=5400
DUMP_UP_COMMAND_DEADLINE_SECONDS=14400
DEFAULT_CREDENTIAL_SESSION_SECONDS=7200
DUMP_UP_CREDENTIAL_SESSION_SECONDS=18000
DUMP_UP_PRE_BOOTSTRAP_ALLOWANCE_SECONDS=3600
DUMP_UP_POST_BOOTSTRAP_ALLOWANCE_SECONDS=2400
LAB_ROLE_MAX_SESSION_SECONDS=18000
TERRAFORM_LOCK_CREDENTIAL_EXPIRY_MARGIN_SECONDS=300
TERRAFORM_LOCK_CREDENTIAL_EXPIRY_BARRIER_SECONDS=$((
  LAB_ROLE_MAX_SESSION_SECONDS + TERRAFORM_LOCK_CREDENTIAL_EXPIRY_MARGIN_SECONDS
))
COMMAND_DEADLINE_SECONDS=$DEFAULT_COMMAND_DEADLINE_SECONDS
CREDENTIAL_SESSION_SECONDS=$DEFAULT_CREDENTIAL_SESSION_SECONDS
INSTANCE_REFRESH_TIMEOUT_SECONDS=900
HEARTBEAT_TTL_SECONDS=180
HEARTBEAT_INTERVAL_SECONDS=60
MUTATION_TERMINATION_GRACE_SECONDS=10

fail() { printf '%s\n' "$1" >&2; exit 1; }

aws_utc_timestamp_epoch() {
  local timestamp=$1 normalized canonical
  [[ "$timestamp" =~ ^[0-9]{4}-(0[1-9]|1[0-2])-([0-2][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\.[0-9]{1,9})?(Z|\+00:00)$ ]] \
    || return 1
  case "$timestamp" in
    *+00:00) normalized="${timestamp%+00:00}Z" ;;
    *Z) normalized=$timestamp ;;
    *) return 1 ;;
  esac
  [[ "$normalized" != *.*Z ]] || normalized="${normalized%%.*}Z"
  canonical=$(jq -nr --arg timestamp "$normalized" \
    '$timestamp | fromdateiso8601 | strftime("%Y-%m-%dT%H:%M:%SZ")') \
    || return 1
  [[ "$canonical" == "$normalized" ]] || return 1
  jq -nr --arg timestamp "$normalized" '$timestamp | fromdateiso8601'
}

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

valid_public_ipv4() {
  local address=$1 first second third fourth
  valid_ipv4 "$address" || return 1
  IFS=. read -r first second third fourth <<<"$address"
  ((first != 0 && first != 10 && first != 127 && first < 224)) || return 1
  ((!(first == 100 && second >= 64 && second <= 127))) || return 1
  ((!(first == 169 && second == 254))) || return 1
  ((!(first == 172 && second >= 16 && second <= 31))) || return 1
  ((!(first == 192 && (
    (second == 0 && (third == 0 || third == 2)) ||
    (second == 88 && third == 99) ||
    second == 168
  )))) || return 1
  ((!(first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))))) || return 1
  ((!(first == 203 && second == 0 && third == 113))) || return 1
}

valid_run_id() {
  local candidate=$1
  [[ "$candidate" =~ ^lab-[a-z0-9][a-z0-9-]{0,27}$ && \
    "$candidate" != *--* && "$candidate" != *- ]]
}

valid_rds_snapshot_identifier() {
  local candidate=$1
  [[ "$candidate" =~ ^airbob-dataset-[a-z0-9][a-z0-9-]{2,47}$ && \
    "$candidate" != *--* && "$candidate" != *- ]]
}

valid_rds_resource_id() {
  local candidate=$1
  [[ "$candidate" =~ ^db-[A-Z0-9]{24}$ ]]
}

validate_snapshot_bootstrap_inputs() {
  case "$database_bootstrap" in
    dump)
      [[ -z "$rds_snapshot_identifier" && -z "$rds_snapshot_source_run_id" && \
        -z "$rds_snapshot_source_resource_id" ]] \
        || fail "dump bootstrap forbids every RDS snapshot source identity"
      ;;
    snapshot)
      valid_rds_snapshot_identifier "$rds_snapshot_identifier" \
        || fail "snapshot bootstrap requires one canonical RDS_SNAPSHOT_IDENTIFIER"
      valid_run_id "$rds_snapshot_source_run_id" \
        || fail "snapshot bootstrap requires one canonical RDS_SNAPSHOT_SOURCE_RUN_ID"
      valid_rds_resource_id "$rds_snapshot_source_resource_id" \
        || fail "snapshot bootstrap requires one canonical RDS_SNAPSHOT_SOURCE_RESOURCE_ID"
      ;;
    *) fail "DATABASE_BOOTSTRAP must be dump or snapshot" ;;
  esac
}

validate_operator_scope_for_action() {
  local force_cleanup=${1:-false} expected_scope
  case "$action" in
    up)
      expected_scope=direct
      [[ "$dns_mode" != cutover ]] || expected_scope=cutover
      ;;
    switch) expected_scope=cutover ;;
    down)
      expected_scope=direct
      [[ "$dns_mode" != cutover ]] || expected_scope=cutover
      if [[ "$expected_scope" == direct && "$operator_scope" == cutover && \
        "$force_cleanup" == true ]]; then
        return 0
      fi
      ;;
    *) return 0 ;;
  esac
  [[ "$operator_scope" == "$expected_scope" ]] \
    || fail "$action with DNS_MODE=$dns_mode requires AWS_LAB_OPERATOR_SCOPE=$expected_scope"
}

[[ "$#" -eq 1 ]] || fail "usage: aws-lab.sh up|status|switch|down"
action=$1
case "$action" in up|status|switch|down) ;; *) fail "unsupported AWS lab action" ;; esac
lease_command=$action

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
lab_root="$repo_root/infra/aws/lab"
lease_script="$script_dir/orchestration-lease.sh"
dns_controller="$script_dir/aws-dns-controller.sh"
orphan_scanner="$script_dir/scan-lab-orphans.sh"
backend_helper="$script_dir/prepare-terraform-backend.sh"
network_verifier="$script_dir/verify-network-egress.sh"
policy_verifier="$script_dir/enforce-measurement-policy.sh"
comparison_projection_filter="$script_dir/readiness-comparison-projection.jq"
toolchain_contract="$repo_root/infra/aws/toolchain.env"

if [[ "$action" == up && "${DATABASE_BOOTSTRAP:-dump}" == dump ]]; then
  COMMAND_DEADLINE_SECONDS=$DUMP_UP_COMMAND_DEADLINE_SECONDS
  CREDENTIAL_SESSION_SECONDS=$DUMP_UP_CREDENTIAL_SESSION_SECONDS
fi

# Short timing values are accepted only by the copied hermetic test fixture.
# A checkout (including a Git worktree) always has a .git entry and cannot use
# this path to weaken the production command deadline or heartbeat cadence.
if [[ -n "${AIRBOB_OPERATOR_TEST_HARNESS:-}" ]]; then
  test_root_prefix=$(CDPATH= cd -P -- "${TMPDIR:-/tmp}" && pwd -P) \
    || fail "cannot resolve the hermetic operator test root"
  [[ "$AIRBOB_OPERATOR_TEST_HARNESS" == hermetic-fake-v1 && ! -e "$repo_root/.git" && \
    "$repo_root" == "$test_root_prefix"/airbob-operator-test.*/operator-repo ]] \
    || fail "operator test timing overrides are outside the hermetic fake harness"
  test_command_deadline=${AIRBOB_TEST_COMMAND_DEADLINE_SECONDS:-}
  test_heartbeat_interval=${AIRBOB_TEST_HEARTBEAT_INTERVAL_SECONDS:-}
  test_termination_grace=${AIRBOB_TEST_TERMINATION_GRACE_SECONDS:-}
  [[ "$test_command_deadline" =~ ^[1-9][0-9]?$ ]] \
    || fail "test command deadline must be 1-99 seconds"
  [[ "$test_heartbeat_interval" =~ ^[1-9][0-9]?$ ]] \
    || fail "test heartbeat interval must be 1-99 seconds"
  [[ "$test_termination_grace" =~ ^[1-9][0-9]?$ ]] \
    || fail "test mutation termination grace must be 1-99 seconds"
  COMMAND_DEADLINE_SECONDS=$test_command_deadline
  HEARTBEAT_INTERVAL_SECONDS=$test_heartbeat_interval
  MUTATION_TERMINATION_GRACE_SECONDS=$test_termination_grace
elif [[ -n "${AIRBOB_TEST_COMMAND_DEADLINE_SECONDS:-}${AIRBOB_TEST_HEARTBEAT_INTERVAL_SECONDS:-}${AIRBOB_TEST_TERMINATION_GRACE_SECONDS:-}" ]]; then
  fail "operator test timing overrides require the hermetic fake harness"
fi

for executable in "$lease_script" "$dns_controller" "$orphan_scanner" "$backend_helper" "$network_verifier" "$policy_verifier"; do
  [[ -x "$executable" && ! -L "$executable" ]] || fail "required AWS lab helper is missing or unsafe"
done
[[ -f "$toolchain_contract" && ! -L "$toolchain_contract" ]] || fail "toolchain contract is missing or unsafe"
[[ -f "$comparison_projection_filter" && ! -L "$comparison_projection_filter" ]] \
  || fail "readiness comparison projection is missing or unsafe"
# shellcheck disable=SC1090
. "$toolchain_contract"

export AWS_REGION=${AWS_REGION:-$AIRBOB_AWS_REGION}
[[ "$AWS_REGION" == "$AIRBOB_AWS_REGION" ]] || fail "AWS_REGION must equal $AIRBOB_AWS_REGION"
command -v aws >/dev/null 2>&1 || fail "AWS CLI is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"
command -v terraform >/dev/null 2>&1 || fail "Terraform is required"

operator_scope=${AWS_LAB_OPERATOR_SCOPE:-direct}
case "$operator_scope" in
  direct)
    lab_role_name=airbob-lab-operator
    lab_role_arn=${AWS_LAB_OPERATOR_ROLE_ARN:-arn:aws:iam::$AIRBOB_AWS_ACCOUNT_ID:role/$lab_role_name}
    ;;
  cutover)
    lab_role_name=airbob-lab-cutover-operator
    lab_role_arn=${AWS_LAB_CUTOVER_OPERATOR_ROLE_ARN:-arn:aws:iam::$AIRBOB_AWS_ACCOUNT_ID:role/$lab_role_name}
    ;;
  *) fail "AWS_LAB_OPERATOR_SCOPE must be direct or cutover" ;;
esac
[[ "$lab_role_arn" == "arn:aws:iam::$AIRBOB_AWS_ACCOUNT_ID:role/$lab_role_name" ]] \
  || fail "lab operator role ARN is outside the selected foundation scope"

ensure_lab_role() {
  local caller_arn credentials
  caller_arn=$(aws sts get-caller-identity --query Arn --output text --region "$AWS_REGION")
  case "$caller_arn" in
    arn:aws:sts::$AIRBOB_AWS_ACCOUNT_ID:assumed-role/"$lab_role_name"/*)
      [[ -n "${AWS_ACCESS_KEY_ID:-}" && -n "${AWS_SECRET_ACCESS_KEY:-}" && \
        -n "${AWS_SESSION_TOKEN:-}" ]] \
        || fail "active Lab role must use one explicit static STS environment credential tuple"
      return
      ;;
    arn:aws:sts::$AIRBOB_AWS_ACCOUNT_ID:assumed-role/airbob-lab-operator/*|\
    arn:aws:sts::$AIRBOB_AWS_ACCOUNT_ID:assumed-role/airbob-lab-cutover-operator/*)
      fail "active Lab credentials do not match AWS_LAB_OPERATOR_SCOPE"
      ;;
  esac
  credentials=$(aws sts assume-role \
    --role-arn "$lab_role_arn" \
    --role-session-name "airbob-lab-${GITHUB_RUN_ID:-local}-$(date +%s)" \
    --duration-seconds "$CREDENTIAL_SESSION_SECONDS" \
    --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' \
    --output text --region "$AWS_REGION") || fail "cannot assume the lab operator role"
  read -r AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN <<EOF
$credentials
EOF
  export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
  [[ -n "$AWS_ACCESS_KEY_ID" && -n "$AWS_SECRET_ACCESS_KEY" && \
    -n "$AWS_SESSION_TOKEN" ]] \
    || fail "assumed Lab role returned an incomplete static STS environment credential tuple"
  caller_arn=$(aws sts get-caller-identity --query Arn --output text --region "$AWS_REGION")
  [[ "$caller_arn" == arn:aws:sts::$AIRBOB_AWS_ACCOUNT_ID:assumed-role/"$lab_role_name"/* ]] \
    || fail "lab operations require credentials from the selected operator scope"
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
terraform_lock_key="${lab_state_key}.tflock"
terraform_lock_path="$state_bucket/$lab_state_key"
dns_controller_role_arn="arn:aws:iam::$AIRBOB_AWS_ACCOUNT_ID:role/airbob-dns-controller"

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-lab.XXXXXX")
heartbeat_pid=''
watchdog_pid=''
active_mutation_pid=''
active_mutation_pgid=''
lease_acquired=false
dns_switched=false
current_tfvars=''
up_in_progress=false
current_stage=not-started
lab_backend_prepared=false
keep_on_failure=${KEEP_ON_FAILURE:-false}
[[ "$keep_on_failure" == true || "$keep_on_failure" == false ]] || fail "keep_on_failure must be true or false"

mutation_group_alive() {
  [[ -n "$active_mutation_pgid" ]] && kill -0 -- "-$active_mutation_pgid" 2>/dev/null
}

stop_active_mutation() {
  local reason=${1:-operator-stop} attempt
  local child_pid=$active_mutation_pid child_pgid=$active_mutation_pgid
  [[ -n "$child_pid" && -n "$child_pgid" ]] || return 0
  printf 'stopping supervised mutation (%s): pid=%s pgid=%s\n' \
    "$reason" "$child_pid" "$child_pgid" >&2
  kill -TERM -- "-$child_pgid" 2>/dev/null || kill -TERM "$child_pid" 2>/dev/null || true
  for ((attempt = 0; attempt < MUTATION_TERMINATION_GRACE_SECONDS * 4; attempt++)); do
    mutation_group_alive || break
    sleep 0.25
  done
  if mutation_group_alive; then
    kill -KILL -- "-$child_pgid" 2>/dev/null || kill -KILL "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
    for ((attempt = 0; attempt < 20; attempt++)); do
      kill -0 -- "-$child_pgid" 2>/dev/null || break
      sleep 0.25
    done
  fi
  wait "$child_pid" 2>/dev/null || true
  active_mutation_pid=''
  active_mutation_pgid=''
  ! kill -0 -- "-$child_pgid" 2>/dev/null \
    || { printf 'supervised mutation process group survived SIGKILL: %s\n' "$child_pgid" >&2; return 1; }
}

abort_operator() {
  local exit_status=$1 reason=$2
  stop_active_mutation "$reason" || exit_status=125
  printf '%s\n' "$reason" >&2
  exit "$exit_status"
}

run_supervised_mutation() {
  local description=$1 result=0 platform
  shift
  [[ "$lease_acquired" == true ]] || fail "supervised mutation requires the orchestration lease"
  [[ -z "$active_mutation_pid" && -z "$active_mutation_pgid" ]] \
    || fail "nested supervised mutations are not allowed"
  platform=$(uname -s)
  case "$platform" in
    Linux)
      command -v setsid >/dev/null 2>&1 \
        || fail "Linux mutation supervision requires setsid"
      setsid "$@" &
      ;;
    Darwin)
      command -v python3 >/dev/null 2>&1 \
        || fail "Darwin mutation supervision requires python3 for a new process group"
      python3 -c 'import os, sys; os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])' "$@" &
      ;;
    *) fail "mutation supervision is unsupported on this operating system" ;;
  esac
  active_mutation_pid=$!
  active_mutation_pgid=$active_mutation_pid
  if wait "$active_mutation_pid"; then
    result=0
  else
    result=$?
  fi
  if mutation_group_alive; then
    stop_active_mutation "$description left descendants" || result=125
    [[ "$result" -ne 0 ]] || result=125
  else
    active_mutation_pid=''
    active_mutation_pgid=''
  fi
  return "$result"
}

run_terraform_command() {
  local description=$1
  shift
  if [[ "$lease_acquired" == true ]]; then
    run_supervised_mutation "$description" terraform "$@"
  else
    terraform "$@"
  fi
}

finish_cleanup() {
  trap - EXIT
  trap '' HUP INT TERM USR1 USR2
  stop_active_mutation "cleanup finalizer" || true
  if [[ -n "$heartbeat_pid" ]]; then
    kill "$heartbeat_pid" 2>/dev/null || true
    wait "$heartbeat_pid" 2>/dev/null || true
    heartbeat_pid=''
  fi
  if [[ -n "$watchdog_pid" ]]; then
    kill "$watchdog_pid" 2>/dev/null || true
    wait "$watchdog_pid" 2>/dev/null || true
    watchdog_pid=''
  fi
  if [[ "$lease_acquired" == true ]]; then
    "$lease_script" release "$lease_table" "$lease_lock_id" "$lease_owner" \
      "$fencing_token" "$run_id" "$lease_command" >/dev/null 2>&1 || true
    lease_acquired=false
  fi
  rm -rf "$temp_dir"
}

cleanup() {
  local status=$?
  local failure_cleanup_prerequisites=false oci_after_failure_cleanup=false
  trap - EXIT
  trap finish_cleanup EXIT
  stop_active_mutation "operator cleanup" || status=125
  if [[ "$status" -ne 0 && "$up_in_progress" == true ]]; then
    ( write_failure_evidence "$status" "$current_stage" ) >/dev/null 2>&1 || true
    if [[ "$dns_switched" == true ]]; then
      invoke_dns_controller remove oci >/dev/null 2>&1 || true
    fi
    if [[ "$keep_on_failure" == false && -n "$current_tfvars" && -f "$current_tfvars" ]]; then
      ( write_terraform_output_evidence best-effort ) >/dev/null 2>&1 || true
      if ( state_object_present ) >/dev/null 2>&1 &&
        ( verify_oci_authority failure-before-destroy ) >/dev/null 2>&1; then
        oci_observation_file="$temp_dir/oci-failure-before-destroy.json"
        if ( ensure_teardown_start ) >/dev/null 2>&1; then
          teardown_start_key="measurements/$run_id/teardown-start.json"
          if teardown_start_version_id=$(aws s3api head-object --bucket "$evidence_bucket" \
            --key "$teardown_start_key" --query VersionId --output text \
            --region "$AWS_REGION" --no-cli-pager 2>/dev/null) &&
            [[ -n "$teardown_start_version_id" && "$teardown_start_version_id" != None ]]; then
            failure_cleanup_prerequisites=true
          fi
        fi
      fi
      if [[ "$failure_cleanup_prerequisites" == true ]]; then
        if { write_tfvars network false "" && destroy_lab; } >/dev/null 2>&1 &&
          ( state_object_present ) >/dev/null 2>&1 &&
          ( terraform_state_is_empty ) >/dev/null 2>&1; then
          if ( verify_oci_authority failure-after-destroy ) >/dev/null 2>&1; then
            oci_observation_file="$temp_dir/oci-failure-after-destroy.json"
            oci_after_failure_cleanup=true
          fi
          if [[ "$oci_after_failure_cleanup" == true ]] &&
            ( AIRBOB_SCAN_SCOPE=global "$orphan_scanner" "$run_id" ) >/dev/null 2>&1; then
            ( finalize_clean_teardown false ) >/dev/null 2>&1 || true
          fi
        else
          printf '%s\n' 'failure cleanup could not prove an empty Terraform state; resources remain blocked' >&2
        fi
      else
        printf '%s\n' \
          'failure cleanup preserved resources because OCI authority or teardown-start could not be verified' >&2
      fi
    fi
  fi
  trap - EXIT
  finish_cleanup
  exit "$status"
}
trap cleanup EXIT
trap 'abort_operator 129 "operator received SIGHUP"' HUP
trap 'abort_operator 130 "operator received SIGINT"' INT
trap 'abort_operator 143 "operator received SIGTERM"' TERM
trap 'abort_operator 75 "orchestration heartbeat failed"' USR1
trap 'abort_operator 124 "operator command deadline exceeded"' USR2

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

canonical_operator_tree_sha256() {
  local inventory="$temp_dir/operator-tree.tsv" relative
  : > "$inventory"
  for relative in \
    Makefile \
    .github/workflows/aws-performance-lab.yml \
    infra/aws/scripts/aws-lab.sh \
    infra/aws/scripts/cleanup-expired-lab.sh \
    infra/aws/scripts/readiness-comparison-projection.jq \
    infra/aws/scripts/scan-lab-orphans.sh \
    infra/aws/lab/variables.tf \
    infra/aws/lab/security.tf \
    infra/aws/lab/modules/security/main.tf \
    infra/aws/lab/modules/security/variables.tf; do
    [[ -f "$repo_root/$relative" && ! -L "$repo_root/$relative" ]] \
      || fail "operator identity file is missing or unsafe: $relative"
    printf '%s\t%s\n' "$(sha256_file "$repo_root/$relative")" "$relative" >> "$inventory"
  done
  LC_ALL=C sort -k2,2 "$inventory" | sha256_text
}

publish_immutable_json() {
  local key=$1 source=$2 readback
  local put_status=0
  readback="$temp_dir/immutable-readback-$(printf '%s' "$key" | sha256_text).json"
  aws s3api put-object --bucket "$evidence_bucket" --key "$key" --body "$source" \
    --if-none-match '*' --tagging Retention=summary --server-side-encryption AES256 \
    --content-type application/json --region "$AWS_REGION" --no-cli-pager >/dev/null 2>&1 \
    || put_status=$?
  if [[ "$put_status" -ne 0 ]]; then
    aws s3api get-object --bucket "$evidence_bucket" --key "$key" "$readback" \
      --region "$AWS_REGION" --no-cli-pager >/dev/null 2>&1 \
      || fail "immutable evidence already exists but cannot be read: $key"
    cmp -s "$source" "$readback" \
      || fail "immutable evidence differs from the requested bytes: $key"
    return 0
  fi
  aws s3api get-object --bucket "$evidence_bucket" --key "$key" "$readback" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "immutable evidence cannot be read back: $key"
  cmp -s "$source" "$readback" \
    || fail "immutable evidence read-back differs: $key"
}

state_object_present() {
  local state_object
  state_object=$(aws s3api list-objects-v2 --bucket "$state_bucket" \
    --prefix "$lab_state_key" --max-items 1 \
    --query "Contents[?Key=='$lab_state_key'].Key | [0]" --output text \
    --region "$AWS_REGION" --no-cli-pager) || fail "cannot inspect the lab state identity"
  [[ "$state_object" == "$lab_state_key" ]]
}

capture_state_object_identity() {
  local identity="$temp_dir/state-object-identity.json" state_file="$temp_dir/terraform-state.json"
  aws s3api head-object --bucket "$state_bucket" --key "$lab_state_key" \
    --query '{versionId:VersionId,contentLength:ContentLength}' --output json \
    --region "$AWS_REGION" --no-cli-pager > "$identity" \
    || fail "cannot read the Terraform state object identity"
  state_version_id=$(jq -er '.versionId | select(type == "string" and length > 0)' "$identity") \
    || fail "Terraform state object has no version identity"
  aws s3api get-object --bucket "$state_bucket" --key "$lab_state_key" \
    --version-id "$state_version_id" "$state_file" --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "cannot read the exact Terraform state version"
  state_object_sha256=$(sha256_file "$state_file")
  state_version_hash=$(printf '%s' "$state_version_id" | sha256_text)
  [[ "$state_object_sha256" =~ ^[0-9a-f]{64}$ && "$state_version_hash" =~ ^[0-9a-f]{64}$ ]] \
    || fail "Terraform state identity hashes are invalid"
}

terraform_state_is_empty() {
  local state_resources
  prepare_lab_backend
  state_resources=$(run_terraform_command "Terraform empty-state inspection" \
    -chdir="$lab_root" state list 2>/dev/null) \
    || fail "cannot inspect Terraform state resources"
  [[ -z "$state_resources" ]]
}

terraform_state_is_identity_only() {
  local state_resources address
  local -a state_addresses=()
  prepare_lab_backend
  state_resources=$(run_terraform_command "Terraform identity-state inspection" \
    -chdir="$lab_root" state list 2>/dev/null) \
    || fail "cannot inspect Terraform state resources"
  while IFS= read -r address; do
    [[ -z "$address" ]] || state_addresses+=("$address")
  done <<<"$state_resources"
  [[ "${#state_addresses[@]}" -eq 1 && \
    "${state_addresses[0]}" == "terraform_data.run_identity" ]] || return 1
  assert_current_state_identity
}

capture_terraform_state_inventory() {
  local inventory_file=$1 state_resources address
  local state_json="$inventory_file.state.json" listed_json="$inventory_file.listed.json"
  local -a listed_addresses=()
  prepare_lab_backend
  state_resources=$(run_terraform_command "Terraform state-list inventory" \
    -chdir="$lab_root" state list 2>/dev/null) \
    || fail "cannot enumerate Terraform state"
  while IFS= read -r address; do
    [[ -z "$address" ]] && continue
    [[ "$address" != -* && "$address" != *$'\r'* ]] \
      || fail "Terraform state returned an unsafe address"
    listed_addresses+=("$address")
  done <<<"$state_resources"
  if [[ "${#listed_addresses[@]}" -eq 0 ]]; then
    printf '%s\n' '[]' > "$listed_json"
  else
    printf '%s\n' "${listed_addresses[@]}" | jq -Rsc 'split("\n")[:-1]' > "$listed_json"
  fi
  run_terraform_command "Terraform JSON state inventory" \
    -chdir="$lab_root" show -json > "$state_json" \
    || fail "cannot inspect Terraform state inventory"
  jq '
    def modules: ., (.child_modules[]? | modules);
    [.values.root_module? | modules | .resources[]? |
      {address,mode,type,name,deposedKey:(.deposed_key? // null)}
    ] |
    group_by(.address) |
    map(
      . as $objects |
      ($objects | map({mode,type,name}) | unique) as $identities |
      ($objects | map(select(.deposedKey == null)) | length) as $currentCount |
      ($objects | map(.deposedKey) | map(select(. != null))) as $deposedKeys |
      {
        address: $objects[0].address,
        mode: $objects[0].mode,
        type: $objects[0].type,
        name: $objects[0].name,
        stateObjectCount: ($objects | length),
        identityVariantCount: ($identities | length),
        currentObjectCount: $currentCount,
        deposedKeys: $deposedKeys
      }
    )
  ' "$state_json" > "$inventory_file"
  jq -e --slurpfile listed "$listed_json" '
    all(.[];
      (.address | type == "string" and length > 0) and
      (.mode == "managed" or .mode == "data") and
      (.type | type == "string" and length > 0) and
      (.name | type == "string" and length > 0) and
      (.stateObjectCount | type == "number" and floor == . and . >= 1) and
      .identityVariantCount == 1 and
      (.currentObjectCount | type == "number" and floor == . and . >= 0 and . <= 1) and
      (.deposedKeys | type == "array") and
      all(.deposedKeys[]; type == "string" and length > 0) and
      (.deposedKeys | length) == (.deposedKeys | unique | length) and
      .stateObjectCount == (.currentObjectCount + (.deposedKeys | length))
    ) and
    ([.[].address] | length) == ([.[].address] | unique | length) and
    ($listed[0] | length) == ($listed[0] | unique | length) and
    ([.[].address] | sort) == ($listed[0] | sort)
  ' "$inventory_file" >/dev/null \
    || fail "Terraform state list and JSON inventory differ"
}

validate_identity_state_file() {
  local state_file=$1 expected_run=$2 expected_resource_fence=$3
  jq -e --arg run "$expected_run" --argjson resourceFence "$expected_resource_fence" '
    .resources[0] as $identity |
    .version == 4 and .terraform_version == "1.15.5" and
    (.lineage | type == "string" and length > 0) and
    (.serial | type == "number" and floor == . and . >= 0) and
    (.resources | type == "array" and length == 1) and
    (($identity.module? // "") == "") and
    $identity.mode == "managed" and
    $identity.type == "terraform_data" and
    $identity.name == "run_identity" and
    $identity.provider == "provider[\"terraform.io/builtin/terraform\"]" and
    ($identity.instances | type == "array" and length == 1) and
    (($identity.instances[0].index_key? // null) == null) and
    $identity.instances[0].schema_version == 0 and
    $identity.instances[0].identity_schema_version == 0 and
    ($identity.instances[0].sensitive_attributes | type == "array" and length == 0) and
    ($identity.instances[0].attributes | keys | sort) == ["id", "input", "output", "triggers_replace"] and
    ($identity.instances[0].attributes.id | type == "string" and length > 0) and
    $identity.instances[0].attributes.triggers_replace == null and
    $identity.instances[0].attributes.input.type ==
      ["object", {"resource_fencing_token":"number", "run_id":"string"}] and
    $identity.instances[0].attributes.output.type ==
      ["object", {"resource_fencing_token":"number", "run_id":"string"}] and
    $identity.instances[0].attributes.input.value ==
      $identity.instances[0].attributes.output.value and
    $identity.instances[0].attributes.input.value.run_id == $run and
    $identity.instances[0].attributes.input.value.resource_fencing_token == $resourceFence and
    $identity.instances[0].attributes.output.value.run_id == $run and
    $identity.instances[0].attributes.output.value.resource_fencing_token == $resourceFence
  ' "$state_file" >/dev/null
}

validate_empty_state_successor() {
  local state_file=$1 expected_lineage=$2 predecessor_serial=$3
  jq -e --arg lineage "$expected_lineage" --argjson predecessorSerial "$predecessor_serial" '
    .lineage == $lineage and
    .serial == ($predecessorSerial + 1) and
    (.resources | type == "array" and length == 0)
  ' "$state_file" >/dev/null
}

validate_teardown_finalize_binding() {
  local finalize_key=$1 finalize_version=$2 expected_run=$3 expected_resource_fence=$4
  local expected_dns_mode=$5 expected_start_key=$6 expected_start_version=$7
  local journal="$temp_dir/teardown-finalize-binding.json"
  local predecessor="$temp_dir/teardown-finalize-predecessor.tfstate"
  local predecessor_key predecessor_version predecessor_sha predecessor_lineage predecessor_serial
  aws s3api get-object --bucket "$evidence_bucket" --key "$finalize_key" \
    --version-id "$finalize_version" "$journal" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "teardown finalization cannot read the exact finalize journal"
  jq -e --arg run "$expected_run" --argjson resourceFence "$expected_resource_fence" \
    --arg dnsMode "$expected_dns_mode" --arg stateKey "$lab_state_key" \
    --arg startKey "$expected_start_key" --arg startVersion "$expected_start_version" '
      .schemaVersion == 1 and .status == "ready" and .runId == $run and
      .resourceFencingToken == $resourceFence and .dnsMode == $dnsMode and
      .teardownStart.key == $startKey and .teardownStart.versionId == $startVersion and
      .terraformState.key == $stateKey and .terraformState.resourceCount == 1 and
      .terraformState.identityAddress == "terraform_data.run_identity" and
      (.terraformState.versionId | type == "string" and length > 0) and
      (.terraformState.versionIdSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.terraformState.objectSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.terraformState.lineage | type == "string" and length > 0) and
      (.terraformState.serial | type == "number" and floor == . and . >= 0)
    ' "$journal" >/dev/null || fail "teardown finalize journal is invalid"
  predecessor_key=$(jq -er '.terraformState.key' "$journal")
  predecessor_version=$(jq -er '.terraformState.versionId' "$journal")
  predecessor_sha=$(jq -er '.terraformState.objectSha256' "$journal")
  predecessor_lineage=$(jq -er '.terraformState.lineage' "$journal")
  predecessor_serial=$(jq -er '.terraformState.serial' "$journal")
  [[ "$(printf '%s' "$predecessor_version" | sha256_text)" == \
    "$(jq -er '.terraformState.versionIdSha256' "$journal")" ]] \
    || fail "teardown finalize predecessor VersionId hash is invalid"
  aws s3api get-object --bucket "$state_bucket" --key "$predecessor_key" \
    --version-id "$predecessor_version" "$predecessor" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "teardown finalization cannot read the exact predecessor state"
  [[ "$(sha256_file "$predecessor")" == "$predecessor_sha" ]] \
    || fail "teardown finalize predecessor state SHA differs"
  validate_identity_state_file "$predecessor" "$expected_run" "$expected_resource_fence" \
    || fail "teardown finalize predecessor is not the matching identity-only state"
  [[ "$(jq -er '.lineage' "$predecessor")" == "$predecessor_lineage" && \
    "$(jq -er '.serial' "$predecessor")" == "$predecessor_serial" ]] \
    || fail "teardown finalize predecessor lineage or serial differs"
  validate_empty_state_successor "$temp_dir/terraform-state.json" \
    "$predecessor_lineage" "$predecessor_serial" \
    || fail "empty Terraform state is not the direct successor of teardown finalize"
}

validate_clean_state_receipt() {
  local receipt="$temp_dir/state-clean.json" start_journal="$temp_dir/state-clean-teardown-start.json"
  local start_key start_version start_resource_fence start_dns_mode finalize_key finalize_version
  capture_state_object_identity
  state_clean_key="measurements/state-clean/$state_version_hash.json"
  aws s3api get-object --bucket "$evidence_bucket" --key "$state_clean_key" "$receipt" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "empty Terraform state has no exact clean-state receipt"
  jq -e --arg key "$lab_state_key" --arg version "$state_version_id" \
    --arg stateSha "$state_object_sha256" --arg versionHash "$state_version_hash" '
      .schemaVersion == 1 and .status == "clean" and
      .terraformState.key == $key and
      .terraformState.versionId == $version and
      .terraformState.versionIdSha256 == $versionHash and
      .terraformState.objectSha256 == $stateSha and
      .terraformState.resourceCount == 0 and
      (.runId | type == "string" and test("^[a-z0-9][a-z0-9-]{2,31}$")) and
      .orphanScan.runId == .runId and
      .teardownStart.key == ("measurements/" + .runId + "/teardown-start.json") and
      (.teardownStart.versionId | type == "string" and length > 0) and
      .teardownFinalize.key == ("measurements/" + .runId + "/teardown-finalize.json") and
      (.teardownFinalize.versionId | type == "string" and length > 0) and
      (.resourceFencingToken | type == "number" and . > 0 and floor == .) and
      (.dnsMode == "direct-only" or .dnsMode == "cutover") and
      .ociAuthority.status == "verified" and
      .orphanScan.status == "clean" and .orphanScan.scope == "global"
    ' "$receipt" >/dev/null || fail "clean-state receipt does not bind the exact empty backend"
  state_clean_run_id=$(jq -er '.runId' "$receipt")
  valid_run_id "$state_clean_run_id" || fail "clean-state receipt run ID is invalid"
  start_key=$(jq -er '.teardownStart.key' "$receipt")
  start_version=$(jq -er '.teardownStart.versionId' "$receipt")
  start_resource_fence=$(jq -er '.resourceFencingToken' "$receipt")
  start_dns_mode=$(jq -er '.dnsMode' "$receipt")
  finalize_key=$(jq -er '.teardownFinalize.key' "$receipt")
  finalize_version=$(jq -er '.teardownFinalize.versionId' "$receipt")
  aws s3api get-object --bucket "$evidence_bucket" --key "$start_key" \
    --version-id "$start_version" "$start_journal" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "clean-state receipt cannot read its exact teardown-start journal"
  jq -e --arg run "$state_clean_run_id" --argjson resourceFence "$start_resource_fence" \
    --arg dnsMode "$start_dns_mode" --arg stateKey "$lab_state_key" '
      .schemaVersion == 1 and .status == "started" and .runId == $run and
      .resourceFencingToken == $resourceFence and .dnsMode == $dnsMode and
      .terraformState.key == $stateKey and
      (.terraformState.versionId | type == "string" and length > 0) and
      (.terraformState.objectSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      .ociAuthority.status == "verified"
    ' "$start_journal" >/dev/null || fail "clean-state receipt has an incoherent teardown-start journal"
  validate_teardown_finalize_binding "$finalize_key" "$finalize_version" \
    "$state_clean_run_id" "$start_resource_fence" "$start_dns_mode" "$start_key" "$start_version"
}

assert_reusable_or_absent_state() {
  if ! state_object_present; then
    AIRBOB_SCAN_SCOPE=global "$orphan_scanner" "$run_id" >/dev/null \
      || fail "absent-state reuse failed a fresh zero-orphan scan"
    return 0
  fi
  terraform_state_is_empty || fail "refusing to replace an active lab; run status or down first"
  validate_clean_state_receipt
  AIRBOB_SCAN_SCOPE=global "$orphan_scanner" "$state_clean_run_id" >/dev/null \
    || fail "empty-state reuse failed a fresh zero-orphan scan"
}

prepare_lab_backend() {
  [[ "$lab_backend_prepared" == false ]] || return 0
  "$backend_helper" lab >/dev/null || return 1
  run_terraform_command "Terraform backend initialization" \
    -chdir="$lab_root" init -input=false -lockfile=readonly \
    -backend-config=backend.generated.hcl >/dev/null || return 1
  lab_backend_prepared=true
}

terraform_lock_object_present() {
  local observed_key
  observed_key=$(aws s3api list-objects-v2 --bucket "$state_bucket" \
    --prefix "$terraform_lock_key" --max-items 1 \
    --query "Contents[?Key=='$terraform_lock_key'].Key | [0]" --output text \
    --region "$AWS_REGION" --no-cli-pager) \
    || fail "cannot inspect the exact Terraform native lock object"
  case "$observed_key" in
    "$terraform_lock_key") return 0 ;;
    None|'') return 1 ;;
    *) fail "Terraform native lock lookup escaped the exact Lab lock key" ;;
  esac
}

recover_prior_terraform_lock() {
  local lock_file="$temp_dir/terraform-lock.json"
  local lock_readback="$temp_dir/terraform-lock-readback.json"
  local lock_head="$temp_dir/terraform-lock-head.json"
  local lock_head_readback="$temp_dir/terraform-lock-head-readback.json"
  local lease_file="$temp_dir/terraform-lock-lease.json"
  local clock_receipt="$temp_dir/terraform-lock-recovery-clock.json"
  local clock_head="$temp_dir/terraform-lock-recovery-clock-head.json"
  local lock_size lock_id lock_created lock_created_base lock_created_epoch
  local lock_version_id lock_last_modified lock_last_modified_epoch
  local clock_key clock_last_modified clock_server_epoch
  local lease_acquired_epoch now_epoch canonical_created credential_expiry_epoch
  local role_name role_max_session

  [[ "$lease_acquired" == true && "$lab_backend_prepared" == true ]] \
    || fail "Terraform lock recovery requires the fenced initialized Lab backend"
  assert_lease
  terraform_lock_object_present || return 0
  [[ -z "$active_mutation_pid" && -z "$active_mutation_pgid" ]] \
    || fail "Terraform lock recovery requires no active supervised process group"
  aws s3api head-object --bucket "$state_bucket" --key "$terraform_lock_key" \
    --query '{contentLength:ContentLength,lastModified:LastModified,versionId:VersionId}' \
    --output json --region "$AWS_REGION" --no-cli-pager > "$lock_head" \
    || fail "cannot inspect the exact Terraform native lock identity"
  jq -e '
    (keys | sort) == ["contentLength","lastModified","versionId"] and
    (.contentLength | type == "number") and
    (.lastModified | type == "string" and length > 0) and
    (.versionId | type == "string" and length > 0 and length <= 1024)
  ' "$lock_head" >/dev/null \
    || fail "Terraform native lock S3 identity is outside the closed contract"
  lock_size=$(jq -er '.contentLength' "$lock_head")
  [[ "$lock_size" =~ ^[1-9][0-9]{1,3}$ && "$lock_size" -le 4096 ]] \
    || fail "Terraform native lock size is outside the closed LockInfo contract"
  lock_version_id=$(jq -er '.versionId' "$lock_head")
  lock_last_modified=$(jq -er '.lastModified' "$lock_head")
  lock_last_modified_epoch=$(aws_utc_timestamp_epoch "$lock_last_modified") \
    || fail "Terraform native lock S3 LastModified is not a real UTC instant"
  aws s3api get-object --bucket "$state_bucket" --key "$terraform_lock_key" \
    "$lock_file" --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "cannot read the exact Terraform native lock"
  jq -e --arg path "$terraform_lock_path" '
    (keys | sort) == ["Created","ID","Info","Operation","Path","Version","Who"] and
    (.ID | type == "string" and test("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) and
    (.Operation == "init" or .Operation == "OperationTypeInvalid" or
      .Operation == "OperationTypePlan" or .Operation == "OperationTypeApply") and
    .Info == "" and
    (.Who | type == "string" and length >= 3 and length <= 255 and test("^[A-Za-z0-9._@-]+$")) and
    .Version == "1.15.5" and .Path == $path and
    (.Created | type == "string" and test("^[0-9]{4}-(0[1-9]|1[0-2])-([0-2][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,9})?Z$")) and
    ([.ID,.Operation,.Info,.Who,.Version,.Created,.Path] |
      all(test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not))
  ' "$lock_file" >/dev/null \
    || fail "Terraform native lock is not a closed secret-free Terraform 1.15.5 LockInfo"
  lock_id=$(jq -er '.ID' "$lock_file")
  lock_created=$(jq -er '.Created' "$lock_file")
  if [[ "$lock_created" == *.*Z ]]; then
    lock_created_base="${lock_created%%.*}Z"
    [[ "${lock_created%Z}" != *0 ]] \
      || fail "Terraform native lock Created is not canonical RFC3339Nano"
  else
    lock_created_base=$lock_created
  fi
  canonical_created=$(jq -nr --arg timestamp "$lock_created_base" \
    '$timestamp | fromdateiso8601 | strftime("%Y-%m-%dT%H:%M:%SZ")') \
    || fail "Terraform native lock Created cannot be parsed"
  [[ "$canonical_created" == "$lock_created_base" ]] \
    || fail "Terraform native lock Created is not a real canonical UTC instant"
  lock_created_epoch=$(jq -nr --arg timestamp "$lock_created_base" '$timestamp | fromdateiso8601')
  aws dynamodb get-item --table-name "$lease_table" \
    --key "{\"LockName\":{\"S\":\"$lease_lock_id\"}}" --consistent-read \
    --query 'Item.{lockName:LockName.S,owner:Owner.S,fencingToken:FencingToken.N,runId:RunId.S,command:Command.S,acquiredAt:AcquiredAt.N,heartbeatAt:HeartbeatAt.N,expiresAt:ExpiresAt.N,commandDeadline:CommandDeadline.N}' \
    --output json --region "$AWS_REGION" --no-cli-pager > "$lease_file" \
    || fail "cannot read the current orchestration lease for Terraform lock recovery"
  now_epoch=$(date +%s)
  jq -e --arg lock "$lease_lock_id" --arg owner "$lease_owner" \
    --arg token "$fencing_token" --arg run "$run_id" --arg command "$lease_command" \
    --argjson now "$now_epoch" '
      (keys | sort) == ["acquiredAt","command","commandDeadline","expiresAt","fencingToken","heartbeatAt","lockName","owner","runId"] and
      .lockName == $lock and .owner == $owner and .fencingToken == $token and .runId == $run and .command == $command and
      ([.acquiredAt,.heartbeatAt,.expiresAt,.commandDeadline] | all(type == "string" and test("^[1-9][0-9]*$"))) and
      (.acquiredAt | tonumber) <= (.heartbeatAt | tonumber) and (.heartbeatAt | tonumber) <= $now and
      (.expiresAt | tonumber) >= $now and (.commandDeadline | tonumber) >= $now
    ' "$lease_file" >/dev/null \
    || fail "Terraform lock recovery lease identity or validity differs"
  lease_acquired_epoch=$(jq -er '.acquiredAt | tonumber' "$lease_file")
  ((lock_created_epoch < lease_acquired_epoch)) \
    || fail "Terraform native lock was created at or after the current orchestration lease"
  assert_lease
  for role_name in airbob-lab-operator airbob-lab-cutover-operator; do
    role_max_session=$(aws iam get-role --role-name "$role_name" \
      --query 'Role.MaxSessionDuration' --output text \
      --region "$AWS_REGION" --no-cli-pager) \
      || fail "cannot verify the Lab role credential-expiry boundary"
    [[ "$role_max_session" == "$LAB_ROLE_MAX_SESSION_SECONDS" ]] \
      || fail "Lab role MaxSessionDuration differs from the Terraform lock recovery boundary"
  done
  clock_key="measurements/$run_id/teardown-terraform-lock-clock-$fencing_token.json"
  jq -nS --arg runId "$run_id" --argjson fencingToken "$fencing_token" \
    --arg stateBucket "$state_bucket" --arg lockKey "$terraform_lock_key" \
    --arg lockVersionId "$lock_version_id" --arg lockId "$lock_id" \
    --argjson credentialExpiryBoundarySeconds "$TERRAFORM_LOCK_CREDENTIAL_EXPIRY_BARRIER_SECONDS" \
    '{schemaVersion:1,runId:$runId,fencingToken:$fencingToken,terraformLock:{bucket:$stateBucket,key:$lockKey,versionId:$lockVersionId,id:$lockId},credentialExpiryBoundarySeconds:$credentialExpiryBoundarySeconds}' \
    > "$clock_receipt"
  publish_immutable_json "$clock_key" "$clock_receipt"
  aws s3api head-object --bucket "$evidence_bucket" --key "$clock_key" \
    --query '{lastModified:LastModified,versionId:VersionId}' --output json \
    --region "$AWS_REGION" --no-cli-pager > "$clock_head" \
    || fail "cannot read the AWS-authoritative Terraform lock recovery clock"
  jq -e '
    (keys | sort) == ["lastModified","versionId"] and
    (.lastModified | type == "string" and length > 0) and
    (.versionId | type == "string" and length > 0 and length <= 1024)
  ' "$clock_head" >/dev/null \
    || fail "Terraform lock recovery clock S3 identity is outside the closed contract"
  clock_last_modified=$(jq -er '.lastModified' "$clock_head")
  clock_server_epoch=$(aws_utc_timestamp_epoch "$clock_last_modified") \
    || fail "Terraform lock recovery clock S3 LastModified is not a real UTC instant"
  credential_expiry_epoch=$((
    lock_last_modified_epoch + TERRAFORM_LOCK_CREDENTIAL_EXPIRY_BARRIER_SECONDS
  ))
  ((clock_server_epoch >= credential_expiry_epoch)) \
    || fail "Terraform native lock is younger than the AWS-authoritative static STS credential-expiry barrier"
  assert_lease
  aws s3api get-object --bucket "$state_bucket" --key "$terraform_lock_key" \
    "$lock_readback" --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "cannot re-read the exact Terraform native lock before recovery"
  cmp -s "$lock_file" "$lock_readback" \
    || fail "Terraform native lock changed during fenced recovery"
  aws s3api head-object --bucket "$state_bucket" --key "$terraform_lock_key" \
    --query '{contentLength:ContentLength,lastModified:LastModified,versionId:VersionId}' \
    --output json --region "$AWS_REGION" --no-cli-pager > "$lock_head_readback" \
    || fail "cannot re-read the Terraform native lock S3 identity before recovery"
  cmp -s "$lock_head" "$lock_head_readback" \
    || fail "Terraform native lock S3 identity changed during fenced recovery"
  assert_lease
  run_supervised_mutation "Terraform native stale-lock recovery" \
    terraform -chdir="$lab_root" force-unlock -force "$lock_id" >/dev/null \
    || fail "Terraform refused the validated native stale-lock identity"
  assert_lease
  if terraform_lock_object_present; then
    fail "Terraform native lock remains after force-unlock readback"
  fi
}

current_state_identity() {
  local state_json state_identity
  prepare_lab_backend
  if state_identity=$(run_terraform_command "Terraform run-identity output" \
    -chdir="$lab_root" output -json run_identity 2>/dev/null); then
    printf '%s\n' "$state_identity"
    return 0
  fi
  state_json=$(run_terraform_command "Terraform run-identity state fallback" \
    -chdir="$lab_root" show -json 2>/dev/null) || return 1
  jq -cer '
    [.values.root_module.resources[]? |
      select(
        .address == "terraform_data.run_identity" and
        .mode == "managed" and .type == "terraform_data" and .name == "run_identity"
      ) | .values.output] |
    select(length == 1) | .[0] |
    select(
      (keys | sort) == ["resource_fencing_token", "run_id"] and
      (.run_id | type == "string") and
      (.resource_fencing_token | type == "number")
    )
  ' <<<"$state_json"
}

current_run_id() {
  current_state_identity | jq -er '.run_id'
}

assert_current_state_identity() {
  local state_identity state_run_id state_resource_fencing_token
  state_identity=$(current_state_identity) || fail "Terraform state run identity is unavailable"
  state_run_id=$(jq -er '.run_id' <<<"$state_identity") \
    || fail "Terraform state run identity is invalid"
  state_resource_fencing_token=$(jq -er '.resource_fencing_token' <<<"$state_identity") \
    || fail "Terraform state resource fencing identity is invalid"
  valid_run_id "$state_run_id" || fail "Terraform state run identity is invalid"
  [[ "$state_resource_fencing_token" =~ ^[1-9][0-9]*$ ]] \
    || fail "Terraform state resource fencing identity is invalid"
  if [[ "$state_run_id" != "$run_id" ]]; then
    fail "Terraform state run changed before lease acquisition"
  fi
  if [[ "$state_resource_fencing_token" != "$resource_fencing_token" ]]; then
    fail "Terraform state resource fencing token differs from the run manifest"
  fi
}

assert_state_run_identity() {
  local requirement=${1:-required}
  [[ "$requirement" == required || "$requirement" == allow-absent ]] \
    || fail "state identity requirement is invalid"
  if ! state_object_present; then
    [[ "$requirement" == allow-absent ]] || fail "Terraform state run identity is unavailable"
    return 0
  fi
  if [[ "$requirement" == allow-absent ]]; then
    assert_reusable_or_absent_state
    return 0
  fi
  assert_current_state_identity
}

read_run_manifest() {
  local selected_run=$1 destination=$2
  aws s3api get-object --bucket "$evidence_bucket" \
    --key "runs/$selected_run/operator.json" "$destination" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "run manifest is missing"
  jq -e --arg run "$selected_run" '(.schemaVersion == 1 or .schemaVersion == 2) and .runId == $run' "$destination" >/dev/null \
    || fail "run manifest is invalid"
}

write_run_manifest() {
  local manifest=$1 readback="$temp_dir/operator-manifest-readback.json"
  aws s3api put-object --bucket "$evidence_bucket" \
    --key "runs/$run_id/operator.json" --body "$manifest" \
    --if-none-match '*' \
    --tagging Retention=summary --server-side-encryption AES256 \
    --content-type application/json --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "RUN_ID has already been used"
  aws s3api get-object --bucket "$evidence_bucket" \
    --key "runs/$run_id/operator.json" "$readback" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "run manifest cannot be read back"
  cmp -s "$manifest" "$readback" || fail "run manifest read-back differs"
}

start_mutation_guard() {
  lease_owner=${LEASE_OWNER:-${GITHUB_REPOSITORY:-local}/${GITHUB_RUN_ID:-$(id -un)}:${GITHUB_RUN_ATTEMPT:-1}}
  [[ "$lease_owner" =~ ^[A-Za-z0-9._:@/-]{3,128}$ ]] || fail "lease owner is not canonical"
  token_output=$("$lease_script" acquire "$lease_table" "$lease_lock_id" "$lease_owner" \
    "$run_id" "$lease_command" "$HEARTBEAT_TTL_SECONDS" "$COMMAND_DEADLINE_SECONDS")
  fencing_token=${token_output#fencing_token=}
  [[ "$fencing_token" =~ ^[1-9][0-9]*$ ]] || fail "lease did not issue a fencing token"
  lease_acquired=true
  parent_pid=$$
  (
    while sleep "$HEARTBEAT_INTERVAL_SECONDS"; do
      "$lease_script" heartbeat "$lease_table" "$lease_lock_id" "$lease_owner" \
        "$fencing_token" "$run_id" "$lease_command" "$HEARTBEAT_TTL_SECONDS" >/dev/null \
        || { kill -USR1 "$parent_pid" 2>/dev/null || true; exit 1; }
    done
  ) &
  heartbeat_pid=$!
  (
    sleep "$COMMAND_DEADLINE_SECONDS"
    kill -USR2 "$parent_pid" 2>/dev/null || true
  ) &
  watchdog_pid=$!
}

assert_lease() {
  "$lease_script" assert "$lease_table" "$lease_lock_id" "$lease_owner" \
    "$fencing_token" "$run_id" "$lease_command" >/dev/null
}

validate_operator_dataset_manifest() {
  local dataset_manifest=$1 expected_release=$2
  jq -e --arg expectedRelease "$expected_release" '
    .schemaVersion == 2 and
    .datasetRelease == $expectedRelease and
    .releaseKind == "pipeline-rehearsal" and
    .mysql.flywayVersion == "27" and
    .mysql.expectedTableRows.flyway_schema_history == 27 and
    .source.legacyBenchmarkManifestKey == "benchmark/manifest.json" and
    (.source.legacyBenchmarkManifestSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    .source.benchmarkDatasetManifestKey == "benchmark/dataset-manifest.json" and
    (.source.benchmarkDatasetManifestSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    .releaseTuple.manifestSha256 == .source.benchmarkDatasetManifestSha256 and
    (.search.enabled | type == "boolean") and
    ([.. | objects | keys[]] |
      all(test("password|passwd|secret|credential|token|session|access.?key|private.?key|service.?account"; "i") | not))
  ' "$dataset_manifest" >/dev/null
}

load_release_smoke_inputs() {
  local dataset_manifest=$1
  local legacy_manifest="$temp_dir/benchmark-manifest.json"
  local composite_manifest="$temp_dir/benchmark-dataset-manifest.json"
  local legacy_manifest_sha composite_manifest_sha

  aws s3api get-object --bucket "$dataset_bucket" \
    --key "datasets/$dataset_release/benchmark/manifest.json" "$legacy_manifest" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "legacy benchmark manifest is unavailable"
  legacy_manifest_sha=$(sha256_file "$legacy_manifest")
  [[ "$legacy_manifest_sha" == "$(jq -r '.source.legacyBenchmarkManifestSha256' "$dataset_manifest")" ]] \
    || fail "legacy benchmark manifest digest does not match the release"
  smoke_accommodation_id=$(jq -er '
    select(.datasetVersion == "nplus1-v1") |
    .hostAccommodations.detailAccommodationId |
    select(type == "number" and floor == . and . > 0)
  ' "$legacy_manifest") || fail "legacy benchmark manifest has no representative accommodation"
  [[ "$smoke_accommodation_id" =~ ^[1-9][0-9]{0,18}$ ]] \
    || fail "representative accommodation ID is invalid"

  smoke_search_enabled=$(jq -r '.search.enabled' "$dataset_manifest")
  smoke_search_target="$temp_dir/search-smoke-target.json"
  if [[ "$smoke_search_enabled" == true ]]; then
    aws s3api get-object --bucket "$dataset_bucket" \
      --key "datasets/$dataset_release/benchmark/dataset-manifest.json" "$composite_manifest" \
      --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "benchmark dataset manifest is unavailable"
    composite_manifest_sha=$(sha256_file "$composite_manifest")
    [[ "$composite_manifest_sha" == "$(jq -r '.source.benchmarkDatasetManifestSha256' "$dataset_manifest")" ]] \
      || fail "benchmark dataset manifest digest does not match the release"
    jq -ce '
      select(.schemaVersion == 2 and .datasetVersion == "benchmark-dataset-v2" and
        .world.version == "world-v2") |
      [.capsules[] |
        select(.capsuleId == "index-query-v1" and .mutability == "READ_ONLY") |
        .targets[] | select(.id == "search-narrow")] as $targets |
      select(($targets | length) == 1) | $targets[0] |
      select(.expectedRows == 1 and
        (.resourceIds | type == "array" and length == 1 and
          .[0] > 0 and .[0] == (.[0] | floor)) and
        (.expectedResultHash | type == "string" and test("^[0-9a-f]{64}$")) and
        (.query | keys) == [
          "adultOccupancy", "bottomRightLat", "bottomRightLng", "childOccupancy",
          "destination", "infantOccupancy", "kind", "maxPrice", "minPrice", "page",
          "petOccupancy", "topLeftLat", "topLeftLng"
        ] and
        .query.kind == "ACCOMMODATION_SEARCH_V1" and
        (.query.destination | type == "string") and
        ([.query.minPrice, .query.maxPrice, .query.adultOccupancy, .query.childOccupancy,
          .query.infantOccupancy, .query.petOccupancy, .query.page] |
          all(.[]; type == "number" and floor == . and . >= 0)) and
        .query.adultOccupancy >= 1 and .query.minPrice <= .query.maxPrice and
        ([.query.topLeftLat, .query.topLeftLng, .query.bottomRightLat, .query.bottomRightLng] |
          all(.[]; type == "number")))
    ' "$composite_manifest" > "$smoke_search_target" \
      || fail "benchmark dataset manifest has no exact search smoke target"
  fi
}

resolve_release_inputs() {
  local checksum_file="$temp_dir/bundle.sha256" dataset_manifest="$temp_dir/dataset-manifest.json"
  local bundle_manifest="$temp_dir/bundle-manifest.json"
  local tagged_app_digest bundle_manifest_key
  app_digest=${IMAGE_DIGEST:-}
  [[ "$app_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "IMAGE_DIGEST must be one canonical sha256 digest"
  bundle_commit=${BUNDLE_COMMIT:-}
  [[ "$bundle_commit" =~ ^[0-9a-f]{40}$ ]] || fail "BUNDLE_COMMIT is required as one full runtime Git commit"
  bundle_manifest_version_id=${BUNDLE_MANIFEST_VERSION_ID:-}
  [[ "$bundle_manifest_version_id" =~ ^[A-Za-z0-9._-]+$ && ${#bundle_manifest_version_id} -le 1024 ]] \
    || fail "BUNDLE_MANIFEST_VERSION_ID is required and must be canonical"
  execution_commit=$(git -C "$repo_root" rev-parse HEAD 2>/dev/null) \
    || fail "execution repository HEAD is unavailable"
  [[ "$execution_commit" =~ ^[0-9a-f]{40}$ ]] || fail "execution repository HEAD is invalid"
  [[ -z "$(git -C "$repo_root" status --porcelain --untracked-files=all)" ]] \
    || fail "up requires one clean reviewed execution commit"
  operator_tree_sha256=$(canonical_operator_tree_sha256)
  dataset_release=${DATASET_RELEASE:-}
  [[ "$dataset_release" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ ]] || fail "DATASET_RELEASE is required and must be canonical"
  dataset_manifest_version_id=${DATASET_MANIFEST_VERSION_ID:-}
  [[ "$dataset_manifest_version_id" =~ ^[A-Za-z0-9._-]+$ && ${#dataset_manifest_version_id} -le 1024 ]] \
    || fail "DATASET_MANIFEST_VERSION_ID is required and must be canonical"

  bundle_archive="airbob-service-bundles-$bundle_commit.tar.gz"
  bundle_manifest_key="service-bundles/$bundle_commit/airbob-service-bundles-$bundle_commit.manifest.json"
  aws s3api get-object --bucket "$bundle_bucket" \
    --key "service-bundles/$bundle_commit/$bundle_archive.sha256" "$checksum_file" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "immutable service bundle checksum is unavailable"
  bundle_sha256=$(awk 'NR == 1 {print $1}' "$checksum_file")
  [[ "$bundle_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "bundle checksum object is invalid"
  bundle_checksum_version_id=$(aws s3api head-object --bucket "$bundle_bucket" \
    --key "service-bundles/$bundle_commit/$bundle_archive.sha256" --query VersionId \
    --output text --region "$AWS_REGION" --no-cli-pager)
  [[ -n "$bundle_checksum_version_id" && "$bundle_checksum_version_id" != None \
    && -n "$bundle_manifest_version_id" && "$bundle_manifest_version_id" != None ]] \
    || fail "service bundle objects have no immutable version identity"
  aws s3api get-object --bucket "$bundle_bucket" --key "$bundle_manifest_key" \
    --version-id "$bundle_manifest_version_id" "$bundle_manifest" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "immutable service bundle manifest is unavailable"
  bundle_manifest_sha256=$(sha256_file "$bundle_manifest")
  jq -e --arg commit "$bundle_commit" --arg archive "$bundle_archive" --arg sha "$bundle_sha256" \
    '.schemaVersion == 1 and .commit == $commit and .archive == $archive and .sha256 == $sha' \
    "$bundle_manifest" >/dev/null || fail "service bundle manifest does not bind the archive"

  aws s3api get-object --bucket "$dataset_bucket" \
    --key "datasets/$dataset_release/manifest.json" --version-id "$dataset_manifest_version_id" "$dataset_manifest" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "dataset completion manifest is unavailable"
  validate_operator_dataset_manifest "$dataset_manifest" "$dataset_release" \
    || fail "dataset completion manifest is invalid"
  dataset_manifest_sha256=$(sha256_file "$dataset_manifest")
  load_release_smoke_inputs "$dataset_manifest"

  app_repository=$(jq -er '.ecr_repositories.APP_IMAGE.url' <<<"$lab_contract")
  app_image_reference="$app_repository@$app_digest"
  app_repository_name=${app_repository#*/}
  tagged_app_digest=$(aws ecr describe-images --repository-name "$app_repository_name" \
    --image-ids "imageTag=$bundle_commit" --query 'imageDetails[0].imageDigest' \
    --output text --region "$AWS_REGION" --no-cli-pager) \
    || fail "application runtime commit tag is unavailable"
  [[ "$tagged_app_digest" == "$app_digest" ]] \
    || fail "application digest does not match the runtime commit tag"

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
    --arg rds_snapshot_identifier "$rds_snapshot_identifier" \
    --arg rds_snapshot_source_run_id "$rds_snapshot_source_run_id" \
    --arg rds_snapshot_source_resource_id "$rds_snapshot_source_resource_id" \
    --arg rds_engine_version "$rds_engine_version" \
    --arg dns_mode "$dns_mode" --arg alb_ingress_cidr "$alb_ingress_cidr" \
    '{run_id:$run_id,expires_at:$expires_at,fencing_token:$fencing_token,deployment_phase:$deployment_phase,ami_id:$ami_id,verified_probe_instance_id:$verified_probe_instance_id,bundle_commit:$bundle_commit,bundle_sha256:$bundle_sha256,infra_image_references:$infra_image_references,app_image_reference:$app_image_reference,app_enabled:$app_enabled,mode:$mode,measurement_policy:$measurement_policy,accommodation_detail_cache_enabled:$cache_enabled,request_count_per_target_per_minute:(if $request_target == "" then null else ($request_target|tonumber) end),load_generator_enabled:$load_generator_enabled,dataset_release:$dataset_release,dataset_manifest_sha256:$dataset_manifest_sha256,database_bootstrap:$database_bootstrap,rds_snapshot_identifier:$rds_snapshot_identifier,rds_snapshot_source_run_id:$rds_snapshot_source_run_id,rds_snapshot_source_resource_id:$rds_snapshot_source_resource_id,rds_engine_version:$rds_engine_version,dns_mode:$dns_mode,alb_ingress_cidr:$alb_ingress_cidr}' \
    > "$current_tfvars"
}

persist_run_identity() {
  local plan_file="$temp_dir/run-identity.tfplan" plan_json="$temp_dir/run-identity-plan.json"
  assert_lease
  prepare_lab_backend
  jq -e --arg run "$run_id" --argjson resourceFence "$resource_fencing_token" '
    (.schemaVersion == 1 or .schemaVersion == 2) and
    .runId == $run and .fencingToken == $resourceFence
  ' "$manifest" >/dev/null || fail "run manifest does not bind the state identity"
  run_supervised_mutation "Terraform run-identity plan" \
    terraform -chdir="$lab_root" plan -input=false -lock-timeout=5m \
    -target=terraform_data.run_identity -var-file="$current_tfvars" -out="$plan_file" >/dev/null \
    || return 1
  run_terraform_command "Terraform run-identity plan inspection" \
    -chdir="$lab_root" show -json "$plan_file" > "$plan_json" || return 1
  jq -e '
    [.resource_changes[]? | select(.change.actions != ["no-op"])] as $changes |
    ($changes | length) == 1 and
    $changes[0].address == "terraform_data.run_identity" and
    $changes[0].mode == "managed" and
    $changes[0].type == "terraform_data" and
    $changes[0].change.actions == ["create"]
  ' "$plan_json" >/dev/null \
    || fail "run identity plan must create only the no-cost state identity"
  assert_lease
  run_supervised_mutation "Terraform run-identity apply" \
    terraform -chdir="$lab_root" apply -input=false -lock-timeout=5m \
    -auto-approve "$plan_file" >/dev/null || return 1
  assert_current_state_identity
}

apply_lab() {
  assert_lease
  prepare_lab_backend
  local plan_file="$temp_dir/lab.tfplan" plan_json="$temp_dir/lab-plan.json"
  run_supervised_mutation "Terraform lab plan" \
    terraform -chdir="$lab_root" plan -input=false -lock-timeout=5m \
    -var-file="$current_tfvars" -out="$plan_file" >/dev/null || return 1
  run_terraform_command "Terraform lab-plan inspection" \
    -chdir="$lab_root" show -json "$plan_file" > "$plan_json" || return 1
  jq -e '
    [.resource_changes[]? |
      select(
        .address == "module.nat.aws_instance.this" or
        .address == "module.nat.aws_eip.this"
      ) |
      select(.change.actions | index("delete") != null)
    ] | length == 0
  ' "$plan_json" >/dev/null \
    || fail "ordinary Lab plans must not replace or delete the singleton NAT instance or EIP"
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
  jq -e '
    all(
      .resource_changes[]? |
      select(.type == "aws_autoscaling_group" and .change.after != null);
      ((.change.after.mixed_instances_policy? // []) | length) == 0 and
      ((.change.after.launch_template? // []) | length) == 1 and
      .change.after.max_size <= 4
    )
  ' "$plan_json" >/dev/null \
    || fail "Lab plans must use one bounded launch template and no mixed-instance override"
  assert_lease
  run_supervised_mutation "Terraform lab apply" \
    terraform -chdir="$lab_root" apply -input=false -lock-timeout=5m \
    -auto-approve "$plan_file" >/dev/null || return 1
}

clear_lab_instance_shutdown_protection() {
  local instance_ids instance_id
  assert_lease
  instance_ids=$(aws ec2 describe-instances \
    --filters \
      Name=tag:Project,Values=airbob \
      Name=tag:Environment,Values=performance-lab \
      Name=tag:Stack,Values=lab \
      Name=tag:ManagedBy,Values=terraform \
      Name=tag:Persistence,Values=ephemeral \
      Name=tag-key,Values=ExpiresAt \
      Name=tag:RunId,Values="$run_id" \
      Name=tag:FencingToken,Values="$resource_fencing_token" \
      Name=instance-state-name,Values=pending,running,stopping,stopped \
    --query 'Reservations[].Instances[].InstanceId' --output text \
    --region "$AWS_REGION" --no-cli-pager) \
    || fail "cannot inventory Lab instances before clearing shutdown protection"
  if [[ -n "$instance_ids" && "$instance_ids" != None ]]; then
    for instance_id in $instance_ids; do
      [[ "$instance_id" =~ ^i-[0-9a-f]{8,17}$ ]] \
        || fail "shutdown-protection inventory returned an invalid instance ID"
      assert_lease
      aws ec2 modify-instance-attribute --instance-id "$instance_id" \
        --disable-api-termination Value=false --region "$AWS_REGION" --no-cli-pager \
        || fail "cannot clear Lab instance termination protection"
      aws ec2 modify-instance-attribute --instance-id "$instance_id" \
        --disable-api-stop Value=false --region "$AWS_REGION" --no-cli-pager \
        || fail "cannot clear Lab instance stop protection"
    done
  fi
}

destroy_lab() {
  local address targets_file
  local before_inventory="$temp_dir/destroy-before-inventory.json"
  local after_inventory="$temp_dir/destroy-after-inventory.json"
  local resource_plan="$temp_dir/destroy-resources.tfplan"
  local resource_plan_json="$temp_dir/destroy-resources-plan.json"
  local identity_plan="$temp_dir/destroy-run-identity.tfplan"
  local identity_plan_json="$temp_dir/destroy-run-identity-plan.json"
  local -a destroy_addresses=() data_addresses=() target_args=()
  [[ -n "$current_tfvars" && -f "$current_tfvars" ]] || return 1
  assert_lease
  prepare_lab_backend
  recover_prior_terraform_lock
  clear_lab_instance_shutdown_protection
  capture_terraform_state_inventory "$before_inventory"
  jq -e '
    [.[] | select(.address == "terraform_data.run_identity" and
      .mode == "managed" and .type == "terraform_data" and .name == "run_identity")] |
    length == 1
  ' "$before_inventory" >/dev/null \
    || fail "Terraform state must contain exactly one run identity before destroy"
  while IFS= read -r address; do
    [[ -z "$address" ]] || destroy_addresses+=("$address")
  done < <(jq -r '.[] | select(.mode == "managed" and .address != "terraform_data.run_identity") | .address' "$before_inventory")

  if [[ "${#destroy_addresses[@]}" -gt 0 ]]; then
    targets_file="$temp_dir/destroy-resource-targets.json"
    printf '%s\n' "${destroy_addresses[@]}" | jq -Rsc 'split("\n")[:-1]' > "$targets_file"
    for address in "${destroy_addresses[@]}"; do
      target_args+=("-target=$address")
    done
    run_supervised_mutation "Terraform ephemeral-resource destroy plan" \
      terraform -chdir="$lab_root" plan -destroy -refresh=false -input=false -lock-timeout=5m \
      "${target_args[@]}" -var-file="$current_tfvars" -out="$resource_plan" >/dev/null \
      || return 1
    run_terraform_command "Terraform resource-destroy plan inspection" \
      -chdir="$lab_root" show -json "$resource_plan" > "$resource_plan_json" || return 1
    jq -e --slurpfile targets "$targets_file" '
      ($targets[0] | sort) as $expected |
      [.resource_changes[]? | select(.change.actions != ["no-op"])] as $changes |
      ($changes | group_by(.address)) as $changeGroups |
      ($expected | length) == ($expected | unique | length) and
      ([ $changes[] | .address ] | unique | sort) == $expected and
      all($changes[];
        .address != "terraform_data.run_identity" and
        .mode == "managed" and
        (.type | type == "string" and length > 0) and
        (.name | type == "string" and length > 0) and
        .change.actions == ["delete"] and
        (.change.before.tags.Persistence? // "") != "persistent" and
        (.change.before.tags_all.Persistence? // "") != "persistent" and
        ((.deposed? // null) == null or (.deposed | type == "string" and length > 0))
      ) and
      all($changeGroups[];
        . as $objects |
        ([ $objects[] | select((.deposed? // null) != null) | .deposed ]) as $deposedKeys |
        ($objects | map({mode,type,name}) | unique | length) == 1 and
        ([ $objects[] | select((.deposed? // null) == null) ] | length) <= 1 and
        ($deposedKeys | length) == ($deposedKeys | unique | length)
      )
    ' "$resource_plan_json" >/dev/null \
      || fail "first destroy plan must delete every ephemeral non-identity state address and preserve persistent resources and run identity"
    assert_lease
    run_supervised_mutation "Terraform ephemeral-resource destroy apply" \
      terraform -chdir="$lab_root" apply -input=false -lock-timeout=5m \
      -auto-approve "$resource_plan" >/dev/null || return 1
  fi
  capture_terraform_state_inventory "$after_inventory"
  jq -e '
    ([.[] | select(.mode == "managed")] | length) == 1 and
    any(.[]; .address == "terraform_data.run_identity" and
      .mode == "managed" and .type == "terraform_data" and .name == "run_identity") and
    all(.[]; .mode == "data" or .address == "terraform_data.run_identity")
  ' "$after_inventory" >/dev/null \
    || fail "resource destroy left managed state outside the run identity"
  while IFS= read -r address; do
    [[ -z "$address" ]] || data_addresses+=("$address")
  done < <(jq -r '.[] | select(.mode == "data") | .address' "$after_inventory")
  if [[ "${#data_addresses[@]}" -gt 0 ]]; then
    assert_lease
    run_supervised_mutation "Terraform data-state removal" \
      terraform -chdir="$lab_root" state rm -lock-timeout=5m "${data_addresses[@]}" >/dev/null \
      || return 1
  fi
  terraform_state_is_identity_only \
    || fail "resource destroy did not preserve exactly the matching run identity"

  assert_lease
  ensure_teardown_finalize
  run_supervised_mutation "Terraform run-identity destroy plan" \
    terraform -chdir="$lab_root" plan -destroy -refresh=false -input=false -lock-timeout=5m \
    -target=terraform_data.run_identity -var-file="$current_tfvars" -out="$identity_plan" >/dev/null \
    || return 1
  run_terraform_command "Terraform identity-destroy plan inspection" \
    -chdir="$lab_root" show -json "$identity_plan" > "$identity_plan_json" || return 1
  jq -e '
    [.resource_changes[]? | select(.change.actions != ["no-op"])] as $changes |
    ($changes | length) == 1 and
    $changes[0].address == "terraform_data.run_identity" and
    $changes[0].mode == "managed" and
    $changes[0].type == "terraform_data" and
    $changes[0].name == "run_identity" and
    $changes[0].change.actions == ["delete"]
  ' "$identity_plan_json" >/dev/null \
    || fail "final destroy plan must delete only the run identity"
  ensure_teardown_finalize
  assert_lease
  run_supervised_mutation "Terraform run-identity state removal" \
    terraform -chdir="$lab_root" state rm -lock-timeout=5m \
    terraform_data.run_identity >/dev/null || return 1
  assert_lease
  terraform_state_is_empty || fail "Terraform state is not empty after exact identity state removal"
  load_teardown_finalize_for_recovery
}

invoke_dns_controller() {
  local dns_action=$1 target=$2
  assert_lease
  run_supervised_mutation "DNS controller $dns_action" env \
    AWS_DNS_CONTROLLER_ROLE_ARN="$dns_controller_role_arn" \
    OCI_ORIGIN_IPV4="$oci_origin_ipv4" \
    AWS_ALB_ARN="$aws_alb_arn" \
    AWS_ALB_DNS_NAME="$aws_alb_dns_name" \
    ALB_FENCING_TOKEN="$resource_fencing_token" \
    LEASE_TABLE="$lease_table" LEASE_LOCK_ID="$lease_lock_id" LEASE_OWNER="$lease_owner" \
    FENCING_TOKEN="$fencing_token" RUN_ID="$run_id" LEASE_COMMAND="$lease_command" \
    KEEP_ON_FAILURE="$keep_on_failure" FORCE_DOWN="${FORCE:-false}" \
    "$dns_controller" "$dns_action" "$target"
}

verify_oci_authority() {
  local observation_name=${1:-current}
  local dns_contract records exact_fqdn direct_body public_body record_projection
  oci_observation_file=''
  assert_lease
  dns_contract=$(aws ssm get-parameter \
    --name /airbob/performance-lab/foundation/dns-contract \
    --query 'Parameter.Value' --output text --region "$AWS_REGION") \
    || fail "cannot read the public DNS contract"
  jq -e '.schemaVersion == 1 and .api_fqdn == "api.airbob.cloud" and (.zone_id | test("^Z[A-Z0-9]+$"))' \
    <<<"$dns_contract" >/dev/null || fail "public DNS contract is invalid"
  dns_zone_id=$(jq -er '.zone_id' <<<"$dns_contract")
  exact_fqdn="$(jq -er '.api_fqdn' <<<"$dns_contract")."
  records=$(aws route53 list-resource-record-sets --hosted-zone-id "$dns_zone_id" \
    --start-record-name api.airbob.cloud \
    --output json --region "$AWS_REGION" --no-cli-pager) \
    || fail "cannot read the public API DNS records"
  jq -e --arg fqdn "$exact_fqdn" --arg oci "$oci_origin_ipv4" '
    [.ResourceRecordSets[] | select(.Name == $fqdn)] as $records |
    ($records | length) == 1 and
    $records[0].Type == "A" and
    $records[0].SetIdentifier == "oci" and
    $records[0].Weight == 100 and
    ($records[0] | has("AliasTarget") | not) and
    ($records[0].ResourceRecords | length) == 1 and
    $records[0].ResourceRecords[0].Value == $oci
  ' <<<"$records" >/dev/null || fail "Route 53 is not in the exact OCI-only posture"

  direct_body=$(curl -4 --fail --silent --show-error --max-time 10 \
    --resolve "api.airbob.cloud:443:$oci_origin_ipv4" "https://api.airbob.cloud/health") \
    || fail "direct OCI origin health check failed"
  [[ "$direct_body" == healthy ]] || fail "direct OCI origin health body is not exact"
  public_body=$(curl -4 --fail --silent --show-error --max-time 10 \
    "https://api.airbob.cloud/health") || fail "public OCI health check failed"
  [[ "$public_body" == healthy ]] || fail "public OCI health body is not exact"

  record_projection=$(jq -cS --arg fqdn "$exact_fqdn" '
    [.ResourceRecordSets[] | select(.Name == $fqdn)] |
    sort_by(.Type, (.SetIdentifier // ""))
  ' <<<"$records")
  oci_observation_file="$temp_dir/oci-$observation_name.json"
  jq -n --arg observedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
    --arg zoneId "$dns_zone_id" --arg fqdn "$exact_fqdn" \
    --arg originIpv4 "$oci_origin_ipv4" \
    --arg recordSetSha256 "$(printf '%s' "$record_projection" | sha256_text)" \
    '{status:"verified",observedAt:$observedAt,zoneId:$zoneId,fqdn:$fqdn,originIpv4:$originIpv4,recordSetSha256:$recordSetSha256,route53:"oci-only",directHealth:"healthy",publicHealth:"healthy"}' \
    > "$oci_observation_file"
}

publish_direct_readiness() {
  local data_key data_head data_receipt="$temp_dir/data-bootstrap.json"
  local network_key network_head network_receipt="$temp_dir/network-clearance.json"
  local network_projection network_clearance_version_id network_clearance_last_modified
  local network_clearance_sha256 network_clearance_projection_sha256
  local ami_shape rds_shape alb_shape alb_ingress_observation auto_scaling_group_shape
  local rds_parameter_group_name rds_parameter_group_family
  local data_projection comparison_projection="$temp_dir/comparison-projection.json"
  local receipt_basis="$temp_dir/direct-readiness-basis.json" receipt="$temp_dir/direct-readiness.json" search_query_sha256=null
  local now_epoch now_utc

  assert_lease
  data_key="data-bootstrap/$run_id/$dataset_release.json"
  data_head=$(aws s3api head-object --bucket "$evidence_bucket" --key "$data_key" \
    --query '{versionId:VersionId,lastModified:LastModified}' --output json \
    --region "$AWS_REGION" --no-cli-pager) || fail "data bootstrap receipt identity is unavailable"
  data_bootstrap_version_id=$(jq -er '.versionId | select(type == "string" and length > 0)' <<<"$data_head") \
    || fail "data bootstrap receipt has no version identity"
  data_bootstrap_last_modified=$(jq -er '.lastModified' <<<"$data_head")
  aws s3api get-object --bucket "$evidence_bucket" --key "$data_key" \
    --version-id "$data_bootstrap_version_id" "$data_receipt" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "exact data bootstrap receipt is unavailable"
  data_bootstrap_sha256=$(sha256_file "$data_receipt")
  jq -e --arg run "$run_id" --arg release "$dataset_release" \
    --arg bootstrap "$database_bootstrap" --arg manifestSha "$dataset_manifest_sha256" \
    --arg resourceId "$rds_resource_id" --arg engine "$rds_engine_version" '
      .schemaVersion == 2 and .runId == $run and .datasetRelease == $release and
      .databaseBootstrap == $bootstrap and .datasetManifestSha256 == $manifestSha and
      .rdsResourceId == $resourceId and .rdsEngineVersion == $engine and
      .outboxState == "empty" and (.redisState == "empty" or .redisState == "coupon-prepared") and
      .connectorState == "RUNNING" and .searchState == "restored"
    ' "$data_receipt" >/dev/null || fail "data bootstrap receipt is not ready for direct evidence"

  network_key="network-clearance/$run_id/$probe_instance_id.json"
  network_head=$(aws s3api head-object --bucket "$evidence_bucket" --key "$network_key" \
    --query '{versionId:VersionId,lastModified:LastModified}' --output json \
    --region "$AWS_REGION" --no-cli-pager) || fail "network-clearance receipt identity is unavailable"
  network_clearance_version_id=$(jq -er '.versionId | select(type == "string" and length > 0)' \
    <<<"$network_head") || fail "network clearance receipt has no version identity"
  network_clearance_last_modified=$(jq -er '.lastModified | select(type == "string" and length > 0)' \
    <<<"$network_head") || fail "network clearance receipt has no last-modified identity"
  aws s3api get-object --bucket "$evidence_bucket" --key "$network_key" \
    --version-id "$network_clearance_version_id" "$network_receipt" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "exact network clearance receipt is unavailable"
  network_clearance_sha256=$(sha256_file "$network_receipt")
  jq -e --arg run "$run_id" --arg vpc "$vpc_id" --arg probe "$probe_instance_id" '
    .schemaVersion == 1 and .runId == $run and .vpcId == $vpc and
    .probeInstanceId == $probe and .instanceState == "terminated" and
    (.clearedAt | type == "string" and length > 0)
  ' "$network_receipt" >/dev/null || fail "network clearance receipt is invalid"
  network_projection=$(jq -cS 'del(.runId,.vpcId,.probeInstanceId,.clearedAt)' "$network_receipt")
  network_clearance_projection_sha256=$(printf '%s' "$network_projection" | sha256_text)
  [[ "$network_clearance_sha256" =~ ^[0-9a-f]{64}$ && \
    "$network_clearance_projection_sha256" =~ ^[0-9a-f]{64}$ ]] \
    || fail "network clearance receipt hashes are invalid"

  ami_shape=$(aws ec2 describe-images --image-ids "$ami_id" \
    --query 'Images[0].{imageId:ImageId,creationDate:CreationDate,architecture:Architecture,rootDeviceType:RootDeviceType,virtualizationType:VirtualizationType}' \
    --output json --region "$AWS_REGION" --no-cli-pager) || fail "cannot attest the selected AMI"
  rds_shape=$(aws rds describe-db-instances --db-instance-identifier "$rds_instance_id" \
    --query 'DBInstances[0].{identifier:DBInstanceIdentifier,resourceId:DbiResourceId,class:DBInstanceClass,engine:Engine,engineVersion:EngineVersion,allocatedStorageGiB:AllocatedStorage,storageType:StorageType,multiAz:MultiAZ,storageEncrypted:StorageEncrypted,availabilityZone:AvailabilityZone,parameterGroups:DBParameterGroups[].DBParameterGroupName}' \
    --output json --region "$AWS_REGION" --no-cli-pager) || fail "cannot attest the restored RDS shape"
  jq -e --arg id "$rds_instance_id" --arg resource "$rds_resource_id" --arg version "$rds_engine_version" '
    .identifier == $id and .resourceId == $resource and .class == "db.t3.micro" and
    .engine == "mysql" and .engineVersion == $version and .allocatedStorageGiB == 100 and
    .storageType == "gp3" and .multiAz == false and .storageEncrypted == true
  ' <<<"$rds_shape" >/dev/null || fail "actual RDS shape differs from the low-cost qualification contract"
  rds_parameter_group_name=$(jq -er '.parameterGroups | select(length == 1) | .[0]' <<<"$rds_shape") \
    || fail "actual RDS parameter-group shape is invalid"
  rds_parameter_group_family=$(aws rds describe-db-parameter-groups \
    --db-parameter-group-name "$rds_parameter_group_name" \
    --query 'DBParameterGroups[0].DBParameterGroupFamily' --output text \
    --region "$AWS_REGION" --no-cli-pager) || fail "cannot attest the RDS parameter-group family"
  [[ "$rds_parameter_group_family" == mysql8.0 ]] \
    || fail "actual RDS parameter-group family differs from mysql8.0"
  alb_shape=$(aws elbv2 describe-load-balancers --load-balancer-arns "$aws_alb_arn" \
    --query 'LoadBalancers[0].{arn:LoadBalancerArn,dnsName:DNSName,scheme:Scheme,type:Type,ipAddressType:IpAddressType,availabilityZones:AvailabilityZones[].ZoneName,securityGroups:SecurityGroups}' \
    --output json --region "$AWS_REGION" --no-cli-pager) || fail "cannot attest the direct ALB shape"
  jq -e --arg arn "$aws_alb_arn" --arg dns "$aws_alb_dns_name" \
    --arg securityGroup "$alb_security_group_id" '
    .arn == $arn and .dnsName == $dns and .scheme == "internet-facing" and
    .type == "application" and .ipAddressType == "ipv4" and
    .securityGroups == [$securityGroup]
  ' \
    <<<"$alb_shape" >/dev/null || fail "actual ALB shape differs from the qualification contract"
  alb_ingress_observation=$(aws ec2 describe-security-group-rules \
    --filters "Name=group-id,Values=$alb_security_group_id" \
    --query 'SecurityGroupRules[?IsEgress==`false`].{ruleId:SecurityGroupRuleId,groupId:GroupId,isEgress:IsEgress,ipProtocol:IpProtocol,fromPort:FromPort,toPort:ToPort,cidrIpv4:CidrIpv4,cidrIpv6:CidrIpv6,prefixListId:PrefixListId,referencedGroupId:ReferencedGroupInfo.GroupId}' \
    --output json --region "$AWS_REGION" --no-cli-pager) \
    || fail "cannot attest the ALB security-group ingress"
  jq -e '
    (type == "array" and length == 1) and
    ((.[0].ruleId | type) == "string") and
    (.[0].ruleId | test("^sgr-[0-9a-f]+$"))
  ' <<<"$alb_ingress_observation" >/dev/null \
    || fail "actual ALB ingress rule count or identity differs from the qualification contract"
  jq -e --arg securityGroup "$alb_security_group_id" --arg cidr "$alb_ingress_cidr" '
    .[0].groupId == $securityGroup and .[0].isEgress == false and
    .[0].ipProtocol == "tcp" and .[0].fromPort == 443 and .[0].toPort == 443 and
    .[0].cidrIpv4 == $cidr and .[0].cidrIpv6 == null and
    .[0].prefixListId == null and .[0].referencedGroupId == null
  ' <<<"$alb_ingress_observation" >/dev/null \
    || fail "actual ALB ingress is not exactly TCP/443 from the requested IPv4 CIDR"
  auto_scaling_group_shape=$(aws autoscaling describe-auto-scaling-groups \
    --auto-scaling-group-names "$asg_name" \
    --query 'AutoScalingGroups[0].{name:AutoScalingGroupName,min:MinSize,desired:DesiredCapacity,max:MaxSize}' \
    --output json --region "$AWS_REGION" --no-cli-pager) \
    || fail "cannot attest the live Auto Scaling capacity"
  jq -e --arg name "$asg_name" --argjson expected "$expected_app_capacity" '
    .name == $name and .min == $expected.min and
    .desired == $expected.desired and .max == $expected.max
  ' <<<"$auto_scaling_group_shape" >/dev/null \
    || fail "live Auto Scaling capacity differs from the Phase 4 contract"

  data_projection=$(jq -cS 'del(.runId,.databaseBootstrap,.rdsResourceId,.verifiedAt)' "$data_receipt")
  data_projection_sha256=$(printf '%s' "$data_projection" | sha256_text)
  if [[ "$smoke_search_enabled" == true ]]; then
    search_query_sha256=$(jq -cS '.query' "$smoke_search_target" | sha256_text)
  fi
  [[ "$execution_commit" =~ ^[0-9a-f]{40}$ && "$operator_tree_sha256" =~ ^[0-9a-f]{64}$ ]] \
    || fail "execution code identity is invalid"

  now_epoch=$(date +%s)
  now_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  jq -nS \
    --arg runId "$run_id" --argjson fencingToken "$fencing_token" \
    --arg resourceStartedAt "$resource_started_at" --arg dataReadyAt "$data_ready_at" \
    --arg directReadyAt "$now_utc" --argjson resourceToDataReadySeconds "$resource_to_data_ready_seconds" \
    --argjson resourceToDirectReadySeconds "$((now_epoch - resource_started_epoch))" \
    --arg executionCommit "$execution_commit" --arg operatorTreeSha256 "$operator_tree_sha256" \
    --arg datasetRelease "$dataset_release" --arg datasetManifestVersionId "$dataset_manifest_version_id" \
    --arg datasetManifestSha256 "$dataset_manifest_sha256" \
    --arg bundleCommit "$bundle_commit" --arg bundleSha256 "$bundle_sha256" \
    --arg bundleChecksumVersionId "$bundle_checksum_version_id" \
    --arg bundleManifestVersionId "$bundle_manifest_version_id" --arg bundleManifestSha256 "$bundle_manifest_sha256" \
    --arg appImageReference "$app_image_reference" --argjson infraImageReferences "$infra_image_references" \
    --arg databaseBootstrap "$database_bootstrap" --arg rdsSnapshotIdentifier "$rds_snapshot_identifier" \
    --arg rdsSnapshotSourceRunId "$rds_snapshot_source_run_id" \
    --arg rdsSnapshotSourceResourceId "$rds_snapshot_source_resource_id" \
    --arg dataKey "$data_key" --arg dataVersionId "$data_bootstrap_version_id" \
    --arg dataSha256 "$data_bootstrap_sha256" --arg dataLastModified "$data_bootstrap_last_modified" \
    --arg networkKey "$network_key" --arg networkVersionId "$network_clearance_version_id" \
    --arg networkSha256 "$network_clearance_sha256" \
    --arg networkLastModified "$network_clearance_last_modified" \
    --arg networkProjectionSha256 "$network_clearance_projection_sha256" \
    --arg amiId "$ami_id" --argjson amiShape "$ami_shape" --argjson rdsShape "$rds_shape" \
    --arg rdsParameterGroupFamily "$rds_parameter_group_family" \
    --arg albArn "$aws_alb_arn" --arg albDnsName "$aws_alb_dns_name" \
    --arg targetGroupArn "$target_group_arn" --arg autoScalingGroupName "$asg_name" \
    --arg albSecurityGroupId "$alb_security_group_id" --argjson albShape "$alb_shape" \
    --argjson observedIngress "$alb_ingress_observation" \
    --argjson autoScalingGroupShape "$auto_scaling_group_shape" \
    --arg mode "$mode" --arg policy "$policy" --arg dnsMode "$dns_mode" --arg albIngressCidr "$alb_ingress_cidr" \
    --argjson cacheEnabled "$cache_enabled" --argjson loadGeneratorEnabled "$load_generator_enabled" \
    --argjson ociAuthority "$(jq -c . "$oci_observation_file")" \
    --argjson accommodationId "$smoke_accommodation_id" --argjson searchEnabled "$smoke_search_enabled" \
    --arg searchQuerySha256 "$search_query_sha256" \
    --arg dataProjectionSha256 "$data_projection_sha256" \
    '{schemaVersion:1,status:"ready",runId:$runId,fencingToken:$fencingToken,executionCode:{commit:$executionCommit,operatorTreeSha256:$operatorTreeSha256},dataset:{release:$datasetRelease,manifestVersionId:$datasetManifestVersionId,manifestSha256:$datasetManifestSha256},bundle:{commit:$bundleCommit,archiveSha256:$bundleSha256,checksumVersionId:$bundleChecksumVersionId,manifestVersionId:$bundleManifestVersionId,manifestSha256:$bundleManifestSha256},images:{app:$appImageReference,infra:$infraImageReferences},bootstrap:{mode:$databaseBootstrap,rdsSnapshotIdentifier:(if $rdsSnapshotIdentifier == "" then null else $rdsSnapshotIdentifier end),rdsSnapshotSourceRunId:(if $rdsSnapshotSourceRunId == "" then null else $rdsSnapshotSourceRunId end),rdsSnapshotSourceResourceId:(if $rdsSnapshotSourceResourceId == "" then null else $rdsSnapshotSourceResourceId end),dataProjectionSha256:$dataProjectionSha256,receipt:{key:$dataKey,versionId:$dataVersionId,sha256:$dataSha256,lastModified:$dataLastModified}},networkClearance:{key:$networkKey,versionId:$networkVersionId,sha256:$networkSha256,lastModified:$networkLastModified,projectionSha256:$networkProjectionSha256},actual:{ami:{id:$amiId,shape:$amiShape},rds:$rdsShape,rdsParameterGroupFamily:$rdsParameterGroupFamily,alb:{arn:$albArn,dnsName:$albDnsName,targetGroupArn:$targetGroupArn,autoScalingGroupName:$autoScalingGroupName,securityGroupId:$albSecurityGroupId,shape:$albShape,observedIngress:$observedIngress},autoScalingGroup:$autoScalingGroupShape},topology:{mode:$mode,policy:$policy,dnsMode:$dnsMode,albIngressCidr:$albIngressCidr,cacheEnabled:$cacheEnabled,loadGeneratorEnabled:$loadGeneratorEnabled},ociAuthority:$ociAuthority,smoke:{health:{passed:true},accommodationDetail:{id:$accommodationId,passed:true},search:{enabled:$searchEnabled,querySha256:(if $searchQuerySha256 == "null" then null else $searchQuerySha256 end),passed:true}},timing:{resourceStartedAt:$resourceStartedAt,dataReadyAt:$dataReadyAt,directReadyAt:$directReadyAt,resourceToDataReadySeconds:$resourceToDataReadySeconds,resourceToDirectReadySeconds:$resourceToDirectReadySeconds}}' \
    > "$receipt_basis"
  jq -Sf "$comparison_projection_filter" "$receipt_basis" > "$comparison_projection" \
    || fail "cannot build the canonical readiness comparison projection"
  comparison_projection_sha256=$(jq -cS . "$comparison_projection" | sha256_text)
  jq --arg comparisonProjectionSha256 "$comparison_projection_sha256" \
    --slurpfile comparisonProjection "$comparison_projection" \
    '. + {comparisonProjection:$comparisonProjection[0],comparisonProjectionSha256:$comparisonProjectionSha256}' \
    "$receipt_basis" > "$receipt"
  publish_immutable_json "measurements/$run_id/direct-readiness.json" "$receipt"
}

ensure_teardown_start() {
  local receipt="$temp_dir/teardown-start.json" existing="$temp_dir/teardown-start-existing.json"
  teardown_start_key="measurements/$run_id/teardown-start.json"
  capture_state_object_identity
  if aws s3api get-object --bucket "$evidence_bucket" --key "$teardown_start_key" "$existing" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null 2>&1; then
    jq -e --arg run "$run_id" --argjson resourceFence "$resource_fencing_token" \
      --arg dnsMode "$dns_mode" --arg stateKey "$lab_state_key" '
        .schemaVersion == 1 and .status == "started" and .runId == $run and
        .resourceFencingToken == $resourceFence and .dnsMode == $dnsMode and
        .terraformState.key == $stateKey and
        (.terraformState.versionId | type == "string" and length > 0) and
        (.terraformState.objectSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
        .ociAuthority.status == "verified"
      ' "$existing" >/dev/null || fail "teardown-start journal drifted from the active run"
  else
    jq -nS --arg runId "$run_id" --argjson fencingToken "$fencing_token" \
      --argjson resourceFencingToken "$resource_fencing_token" --arg dnsMode "$dns_mode" \
      --arg startedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
      --arg key "$lab_state_key" --arg versionId "$state_version_id" \
      --arg objectSha256 "$state_object_sha256" \
      --argjson ociAuthority "$(jq -c . "$oci_observation_file")" \
      '{schemaVersion:1,status:"started",runId:$runId,fencingToken:$fencingToken,resourceFencingToken:$resourceFencingToken,dnsMode:$dnsMode,startedAt:$startedAt,terraformState:{key:$key,versionId:$versionId,objectSha256:$objectSha256},ociAuthority:$ociAuthority}' \
      > "$receipt"
    publish_immutable_json "$teardown_start_key" "$receipt"
  fi
  teardown_start_version_id=$(aws s3api head-object --bucket "$evidence_bucket" \
    --key "$teardown_start_key" --query VersionId --output text \
    --region "$AWS_REGION" --no-cli-pager)
  [[ -n "$teardown_start_version_id" && "$teardown_start_version_id" != None ]] \
    || fail "teardown-start journal has no version identity"
}

load_teardown_start_for_recovery() {
  local journal="$temp_dir/teardown-start-recovery.json"
  teardown_start_key="measurements/$run_id/teardown-start.json"
  teardown_start_version_id=$(aws s3api head-object --bucket "$evidence_bucket" \
    --key "$teardown_start_key" --query VersionId --output text \
    --region "$AWS_REGION" --no-cli-pager) \
    || fail "teardown finalization recovery cannot read the start journal identity"
  [[ -n "$teardown_start_version_id" && "$teardown_start_version_id" != None ]] \
    || fail "teardown finalization recovery journal has no version identity"
  aws s3api get-object --bucket "$evidence_bucket" --key "$teardown_start_key" "$journal" \
    --version-id "$teardown_start_version_id" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "teardown finalization recovery requires a start journal"
  jq -e --arg run "$run_id" --argjson resourceFence "$resource_fencing_token" --arg dnsMode "$dns_mode" '
    .schemaVersion == 1 and .status == "started" and .runId == $run and
    .resourceFencingToken == $resourceFence and .dnsMode == $dnsMode and
    (.terraformState.versionId | type == "string" and length > 0) and
    (.terraformState.objectSha256 | test("^[0-9a-f]{64}$"))
  ' "$journal" >/dev/null || fail "teardown-start recovery journal is invalid"
}

ensure_teardown_finalize() {
  local receipt="$temp_dir/teardown-finalize.json"
  local existing="$temp_dir/teardown-finalize-existing.json"
  local exact_readback="$temp_dir/teardown-finalize-exact-readback.json"
  local predecessor_lineage predecessor_serial
  local existing_found=false
  teardown_finalize_key="measurements/$run_id/teardown-finalize.json"
  terraform_state_is_identity_only \
    || fail "teardown finalize requires exactly the matching run identity in state"
  capture_state_object_identity
  validate_identity_state_file "$temp_dir/terraform-state.json" "$run_id" "$resource_fencing_token" \
    || fail "teardown finalize predecessor is not the matching identity-only state"
  predecessor_lineage=$(jq -er '.lineage' "$temp_dir/terraform-state.json")
  predecessor_serial=$(jq -er '.serial' "$temp_dir/terraform-state.json")
  if aws s3api get-object --bucket "$evidence_bucket" --key "$teardown_finalize_key" "$existing" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null 2>&1; then
    existing_found=true
    jq -e --arg run "$run_id" --argjson resourceFence "$resource_fencing_token" \
      --arg dnsMode "$dns_mode" --arg startKey "$teardown_start_key" \
      --arg startVersion "$teardown_start_version_id" --arg stateKey "$lab_state_key" \
      --arg version "$state_version_id" --arg versionHash "$state_version_hash" \
      --arg stateSha "$state_object_sha256" --arg lineage "$predecessor_lineage" \
      --argjson serial "$predecessor_serial" '
        .schemaVersion == 1 and .status == "ready" and .runId == $run and
        .resourceFencingToken == $resourceFence and .dnsMode == $dnsMode and
        .teardownStart.key == $startKey and .teardownStart.versionId == $startVersion and
        .terraformState.key == $stateKey and .terraformState.versionId == $version and
        .terraformState.versionIdSha256 == $versionHash and
        .terraformState.objectSha256 == $stateSha and
        .terraformState.lineage == $lineage and .terraformState.serial == $serial and
        .terraformState.resourceCount == 1 and
        .terraformState.identityAddress == "terraform_data.run_identity"
      ' "$existing" >/dev/null \
      || fail "existing teardown finalize journal differs from the identity-only state"
  else
    jq -nS --arg runId "$run_id" \
    --argjson resourceFencingToken "$resource_fencing_token" --arg dnsMode "$dns_mode" \
    --arg startKey "$teardown_start_key" --arg startVersionId "$teardown_start_version_id" \
    --arg stateKey "$lab_state_key" --arg versionId "$state_version_id" \
    --arg versionIdSha256 "$state_version_hash" --arg objectSha256 "$state_object_sha256" \
    --arg lineage "$predecessor_lineage" --argjson serial "$predecessor_serial" \
      '{schemaVersion:1,status:"ready",runId:$runId,resourceFencingToken:$resourceFencingToken,dnsMode:$dnsMode,teardownStart:{key:$startKey,versionId:$startVersionId},terraformState:{key:$stateKey,versionId:$versionId,versionIdSha256:$versionIdSha256,objectSha256:$objectSha256,lineage:$lineage,serial:$serial,resourceCount:1,identityAddress:"terraform_data.run_identity"}}' \
      > "$receipt"
    publish_immutable_json "$teardown_finalize_key" "$receipt"
  fi
  teardown_finalize_version_id=$(aws s3api head-object --bucket "$evidence_bucket" \
    --key "$teardown_finalize_key" --query VersionId --output text \
    --region "$AWS_REGION" --no-cli-pager) \
    || fail "teardown finalize journal has no readable version identity"
  [[ -n "$teardown_finalize_version_id" && "$teardown_finalize_version_id" != None ]] \
    || fail "teardown finalize journal has no version identity"
  aws s3api get-object --bucket "$evidence_bucket" --key "$teardown_finalize_key" \
    --version-id "$teardown_finalize_version_id" "$exact_readback" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "teardown finalize journal exact version cannot be read back"
  [[ "$existing_found" == false ]] || receipt=$existing
  cmp -s "$receipt" "$exact_readback" \
    || fail "teardown finalize journal exact-version read-back differs"
}

load_teardown_finalize_for_recovery() {
  teardown_finalize_key="measurements/$run_id/teardown-finalize.json"
  teardown_finalize_version_id=$(aws s3api head-object --bucket "$evidence_bucket" \
    --key "$teardown_finalize_key" --query VersionId --output text \
    --region "$AWS_REGION" --no-cli-pager) \
    || fail "teardown finalization recovery cannot read the finalize journal identity"
  [[ -n "$teardown_finalize_version_id" && "$teardown_finalize_version_id" != None ]] \
    || fail "teardown finalization recovery journal has no version identity"
  capture_state_object_identity
  terraform_state_is_empty || fail "teardown finalization recovery requires empty Terraform state"
  validate_teardown_finalize_binding "$teardown_finalize_key" "$teardown_finalize_version_id" \
    "$run_id" "$resource_fencing_token" "$dns_mode" \
    "$teardown_start_key" "$teardown_start_version_id"
}

finalize_clean_teardown() {
  local recovered=${1:-false} receipt="$temp_dir/state-clean-final.json" existing="$temp_dir/state-clean-existing.json"
  if [[ -z "${teardown_finalize_key:-}" || -z "${teardown_finalize_version_id:-}" ]]; then
    load_teardown_finalize_for_recovery
  fi
  terraform_state_is_empty || fail "Terraform state is not empty after destroy"
  capture_state_object_identity
  state_clean_key="measurements/state-clean/$state_version_hash.json"
  if aws s3api get-object --bucket "$evidence_bucket" --key "$state_clean_key" "$existing" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null 2>&1; then
    jq -e --arg run "$run_id" --argjson resourceFence "$resource_fencing_token" \
      --arg stateKey "$lab_state_key" --arg version "$state_version_id" \
      --arg versionHash "$state_version_hash" --arg stateSha "$state_object_sha256" \
      --arg startKey "$teardown_start_key" --arg startVersion "$teardown_start_version_id" \
      --arg finalizeKey "$teardown_finalize_key" --arg finalizeVersion "$teardown_finalize_version_id" '
        .schemaVersion == 1 and .status == "clean" and .runId == $run and
        .resourceFencingToken == $resourceFence and
        .terraformState.key == $stateKey and .terraformState.versionId == $version and
        .terraformState.versionIdSha256 == $versionHash and
        .terraformState.objectSha256 == $stateSha and .terraformState.resourceCount == 0 and
        .teardownStart.key == $startKey and .teardownStart.versionId == $startVersion and
        .teardownFinalize.key == $finalizeKey and .teardownFinalize.versionId == $finalizeVersion and
        .ociAuthority.status == "verified" and
        .orphanScan.status == "clean" and .orphanScan.scope == "global" and
        .orphanScan.runId == $run
      ' "$existing" >/dev/null || fail "existing clean-state receipt drifted"
    return 0
  fi
  jq -nS --arg runId "$run_id" --argjson fencingToken "$fencing_token" \
    --argjson resourceFencingToken "$resource_fencing_token" --arg dnsMode "$dns_mode" \
    --arg completedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" --argjson recovered "$recovered" \
    --arg stateKey "$lab_state_key" --arg versionId "$state_version_id" \
    --arg versionIdSha256 "$state_version_hash" --arg objectSha256 "$state_object_sha256" \
    --arg startKey "$teardown_start_key" --arg startVersionId "$teardown_start_version_id" \
    --arg finalizeKey "$teardown_finalize_key" --arg finalizeVersionId "$teardown_finalize_version_id" \
    --argjson ociAuthority "$(jq -c . "$oci_observation_file")" \
    '{schemaVersion:1,status:"clean",runId:$runId,fencingToken:$fencingToken,resourceFencingToken:$resourceFencingToken,dnsMode:$dnsMode,completedAt:$completedAt,recoveredFinalization:$recovered,teardownStart:{key:$startKey,versionId:$startVersionId},teardownFinalize:{key:$finalizeKey,versionId:$finalizeVersionId},terraformState:{key:$stateKey,versionId:$versionId,versionIdSha256:$versionIdSha256,objectSha256:$objectSha256,resourceCount:0},ociAuthority:$ociAuthority,orphanScan:{status:"clean",scope:"global",runId:$runId}}' \
    > "$receipt"
  publish_immutable_json "$state_clean_key" "$receipt"
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
  if ! run_terraform_command "Terraform output evidence read" \
    -chdir="$lab_root" output -json > "$raw_outputs" 2>/dev/null ||
    ! jq -e '
      (keys | sort) == [
        "persistent_resource_contract",
        "phase2_contract",
        "phase3_contract",
        "phase4_contract",
        "run_identity",
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
  run_supervised_mutation "Auto Scaling instance-refresh rollback" \
    aws autoscaling rollback-instance-refresh --auto-scaling-group-name "$asg_name" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null 2>&1 || \
    run_supervised_mutation "Auto Scaling instance-refresh cancellation" \
      aws autoscaling cancel-instance-refresh --auto-scaling-group-name "$asg_name" \
      --region "$AWS_REGION" --no-cli-pager >/dev/null 2>&1 || true
  fail "application refresh/target-health gate exceeded 15 minutes"
}

verify_aws_application_smoke() {
  local route=$1 health_payload detail_payload search_payload
  local expected_search_id expected_search_rows
  local -a curl_arguments search_arguments
  curl_arguments=(--fail --silent --show-error --max-time 15)
  case "$route" in
    direct) curl_arguments+=(--connect-to "api.airbob.cloud:443:$aws_alb_dns_name:443") ;;
    public) ;;
    *) fail "AWS application smoke route is invalid" ;;
  esac

  health_payload=$(curl "${curl_arguments[@]}" "https://api.airbob.cloud/actuator/health") \
    || fail "$route AWS health smoke failed"
  jq -e '.status == "UP"' <<<"$health_payload" >/dev/null \
    || fail "$route AWS health smoke returned an invalid contract"

  detail_payload=$(curl "${curl_arguments[@]}" \
    "https://api.airbob.cloud/api/v1/accommodations/$smoke_accommodation_id") \
    || fail "$route AWS MySQL accommodation smoke failed"
  jq -e --argjson expectedId "$smoke_accommodation_id" \
    '.success == true and .data.id == $expectedId' <<<"$detail_payload" >/dev/null \
    || fail "$route AWS MySQL accommodation smoke returned an invalid contract"

  if [[ "$smoke_search_enabled" == true ]]; then
    expected_search_id=$(jq -r '.resourceIds[0]' "$smoke_search_target")
    expected_search_rows=$(jq -r '.expectedRows' "$smoke_search_target")
    search_arguments=("${curl_arguments[@]}" --get
      --data-urlencode "destination=$(jq -r '.query.destination' "$smoke_search_target")"
      --data-urlencode "minPrice=$(jq -r '.query.minPrice' "$smoke_search_target")"
      --data-urlencode "maxPrice=$(jq -r '.query.maxPrice' "$smoke_search_target")"
      --data-urlencode "adultOccupancy=$(jq -r '.query.adultOccupancy' "$smoke_search_target")"
      --data-urlencode "childOccupancy=$(jq -r '.query.childOccupancy' "$smoke_search_target")"
      --data-urlencode "infantOccupancy=$(jq -r '.query.infantOccupancy' "$smoke_search_target")"
      --data-urlencode "petOccupancy=$(jq -r '.query.petOccupancy' "$smoke_search_target")"
      --data-urlencode "topLeftLat=$(jq -r '.query.topLeftLat' "$smoke_search_target")"
      --data-urlencode "topLeftLng=$(jq -r '.query.topLeftLng' "$smoke_search_target")"
      --data-urlencode "bottomRightLat=$(jq -r '.query.bottomRightLat' "$smoke_search_target")"
      --data-urlencode "bottomRightLng=$(jq -r '.query.bottomRightLng' "$smoke_search_target")"
      --data-urlencode "page=$(jq -r '.query.page' "$smoke_search_target")")
    search_payload=$(curl "${search_arguments[@]}" \
      "https://api.airbob.cloud/api/v1/search/accommodations") \
      || fail "$route AWS Elasticsearch search smoke failed"
    jq -e --argjson expectedId "$expected_search_id" --argjson expectedRows "$expected_search_rows" '
      .success == true and
      (.data.stay_search_result_listing | type == "array" and length == 1) and
      .data.stay_search_result_listing[0].id == $expectedId and
      .data.page_info.current_page == 0 and
      .data.page_info.total_elements == $expectedRows and
      .data.page_info.total_pages == 1
    ' <<<"$search_payload" >/dev/null \
      || fail "$route AWS Elasticsearch search smoke returned an invalid contract"
  fi
}

verify_direct_aws_smoke() {
  verify_aws_application_smoke direct
}

verify_public_aws_smoke() {
  local attempt
  for attempt in 1 2 3; do
    verify_aws_application_smoke public
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
    dns_mode=${DNS_MODE:-direct-only}
    cache_enabled=${CACHE_ENABLED:-true}
    request_target=${REQUEST_TARGET:-}
    load_generator_enabled=${LOAD_GENERATOR_ENABLED:-false}
    ami_id=${AMI_ID:-}
    oci_origin_ipv4=${OCI_ORIGIN_IPV4:-}
    requested_alb_ingress_cidr=${ALB_INGRESS_CIDR:-}
    database_bootstrap=${DATABASE_BOOTSTRAP:-dump}
    default_ttl_hours=2
    [[ "$database_bootstrap" != dump ]] || default_ttl_hours=5
    ttl_hours=${TTL_HOURS:-$default_ttl_hours}
    rds_snapshot_identifier=${RDS_SNAPSHOT_IDENTIFIER:-}
    rds_snapshot_source_run_id=${RDS_SNAPSHOT_SOURCE_RUN_ID:-}
    rds_snapshot_source_resource_id=${RDS_SNAPSHOT_SOURCE_RESOURCE_ID:-}
    rds_engine_version=${RDS_ENGINE_VERSION:-}
    [[ "$mode" == performance || "$mode" == scaling ]] \
      || fail "MODE must be performance or scaling"
    [[ "$policy" == integrated-smoke || "$policy" == isolated-read ]] || fail "POLICY is invalid"
    [[ "$dns_mode" == direct-only || "$dns_mode" == cutover ]] \
      || fail "DNS_MODE must be direct-only or cutover"
    validate_operator_scope_for_action false
    [[ "$mode:$policy" != scaling:integrated-smoke ]] || fail "scaling requires isolated-read"
    [[ "$cache_enabled" == true || "$cache_enabled" == false ]] || fail "CACHE_ENABLED must be true or false"
    [[ "$load_generator_enabled" == true || "$load_generator_enabled" == false ]] || fail "LOAD_GENERATOR_ENABLED must be true or false"
    [[ "$ttl_hours" =~ ^[1-9][0-9]?$ && "$ttl_hours" -le 24 ]] || fail "TTL_HOURS must be 1-24"
    [[ "$database_bootstrap" != dump || "$ttl_hours" -ge 5 ]] \
      || fail "dump bootstrap requires TTL_HOURS of at least 5"
    [[ "$database_bootstrap" != snapshot || "$ttl_hours" -eq 2 ]] \
      || fail "snapshot bootstrap requires explicit TTL_HOURS=2"
    [[ "$mode" != scaling || "$request_target" =~ ^[1-9][0-9]*$ ]] || fail "scaling requires REQUEST_TARGET"
    [[ "$mode" == scaling || -z "$request_target" ]] || fail "REQUEST_TARGET is valid only for scaling"
    [[ "$ami_id" =~ ^ami-[0-9a-f]{8,17}$ ]] || fail "AMI_ID is required and must be reviewed"
    valid_ipv4 "$oci_origin_ipv4" || fail "OCI_ORIGIN_IPV4 must be one canonical IPv4 address"
    if [[ "$dns_mode" == direct-only ]]; then
      [[ "$requested_alb_ingress_cidr" =~ ^([^/]+)/32$ ]] \
        || fail "ALB_INGRESS_CIDR is required as one canonical /32 for direct-only"
      operator_ingress_ipv4=${BASH_REMATCH[1]}
      valid_public_ipv4 "$operator_ingress_ipv4" \
        || fail "ALB_INGRESS_CIDR is required as one canonical public /32 for direct-only"
      [[ "$requested_alb_ingress_cidr" == "$operator_ingress_ipv4/32" ]] \
        || fail "ALB_INGRESS_CIDR is required as one canonical /32 for direct-only"
      alb_ingress_cidr=$requested_alb_ingress_cidr
    else
      alb_ingress_cidr=0.0.0.0/0
    fi
    [[ "$rds_engine_version" =~ ^8\.0\.[0-9]+$ ]] || fail "RDS_ENGINE_VERSION is required and must be exact"
    validate_snapshot_bootstrap_inputs
    approved_rds_snapshot_identifier=$(jq -er '.approved_rds_snapshot_identifier // ""' <<<"$lab_contract") \
      || fail "foundation lab contract has no approved RDS snapshot field"
    if [[ "$database_bootstrap" == snapshot ]]; then
      [[ -n "$approved_rds_snapshot_identifier" && \
        "$rds_snapshot_identifier" == "$approved_rds_snapshot_identifier" ]] \
        || fail "snapshot bootstrap requires the exact Foundation-approved RDS snapshot"
    fi
    now_epoch=$(date +%s)
    expires_at=$((now_epoch + ttl_hours * 3600))
    run_id=${RUN_ID:-lab-$(date -u +%Y%m%d%H%M%S)-${GITHUB_RUN_ID:-local}}
    run_id=$(printf '%.32s' "$run_id" | sed 's/-$//')
    valid_run_id "$run_id" || fail "generated RUN_ID is not canonical"
    current_stage=release-validation
    resolve_release_inputs
    start_mutation_guard
    if terraform_lock_object_present; then
      prepare_lab_backend
      recover_prior_terraform_lock
    fi
    resource_fencing_token=$fencing_token
    assert_reusable_or_absent_state
    verify_oci_authority before-create
    manifest="$temp_dir/operator.json"
    jq -n --arg runId "$run_id" --arg expiresAt "$expires_at" --argjson fencingToken "$resource_fencing_token" \
      --arg mode "$mode" --arg policy "$policy" --arg imageDigest "$app_digest" --arg datasetRelease "$dataset_release" \
      --arg bundleCommit "$bundle_commit" --arg bundleSha256 "$bundle_sha256" --arg datasetManifestSha256 "$dataset_manifest_sha256" \
      --arg amiId "$ami_id" --arg ociOriginIpv4 "$oci_origin_ipv4" --arg rdsEngineVersion "$rds_engine_version" \
      --arg databaseBootstrap "$database_bootstrap" --arg rdsSnapshotIdentifier "$rds_snapshot_identifier" \
      --arg rdsSnapshotSourceRunId "$rds_snapshot_source_run_id" \
      --arg rdsSnapshotSourceResourceId "$rds_snapshot_source_resource_id" \
      --argjson cacheEnabled "$cache_enabled" --arg requestTarget "$request_target" --argjson loadGeneratorEnabled "$load_generator_enabled" \
      --arg appImageReference "$app_image_reference" --argjson infraImageReferences "$infra_image_references" \
      --arg dnsMode "$dns_mode" --arg albIngressCidr "$alb_ingress_cidr" \
      --arg datasetManifestVersionId "$dataset_manifest_version_id" \
      --arg bundleChecksumVersionId "$bundle_checksum_version_id" \
      --arg bundleManifestVersionId "$bundle_manifest_version_id" --arg bundleManifestSha256 "$bundle_manifest_sha256" \
      '{schemaVersion:2,runId:$runId,expiresAt:$expiresAt,fencingToken:$fencingToken,mode:$mode,policy:$policy,dnsMode:$dnsMode,albIngressCidr:$albIngressCidr,imageDigest:$imageDigest,datasetRelease:$datasetRelease,datasetManifestVersionId:$datasetManifestVersionId,bundleCommit:$bundleCommit,bundleSha256:$bundleSha256,bundleChecksumVersionId:$bundleChecksumVersionId,bundleManifestVersionId:$bundleManifestVersionId,bundleManifestSha256:$bundleManifestSha256,datasetManifestSha256:$datasetManifestSha256,amiId:$amiId,ociOriginIpv4:$ociOriginIpv4,rdsEngineVersion:$rdsEngineVersion,databaseBootstrap:$databaseBootstrap,rdsSnapshotIdentifier:$rdsSnapshotIdentifier,rdsSnapshotSourceRunId:$rdsSnapshotSourceRunId,rdsSnapshotSourceResourceId:$rdsSnapshotSourceResourceId,cacheEnabled:$cacheEnabled,requestTarget:$requestTarget,loadGeneratorEnabled:$loadGeneratorEnabled,appImageReference:$appImageReference,infraImageReferences:$infraImageReferences}' \
      > "$manifest"
    write_run_manifest "$manifest"
    write_tfvars network false ""
    up_in_progress=true

    current_stage=state-identity
    persist_run_identity
    current_stage=network
    resource_started_epoch=$(date +%s)
    resource_started_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
    apply_lab # deployment_phase=network
    phase2=$(run_terraform_command "Terraform Phase 2 output" \
      -chdir="$lab_root" output -json phase2_contract)
    vpc_id=$(jq -er '.vpc_id' <<<"$phase2")
    route_table_id=$(jq -er '.primary_private_route_table' <<<"$phase2")
    probe_instance_id=$(jq -er '.probe_instance_id' <<<"$phase2")
    run_supervised_mutation "network egress verification" \
      "$network_verifier" egress "$run_id" "$vpc_id" "$route_table_id" \
      "$probe_instance_id" "$ami_id" "$evidence_bucket" >/dev/null

    current_stage=probe-cleared
    write_tfvars probe-cleared false "$probe_instance_id"
    apply_lab # deployment_phase=probe-cleared
    run_supervised_mutation "network clearance verification" \
      "$network_verifier" cleared "$run_id" "$vpc_id" "$probe_instance_id" \
      "$evidence_bucket" >/dev/null
    current_stage=services-and-data-bootstrap
    write_tfvars services false "$probe_instance_id"
    apply_lab # deployment_phase=services
    phase2=$(run_terraform_command "Terraform Phase 2 services output" \
      -chdir="$lab_root" output -json phase2_contract)
    phase3=$(run_terraform_command "Terraform Phase 3 data output" \
      -chdir="$lab_root" output -json phase3_contract)
    debezium_instance_id=$(jq -er '.services.debezium' <<<"$phase2")
    kafka_instance_id=$(jq -er '.services.kafka' <<<"$phase2")
    rds_instance_id=$(jq -er '.rds_instance_id' <<<"$phase3")
    rds_resource_id=$(jq -er '.rds_resource_id' <<<"$phase3")
    rds_endpoint=$(jq -er '.rds_endpoint' <<<"$phase3")
    rds_secret_arn=$(aws rds describe-db-instances --db-instance-identifier "$rds_instance_id" \
      --query 'DBInstances[0].MasterUserSecret.SecretArn' --output text --region "$AWS_REGION" --no-cli-pager)
    assert_lease
    run_supervised_mutation "data bootstrap and measurement-policy verification" \
      "$policy_verifier" "$policy" "$run_id" "$debezium_instance_id" "$kafka_instance_id" \
      "$rds_endpoint" "$rds_secret_arn" "$evidence_bucket" "$fencing_token" >/dev/null
    current_stage=data-ready-and-app
    write_tfvars data-ready true "$probe_instance_id"
    apply_lab # deployment_phase=data-ready
    data_ready_epoch=$(date +%s)
    data_ready_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
    resource_to_data_ready_seconds=$((data_ready_epoch - resource_started_epoch))
    phase4=$(run_terraform_command "Terraform Phase 4 application output" \
      -chdir="$lab_root" output -json phase4_contract)
    aws_alb_arn=$(jq -er '.alb_arn' <<<"$phase4")
    aws_alb_dns_name=$(jq -er '.alb_dns_name' <<<"$phase4")
    target_group_arn=$(jq -er '.target_group_arn' <<<"$phase4")
    asg_name=$(jq -er '.auto_scaling_group_name' <<<"$phase4")
    alb_security_group_id=$(jq -er \
      '.alb_security_group_id | select(type == "string" and test("^sg-[0-9a-f]{8,17}$"))' \
      <<<"$phase4") || fail "Phase 4 ALB security-group identity is invalid"
    expected_app_capacity=$(jq -cer '
      .capacity |
      select(
        (keys | sort) == ["desired", "max", "min"] and
        all(.[]; type == "number" and floor == . and . >= 0) and
        .min <= .desired and .desired <= .max
      )
    ' <<<"$phase4") || fail "Phase 4 application capacity is invalid"
    wait_for_application
    current_stage=application-smoke
    verify_direct_aws_smoke
    current_stage=oci-post-direct-smoke
    verify_oci_authority after-direct-smoke
    current_stage=direct-readiness
    publish_direct_readiness
    if [[ "$dns_mode" == cutover ]]; then
      current_stage=dns-stage
      invoke_dns_controller stage oci >/dev/null
      current_stage=dns-switch
      invoke_dns_controller switch aws >/dev/null
      dns_switched=true
      verify_public_aws_smoke
    fi
    current_stage=evidence
    write_terraform_output_evidence required
    up_in_progress=false
    printf 'run_id=%s\nfencing_token=%s\nexpires_at=%s\ndns_mode=%s\ndns_target=%s\n' \
      "$run_id" "$fencing_token" "$expires_at" "$dns_mode" "$([[ "$dns_mode" == cutover ]] && printf aws || printf oci)"
    ;;
  switch|down)
    explicit_run_id=${RUN_ID:-}
    run_id=${explicit_run_id:-$(current_run_id)}
    valid_run_id "$run_id" || fail "RUN_ID is unavailable or invalid"
    manifest="$temp_dir/operator.json"
    read_run_manifest "$run_id" "$manifest"
    resource_fencing_token=$(jq -er '.fencingToken' "$manifest")
    [[ "$resource_fencing_token" =~ ^[1-9][0-9]*$ ]] || fail "run manifest fencing token is invalid"
    expires_at=$(jq -er '.expiresAt' "$manifest")
    [[ "$expires_at" =~ ^[1-9][0-9]{9}$ ]] || fail "run manifest expiry is invalid"
    mode=$(jq -er '.mode' "$manifest")
    policy=$(jq -er '.policy' "$manifest")
    dns_mode=$(jq -r '.dnsMode // "cutover"' "$manifest")
    alb_ingress_cidr=$(jq -r '.albIngressCidr // "0.0.0.0/0"' "$manifest")
    [[ "$dns_mode" == direct-only || "$dns_mode" == cutover ]] \
      || fail "run manifest DNS mode is invalid"
    cache_enabled=$(jq -r '.cacheEnabled' "$manifest")
    request_target=$(jq -r '.requestTarget' "$manifest")
    load_generator_enabled=$(jq -r '.loadGeneratorEnabled' "$manifest")
    [[ "$cache_enabled" == true || "$cache_enabled" == false ]] || fail "run manifest cache toggle is invalid"
    [[ "$load_generator_enabled" == true || "$load_generator_enabled" == false ]] \
      || fail "run manifest load-generator toggle is invalid"
    ami_id=$(jq -er '.amiId' "$manifest")
    oci_origin_ipv4=$(jq -er '.ociOriginIpv4' "$manifest")
    database_bootstrap=$(jq -er '.databaseBootstrap' "$manifest")
    rds_snapshot_identifier=$(jq -r '.rdsSnapshotIdentifier // ""' "$manifest")
    rds_snapshot_source_run_id=$(jq -r '.rdsSnapshotSourceRunId // ""' "$manifest")
    rds_snapshot_source_resource_id=$(jq -r '.rdsSnapshotSourceResourceId // ""' "$manifest")
    validate_snapshot_bootstrap_inputs
    rds_engine_version=$(jq -er '.rdsEngineVersion' "$manifest")
    bundle_commit=$(jq -er '.bundleCommit' "$manifest")
    bundle_sha256=$(jq -er '.bundleSha256' "$manifest")
    dataset_release=$(jq -er '.datasetRelease' "$manifest")
    dataset_manifest_sha256=$(jq -er '.datasetManifestSha256' "$manifest")
    [[ "$dataset_release" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ \
      && "$dataset_manifest_sha256" =~ ^[0-9a-f]{64}$ ]] \
      || fail "run manifest dataset identity is invalid"
    app_image_reference=$(jq -er '.appImageReference' "$manifest")
    infra_image_references=$(jq -c '.infraImageReferences' "$manifest")
    force=false
    if [[ "$action" == down ]]; then
      force=${FORCE:-false}
      [[ "$force" == true || "$force" == false ]] || fail "FORCE must be true or false"
    fi
    validate_operator_scope_for_action "$force"
    start_mutation_guard
    prepare_lab_backend
    recover_prior_terraform_lock
    recovery_finalization=false
    identity_only_finalization=false
    if [[ "$action" == down ]] && terraform_state_is_empty; then
      [[ -n "$explicit_run_id" ]] \
        || fail "empty-state teardown finalization requires an explicit RUN_ID"
      state_object_present || fail "teardown finalization requires the versioned backend state object"
      recovery_finalization=true
    else
      assert_state_run_identity required
      if [[ "$action" == down ]] && terraform_state_is_identity_only; then
        identity_only_finalization=true
      fi
    fi
    phase4=$(run_terraform_command "Terraform active Phase 4 output" \
      -chdir="$lab_root" output -json phase4_contract 2>/dev/null || printf '{}')
    aws_alb_arn=$(jq -r '.alb_arn // empty' <<<"$phase4")
    aws_alb_dns_name=$(jq -r '.alb_dns_name // empty' <<<"$phase4")
    if [[ "$action" == switch ]]; then
      [[ "$dns_mode" == cutover ]] || fail "direct-only runs cannot switch DNS"
      [[ -n "$aws_alb_arn" && -n "$aws_alb_dns_name" ]] || fail "switch requires an active lab ALB"
      target=${TARGET:-}
      [[ "$target" == aws || "$target" == oci ]] || fail "TARGET must be aws or oci"
      if [[ "$target" == aws ]]; then
        switch_dataset_manifest="$temp_dir/dataset-manifest.json"
        aws s3api get-object --bucket "$dataset_bucket" \
          --key "datasets/$dataset_release/manifest.json" "$switch_dataset_manifest" \
          --region "$AWS_REGION" --no-cli-pager >/dev/null \
          || fail "dataset completion manifest is unavailable for AWS switch smoke"
        [[ "$(sha256_file "$switch_dataset_manifest")" == "$dataset_manifest_sha256" ]] \
          || fail "AWS switch dataset manifest differs from the provisioned run"
        validate_operator_dataset_manifest "$switch_dataset_manifest" "$dataset_release" \
          || fail "AWS switch dataset completion manifest is invalid"
        load_release_smoke_inputs "$switch_dataset_manifest"
        current_stage=dns-switch
        up_in_progress=true
      fi
      invoke_dns_controller switch "$target"
      if [[ "$target" == aws ]]; then
        dns_switched=true
        current_stage=public-application-smoke
        verify_public_aws_smoke
        up_in_progress=false
        dns_switched=false
      fi
      exit 0
    fi
    if [[ "$force" == true && $(date +%s) -lt "$expires_at" ]]; then
      fail "FORCE teardown is permitted only at or after expiry"
    fi
    if [[ "$force" == true ]]; then
      cleanup_evidence="$temp_dir/forced-cleanup.json"
      jq -n --arg runId "$run_id" --argjson fencingToken "$fencing_token" \
        --argjson observedAt "$(date +%s)" \
        '{schemaVersion:1,runId:$runId,fencingToken:$fencingToken,reason:"expired",observedAt:$observedAt}' \
        > "$cleanup_evidence"
      aws s3api put-object --bucket "$evidence_bucket" \
        --key "cleanup/$run_id/forced-$fencing_token.json" --body "$cleanup_evidence" \
        --tagging Retention=summary --server-side-encryption AES256 \
        --content-type application/json --region "$AWS_REGION" --no-cli-pager >/dev/null
    fi
    if [[ "$recovery_finalization" == true ]]; then
      load_teardown_start_for_recovery
      load_teardown_finalize_for_recovery
      verify_oci_authority teardown-recovery
      AIRBOB_SCAN_SCOPE=global "$orphan_scanner" "$run_id"
      finalize_clean_teardown true
      printf 'destroyed_run_id=%s\ndns_mode=%s\ndns_target=oci\nteardown_recovered=true\n' "$run_id" "$dns_mode"
      exit 0
    fi
    if [[ "$dns_mode" == cutover ]]; then
      invoke_dns_controller remove oci
    fi
    verify_oci_authority before-destroy
    ensure_teardown_start
    if [[ "$force" == true || "$identity_only_finalization" == true ]]; then
      write_terraform_output_evidence best-effort || true
    else
      write_terraform_output_evidence required
    fi
    write_tfvars network false ""
    destroy_lab
    terraform_state_is_empty || fail "Terraform state is not empty after destroy"
    verify_oci_authority after-destroy
    AIRBOB_SCAN_SCOPE=global "$orphan_scanner" "$run_id"
    finalize_clean_teardown false
    printf 'destroyed_run_id=%s\ndns_mode=%s\ndns_target=oci\nteardown_recovered=false\n' "$run_id" "$dns_mode"
    ;;
esac
