#!/usr/bin/env bash
set -euo pipefail
umask 077
# Preserve an operator diagnostic channel across the intentionally quiet
# automatic-cleanup compound command.
exec 9>&2

DEFAULT_COMMAND_DEADLINE_SECONDS=5400
UP_COMMAND_DEADLINE_SECONDS=18000
DEFAULT_CREDENTIAL_SESSION_SECONDS=7200
UP_CREDENTIAL_SESSION_SECONDS=21600
UP_POST_FAILURE_CLEANUP_ALLOWANCE_SECONDS=2400
LAB_ROLE_MAX_SESSION_SECONDS=21600
TERRAFORM_LOCK_CREDENTIAL_EXPIRY_MARGIN_SECONDS=300
WORKFLOW_FINALIZATION_MARGIN_SECONDS=300
LEASE_TRANSITION_MARGIN_SECONDS=300
LEASE_ACQUIRE_MAX_SECONDS=120
LEASE_ACQUIRE_RECOVERY_MAX_SECONDS=60
LEASE_CONTROL_CALL_MAX_SECONDS=30
TERRAFORM_LOCK_CREDENTIAL_EXPIRY_BARRIER_SECONDS=$((
  LAB_ROLE_MAX_SESSION_SECONDS + TERRAFORM_LOCK_CREDENTIAL_EXPIRY_MARGIN_SECONDS
))
COMMAND_DEADLINE_SECONDS=$DEFAULT_COMMAND_DEADLINE_SECONDS
LEASE_DEADLINE_SECONDS=$DEFAULT_COMMAND_DEADLINE_SECONDS
CREDENTIAL_SESSION_SECONDS=$DEFAULT_CREDENTIAL_SESSION_SECONDS
UP_FAILURE_CLEANUP_ALLOWANCE_SECONDS=0
INSTANCE_REFRESH_TIMEOUT_SECONDS=900
HEARTBEAT_TTL_SECONDS=180
HEARTBEAT_INTERVAL_SECONDS=60
MUTATION_TERMINATION_GRACE_SECONDS=10

fail() {
  local message=$1
  if [[ "${cleanup_in_progress:-false}" == true && "${BASH_SUBSHELL:-0}" -eq 0 ]]; then
    abort_cleanup 1 "$message"
  fi
  printf '%s\n' "$message" >&2
  exit 1
}

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
  [[ "$candidate" =~ ^db-[A-Z0-9]+$ ]]
}

require_global_b_execution_deadline() {
  [[ "$approved_execution_deadline_epoch" =~ ^[1-9][0-9]{9}$ ]] \
    || fail "B operations require APPROVED_EXECUTION_DEADLINE_EPOCH as the approved common UTC epoch"
}

set_global_b_execution_expiry() {
  local selected_now=$1 selected_ttl_hours=$2
  require_global_b_execution_deadline
  expires_at=$((selected_now + selected_ttl_hours * 3600))
  if (( expires_at > approved_execution_deadline_epoch )); then
    expires_at=$approved_execution_deadline_epoch
  fi
  (( expires_at - selected_now >= UP_CREDENTIAL_SESSION_SECONDS )) \
    || fail "A new B run needs at least six hours before its capped resource expiry"
}

validate_retained_global_b_execution_deadline() {
  local original=$1 retained_deadline
  load_retained_rds_class "$original"
  require_global_b_execution_deadline
  retained_deadline=$(jq -er '.approvedExecutionDeadlineEpoch |
    select(type=="number" and floor==. and (tostring|test("^[1-9][0-9]{9}$")))' "$original") \
    || fail "Retained B run has no immutable approved execution deadline"
  [[ "$retained_deadline" == "$approved_execution_deadline_epoch" ]] \
    || fail "B continuation cannot change the approved common execution deadline"
  (( expires_at <= retained_deadline && expires_at > $(date +%s) + LEASE_DEADLINE_SECONDS )) \
    || fail "Original B resource expiry cannot cover this lease or exceeds the approved deadline"
}

load_retained_rds_class() {
  local original=$1 transition_ref=${B_MAC_DOWNSIZE_RECEIPT_JSON:-}
  set -- selected --operator "$original"
  rds_class_transition_file=''
  rds_class_transition_sha=''
  if [[ -n "$transition_ref" ]]; then
    printf '%s\n' "$transition_ref" > "$temp_dir/mac-downsize-reference.json"
    python3 - "$script_dir" "$original" "$temp_dir/mac-downsize-reference.json" <<'MAC_DOWNSIZE_REF' || fail "Mac downsize reference differs from the original run"
import sys
sys.path.insert(0, sys.argv[1])
import growth_b_rds_class as gate
import growth_b_mac_downsize as downsize
downsize.reference(gate.read(sys.argv[3]), gate.read(sys.argv[2]))
MAC_DOWNSIZE_REF
    rds_class_transition_file="$temp_dir/mac-downsize-transition.json"
    fetch_b_class_evidence "$transition_ref" "$rds_class_transition_file"
    rds_class_transition_sha=$(jq -er '.sha256' <<<"$transition_ref")
    set -- "$@" --transition "$rds_class_transition_file" --transition-sha256 "$rds_class_transition_sha"
  fi
  rds_instance_class=$(python3 "$script_dir/growth_b_rds_class.py" "$@") \
    || fail "The original RDS class selection is invalid"
  [[ -z "${B_RDS_INSTANCE_CLASS:-}" || "$B_RDS_INSTANCE_CLASS" == "$rds_instance_class" ]] \
    || fail "Retained operations cannot change the original RDS class"
  [[ -z "${B_RDS_CLASS_REHEARSAL_JSON:-}" ]] || fail "Retained operations cannot replace initial class qualification"
  rds_class_operator_file=$original
}

verify_retained_rds_class() {
  [[ "$global_b_prepare_only" == true || "$global_b_snapshot_restore_only" == true || "$global_b_services" == true || -n "$global_b_snapshot_operation" ]] || return 0
  [[ -n "$rds_class_operator_file" && -f "$rds_class_operator_file" ]] || fail "Original RDS class binding is missing"
  printf '%s\n' "$phase3" > "$temp_dir/rds-class-phase3.json"
  aws rds describe-db-instances --db-instance-identifier "airbob-$run_id" --region "$AWS_REGION" \
    --no-cli-pager --cli-connect-timeout 5 --cli-read-timeout 15 > "$temp_dir/rds-class-live.json" \
    || fail "RDS class observation failed"
  set -- live --operator "$rds_class_operator_file" --phase3 "$temp_dir/rds-class-phase3.json" --rds "$temp_dir/rds-class-live.json"
  if [[ -n "${rds_class_transition_file:-}" ]]; then
    set -- "$@" --transition "$rds_class_transition_file" --transition-sha256 "$rds_class_transition_sha"
  fi
  python3 "$script_dir/growth_b_rds_class.py" "$@" >/dev/null \
    || fail "Live RDS class, immutable selection, pending class, or original identity differs"
}

fetch_b_class_evidence() {
  local reference=$1 destination=$2 response="$temp_dir/rds-class-object.json"
  aws s3api head-object --bucket "$evidence_bucket" --key "$(jq -er '.key' <<<"$reference")" \
    --version-id "$(jq -er '.versionId' <<<"$reference")" --region "$AWS_REGION" \
    --no-cli-pager --cli-connect-timeout 5 --cli-read-timeout 15 > "$response" || fail "Class evidence metadata is unavailable"
  [[ "$(jq -er '.VersionId' "$response")" == "$(jq -er '.versionId' <<<"$reference")" && \
    "$(jq -er '.ContentLength' "$response")" == "$(jq -er '.bytes' <<<"$reference")" ]] \
    || fail "Class evidence exceeds the reviewed byte/version bound"
  aws s3api get-object --bucket "$evidence_bucket" --key "$(jq -er '.key' <<<"$reference")" \
    --version-id "$(jq -er '.versionId' <<<"$reference")" "$destination" --region "$AWS_REGION" \
    --no-cli-pager --cli-connect-timeout 5 --cli-read-timeout 15 > "$response" || fail "Pinned class evidence is unavailable"
  [[ "$(jq -er '.VersionId' "$response")" == "$(jq -er '.versionId' <<<"$reference")" && \
    "$(sha256_file "$destination")" == "$(jq -er '.sha256' <<<"$reference")" && \
    "$(wc -c < "$destination" | tr -d ' ')" == "$(jq -er '.bytes' <<<"$reference")" ]] \
    || fail "Class evidence version or bytes differ"
}

validate_b_class_rehearsal() {
  local mode=$1 ref=${B_RDS_CLASS_REHEARSAL_JSON:-} qualification="$temp_dir/class-rehearsal.json" raw_ref
  [[ "$rds_instance_class" == db.m6i.large ]] || { [[ -z "$ref" ]] || fail "Class qualification requires explicit large selection"; return 0; }
  [[ -n "$ref" ]] || fail "New class final execution requires actual same-class small qualification"
  printf '%s\n' "$ref" > "$temp_dir/class-rehearsal-reference.json"
  python3 - "$script_dir" "$temp_dir/class-rehearsal-reference.json" <<'B_CLASS_REF'
import sys
sys.path.insert(0,sys.argv[1])
import growth_b_rds_class as gate
gate.reference(gate.read(sys.argv[2]))
B_CLASS_REF
  fetch_b_class_evidence "$ref" "$qualification"
  python3 - "$script_dir" "$temp_dir/class-rehearsal-reference.json" "$qualification" <<'B_CLASS_REF_BINDING'
import sys
sys.path.insert(0,sys.argv[1])
import growth_b_rds_class as gate
ref=gate.reference(gate.read(sys.argv[2])); value=gate.read(sys.argv[3])
gate.need(ref['key']==f'data-bootstrap/{value["runId"]}/{value["datasetId"]}-rds-class.json','CLASS_PRODUCER_PREFIX')
B_CLASS_REF_BINDING
  if [[ "$mode" == snapshot ]]; then
    raw_ref=$(python3 - "$script_dir" "$qualification" <<'B_CLASS_RAW_REF'
import json,sys
sys.path.insert(0,sys.argv[1])
import growth_b_rds_class as gate
v=gate.read(sys.argv[2])
print(json.dumps(gate.standalone_reference(v['standaloneReceipt'],v['runId'],v['datasetId'])))
B_CLASS_RAW_REF
    ) || fail "Class qualification raw small reference differs"
    fetch_b_class_evidence "$raw_ref" "$temp_dir/b-small-rds-receipt.json"
    python3 "$script_dir/growth_b_rds_class.py" validate-snapshot-rehearsal --qualification "$qualification" \
      --standalone "$temp_dir/b-small-rds-receipt.json" --provenance "$temp_dir/dataset-manifest.json" \
      --instance-class "$rds_instance_class" >/dev/null || fail "Snapshot class rehearsal and actual source contract differ"
  else
    python3 "$script_dir/growth_b_rds_class.py" validate-rehearsal --qualification "$qualification" \
      --manifest "$temp_dir/dataset-manifest.json" --standalone "$temp_dir/b-small-rds-receipt.json" \
      --instance-class "$rds_instance_class" >/dev/null || fail "Final wrapper and same-class small evidence differ"
  fi
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

[[ "$#" -eq 1 ]] || fail "usage: aws-lab.sh up|prepare|services|cdc|asg-probe|snapshot-create|snapshot-restore|snapshot-prepare|snapshot-retire|status|switch|down"
action=$1
# B preparation shares the existing up lease/deadline and teardown machinery.
# Its explicit selector never enters the legacy data-ready/application stage.
global_b_prepare_only=false
global_b_import_from_mac=${B_IMPORT_FROM_MAC:-false}
global_b_services=false
global_b_cdc=false
global_b_snapshot_service=false
global_b_asg_probe=false
global_b_snapshot_restore_only=false
global_b_snapshot_source_mode=${B_SNAPSHOT_SOURCE_MODE:-verified-global-b-snapshot}
lab_power_request=null
global_b_snapshot_operation=""
global_b_snapshot_provenance=null
global_b_service_release=""
global_b_service_bootstrap_enabled=false
global_b_readiness_receipt=null
approved_execution_deadline_epoch=${APPROVED_EXECUTION_DEADLINE_EPOCH:-}
rds_instance_class=${B_RDS_INSTANCE_CLASS:-db.t3.small}
rds_class_operator_file=""
if [[ "$action" == prepare ]]; then
  global_b_prepare_only=true
  action=up
elif [[ "$action" == services ]]; then
  global_b_services=true
  global_b_service_release=${B_SERVICE_RELEASE:-}
  action=up
elif [[ "$action" == asg-probe ]]; then
  global_b_asg_probe=true
  global_b_services=true
  action=up
elif [[ "$action" == cdc ]]; then
  global_b_cdc=true
  global_b_services=true
  action=up
elif [[ "$action" == snapshot-restore ]]; then
  global_b_snapshot_restore_only=true
  action=up
elif [[ "$action" == snapshot-create || "$action" == snapshot-prepare || "$action" == snapshot-retire ]]; then
  global_b_snapshot_operation=${action#snapshot-}
  action=up
fi
[[ "$global_b_import_from_mac" == true || "$global_b_import_from_mac" == false ]] || fail "B_IMPORT_FROM_MAC must be true or false"
[[ "$global_b_import_from_mac" == false || "$global_b_prepare_only" == true ]] || fail "Mac import is selected only with prepare"
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

if [[ "$action" == up ]]; then
  COMMAND_DEADLINE_SECONDS=$UP_COMMAND_DEADLINE_SECONDS
  CREDENTIAL_SESSION_SECONDS=$UP_CREDENTIAL_SESSION_SECONDS
  UP_FAILURE_CLEANUP_ALLOWANCE_SECONDS=$UP_POST_FAILURE_CLEANUP_ALLOWANCE_SECONDS
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
  test_failure_cleanup_allowance=${AIRBOB_TEST_FAILURE_CLEANUP_ALLOWANCE_SECONDS:-}
  test_lease_acquire_max=${AIRBOB_TEST_LEASE_ACQUIRE_MAX_SECONDS:-}
  test_lease_acquire_recovery_max=${AIRBOB_TEST_LEASE_ACQUIRE_RECOVERY_MAX_SECONDS:-}
  test_lease_control_call_max=${AIRBOB_TEST_LEASE_CONTROL_CALL_MAX_SECONDS:-}
  test_heartbeat_interval=${AIRBOB_TEST_HEARTBEAT_INTERVAL_SECONDS:-}
  test_heartbeat_ttl=${AIRBOB_TEST_HEARTBEAT_TTL_SECONDS:-}
  test_termination_grace=${AIRBOB_TEST_TERMINATION_GRACE_SECONDS:-}
  for test_failure_injection in \
    "${AIRBOB_TEST_TIMEOUT_MARKER_WRITE_FAILURE:-}" \
    "${AIRBOB_TEST_HEARTBEAT_FAILURE_MARKER_WRITE_FAILURE:-}" \
    "${AIRBOB_TEST_CLEANUP_MARKER_REMOVE_FAILURE:-}" \
    "${AIRBOB_TEST_LEASE_CAPTURE_PREPARE_FAILURE:-}"; do
    [[ -z "$test_failure_injection" || "$test_failure_injection" == true ]] \
      || fail "operator test failure injections must be true or unset"
  done
  [[ "$test_command_deadline" =~ ^[1-9][0-9]?$ ]] \
    || fail "test command deadline must be 1-99 seconds"
  [[ "$test_heartbeat_interval" =~ ^[1-9][0-9]?$ ]] \
    || fail "test heartbeat interval must be 1-99 seconds"
  [[ "$test_termination_grace" =~ ^[1-9][0-9]?$ ]] \
    || fail "test mutation termination grace must be 1-99 seconds"
  if [[ -n "$test_failure_cleanup_allowance" ]]; then
    [[ "$action" == up && "$test_failure_cleanup_allowance" =~ ^[1-9][0-9]?$ ]] \
      || fail "test failure cleanup allowance must be 1-99 seconds for up"
    UP_FAILURE_CLEANUP_ALLOWANCE_SECONDS=$test_failure_cleanup_allowance
  fi
  if [[ -n "$test_lease_acquire_max" ]]; then
    [[ "$test_lease_acquire_max" =~ ^[1-9][0-9]?$ ]] \
      || fail "test lease acquisition deadline must be 1-99 seconds"
    LEASE_ACQUIRE_MAX_SECONDS=$test_lease_acquire_max
  fi
  if [[ -n "$test_lease_acquire_recovery_max" ]]; then
    [[ "$test_lease_acquire_recovery_max" =~ ^[1-9][0-9]?$ ]] \
      || fail "test lease acquisition recovery deadline must be 1-99 seconds"
    LEASE_ACQUIRE_RECOVERY_MAX_SECONDS=$test_lease_acquire_recovery_max
  fi
  if [[ -n "$test_lease_control_call_max" ]]; then
    [[ "$test_lease_control_call_max" =~ ^[1-9][0-9]?$ ]] \
      || fail "test lease control-call deadline must be 1-99 seconds"
    LEASE_CONTROL_CALL_MAX_SECONDS=$test_lease_control_call_max
  fi
  if [[ -n "$test_heartbeat_ttl" ]]; then
    [[ "$test_heartbeat_ttl" =~ ^[1-9][0-9]?$ && \
      "$test_heartbeat_ttl" -ge 2 && \
      "$test_heartbeat_ttl" -gt "$test_heartbeat_interval" ]] \
      || fail "test heartbeat TTL must be 2-99 seconds and exceed its interval"
    HEARTBEAT_TTL_SECONDS=$test_heartbeat_ttl
  fi
  COMMAND_DEADLINE_SECONDS=$test_command_deadline
  HEARTBEAT_INTERVAL_SECONDS=$test_heartbeat_interval
  MUTATION_TERMINATION_GRACE_SECONDS=$test_termination_grace
elif [[ -n "${AIRBOB_TEST_COMMAND_DEADLINE_SECONDS:-}${AIRBOB_TEST_FAILURE_CLEANUP_ALLOWANCE_SECONDS:-}${AIRBOB_TEST_LEASE_ACQUIRE_MAX_SECONDS:-}${AIRBOB_TEST_LEASE_ACQUIRE_RECOVERY_MAX_SECONDS:-}${AIRBOB_TEST_LEASE_CONTROL_CALL_MAX_SECONDS:-}${AIRBOB_TEST_HEARTBEAT_INTERVAL_SECONDS:-}${AIRBOB_TEST_HEARTBEAT_TTL_SECONDS:-}${AIRBOB_TEST_TERMINATION_GRACE_SECONDS:-}${AIRBOB_TEST_TIMEOUT_MARKER_WRITE_FAILURE:-}${AIRBOB_TEST_HEARTBEAT_FAILURE_MARKER_WRITE_FAILURE:-}${AIRBOB_TEST_CLEANUP_MARKER_REMOVE_FAILURE:-}${AIRBOB_TEST_LEASE_CAPTURE_PREPARE_FAILURE:-}" ]]; then
  fail "operator test timing overrides require the hermetic fake harness"
fi

HEARTBEAT_LOOP_STOP_MAX_SECONDS=$((LEASE_CONTROL_CALL_MAX_SECONDS + 1))
FAILURE_CLEANUP_LEASE_HANDOFF_MAX_SECONDS=$((
  HEARTBEAT_LOOP_STOP_MAX_SECONDS + LEASE_CONTROL_CALL_MAX_SECONDS * 2
))
((FAILURE_CLEANUP_LEASE_HANDOFF_MAX_SECONDS <= LEASE_TRANSITION_MARGIN_SECONDS)) \
  || fail "failure cleanup lease handoff exceeds its transition margin"

if [[ "$action" == up ]]; then
  LEASE_DEADLINE_SECONDS=$((
    COMMAND_DEADLINE_SECONDS + UP_FAILURE_CLEANUP_ALLOWANCE_SECONDS +
      LEASE_TRANSITION_MARGIN_SECONDS
  ))
else
  LEASE_DEADLINE_SECONDS=$COMMAND_DEADLINE_SECONDS
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
    --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken,Expiration]' \
    --output text --region "$AWS_REGION") || fail "cannot assume the lab operator role"
  read -r AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AIRBOB_AWS_CREDENTIAL_EXPIRATION <<EOF
$credentials
EOF
  export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AIRBOB_AWS_CREDENTIAL_EXPIRATION
  [[ -n "$AWS_ACCESS_KEY_ID" && -n "$AWS_SECRET_ACCESS_KEY" && \
    -n "$AWS_SESSION_TOKEN" && -n "$AIRBOB_AWS_CREDENTIAL_EXPIRATION" ]] \
    || fail "assumed Lab role returned an incomplete static STS environment credential tuple"
  caller_arn=$(aws sts get-caller-identity --query Arn --output text --region "$AWS_REGION")
  [[ "$caller_arn" == arn:aws:sts::$AIRBOB_AWS_ACCOUNT_ID:assumed-role/"$lab_role_name"/* ]] \
    || fail "lab operations require credentials from the selected operator scope"
}
ensure_lab_role

validate_up_credential_budget() {
  local expiration=${AIRBOB_AWS_CREDENTIAL_EXPIRATION:-}
  local expiration_epoch now_epoch required_remaining_seconds remaining_seconds

  [[ "$action" == up ]] || return 0
  [[ -n "$expiration" ]] \
    || fail "up requires the exact static STS credential expiration"
  expiration_epoch=$(aws_utc_timestamp_epoch "$expiration") \
    || fail "static STS credential expiration is not a real UTC instant"
  now_epoch=$(date -u +%s)
  required_remaining_seconds=$((
    LEASE_DEADLINE_SECONDS + TERRAFORM_LOCK_CREDENTIAL_EXPIRY_MARGIN_SECONDS
  ))
  remaining_seconds=$((expiration_epoch - now_epoch))
  ((remaining_seconds >= required_remaining_seconds)) \
    || fail "static STS credential lifetime cannot cover the lease deadline and safety margin"
}
validate_up_credential_budget

validate_workflow_deadline_budget() {
  local deadline_epoch=${AIRBOB_WORKFLOW_DEADLINE_EPOCH:-}
  local now_epoch required_remaining_seconds remaining_seconds

  [[ "$action" == up ]] || return 0
  if [[ -z "$deadline_epoch" ]]; then
    [[ "${GITHUB_ACTIONS:-false}" != true ]] \
      || fail "GitHub Actions up requires AIRBOB_WORKFLOW_DEADLINE_EPOCH"
    return 0
  fi
  [[ "$deadline_epoch" =~ ^[1-9][0-9]{9}$ ]] \
    || fail "workflow deadline epoch is not canonical"
  now_epoch=$(date -u +%s)
  required_remaining_seconds=$((
    LEASE_DEADLINE_SECONDS + WORKFLOW_FINALIZATION_MARGIN_SECONDS
  ))
  remaining_seconds=$((deadline_epoch - now_epoch))
  ((remaining_seconds >= required_remaining_seconds)) \
    || fail "workflow lifetime cannot cover the lease deadline and finalization margin"
}

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
heartbeat_failure_file="$temp_dir/heartbeat-failure"
heartbeat_pid=''
lease_control_watchdog_pid=''
command_watchdog_pid=''
cleanup_watchdog_pid=''
active_mutation_pid=''
active_mutation_pgid=''
lease_acquired=false
lease_acquire_interrupted_status=0
lease_acquire_interrupted_reason=''
bounded_lease_command_timed_out=false
bounded_lease_command_capture_ready=false
dns_switched=false
current_tfvars=''
up_in_progress=false
cleanup_in_progress=false
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

abort_cleanup() {
  local exit_status=$1 reason=$2
  trap '' HUP INT TERM USR1 USR2 ALRM
  stop_active_mutation "$reason" || exit_status=125
  printf '%s\n' "$reason" >&9
  finish_cleanup
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

watchdog_sleep() {
  local delay_seconds=$1 sleeper_pid=''
  trap '
    if [[ -n "$sleeper_pid" ]]; then
      kill -TERM "$sleeper_pid" 2>/dev/null || true
      wait "$sleeper_pid" 2>/dev/null || true
    fi
    exit 0
  ' TERM
  sleep "$delay_seconds" &
  sleeper_pid=$!
  wait "$sleeper_pid"
  trap - TERM
}

stop_watchdog() {
  local pid=${1:-}
  [[ -n "$pid" ]] || return 0
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}

stop_heartbeat_loop() {
  local pid=$heartbeat_pid stop_fallback_pid
  [[ -n "$pid" ]] || return 0
  kill -TERM "$pid" 2>/dev/null || true
  (
    watchdog_sleep "$HEARTBEAT_LOOP_STOP_MAX_SECONDS"
    kill -KILL "$pid" 2>/dev/null || true
  ) &
  stop_fallback_pid=$!
  wait "$pid" 2>/dev/null || true
  stop_watchdog "$stop_fallback_pid"
  heartbeat_pid=''
}

heartbeat_wait_interval() {
  local remaining_seconds=$HEARTBEAT_INTERVAL_SECONDS
  while ((remaining_seconds > 0)); do
    [[ "${heartbeat_stop_requested:-false}" == false ]] || return 1
    sleep 1 || true
    [[ "${heartbeat_stop_requested:-false}" == false ]] || return 1
    remaining_seconds=$((remaining_seconds - 1))
  done
  return 0
}

run_bounded_heartbeat() {
  run_bounded_lease_command "$LEASE_CONTROL_CALL_MAX_SECONDS" \
    "$temp_dir/lease-heartbeat.out" "$temp_dir/lease-heartbeat.err" \
    "$temp_dir/lease-heartbeat.timeout" \
    heartbeat "$lease_table" "$lease_lock_id" "$lease_owner" \
    "$fencing_token" "$run_id" "$lease_command" "$HEARTBEAT_TTL_SECONDS"
}

publish_heartbeat_failure() {
  [[ "${AIRBOB_TEST_HEARTBEAT_FAILURE_MARKER_WRITE_FAILURE:-false}" != true ]] \
    || return 2
  (
    set -o noclobber
    printf '%s\n' failed > "$heartbeat_failure_file"
  ) 2>/dev/null && return 0
  [[ -e "$heartbeat_failure_file" ]] && return 1
  return 2
}

start_heartbeat_loop() {
  local parent_pid=$$
  [[ "$lease_acquired" == true && -z "$heartbeat_pid" ]] \
    || fail "heartbeat loop requires one acquired lease and no prior loop"
  (
    heartbeat_stop_requested=false
    trap 'heartbeat_stop_requested=true' TERM
    while heartbeat_wait_interval; do
      if run_bounded_heartbeat; then
        [[ "$heartbeat_stop_requested" == false ]] || break
      else
        [[ "$heartbeat_stop_requested" == false ]] || break
        heartbeat_failure_publish_status=0
        if publish_heartbeat_failure; then
          kill -USR1 "$parent_pid" 2>/dev/null || true
        else
          heartbeat_failure_publish_status=$?
          if [[ "$heartbeat_failure_publish_status" -eq 2 ]]; then
            kill -USR1 "$parent_pid" 2>/dev/null || true
            break
          fi
        fi
      fi
    done
  ) &
  heartbeat_pid=$!
}

renew_cleanup_lease() {
  run_bounded_heartbeat
}

assert_cleanup_lease() {
  run_bounded_lease_command "$LEASE_CONTROL_CALL_MAX_SECONDS" \
    "$temp_dir/lease-cleanup-assert.out" "$temp_dir/lease-cleanup-assert.err" \
    "$temp_dir/lease-cleanup-assert.timeout" \
    assert "$lease_table" "$lease_lock_id" "$lease_owner" \
    "$fencing_token" "$run_id" "$lease_command"
}

clear_heartbeat_failure_marker() {
  [[ "${AIRBOB_TEST_CLEANUP_MARKER_REMOVE_FAILURE:-false}" != true ]] \
    || return 1
  rm -f "$heartbeat_failure_file"
}

start_failure_cleanup_watchdog() {
  local deadline_seconds=$UP_FAILURE_CLEANUP_ALLOWANCE_SECONDS parent_pid=$$
  [[ "$deadline_seconds" =~ ^[1-9][0-9]*$ ]] \
    || fail "failure cleanup watchdog requires a positive deadline"
  (
    watchdog_sleep "$deadline_seconds"
    kill -ALRM "$parent_pid" 2>/dev/null || true
  ) &
  cleanup_watchdog_pid=$!
  printf 'failure cleanup watchdog armed for %s seconds\n' "$deadline_seconds" >&2
}

release_lease_bounded_best_effort() {
  run_bounded_lease_command "$LEASE_CONTROL_CALL_MAX_SECONDS" \
    "$temp_dir/lease-final-release.out" "$temp_dir/lease-final-release.err" \
    "$temp_dir/lease-final-release.timeout" \
    release "$lease_table" "$lease_lock_id" "$lease_owner" \
    "$fencing_token" "$run_id" "$lease_command"
}

finish_cleanup() {
  trap - EXIT
  trap '' HUP INT TERM USR1 USR2 ALRM
  stop_active_mutation "cleanup finalizer" || true
  stop_heartbeat_loop
  stop_watchdog "$lease_control_watchdog_pid"
  lease_control_watchdog_pid=''
  stop_watchdog "$command_watchdog_pid"
  command_watchdog_pid=''
  stop_watchdog "$cleanup_watchdog_pid"
  cleanup_watchdog_pid=''
  if [[ "$lease_acquired" == true ]]; then
    release_lease_bounded_best_effort || true
    lease_acquired=false
  fi
  rm -rf "$temp_dir"
}

cleanup() {
  local status=$?
  local automatic_failure_cleanup=false
  local failure_cleanup_prerequisites=false oci_after_failure_cleanup=false
  trap - EXIT
  cleanup_in_progress=true
  trap '' HUP INT TERM USR1 USR2 ALRM
  trap finish_cleanup EXIT
  stop_watchdog "$command_watchdog_pid"
  command_watchdog_pid=''
  if [[ "$status" -ne 0 && "$up_in_progress" == true && \
    "$keep_on_failure" == false && -n "$current_tfvars" && -f "$current_tfvars" ]]; then
    automatic_failure_cleanup=true
  fi
  trap 'abort_cleanup 129 "operator cleanup received SIGHUP"' HUP
  trap 'abort_cleanup 130 "operator cleanup received SIGINT"' INT
  trap 'abort_cleanup 143 "operator cleanup received SIGTERM"' TERM
  if [[ "$automatic_failure_cleanup" == true ]]; then
    stop_heartbeat_loop
    if ! clear_heartbeat_failure_marker; then
      printf '%s\n' \
        'failure cleanup marker reset failed; resources remain preserved' >&9
      finish_cleanup
      exit "$status"
    fi
    if ! renew_cleanup_lease; then
      printf '%s\n' \
        'failure cleanup lease renewal failed; resources remain preserved' >&9
      finish_cleanup
      exit "$status"
    fi
    if ! assert_cleanup_lease; then
      printf '%s\n' \
        'failure cleanup lease validation failed; resources remain preserved' >&9
      finish_cleanup
      exit "$status"
    fi
    trap 'abort_cleanup 75 "orchestration heartbeat failed during operator cleanup"' USR1
    start_heartbeat_loop
    if [[ -e "$heartbeat_failure_file" ]]; then
      printf '%s\n' \
        'failure cleanup lease validation failed; resources remain preserved' >&9
      finish_cleanup
      exit "$status"
    fi
    printf '%s\n' 'failure cleanup lease renewed; heartbeat continuity verified' >&2
    trap 'abort_cleanup 124 "failure cleanup deadline exceeded"' ALRM
    start_failure_cleanup_watchdog
  else
    trap 'abort_cleanup 75 "orchestration heartbeat failed during operator cleanup"' USR1
  fi
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
    infra/aws/scripts/compute-target-fingerprint.sh \
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
  if [[ "$global_b_prepare_only" == true || "$global_b_services" == true || "$global_b_snapshot_restore_only" == true || -n "$global_b_snapshot_operation" ]]; then
    for relative in infra/aws/scripts/growth_b_prepare.py infra/aws/scripts/bootstrap-growth-b-entry.sh \
      infra/aws/scripts/growth_b_rds_class.py infra/aws/lab/rds.tf infra/aws/lab/modules/rds/main.tf infra/aws/lab/modules/rds/variables.tf infra/aws/lab/modules/rds/outputs.tf \
      infra/aws/scripts/bootstrap-growth-b-stop.sh infra/aws/scripts/growth_b_aws_restore.py \
      infra/aws/scripts/growth_b_aws_contract.py infra/aws/scripts/growth_b_contract.py infra/aws/scripts/growth_b_runtime.py infra/aws/scripts/growth_b_inventory.py \
      infra/aws/scripts/growth_b_snapshot_controller.py infra/aws/scripts/growth_b_snapshot.py infra/aws/scripts/growth_b_snapshot_host.py \
      infra/aws/lab/growth-b.tf infra/aws/lab/app.tf infra/aws/lab/iam.tf infra/aws/lab/monitoring.tf \
      infra/aws/lab/locals.tf infra/aws/lab/checks.tf infra/aws/lab/data.tf infra/aws/lab/outputs.tf \
      infra/aws/lab/ssm.tf infra/aws/lab/private-dns.tf infra/aws/lab/service-hosts.tf \
      infra/aws/lab/templates/growth-b-host-user-data.sh.tftpl infra/aws/toolchain.env; do
      [[ -f "$repo_root/$relative" && ! -L "$repo_root/$relative" ]] || fail "B operator identity file is unavailable"
      printf '%s\t%s\n' "$(sha256_file "$repo_root/$relative")" "$relative" >> "$inventory"
    done
  fi
  if [[ "$global_b_services" == true ]]; then
    for relative in infra/aws/scripts/growth_b_service.py infra/aws/scripts/growth_b_search.py infra/aws/scripts/growth_b_app_runtime.py \
      infra/aws/scripts/growth_b_mac_service.py infra/aws/scripts/growth_b_mac_downsize.py \
      infra/aws/scripts/bootstrap-growth-b-services.sh infra/aws/lab/templates/start-growth-b-app.sh.tftpl; do
      [[ -f "$repo_root/$relative" && ! -L "$repo_root/$relative" ]] || fail "B service identity file is unavailable"
      printf '%s\t%s\n' "$(sha256_file "$repo_root/$relative")" "$relative" >> "$inventory"
    done
    if [[ "${B_SERVICE_STAGE:-}" == native-restore || "${B_SERVICE_STAGE:-}" == native-snapshot-restore ]]; then
      python3 - "$script_dir" >> "$inventory" <<'AIRBOB_B_NATIVE_IDENTITY'
import sys
sys.path.insert(0, sys.argv[1])
from growth_b_search_controller import source_files
for relative, digest in sorted(source_files().items()):
    print(digest + '\t' + relative)
AIRBOB_B_NATIVE_IDENTITY
    fi
  fi
  if [[ "$global_b_asg_probe" == true ]]; then
    for relative in infra/aws/scripts/growth_b_asg_probe.py infra/aws/scripts/growth_b_asg_controller.py infra/aws/scripts/growth_b_app_runtime.py; do
      [[ -f "$repo_root/$relative" && ! -L "$repo_root/$relative" ]] || fail "ASG probe identity file is unavailable"
      printf '%s\t%s\n' "$(sha256_file "$repo_root/$relative")" "$relative" >> "$inventory"
    done
  fi
  if [[ "$global_b_cdc" == true ]]; then
    python3 - "$script_dir" >> "$inventory" <<'AIRBOB_B_CDC_IDENTITY'
import sys
sys.path.insert(0, sys.argv[1])
from growth_b_cdc_supervisor import source_files
for relative, digest in sorted(source_files().items()):
    print(digest + '\t' + relative)
AIRBOB_B_CDC_IDENTITY
  fi
  if [[ "$global_b_snapshot_service" == true ]]; then
    python3 - "$script_dir" >> "$inventory" <<'AIRBOB_B_SNAPSHOT_SERVICE_IDENTITY'
import sys
sys.path.insert(0, sys.argv[1])
from growth_b_snapshot_service_verify import source_files
for relative, digest in sorted(source_files().items()):
    print(digest + '\t' + relative)
AIRBOB_B_SNAPSHOT_SERVICE_IDENTITY
  fi
  if [[ "$global_b_snapshot_restore_only" == true || -n "$global_b_snapshot_operation" || ( "$global_b_services" == true && "$database_bootstrap" == snapshot ) ]]; then
    for relative in infra/aws/scripts/growth_b_snapshot.py infra/aws/lab/growth-b-snapshot.tf; do
      [[ -f "$repo_root/$relative" && ! -L "$repo_root/$relative" ]] || fail "B snapshot identity file is unavailable"
      printf '%s\t%s\n' "$(sha256_file "$repo_root/$relative")" "$relative" >> "$inventory"
    done
  fi
  if [[ "$global_b_snapshot_restore_only" == true || -n "$global_b_snapshot_operation" ]]; then
    for relative in infra/aws/scripts/growth_b_snapshot_controller.py infra/aws/scripts/growth_b_snapshot_host.py \
      infra/aws/scripts/bootstrap-growth-b-snapshot.sh; do
      [[ -f "$repo_root/$relative" && ! -L "$repo_root/$relative" ]] || fail "Snapshot bridge identity file is unavailable"
      printf '%s\t%s\n' "$(sha256_file "$repo_root/$relative")" "$relative" >> "$inventory"
    done
  fi
  if [[ "$global_b_snapshot_source_mode" == mac-snapshot-counts-ddl ]]; then
    for relative in infra/aws/scripts/growth_b_mac_snapshot.py infra/aws/scripts/growth_b_mac_snapshot_controller.py \
      infra/aws/lab/growth-b-mac-snapshot.tf; do
      [[ -f "$repo_root/$relative" && ! -L "$repo_root/$relative" ]] || fail "Mac snapshot controller identity file is unavailable"
      printf '%s\t%s\n' "$(sha256_file "$repo_root/$relative")" "$relative" >> "$inventory"
    done
  fi
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

run_bounded_lease_command() {
  local deadline_seconds=$1 output_file=$2 error_file=$3 timeout_file=$4
  local platform lease_command_pid result=0
  local bounded_started_seconds=$SECONDS bounded_elapsed_seconds
  shift 4
  bounded_lease_command_timed_out=false
  bounded_lease_command_capture_ready=false
  [[ "$deadline_seconds" =~ ^[1-9][0-9]*$ ]] \
    || fail "bounded lease command requires a positive deadline"
  if [[ "${AIRBOB_TEST_LEASE_CAPTURE_PREPARE_FAILURE:-false}" == true ]] ||
    ! { : > "$output_file" && : > "$error_file" && rm -f "$timeout_file"; }; then
    printf '%s\n' 'cannot prepare bounded lease command capture files' >&2
    return 125
  fi
  bounded_lease_command_capture_ready=true
  platform=$(uname -s)
  case "$platform" in
    Linux)
      command -v setsid >/dev/null 2>&1 \
        || fail "Linux bounded lease command requires setsid"
      setsid "$lease_script" "$@" >"$output_file" 2>"$error_file" &
      ;;
    Darwin)
      command -v python3 >/dev/null 2>&1 \
        || fail "Darwin bounded lease command requires python3 for a new process group"
      python3 -c 'import os, sys; os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])' \
        "$lease_script" "$@" >"$output_file" 2>"$error_file" &
      ;;
    *) fail "bounded lease commands are unsupported on this operating system" ;;
  esac
  lease_command_pid=$!
  active_mutation_pid=$lease_command_pid
  active_mutation_pgid=$lease_command_pid
  (
    watchdog_sleep "$deadline_seconds"
    kill -KILL -- "-$lease_command_pid" 2>/dev/null \
      || kill -KILL "$lease_command_pid" 2>/dev/null || true
    if [[ "${AIRBOB_TEST_TIMEOUT_MARKER_WRITE_FAILURE:-false}" != true ]]; then
      : > "$timeout_file" 2>/dev/null || true
    fi
  ) &
  lease_control_watchdog_pid=$!
  if wait "$lease_command_pid"; then
    result=0
  else
    result=$?
  fi
  bounded_elapsed_seconds=$((SECONDS - bounded_started_seconds))
  stop_watchdog "$lease_control_watchdog_pid"
  lease_control_watchdog_pid=''
  if mutation_group_alive; then
    stop_active_mutation "bounded lease command left descendants" || result=125
    [[ "$result" -ne 0 ]] || result=125
  else
    active_mutation_pid=''
    active_mutation_pgid=''
  fi
  if [[ -e "$timeout_file" || \
    ( "$result" -ne 0 && "$bounded_elapsed_seconds" -ge "$deadline_seconds" ) ]]; then
    bounded_lease_command_timed_out=true
  fi
  return "$result"
}

run_bounded_lease_acquire() {
  local output_file="$temp_dir/lease-acquire.out"
  local error_file="$temp_dir/lease-acquire.err"
  local timeout_file="$temp_dir/lease-acquire.timeout"
  local result=0
  if run_bounded_lease_command "$LEASE_ACQUIRE_MAX_SECONDS" \
    "$output_file" "$error_file" "$timeout_file" \
    acquire "$lease_table" "$lease_lock_id" "$lease_owner" \
    "$run_id" "$lease_command" "$HEARTBEAT_TTL_SECONDS" "$LEASE_DEADLINE_SECONDS"; then
    result=0
  else
    result=$?
  fi
  token_output=''
  if [[ "$bounded_lease_command_capture_ready" == true ]]; then
    token_output=$(<"$output_file")
  fi
  lease_acquire_timed_out=$bounded_lease_command_timed_out
  if [[ "$result" -ne 0 && "$lease_acquire_timed_out" == false ]]; then
    cat "$error_file" >&2
  fi
  return "$result"
}

lease_status_matches_failed_acquire() {
  local status_output=$1 invocation_start=$2 invocation_finish=$3
  local actual_owner actual_token actual_run actual_command actual_acquired
  local actual_heartbeat actual_expiry actual_deadline extra
  local expected_expiry expected_deadline
  [[ "$status_output" != *$'\n'* ]] || return 1
  read -r actual_owner actual_token actual_run actual_command actual_acquired \
    actual_heartbeat actual_expiry actual_deadline extra <<< "$status_output"
  [[ -z "${extra:-}" && "$actual_owner" == "$lease_owner" && \
    "$actual_token" =~ ^[1-9][0-9]*$ && "$actual_run" == "$run_id" && \
    "$actual_command" == "$lease_command" && \
    "$actual_acquired" =~ ^[1-9][0-9]{9}$ && \
    "$actual_heartbeat" =~ ^[1-9][0-9]{9}$ && \
    "$actual_expiry" =~ ^[1-9][0-9]{9}$ && \
    "$actual_deadline" =~ ^[1-9][0-9]{9}$ ]] || return 1
  expected_expiry=$((actual_acquired + HEARTBEAT_TTL_SECONDS))
  expected_deadline=$((actual_acquired + LEASE_DEADLINE_SECONDS))
  ((actual_acquired >= invocation_start && actual_acquired <= invocation_finish && \
    actual_heartbeat == actual_acquired && actual_expiry == expected_expiry && \
    actual_deadline == expected_deadline)) || return 1
  recovered_fencing_token=$actual_token
}

recover_tokenless_lease_acquire() {
  local invocation_start=$1 invocation_finish=$2
  local recovery_started recovery_deadline now remaining call_deadline status_output
  local status_file="$temp_dir/lease-recovery-status.out"
  local status_error="$temp_dir/lease-recovery-status.err"
  local status_timeout="$temp_dir/lease-recovery-status.timeout"
  local release_output="$temp_dir/lease-recovery-release.out"
  local release_error="$temp_dir/lease-recovery-release.err"
  local release_timeout="$temp_dir/lease-recovery-release.timeout"
  recovery_started=$(date +%s)
  recovery_deadline=$((recovery_started + LEASE_ACQUIRE_RECOVERY_MAX_SECONDS))
  while :; do
    now=$(date +%s)
    remaining=$((recovery_deadline - now))
    ((remaining > 0)) || break
    call_deadline=$remaining
    ((call_deadline <= LEASE_CONTROL_CALL_MAX_SECONDS)) \
      || call_deadline=$LEASE_CONTROL_CALL_MAX_SECONDS
    if run_bounded_lease_command "$call_deadline" \
      "$status_file" "$status_error" "$status_timeout" \
      status "$lease_table" "$lease_lock_id"; then
      status_output=$(<"$status_file")
      if lease_status_matches_failed_acquire "$status_output" \
        "$invocation_start" "$invocation_finish"; then
        now=$(date +%s)
        remaining=$((recovery_deadline - now))
        ((remaining > 0)) || break
        call_deadline=$remaining
        ((call_deadline <= LEASE_CONTROL_CALL_MAX_SECONDS)) \
          || call_deadline=$LEASE_CONTROL_CALL_MAX_SECONDS
        if run_bounded_lease_command "$call_deadline" \
          "$release_output" "$release_error" "$release_timeout" \
          release "$lease_table" "$lease_lock_id" "$lease_owner" \
          "$recovered_fencing_token" "$run_id" "$lease_command"; then
          printf '%s\n' \
            'recovered and released an exact tokenless orchestration lease acquisition' >&2
          return 0
        fi
      fi
    fi
    now=$(date +%s)
    ((recovery_deadline - now > 1)) || break
    sleep 1
  done
  return 1
}

start_command_watchdog() {
  local parent_pid=$$
  [[ -z "$command_watchdog_pid" ]] || return 0
  (
    watchdog_sleep "$COMMAND_DEADLINE_SECONDS"
    kill -USR2 "$parent_pid" 2>/dev/null || true
  ) &
  command_watchdog_pid=$!
}

install_operator_signal_traps() {
  trap 'abort_operator 129 "operator received SIGHUP"' HUP
  trap 'abort_operator 130 "operator received SIGINT"' INT
  trap 'abort_operator 143 "operator received SIGTERM"' TERM
  trap 'abort_operator 75 "orchestration heartbeat failed"' USR1
  trap 'abort_operator 124 "operator command deadline exceeded"' USR2
}

interrupt_lease_acquire() {
  local exit_status=$1 reason=$2
  if [[ "$lease_acquire_interrupted_status" -eq 0 ]]; then
    lease_acquire_interrupted_status=$exit_status
    lease_acquire_interrupted_reason=$reason
  fi
  stop_active_mutation "$reason" || lease_acquire_interrupted_status=125
}

start_mutation_guard() {
  local lease_acquire_started_epoch lease_acquire_finished_epoch lease_acquire_recovered=false
  local lease_acquire_elapsed_seconds lease_acquire_result=0
  local lease_owner_base lease_owner_suffix lease_owner_base_max invocation_id
  lease_owner_base=${LEASE_OWNER:-${GITHUB_REPOSITORY:-local}/${GITHUB_RUN_ID:-$(id -un)}:${GITHUB_RUN_ATTEMPT:-1}}
  [[ "$lease_owner_base" =~ ^[A-Za-z0-9._:@/-]{3,128}$ ]] \
    || fail "lease owner is not canonical"
  invocation_id="${temp_dir##*.}-$$"
  [[ "$invocation_id" =~ ^[A-Za-z0-9]+-[1-9][0-9]*$ ]] \
    || fail "lease invocation identity is not canonical"
  lease_owner_suffix="@$invocation_id"
  lease_owner_base_max=$((128 - ${#lease_owner_suffix}))
  ((lease_owner_base_max >= 3)) || fail "lease owner leaves no invocation identity capacity"
  lease_owner="${lease_owner_base:0:lease_owner_base_max}$lease_owner_suffix"
  export LEASE_OWNER=$lease_owner
  [[ "$action" == up ]] || start_command_watchdog
  lease_acquire_interrupted_status=0
  lease_acquire_interrupted_reason=''
  trap 'interrupt_lease_acquire 129 "operator received SIGHUP during lease acquisition"' HUP
  trap 'interrupt_lease_acquire 130 "operator received SIGINT during lease acquisition"' INT
  trap 'interrupt_lease_acquire 143 "operator received SIGTERM during lease acquisition"' TERM
  trap 'interrupt_lease_acquire 124 "operator command deadline exceeded during lease acquisition"' USR2
  lease_acquire_started_epoch=$(date +%s)
  if run_bounded_lease_acquire; then
    lease_acquire_result=0
  else
    lease_acquire_result=$?
  fi
  lease_acquire_finished_epoch=$(date +%s)
  fencing_token=${token_output#fencing_token=}
  if [[ "$fencing_token" =~ ^[1-9][0-9]*$ ]]; then
    lease_acquired=true
  fi
  lease_acquire_elapsed_seconds=$((
    lease_acquire_finished_epoch - lease_acquire_started_epoch
  ))
  if [[ "$lease_acquired" == false ]]; then
    if recover_tokenless_lease_acquire "$lease_acquire_started_epoch" \
      "$lease_acquire_finished_epoch"; then
      lease_acquire_recovered=true
    fi
    install_operator_signal_traps
    if [[ "$lease_acquire_interrupted_status" -ne 0 ]]; then
      printf '%s\n' "$lease_acquire_interrupted_reason" >&2
      exit "$lease_acquire_interrupted_status"
    fi
    if [[ "$lease_acquire_recovered" == true ]]; then
      fail "tokenless orchestration lease acquisition was recovered and released"
    fi
    if [[ "$lease_acquire_timed_out" == true ]]; then
      fail "orchestration lease acquisition timed out without one recoverable exact lease"
    fi
    fail "orchestration lease acquisition failed without one recoverable exact lease"
  fi
  install_operator_signal_traps
  if [[ "$lease_acquire_interrupted_status" -ne 0 ]]; then
    abort_operator "$lease_acquire_interrupted_status" "$lease_acquire_interrupted_reason"
  fi
  [[ "$lease_acquire_result" -eq 0 ]] \
    || [[ "$lease_acquire_timed_out" == true && "$lease_acquired" == true ]] \
    || fail "orchestration lease acquisition failed"
  [[ "$lease_acquired" == true ]] || fail "lease did not issue a fencing token"
  [[ "$lease_acquire_timed_out" == false ]] && \
    ((lease_acquire_elapsed_seconds >= 0 && \
      lease_acquire_elapsed_seconds <= LEASE_ACQUIRE_MAX_SECONDS)) \
    || fail "orchestration lease acquisition exceeded the bounded deadline"
  validate_workflow_deadline_budget
  validate_up_credential_budget
  start_heartbeat_loop
  start_command_watchdog
}

assert_lease() {
  local output_file="$temp_dir/lease-assert.out"
  local error_file="$temp_dir/lease-assert.err"
  local timeout_file="$temp_dir/lease-assert.timeout"
  if run_bounded_lease_command "$LEASE_CONTROL_CALL_MAX_SECONDS" \
    "$output_file" "$error_file" "$timeout_file" \
    assert "$lease_table" "$lease_lock_id" "$lease_owner" \
    "$fencing_token" "$run_id" "$lease_command"; then
    return 0
  fi
  if [[ "$bounded_lease_command_capture_ready" == true ]]; then
    cat "$error_file" >&2 || true
  fi
  return 1
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
  local legacy_manifest_sha composite_manifest_sha target_fingerprint

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
  # Pure metadata qualification must fail before lease acquisition or resource
  # creation, even for a release whose Elasticsearch smoke is disabled.
  aws s3api get-object --bucket "$dataset_bucket" \
    --key "datasets/$dataset_release/benchmark/dataset-manifest.json" "$composite_manifest" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "benchmark dataset manifest is unavailable"
  composite_manifest_sha=$(sha256_file "$composite_manifest")
  [[ "$composite_manifest_sha" == "$(jq -r '.source.benchmarkDatasetManifestSha256' "$dataset_manifest")" ]] \
    || fail "benchmark dataset manifest digest does not match the release"
  target_fingerprint=$("$script_dir/compute-target-fingerprint.sh" "$composite_manifest") \
    || fail "target fingerprint preflight calculation failed"
  [[ "$target_fingerprint" == "$(jq -r '.targetFingerprint' "$composite_manifest")" && \
    "$target_fingerprint" == "$(jq -r '.releaseTuple.targetFingerprintSha256' "$dataset_manifest")" ]] \
    || fail "target fingerprint preflight differs from the immutable release"
  if [[ "$smoke_search_enabled" == true ]]; then
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

fetch_global_b_input() {
  local name=$1 destination=$2 reference key version digest bytes response="$temp_dir/b-object-response.json"
  reference=$(jq -ce --arg name "$name" '.files[$name]' "$temp_dir/dataset-manifest.json")
  key=$(jq -er '.key' <<<"$reference")
  version=$(jq -er '.versionId' <<<"$reference")
  digest=$(jq -er '.sha256' <<<"$reference")
  bytes=$(jq -er '.bytes' <<<"$reference")
  aws s3api get-object --bucket "$dataset_bucket" --key "$key" --version-id "$version" "$destination" \
    --region "$AWS_REGION" --no-cli-pager > "$response" || fail "Pinned B preparation input is unavailable"
  [[ "$(jq -er '.VersionId' "$response")" == "$version" && "$(wc -c < "$destination" | tr -d ' ')" == "$bytes" \
    && "$(sha256_file "$destination")" == "$digest" ]] || fail "Pinned B preparation bytes/version differ"
}

validate_global_b_inputs() {
  local selected_manifest=$1 reference key version bytes result="$temp_dir/b-object-head.json"
  local -a receipt_args=(--manifest "$selected_manifest")
  [[ "${B_PREPARATION_SHA256:-}" =~ ^[0-9a-f]{64}$ && "$B_PREPARATION_SHA256" == "$dataset_manifest_sha256" ]] \
    || fail "B prepare requires the explicit reviewed aws-preparation.json SHA256"
  python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3,12) else 1)' \
    || fail "B offline admission requires Python 3.12 or newer"
  if [[ "$global_b_import_from_mac" == true ]]; then
    fetch_global_b_input envelope "$temp_dir/b-envelope.json"
    python3 "$script_dir/growth_b_prepare.py" validate-mac-inputs --manifest "$selected_manifest" \
      --sha256 "$dataset_manifest_sha256" --dataset-id "$dataset_release" --envelope "$temp_dir/b-envelope.json" \
      > "$temp_dir/b-input-admission.json" || fail "Mac import dataset or storage metadata differs"
    return
  fi
  python3 "$script_dir/growth_b_prepare.py" validate-manifest --manifest "$selected_manifest" \
    --sha256 "$dataset_manifest_sha256" --dataset-id "$dataset_release" > "$temp_dir/b-manifest-admission.json" \
    || fail "B preparation wrapper or reviewed helper identity is invalid"
  fetch_global_b_input envelope "$temp_dir/b-envelope.json"
  if [[ "$(jq -r '.scope' "$selected_manifest")" == final-b-rds ]]; then
    fetch_global_b_input smallRdsReceipt "$temp_dir/b-small-rds-receipt.json"
    receipt_args+=(--small-receipt "$temp_dir/b-small-rds-receipt.json")
  fi
  python3 "$script_dir/growth_b_prepare.py" validate-inputs \
    --sha256 "$dataset_manifest_sha256" --dataset-id "$dataset_release" --envelope "$temp_dir/b-envelope.json" \
    "${receipt_args[@]}" > "$temp_dir/b-input-admission.json" \
    || fail "B preparation envelope, measured capacity, or current small RDS receipt is invalid"
  if [[ "$(jq -er '.scope' "$selected_manifest")" == final-b-rds ]]; then
    validate_b_class_rehearsal dump
  else
    [[ -z "${B_RDS_CLASS_REHEARSAL_JSON:-}" ]] || fail "Small execution cannot consume a final class prerequisite"
  fi
  # Existence/size/version before any provision; the host verifies every SHA
  # and the complete sealed contract before its first database connection.
  while IFS= read -r reference; do
    key=$(jq -er '.key' <<<"$reference"); version=$(jq -er '.versionId' <<<"$reference"); bytes=$(jq -er '.bytes' <<<"$reference")
    aws s3api head-object --bucket "$dataset_bucket" --key "$key" --version-id "$version" \
      --region "$AWS_REGION" --no-cli-pager > "$result" || fail "B preparation object has not been staged"
    jq -e --arg version "$version" --argjson bytes "$bytes" \
      '.VersionId == $version and .ContentLength == $bytes' "$result" >/dev/null \
      || fail "Staged B object version/size differs"
  done < <(jq -cs '.[0].files[], .[1].objects[]' "$selected_manifest" "$temp_dir/b-envelope.json")
}

verify_global_b_receipt() {
  local key="data-bootstrap/$run_id/$dataset_release.json" version receipt="$temp_dir/b-preparation-receipt.json"
  local standalone="$temp_dir/b-standalone-receipt.json" reference result="$temp_dir/b-receipt-object.json"
  local topology
  assert_lease
  topology=$(run_terraform_command "Terraform B preparation output" -chdir="$lab_root" output -json global_b_preparation)
  jq -e --arg id "$debezium_instance_id" '.selected == true and .host_instance_id == $id and
    .app_asg_created == false and .alb_created == false and .cdc_started == false and .deployment_ready == false' <<<"$topology" >/dev/null \
    || fail "B preparation topology differs"
  jq -e '.services | keys == ["debezium"]' <<<"$phase2" >/dev/null || fail "B preparation has unexpected service hosts"
  version=$(aws s3api head-object --bucket "$evidence_bucket" --key "$key" --query VersionId --output text \
    --region "$AWS_REGION" --no-cli-pager) || fail "B host completion receipt is unavailable"
  [[ "$version" =~ ^[A-Za-z0-9._~+/=-]+$ && "$version" != null && "$version" != None ]] || fail "B receipt has no version identity"
  aws s3api get-object --bucket "$evidence_bucket" --key "$key" --version-id "$version" "$receipt" \
    --region "$AWS_REGION" --no-cli-pager > "$result" || fail "B host receipt read failed"
  [[ "$(jq -er '.VersionId' "$result")" == "$version" ]] || fail "B receipt version changed"
  reference=$(jq -ce --arg key "data-bootstrap/$run_id/$dataset_release-standalone-rds.json" '
    .standaloneReceiptObject | select((keys|sort)==["bytes","key","sha256","versionId"] and .key==$key and
      (.versionId|type=="string" and test("^[A-Za-z0-9._~+/=-]+$") and .!="null" and .!="None") and
      (.sha256|test("^[0-9a-f]{64}$")) and (.bytes|type=="number" and .>0 and .<10485760))' "$receipt") \
    || fail "B standalone receipt reference is invalid"
  aws s3api get-object --bucket "$evidence_bucket" --key "$(jq -r '.key' <<<"$reference")" \
    --version-id "$(jq -r '.versionId' <<<"$reference")" "$standalone" --region "$AWS_REGION" --no-cli-pager > "$result" \
    || fail "B standalone receipt read failed"
  [[ "$(jq -r '.VersionId' "$result")" == "$(jq -r '.versionId' <<<"$reference")" && \
    "$(wc -c < "$standalone" | tr -d ' ')" == "$(jq -r '.bytes' <<<"$reference")" && \
    "$(sha256_file "$standalone")" == "$(jq -r '.sha256' <<<"$reference")" ]] || fail "B standalone receipt bytes differ"
  jq -n --arg run "$run_id" --arg manifest "$dataset_manifest_sha256" --arg rds "$(jq -er '.rds_resource_id' <<<"$phase3")" \
    '{runId:$run,manifestSha256:$manifest,rdsResourceId:$rds}' > "$temp_dir/b-receipt-context.json"
  python3 "$script_dir/growth_b_prepare.py" validate-receipt --manifest "$temp_dir/dataset-manifest.json" \
    --sha256 "$dataset_manifest_sha256" --dataset-id "$dataset_release" --envelope "$temp_dir/b-envelope.json" \
    --context "$temp_dir/b-receipt-context.json" --receipt "$receipt" --standalone-receipt "$standalone" \
    > "$temp_dir/b-receipt-admission.json" || fail "B data-only completion proof is invalid"
  if [[ "$rds_instance_class" == db.m6i.large && "$(jq -er '.scope' "$temp_dir/dataset-manifest.json")" == small-rds-rehearsal ]]; then
    local class_reference class_proof="$temp_dir/b-small-class-qualification.json"
    class_reference=$(jq -ce '.rdsClassQualificationObject' "$receipt") || fail "Actual small class qualification is missing"
    printf '%s\n' "$class_reference" > "$temp_dir/b-small-class-reference.json"
    python3 - "$script_dir" "$temp_dir/b-small-class-reference.json" "$run_id" "$dataset_release" <<'B_CLASS_COMPLETION_REF'
import sys
sys.path.insert(0,sys.argv[1])
import growth_b_rds_class as gate
value=gate.reference(gate.read(sys.argv[2]))
gate.need(value['key']==f'data-bootstrap/{sys.argv[3]}/{sys.argv[4]}-rds-class.json','CLASS_COMPLETION_REFERENCE')
B_CLASS_COMPLETION_REF
    fetch_b_class_evidence "$class_reference" "$class_proof"
    python3 "$script_dir/growth_b_rds_class.py" verify-small --qualification "$class_proof" \
      --manifest "$temp_dir/dataset-manifest.json" --standalone "$standalone" --instance-class "$rds_instance_class" >/dev/null \
      || fail "Small class observations and raw frozen receipt differ"
    jq -e --arg run "$run_id" --argjson fence "$resource_fencing_token" --slurpfile original "$rds_class_operator_file" \
      '.runId==$run and .resourceFence==$fence and (.originalContext.expiresAt|tostring)==($original[0].expiresAt|tostring)' \
      "$class_proof" >/dev/null || fail "Small qualification changed the original run, fence, or expiry"
  fi
}

stop_global_b_bootstrap() {
  local instances instance_id payload="$temp_dir/b-stop-command.json" command_id status attempt
  assert_lease
  instances=$(aws ec2 describe-instances --filters Name=tag:Project,Values=airbob \
    Name=tag:Environment,Values=performance-lab Name=tag:Stack,Values=lab Name=tag:ManagedBy,Values=terraform \
    Name=tag:Persistence,Values=ephemeral Name=tag:RunId,Values="$run_id" \
    Name=tag:FencingToken,Values="$resource_fencing_token" Name=tag:Service,Values=debezium \
    Name=instance-state-name,Values=pending,running \
    --query 'Reservations[].Instances[].InstanceId' --output text --region "$AWS_REGION" --no-cli-pager) \
    || fail "B bootstrap cancellation inventory is unavailable"
  for instance_id in $instances; do
    [[ "$instance_id" != None ]] || continue
    [[ "$instance_id" =~ ^i-[0-9a-f]{8,17}$ ]] || fail "Invalid B bootstrap cancellation target"
    jq -n --arg encoded "$(base64 < "$script_dir/bootstrap-growth-b-stop.sh" | tr -d '\n')" \
      --arg sha "$(sha256_file "$script_dir/bootstrap-growth-b-stop.sh")" --arg run "$run_id" --arg fence "$resource_fencing_token" \
      '{commands:["set -eu; umask 077", "install -d -m 700 /opt/airbob/bootstrap-helpers",
        ("printf %s " + $encoded + " | base64 --decode > /opt/airbob/bootstrap-helpers/stop-global-b.sh"),
        ("printf '\''%s  %s\\n'\'' " + $sha + " /opt/airbob/bootstrap-helpers/stop-global-b.sh | sha256sum --check --status"),
        ("bash /opt/airbob/bootstrap-helpers/stop-global-b.sh " + $run + " " + $fence)],executionTimeout:["180"]}' > "$payload"
    assert_lease
    command_id=$(aws ssm send-command --document-name AWS-RunShellScript --instance-ids "$instance_id" \
      --parameters "file://$payload" --timeout-seconds 180 --query 'Command.CommandId' --output text \
      --region "$AWS_REGION" --no-cli-pager) || fail "B bootstrap cancellation could not be sent; resources remain preserved"
    [[ "$command_id" =~ ^[0-9a-f-]{36}$ ]] || fail "Invalid B cancellation command identity"
    status=Pending
    for attempt in $(seq 1 42); do
      assert_lease
      status=$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" \
        --query Status --output text --region "$AWS_REGION" --no-cli-pager 2>/dev/null || printf Pending)
      case "$status" in Success) break ;; Failed|Cancelled|TimedOut) break ;; esac
      sleep 5
    done
    [[ "$status" == Success ]] || fail "B bootstrap stop was not attested; resources remain preserved"
  done
}

resolve_release_inputs() {
  local checksum_file="$temp_dir/bundle.sha256" dataset_manifest="$temp_dir/dataset-manifest.json"
  local bundle_manifest="$temp_dir/bundle-manifest.json"
  local tagged_app_digest bundle_manifest_key dataset_manifest_key
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

  dataset_manifest_key="datasets/$dataset_release/manifest.json"
  if [[ "$global_b_prepare_only" == true ]]; then
    [[ "${B_PREPARATION_SHA256:-}" =~ ^[0-9a-f]{64}$ ]] || fail "B prepare requires a canonical wrapper SHA before selecting its key"
    dataset_manifest_key="datasets/$dataset_release-aws-preparation/aws-preparation-$B_PREPARATION_SHA256.json"
  fi
  [[ "$global_b_services" != true ]] || dataset_manifest_key="datasets/$dataset_release-aws-service/$global_b_service_release/aws-service.json"
  if [[ "$global_b_snapshot_restore_only" == true ]]; then
    [[ "${B_SNAPSHOT_PROVENANCE_SHA256:-}" =~ ^[0-9a-f]{64}$ ]] || fail "B snapshot provenance SHA is required before key selection"
    if [[ "$global_b_snapshot_source_mode" == mac-snapshot-counts-ddl ]]; then
      dataset_manifest_key="datasets/$dataset_release-mac-snapshots/$rds_snapshot_identifier/source-$B_SNAPSHOT_PROVENANCE_SHA256.json"
    else
      [[ "$global_b_snapshot_source_mode" == verified-global-b-snapshot ]] || fail "B snapshot source mode is invalid"
      dataset_manifest_key="datasets/$dataset_release-aws-snapshots/$rds_snapshot_identifier/provenance-$B_SNAPSHOT_PROVENANCE_SHA256.json"
    fi
  fi
  local selected_manifest_bucket=$dataset_bucket
  if [[ "$global_b_snapshot_restore_only" == true && -n "${B_SNAPSHOT_PROVENANCE_KEY:-}" ]]; then
    if [[ "$global_b_snapshot_source_mode" == mac-snapshot-counts-ddl ]]; then
      [[ "$B_SNAPSHOT_PROVENANCE_KEY" == "$dataset_manifest_key" ]] || fail "Mac snapshot source key differs from its exact selected snapshot and SHA"
    else
      dataset_manifest_key=$B_SNAPSHOT_PROVENANCE_KEY
      [[ "$dataset_manifest_key" =~ ^data-bootstrap/$rds_snapshot_source_run_id/$dataset_release-snapshot/[a-z0-9][a-z0-9-]{2,47}/snapshot-provenance[.]json$ ]] \
        || fail "B snapshot provenance must name its exact source run and dataset"
      selected_manifest_bucket=$evidence_bucket
    fi
  fi
  aws s3api get-object --bucket "$selected_manifest_bucket" \
    --key "$dataset_manifest_key" --version-id "$dataset_manifest_version_id" "$dataset_manifest" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null || fail "dataset completion manifest is unavailable"
  dataset_manifest_sha256=$(sha256_file "$dataset_manifest")
  if [[ "$global_b_snapshot_restore_only" == true ]]; then
    [[ "$dataset_manifest_sha256" == "$B_SNAPSHOT_PROVENANCE_SHA256" ]] || fail "B snapshot provenance bytes changed"
    if [[ "$global_b_snapshot_source_mode" == mac-snapshot-counts-ddl ]]; then
      [[ "$rds_instance_class" == db.t3.small && -z "${B_RDS_CLASS_REHEARSAL_JSON:-}" ]] || fail "Mac snapshot restore uses the small RDS class"
      python3 "$script_dir/growth_b_mac_snapshot_controller.py" verify-source --source "$dataset_manifest" >/dev/null
      jq -e --arg run "$rds_snapshot_source_run_id" --arg rid "$rds_snapshot_source_resource_id" --arg snap "$rds_snapshot_identifier" \
        '.source.runId==$run and .source.rds.resourceId==$rid and .snapshot.identifier==$snap' "$dataset_manifest" >/dev/null \
        || fail "Mac snapshot source coordinates changed"
    else
      python3 - "$script_dir" "$dataset_manifest" <<'AIRBOB_B_SNAPSHOT_ADMISSION'
import sys
sys.path.insert(0,sys.argv[1])
import growth_b_snapshot as snapshot
snapshot.verify(snapshot.restore.Aws(), snapshot.read(sys.argv[2]))
print('B snapshot provenance and live snapshot metadata verified')
AIRBOB_B_SNAPSHOT_ADMISSION
      validate_b_class_rehearsal snapshot
    fi
    global_b_snapshot_provenance=$(jq -n --arg key "$dataset_manifest_key" --arg version "$dataset_manifest_version_id" \
      --arg sha "$dataset_manifest_sha256" --argjson bytes "$(wc -c < "$dataset_manifest" | tr -d ' ')" \
      '{key:$key,version_id:$version,sha256:$sha,bytes:$bytes}')
  elif [[ "$global_b_prepare_only" == true ]]; then
    validate_global_b_inputs "$dataset_manifest"
  elif [[ "$global_b_services" == true ]]; then
    [[ "${B_SERVICE_SHA256:-}" == "$dataset_manifest_sha256" ]] || fail "B services require the exact reviewed service manifest SHA256"
    python3 "$script_dir/growth_b_service.py" validate --manifest "$dataset_manifest" --sha256 "$dataset_manifest_sha256" \
      --dataset-id "$dataset_release" --run-id "$run_id" --release "$global_b_service_release" >/dev/null \
      || fail "B service manifest or current helper inventory differs"
  else
    validate_operator_dataset_manifest "$dataset_manifest" "$dataset_release" \
      || fail "dataset completion manifest is invalid"
    load_release_smoke_inputs "$dataset_manifest"
  fi

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
  if [[ "$global_b_services" == true ]]; then
    # B/MySQL 8.4 consumes a separately built 3.0.8 connector. The old shared
    # runtime commit tags and the legacy V27 image contract remain immutable.
    local b_debezium_commit b_debezium_image b_debezium_digest
    b_debezium_commit=$(jq -er '.debezium.buildCommit' "$dataset_manifest")
    b_debezium_image=$(jq -er '.debezium.image' "$dataset_manifest")
    repository_url=$(jq -er '.ecr_repositories.DEBEZIUM_IMAGE.url' <<<"$lab_contract")
    b_debezium_digest=$(aws ecr describe-images --repository-name "${repository_url#*/}" \
      --image-ids "imageTag=global-b-$b_debezium_commit" --query 'imageDetails[0].imageDigest' \
      --output text --region "$AWS_REGION" --no-cli-pager) || fail "Separate immutable B Debezium image is unavailable"
    [[ "$b_debezium_digest" =~ ^sha256:[0-9a-f]{64}$ && "$repository_url@$b_debezium_digest" == "$b_debezium_image" ]] \
      || fail "B Debezium image does not match its separate immutable build tag"
    infra_image_references=$(jq -c --arg ref "$b_debezium_image" '.DEBEZIUM_IMAGE=$ref' <<<"$infra_image_references")
  fi
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
    --argjson global_b_prepare_only "$global_b_prepare_only" \
    --arg global_b_manifest_version_id "${dataset_manifest_version_id:-}" --arg global_b_lease_owner "${lease_owner:-}" \
    --arg rds_snapshot_identifier "$rds_snapshot_identifier" \
    --arg rds_snapshot_source_run_id "$rds_snapshot_source_run_id" \
    --arg rds_snapshot_source_resource_id "$rds_snapshot_source_resource_id" \
    --arg rds_engine_version "$rds_engine_version" --arg rds_instance_class "$rds_instance_class" \
    --arg dns_mode "$dns_mode" --arg alb_ingress_cidr "$alb_ingress_cidr" \
    '{run_id:$run_id,expires_at:$expires_at,fencing_token:$fencing_token,deployment_phase:$deployment_phase,ami_id:$ami_id,verified_probe_instance_id:$verified_probe_instance_id,bundle_commit:$bundle_commit,bundle_sha256:$bundle_sha256,infra_image_references:$infra_image_references,app_image_reference:$app_image_reference,app_enabled:$app_enabled,mode:$mode,measurement_policy:$measurement_policy,accommodation_detail_cache_enabled:$cache_enabled,request_count_per_target_per_minute:(if $request_target == "" then null else ($request_target|tonumber) end),load_generator_enabled:$load_generator_enabled,dataset_release:$dataset_release,dataset_manifest_sha256:$dataset_manifest_sha256,database_bootstrap:$database_bootstrap,rds_snapshot_identifier:$rds_snapshot_identifier,rds_snapshot_source_run_id:$rds_snapshot_source_run_id,rds_snapshot_source_resource_id:$rds_snapshot_source_resource_id,rds_engine_version:$rds_engine_version,rds_instance_class:$rds_instance_class,dns_mode:$dns_mode,alb_ingress_cidr:$alb_ingress_cidr}' \
    | jq --argjson selected "$global_b_prepare_only" --argjson macImport "$global_b_import_from_mac" --arg version "${dataset_manifest_version_id:-}" \
      --arg owner "${lease_owner:-}" --argjson services "$global_b_services" --arg serviceRelease "$global_b_service_release" \
      --argjson bootstrap "$global_b_service_bootstrap_enabled" --argjson readiness "$global_b_readiness_receipt" \
      --argjson snapshotOnly "$global_b_snapshot_restore_only" --argjson provenance "$global_b_snapshot_provenance" \
      --arg sourceMode "$global_b_snapshot_source_mode" --argjson power "$lab_power_request" \
      --argjson operationFence "$fencing_token" --argjson resourceFence "${resource_fencing_token:-$fencing_token}" \
      '. + {global_b_prepare_only:$selected,global_b_import_from_mac:$macImport,global_b_services:$services,global_b_service_release:$serviceRelease,
        global_b_service_bootstrap_enabled:$bootstrap,global_b_readiness_receipt:$readiness,
        global_b_snapshot_restore_only:$snapshotOnly,global_b_snapshot_provenance:$provenance,global_b_snapshot_source_mode:$sourceMode,
        lab_power:$power,
        global_b_manifest_version_id:(if $selected or $services then $version else "" end),
        global_b_lease_owner:(if $selected or $services then $owner else "" end),
        global_b_lease_fencing_token:(if $services then $operationFence else 0 end),
        fencing_token:(if $services then $resourceFence else .fencing_token end)}' \
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
  if [[ "$global_b_prepare_only" == true || "$global_b_snapshot_restore_only" == true || "$global_b_services" == true ]]; then
    local -a class_plan_args=()
    if [[ "$global_b_snapshot_restore_only" == true && "$global_b_snapshot_source_mode" == mac-snapshot-counts-ddl ]]; then
      python3 "$script_dir/growth_b_mac_snapshot_controller.py" plan --plan "$plan_json" --source "$temp_dir/dataset-manifest.json" >/dev/null \
        || fail "Mac snapshot plan changes its RDS or adds an import/service host"
    else
      if [[ "$global_b_snapshot_restore_only" == true ]]; then
      class_plan_args=(--provenance "$temp_dir/dataset-manifest.json" --provenance-sha256 "$dataset_manifest_sha256")
      fi
      python3 "$script_dir/growth_b_rds_class.py" plan --rds "$plan_json" --instance-class "$rds_instance_class" "${class_plan_args[@]}" >/dev/null \
        || fail "B Terraform plan changes the original RDS class or storage shape"
    fi
  fi
  if [[ "$global_b_prepare_only" == true || "$global_b_snapshot_restore_only" == true ]]; then
    jq -e '[.resource_changes[]? | select(.change.after != null) |
      select(.type == "aws_autoscaling_group" or .type == "aws_lb" or .type == "aws_lb_listener" or
        .type == "aws_lb_target_group" or .type == "aws_launch_template")] | length == 0' "$plan_json" >/dev/null \
      || fail "B preparation forbids all application ASG/ALB resources"
  fi
  if [[ "$global_b_services" == true ]]; then
    local mac_connect_create=false
    if [[ "${B_SERVICE_STAGE:-dependencies}" == dependencies ]] &&
      jq -e '.preparation.sourceMode == "mac-sql-postcheck"' "$temp_dir/dataset-manifest.json" >/dev/null; then
      [[ "$rds_instance_class" == db.t3.small &&
        "$(sha256_file "$temp_dir/dataset-manifest.json")" == "$dataset_manifest_sha256" ]] \
        || fail "Mac service plan source differs from the reviewed manifest"
      # Only the real Mac source admission can open this first-host exception.
      # Recheck its unchanged public inputs at the saved-plan apply boundary.
      python3 - "$script_dir" "$temp_dir/dataset-manifest.json" "$rds_class_operator_file" \
        "$rds_class_transition_file" "$temp_dir/mac-sql-complete.json" "$temp_dir/mac-sql-postcheck.json" \
        "$run_id" "$resource_fencing_token" "$expires_at" "$ami_id" <<'AIRBOB_MAC_CONNECT_PLAN' \
        || fail "Mac service plan requires its unchanged SQL and same-RDS source admission"
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import growth_b_service as service
import growth_b_mac_service as mac
manifest, original = service.read(sys.argv[2]), service.read(sys.argv[3])
for path, name in ((sys.argv[4], 'receipt'), (sys.argv[5], 'sqlImportReceipt'), (sys.argv[6], 'postcheckReceipt')):
    reference = manifest['preparation'][name]
    service.require(service.sha(path) == reference['sha256'] and Path(path).stat().st_size == reference['bytes'],
        'Mac source bytes changed before plan admission')
mac.validate_source(manifest, service.read(sys.argv[5]), service.read(sys.argv[6]), service.read(sys.argv[4]),
    original=original, original_sha=service.sha(sys.argv[3]))
service.require(original['runId'] == sys.argv[7] and str(original['fencingToken']) == sys.argv[8]
    and str(original['expiresAt']) == sys.argv[9] and original['amiId'] == sys.argv[10],
    'Mac plan must retain the original run, resource fence, expiry and AMI')
AIRBOB_MAC_CONNECT_PLAN
      mac_connect_create=true
    elif [[ "${B_SERVICE_STAGE:-dependencies}" == dependencies ]] &&
      jq -e '.preparation.sourceMode == "mac-snapshot-counts-ddl"' "$temp_dir/dataset-manifest.json" >/dev/null; then
      [[ "$rds_instance_class" == db.t3.small && "$(sha256_file "$temp_dir/dataset-manifest.json")" == "$dataset_manifest_sha256" ]] \
        || fail "Mac snapshot service plan source changed after admission"
      python3 - "$script_dir" "$temp_dir/dataset-manifest.json" "$rds_class_operator_file" \
        "$temp_dir/mac-snapshot-service-source" "$run_id" "$resource_fencing_token" "$expires_at" "$ami_id" \
        <<'AIRBOB_MAC_SNAPSHOT_CONNECT_PLAN' || fail "Mac snapshot first-host plan requires its exact new-target count/DDL proof"
import sys
sys.path.insert(0, sys.argv[1])
import growth_b_mac_snapshot_controller as controller
manifest, original = controller.read(sys.argv[2]), controller.read(sys.argv[3])
controller.validate_service_files(manifest, original, sys.argv[4])
controller.need(original['runId'] == sys.argv[5] and str(original['fencingToken']) == sys.argv[6]
    and str(original['expiresAt']) == sys.argv[7] and original['amiId'] == sys.argv[8], 'MAC_TARGET_PLAN_IDENTITY_CHANGED')
AIRBOB_MAC_SNAPSHOT_CONNECT_PLAN
      mac_connect_create=true
    fi
    jq -e --argjson macCreate "$mac_connect_create" --arg run "$run_id" --arg ami "$ami_id" \
      --arg fence "$resource_fencing_token" --arg expiry "$expires_at" '
      def creates_or_deletes: .change.actions | (index("delete") != null or index("create") != null);
      "module.service_hosts.aws_instance.this[\"debezium\"]" as $hostAddress |
      [.resource_changes[]? | select(.type == "aws_db_instance" or .address == "module.rds[0].aws_db_instance.this") |
        select(creates_or_deletes)] as $rdsChanges |
      [.resource_changes[]? | select(.address == $hostAddress)] as $hosts |
      ($rdsChanges | length) == 0 and (
        ([$hosts[] | select(creates_or_deletes)] | length) == 0 or (
          $macCreate and ($hosts | length) == 1 and
          ([.prior_state? | .. | objects | select(.address? == $hostAddress)] | length) == 0 and
          ($hosts[0] | .mode == "managed" and .type == "aws_instance" and .previous_address? == null and
            .change.actions == ["create"] and .change.before == null and .change.importing? == null and
            (.change.after | .ami == $ami and .instance_type == "t3.medium" and .associate_public_ip_address == false and
              .iam_instance_profile == ("airbob-lab-host-" + $run + "-debezium") and
              (.root_block_device | length) == 1 and .root_block_device[0].encrypted == true and
              .root_block_device[0].delete_on_termination == true and .root_block_device[0].volume_type == "gp3" and
              .root_block_device[0].volume_size == 20 and
              .tags.Project == "airbob" and .tags.Environment == "performance-lab" and .tags.Stack == "lab" and
              .tags.ManagedBy == "terraform" and .tags.Persistence == "ephemeral" and .tags.Service == "debezium" and
              .tags.Name == ("airbob-" + $run + "-debezium") and .tags.RunId == $run and
              .tags.FencingToken == $fence and .tags.ExpiresAt == $expiry))
        ))' "$plan_json" >/dev/null \
      || fail "B service transition must retain the prepared RDS and data host"
  fi
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
  [[ "$global_b_prepare_only" != true || "$global_b_import_from_mac" == true ]] || stop_global_b_bootstrap
  clear_lab_instance_shutdown_protection
  capture_terraform_state_inventory "$before_inventory"
  if [[ "$global_b_prepare_only" == true || "$global_b_snapshot_restore_only" == true ]]; then
    finalize_b_snapshot_controller_access "$before_inventory"
  fi
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

verify_snapshot_receipt_parity() {
  local current_data=$1 current_readiness=$2
  [[ "$database_bootstrap" == snapshot ]] || return 0
  local snapshot tags prefix key version expected_version_sha expected_sha destination
  local source_data="$temp_dir/snapshot-source-data.json" source_readiness="$temp_dir/snapshot-source-readiness.json"
  local source_data_version source_data_sha source_projection current_projection source_data_projection
  local source_data_key="data-bootstrap/$rds_snapshot_source_run_id/$dataset_release.json"
  local source_readiness_key="measurements/$rds_snapshot_source_run_id/direct-readiness.json"

  assert_lease
  snapshot=$(aws rds describe-db-snapshots --db-snapshot-identifier "$rds_snapshot_identifier" \
    --output json --region "$AWS_REGION" --no-cli-pager) \
    || fail "snapshot receipt source metadata is unavailable"
  tags=$(jq -ce --arg snapshot "$rds_snapshot_identifier" --arg run "$rds_snapshot_source_run_id" \
    --arg resource "$rds_snapshot_source_resource_id" --arg release "$dataset_release" \
    --arg dataKey "$source_data_key" --arg readinessKey "$source_readiness_key" '
    .DBSnapshots | select(length == 1) | .[0] |
    select(.DBSnapshotIdentifier == $snapshot and .DbiResourceId == $resource) |
    .TagList | select((map(.Key) | unique | length) == length) |
    map({key:.Key,value:.Value}) | from_entries |
    select(.SourceLabRunId == $run and .SourceRdsResourceId == $resource and
      .DatasetRelease == $release and .PromotionReceiptSchemaVersion == "2" and
      .DataBootstrapKey == $dataKey and .DirectReadinessKey == $readinessKey) |
    select(all([.DataBootstrapVersionIdSha256,.DataBootstrapSha256,
      .DirectReadinessVersionIdSha256,.DirectReadinessSha256][];
      type == "string" and test("^[0-9a-f]{64}$")))
  ' <<<"$snapshot") || fail "snapshot receipt source identity differs from the promoted snapshot"

  for prefix in DataBootstrap DirectReadiness; do
    key=$(jq -r --arg key "${prefix}Key" '.[$key]' <<<"$tags")
    expected_version_sha=$(jq -r --arg key "${prefix}VersionIdSha256" '.[$key]' <<<"$tags")
    expected_sha=$(jq -r --arg key "${prefix}Sha256" '.[$key]' <<<"$tags")
    destination=$source_data
    [[ "$prefix" != DirectReadiness ]] || destination=$source_readiness
    # These keys are immutable. A different latest version fails closed; never
    # silently substitute newer evidence or enumerate history for a match.
    version=$(aws s3api head-object --bucket "$evidence_bucket" --key "$key" \
      --query '{versionId:VersionId}' --output json --region "$AWS_REGION" --no-cli-pager \
      | jq -er '.versionId | select(type == "string" and length > 0 and . != "null")') \
      || fail "snapshot source receipt has no immutable version identity"
    [[ "$(printf '%s' "$version" | sha256_text)" == "$expected_version_sha" ]] \
      || fail "snapshot source receipt version differs from the promoted version"
    aws s3api get-object --bucket "$evidence_bucket" --key "$key" --version-id "$version" \
      "$destination" --region "$AWS_REGION" --no-cli-pager >/dev/null \
      || fail "exact snapshot source receipt is unavailable"
    [[ "$(sha256_file "$destination")" == "$expected_sha" ]] \
      || fail "snapshot source receipt content differs from the promoted content"
    if [[ "$prefix" == DataBootstrap ]]; then
      source_data_version=$version
      source_data_sha=$expected_sha
    fi
  done
  jq -e --arg run "$rds_snapshot_source_run_id" --arg resource "$rds_snapshot_source_resource_id" \
    --arg release "$dataset_release" '
    .schemaVersion == 2 and .runId == $run and .rdsResourceId == $resource and
    .datasetRelease == $release and .databaseBootstrap == "dump" and
    (.semanticAttestationSha256 | type == "string" and test("^[0-9a-f]{64}$"))
  ' "$source_data" >/dev/null || fail "snapshot source data receipt is not the promoted dump receipt"
  source_data_projection=$(jq -cS 'del(.runId,.databaseBootstrap,.rdsResourceId,.verifiedAt)' "$source_data")
  [[ "$source_data_projection" == "$(jq -cS 'del(.runId,.databaseBootstrap,.rdsResourceId,.verifiedAt)' "$current_data")" ]] \
    || fail "snapshot data projection differs from the source dump receipt"
  jq -e --arg run "$rds_snapshot_source_run_id" --arg resource "$rds_snapshot_source_resource_id" \
    --arg key "$source_data_key" --arg version "$source_data_version" --arg sha "$source_data_sha" \
    --arg projectionSha "$(printf '%s' "$source_data_projection" | sha256_text)" '
    .schemaVersion == 1 and .status == "ready" and .runId == $run and
    .actual.rds.resourceId == $resource and .bootstrap.mode == "dump" and
    .bootstrap.rdsSnapshotIdentifier == null and .bootstrap.rdsSnapshotSourceRunId == null and
    .bootstrap.rdsSnapshotSourceResourceId == null and
    .bootstrap.receipt.key == $key and .bootstrap.receipt.versionId == $version and
    .bootstrap.receipt.sha256 == $sha and .bootstrap.dataProjectionSha256 == $projectionSha
  ' "$source_readiness" >/dev/null || fail "snapshot source readiness does not bind the exact dump receipt"
  source_projection=$(jq -cSf "$comparison_projection_filter" "$source_readiness") \
    || fail "snapshot source readiness projection is invalid"
  jq -e --argjson projection "$source_projection" \
    --arg sha "$(printf '%s\n' "$source_projection" | sha256_text)" '
    .comparisonProjection == $projection and .comparisonProjectionSha256 == $sha
  ' "$source_readiness" >/dev/null || fail "snapshot source readiness projection binding is invalid"
  current_projection=$(jq -cSf "$comparison_projection_filter" "$current_readiness") \
    || fail "snapshot readiness projection is invalid"
  [[ "$current_projection" == "$source_projection" ]] \
    || fail "snapshot readiness projection differs from the source dump receipt"
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
    --query 'DBInstances[0].{identifier:DBInstanceIdentifier,resourceId:DbiResourceId,class:DBInstanceClass,engine:Engine,engineVersion:EngineVersion,allocatedStorageGiB:AllocatedStorage,storageType:StorageType,iops:Iops,storageThroughputMiBps:StorageThroughput,multiAz:MultiAZ,storageEncrypted:StorageEncrypted,publiclyAccessible:PubliclyAccessible,availabilityZone:AvailabilityZone,parameterGroups:DBParameterGroups[].DBParameterGroupName}' \
    --output json --region "$AWS_REGION" --no-cli-pager) || fail "cannot attest the restored RDS shape"
  jq -e --arg id "$rds_instance_id" --arg resource "$rds_resource_id" --arg version "$rds_engine_version" --arg class "$rds_instance_class" '
    .identifier == $id and .resourceId == $resource and .class == $class and
    .engine == "mysql" and .engineVersion == $version and .allocatedStorageGiB == 100 and
    .storageType == "gp3" and .iops == 3000 and .storageThroughputMiBps == 125 and
    .multiAz == false and .storageEncrypted == true and .publiclyAccessible == false
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
  verify_snapshot_receipt_parity "$data_receipt" "$receipt"
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
      (del(.global_b_preparation, .global_b_mac_import, .global_b_service, .global_b_snapshot, .lab_power) | keys | sort) == [
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
  [[ "$output_status" == available || "$requirement" == best-effort ]] \
    || fail "Required Terraform output evidence is unavailable; no destroy was started"
}

wait_for_application() {
  local deadline status unhealthy healthy desired timeout_seconds=${1:-$INSTANCE_REFRESH_TIMEOUT_SECONDS}
  deadline=$(($(date +%s) + timeout_seconds))
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
  fail "application refresh/target-health gate exceeded $timeout_seconds seconds"
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

select_global_b_service_ingress() {
  local original=$1 selected address
  selected=${ALB_INGRESS_CIDR:-$(jq -er '.albIngressCidr' "$original")}
  [[ "$selected" =~ ^([^/]+)/32$ ]] || fail "B services require one current operator IPv4 /32"
  address=${BASH_REMATCH[1]}
  valid_public_ipv4 "$address" && [[ "$selected" == "$address/32" ]] \
    || fail "B services require one canonical public operator IPv4 /32"
  alb_ingress_cidr=$selected
}

assert_b_source_not_retired() {
  local key="data-bootstrap/$run_id/b-source-retirement.json" found
  found=$(aws s3api list-objects-v2 --bucket "$evidence_bucket" --prefix "$key" --max-items 1 \
    --query "Contents[?Key=='$key'].Key | [0]" --output text --region "$AWS_REGION" --no-cli-pager) \
    || fail "Cannot inspect B source retirement fence"
  [[ "$found" == None || "$found" == null || -z "$found" ]] || fail "A retired B source cannot be reactivated"
}

write_current_lease_file() {
  local path=$1
  jq -n --arg table "$lease_table" --arg lock "$lease_lock_id" --arg owner "$lease_owner" \
    --arg run "$run_id" --arg command "$lease_command" --argjson fence "$fencing_token" \
    '{table:$table,lockName:$lock,owner:$owner,runId:$run,command:$command,fencingToken:$fence}' > "$path"
}

finalize_b_snapshot_controller_access() {
  local inventory=$1 key="data-bootstrap/$run_id/b-source-retirement.json" found
  local lease_file="$temp_dir/b-snapshot-cleanup-lease.json"
  write_current_lease_file "$lease_file"
  found=$(aws s3api list-objects-v2 --bucket "$evidence_bucket" --prefix "$key" --max-items 1 \
    --query "Contents[?Key=='$key'].Key | [0]" --output text --region "$AWS_REGION" --no-cli-pager) \
    || fail "Cannot inspect B source retirement proof before teardown"
  if [[ "$found" == "$key" ]]; then
    run_supervised_mutation "Verify exact retired B source before teardown" \
      python3 "$script_dir/growth_b_snapshot_controller.py" verify-retirement \
      --dataset-id "$dataset_release" --run-id "$run_id" --resource-fence "$resource_fencing_token" \
      --lease "$lease_file" --output "$temp_dir/b-retirement-check"
  else
    [[ "$found" == None || "$found" == null || -z "$found" ]] || fail "Ambiguous B source retirement marker"
  fi
  if jq -e 'any(.[]; .address=="aws_iam_role.host[\"debezium\"]")' "$inventory" >/dev/null; then
    run_supervised_mutation "Remove own temporary B snapshot read policies" \
      python3 "$script_dir/growth_b_snapshot_controller.py" cleanup-access \
      --dataset-id "$dataset_release" --run-id "$run_id" --resource-fence "$resource_fencing_token" \
      --lease "$lease_file" --output "$temp_dir/b-snapshot-access-cleanup"
  fi
}

continue_global_b_snapshot_operation() {
  local original="$temp_dir/snapshot-source-operator.json" operation_id=${B_SNAPSHOT_OPERATION_ID:-}
  local operation_sha=${B_SNAPSHOT_OPERATION_SHA256:-} operation_version=${DATASET_MANIFEST_VERSION_ID:-}
  local operation_key operation_file="$temp_dir/snapshot-host-manifest.json" response="$temp_dir/snapshot-host-download.json"
  local phase2 phase3 instance rds_json context="$temp_dir/snapshot-host-context.json" deadline source_mode
  run_id=${RUN_ID:-}
  valid_run_id "$run_id" || fail "Snapshot host operations require the exact retained RUN_ID"
  [[ "$operation_id" =~ ^[a-z0-9][a-z0-9-]{2,47}$ && "$operation_sha" =~ ^[0-9a-f]{64}$ &&
    "$operation_version" =~ ^[A-Za-z0-9._~+/=-]+$ && "$operation_version" != null && "$operation_version" != None ]] \
    || fail "Snapshot operation requires exact operationId, manifest SHA and VersionId"
  read_run_manifest "$run_id" "$original"
  jq -e '.mode=="performance" and .dnsMode=="direct-only" and .loadGeneratorEnabled==false and .rdsEngineVersion=="8.4.11" and
    (.globalBPrepareOnly==true or .globalBSnapshotRestoreOnly==true)' "$original" >/dev/null \
    || fail "Snapshot operations can use only an exact retained B run"
  source_mode=$(jq -r '.globalBSnapshotRestoreOnly // false' "$original")
  [[ "$global_b_snapshot_operation:$source_mode" == prepare:true || ( "$global_b_snapshot_operation" != prepare && "$source_mode" == false ) ]] \
    || fail "Snapshot source and restored-target host modes cannot be interchanged"
  dataset_release=$(jq -er '.datasetRelease' "$original")
  [[ "${DATASET_RELEASE:-}" == "$dataset_release" ]] || fail "Snapshot operation dataset differs from its run"
  resource_fencing_token=$(jq -er '.fencingToken' "$original"); expires_at=$(jq -er '.expiresAt' "$original")
  [[ "$resource_fencing_token" =~ ^[1-9][0-9]*$ && "$expires_at" =~ ^[1-9][0-9]{9}$ ]] || fail "Retained source identity is invalid"
  validate_retained_global_b_execution_deadline "$original"
  (( expires_at > $(date +%s) + LEASE_DEADLINE_SECONDS )) || fail "Retained resource TTL cannot cover snapshot operation; no automatic extension"
  mode=performance; policy=isolated-read; dns_mode=direct-only; load_generator_enabled=false
  database_bootstrap=$(jq -er '.databaseBootstrap' "$original")
  bundle_commit=$(jq -er '.bundleCommit' "$original")
  [[ -z "$(git -C "$repo_root" status --porcelain --untracked-files=all)" ]] || fail "Snapshot operations require a clean reviewed execution commit"
  execution_commit=$(git -C "$repo_root" rev-parse HEAD); operator_tree_sha256=$(canonical_operator_tree_sha256)
  validate_operator_scope_for_action false
  assert_b_source_not_retired
  operation_key="datasets/$dataset_release-aws-snapshots/operations/$run_id/$operation_id/manifest-$operation_sha.json"
  aws s3api get-object --bucket "$dataset_bucket" --key "$operation_key" --version-id "$operation_version" "$operation_file" \
    --region "$AWS_REGION" --no-cli-pager > "$response"
  [[ "$(jq -er '.VersionId' "$response")" == "$operation_version" && "$(sha256_file "$operation_file")" == "$operation_sha" ]] \
    || fail "Snapshot operation manifest version/bytes changed"
  python3 "$script_dir/growth_b_snapshot_controller.py" validate --manifest "$operation_file" --sha256 "$operation_sha" \
    --dataset-id "$dataset_release" --run-id "$run_id" --operation-id "$operation_id" >/dev/null
  jq -e --arg mode "$global_b_snapshot_operation" --slurpfile original "$original" '
    .operation==$mode and .application.mainCommit==$original[0].bundleCommit and .application.image==$original[0].appImageReference
  ' "$operation_file" >/dev/null || fail "Snapshot host manifest changed the prepared application or operation"
  if [[ "$global_b_snapshot_operation" == prepare ]]; then
    jq -e --slurpfile original "$original" '.evidence.provenance as $p | $original[0].globalBSnapshotProvenance as $o |
      $p.key==$o.key and $p.versionId==$o.version_id and $p.sha256==$o.sha256 and $p.bytes==$o.bytes' "$operation_file" >/dev/null \
      || fail "Target preparation provenance differs from the actual RDS restore"
  elif [[ "$global_b_snapshot_operation" == create ]]; then
    [[ "$(jq -er '.snapshotIdentifier' "$operation_file")" == "$(jq -er '.approved_b_snapshot_creation_identifier // ""' <<<"$lab_contract")" ]] \
      || fail "The exact B snapshot identifier is not approved by the foundation contract"
  fi
  validate_workflow_deadline_budget; validate_up_credential_budget
  validate_retained_global_b_execution_deadline "$original"
  start_mutation_guard
  deadline=$(($(date +%s) + COMMAND_DEADLINE_SECONDS - 60))
  prepare_lab_backend; recover_prior_terraform_lock; assert_state_run_identity required
  phase2=$(run_terraform_command "Terraform snapshot host identity" -chdir="$lab_root" output -json phase2_contract)
  phase3=$(run_terraform_command "Terraform snapshot RDS identity" -chdir="$lab_root" output -json phase3_contract)
  verify_retained_rds_class
  instance=$(jq -er '.services.debezium' <<<"$phase2")
  rds_json=$(aws rds describe-db-instances --db-instance-identifier "airbob-$run_id" --region "$AWS_REGION" --no-cli-pager)
  jq -e --arg run "$run_id" --arg resource "$(jq -er '.rds_resource_id' <<<"$phase3")" \
    '.DBInstances|length==1 and (.[0].DBInstanceIdentifier==("airbob-"+$run) and .[0].DbiResourceId==$resource)' <<<"$rds_json" >/dev/null \
    || fail "Current RDS differs from retained Terraform state"
  write_current_lease_file "$temp_dir/snapshot-lease.json"
  jq -n --slurpfile manifest "$operation_file" --slurpfile lease "$temp_dir/snapshot-lease.json" \
    --arg key "$operation_key" --arg version "$operation_version" --arg sha "$operation_sha" --argjson bytes "$(wc -c < "$operation_file" | tr -d ' ')" \
    --argjson rds "$rds_json" --arg redis "$(jq -er '.infraImageReferences.REDIS_IMAGE' "$original")" \
    --arg instance "$instance" --arg class "$rds_instance_class" --argjson resourceFence "$resource_fencing_token" \
    --argjson deadline "$deadline" --arg cli "$AIRBOB_AWS_CLI_VERSION" --arg cliSha "$AIRBOB_AWS_CLI_LINUX_X86_64_SHA256" '
      $manifest[0] as $m | $rds.DBInstances[0] as $r | {schemaVersion:1,kind:"global-growth-b-snapshot-host-context",
      operation:$m.operation,operationId:$m.operationId,datasetId:$m.datasetId,runId:$m.runId,
      manifest:{key:$key,versionId:$version,sha256:$sha,bytes:$bytes},toolSources:$m.toolSources,lease:$lease[0],
      rds:{identifier:$r.DBInstanceIdentifier,resourceId:$r.DbiResourceId,endpoint:$r.Endpoint.Address,masterSecretArn:$r.MasterUserSecret.SecretArn},
      rdsInstanceClass:$class,resourceFence:$resourceFence,
      redisImage:$redis,hostInstanceId:$instance,deadlineEpoch:$deadline,
      evidencePrefix:("data-bootstrap/"+$m.runId+"/"+$m.datasetId+"-snapshot/"+$m.operationId+"/"),awsCli:{version:$cli,archiveSha256:$cliSha}}' > "$context"
  current_stage=b-snapshot-$global_b_snapshot_operation
  run_supervised_mutation "B snapshot host/controller operation" python3 "$script_dir/growth_b_snapshot_controller.py" run \
    --manifest "$operation_file" --sha256 "$operation_sha" --dataset-id "$dataset_release" --run-id "$run_id" --operation-id "$operation_id" \
    --context "$context" --output "$temp_dir/snapshot-controller" --resource-fence "$resource_fencing_token" \
    --approved-snapshot "$(jq -r '.approved_b_snapshot_creation_identifier // ""' <<<"$lab_contract")"
  assert_lease
  printf 'run_id=%s\nb_snapshot_operation=%s\nsource_deleted=false\napplication_started=false\n' "$run_id" "$global_b_snapshot_operation"
}

prepare_global_b_mac_snapshot_operation() {
  local clean="$temp_dir/state-clean.json" head="$temp_dir/mac-snapshot-state-clean-head.json"
  local exact="$temp_dir/mac-snapshot-state-clean.json" version
  [[ -f "$clean" && -n "${state_clean_key:-}" && -n "${state_version_id:-}" && -n "${state_object_sha256:-}" ]] \
    || fail "Mac snapshot restore requires the latest completed teardown and empty-state receipt"
  aws s3api head-object --bucket "$evidence_bucket" --key "$state_clean_key" --region "$AWS_REGION" --no-cli-pager > "$head"
  version=$(jq -er '.VersionId' "$head")
  aws s3api get-object --bucket "$evidence_bucket" --key "$state_clean_key" --version-id "$version" "$exact" \
    --region "$AWS_REGION" --no-cli-pager > "$temp_dir/mac-snapshot-state-clean-get.json"
  [[ "$(sha256_file "$clean")" == "$(sha256_file "$exact")" && \
    "$(jq -er '.VersionId' "$temp_dir/mac-snapshot-state-clean-get.json")" == "$version" ]] \
    || fail "Latest clean-state evidence changed after its teardown-chain validation"
  jq -n --arg bucket "$evidence_bucket" --arg key "$state_clean_key" --arg version "$version" \
    --arg sha "$(sha256_file "$exact")" --argjson bytes "$(wc -c < "$exact" | tr -d ' ')" --rawfile raw "$exact" \
    '{reference:{bucket:$bucket,key:$key,versionId:$version,sha256:$sha,bytes:$bytes},rawUtf8:$raw}' \
    > "$temp_dir/mac-snapshot-retirement.json"
  jq -n --arg run "$run_id" --argjson fence "$resource_fencing_token" --arg commit "$(git -C "$repo_root" rev-parse HEAD)" \
    --argjson source "$global_b_snapshot_provenance" --slurpfile retirement "$temp_dir/mac-snapshot-retirement.json" \
    --arg stateKey "$lab_state_key" --arg stateVersion "$state_version_id" --arg stateSha "$state_object_sha256" \
    --argjson started "$(date +%s)" --argjson expires "$expires_at" --argjson approved "$approved_execution_deadline_epoch" '
      {schemaVersion:1,kind:"global-b-mac-snapshot-restore-operation",operationId:("mac-restore-"+($fence|tostring)),
       runId:$run,resourceFence:$fence,executionCommit:$commit,targetIdentifier:("airbob-"+$run),
       sourceProvenance:{key:$source.key,versionId:$source.version_id,sha256:$source.sha256,bytes:$source.bytes},
       retirementReference:($retirement[0].reference|del(.bucket)),
       emptyState:{key:$stateKey,versionId:$stateVersion,sha256:$stateSha},
       window:{startedAtEpoch:$started,expiresAt:$expires,approvedDeadlineEpoch:$approved}}' > "$temp_dir/mac-snapshot-operation.json"
}

capture_global_b_mac_snapshot_restore() {
  write_current_lease_file "$temp_dir/mac-snapshot-lease.json"
  run_supervised_mutation "Mac snapshot first available observation and restore event" \
    python3 "$script_dir/growth_b_mac_snapshot_controller.py" capture-restore --source "$dataset_manifest" \
    --operation "$temp_dir/mac-snapshot-operation.json" --retirement "$temp_dir/mac-snapshot-retirement.json" \
    --lease "$temp_dir/mac-snapshot-lease.json" --output "$temp_dir/mac-snapshot-restore"
  jq -er '.restoreReference | "B_MAC_SNAPSHOT_RESTORE_KEY=\(.key)\nB_MAC_SNAPSHOT_RESTORE_VERSION_ID=\(.versionId)\nB_MAC_SNAPSHOT_RESTORE_SHA256=\(.sha256)"' \
    "$temp_dir/mac-snapshot-restore/result.json"
}

write_global_b_snapshot_admission() {
  local proof="$temp_dir/b-snapshot-admission.json" config="$temp_dir/b-snapshot-admission-config.json"
  local key="data-bootstrap/$run_id/b-snapshot-admission-$fencing_token.json" version
  assert_lease
  if [[ "$global_b_snapshot_source_mode" == mac-snapshot-counts-ddl ]]; then
    write_current_lease_file "$temp_dir/mac-snapshot-lease.json"
    python3 "$script_dir/growth_b_mac_snapshot_controller.py" admit --source "$dataset_manifest" \
      --operation "$temp_dir/mac-snapshot-operation.json" --retirement "$temp_dir/mac-snapshot-retirement.json" \
      --lease "$temp_dir/mac-snapshot-lease.json" --output "$temp_dir/mac-snapshot-admission"
    return
  fi
  jq -n --arg path "$dataset_manifest" --arg sha "$dataset_manifest_sha256" \
    --arg target "airbob-$run_id" --arg table "$lease_table" --arg lock "$lease_lock_id" \
    --arg owner "$lease_owner" --arg run "$run_id" --argjson fence "$fencing_token" \
    '{schemaVersion:1,kind:"global-growth-b-rds-snapshot-configuration",operation:"restore-admission",
      operationTimeoutSeconds:300,provenance:{path:$path,sha256:$sha},targetIdentifier:$target,
      lease:{table:$table,lockName:$lock,owner:$owner,runId:$run,command:"up",fencingToken:$fence}}' > "$config"
  python3 - "$script_dir" "$config" "$proof" <<'AIRBOB_B_SNAPSHOT_ADMISSION'
import sys
sys.path.insert(0, sys.argv[1])
import growth_b_snapshot as snapshot
configuration = snapshot.config(sys.argv[2])
snapshot.write(sys.argv[3], snapshot.restore_admission(configuration, snapshot.restore.Aws()))
AIRBOB_B_SNAPSHOT_ADMISSION
  assert_lease
  publish_immutable_json "$key" "$proof"
  version=$(aws s3api head-object --bucket "$evidence_bucket" --key "$key" --query VersionId \
    --output text --region "$AWS_REGION" --no-cli-pager)
  [[ "$version" =~ ^[A-Za-z0-9._~+/=-]+$ && "$version" != None && "$version" != null ]] \
    || fail "B snapshot restore admission has no immutable VersionId"
  printf 'B_SNAPSHOT_ADMISSION_KEY=%s\nB_SNAPSHOT_ADMISSION_VERSION_ID=%s\nB_SNAPSHOT_ADMISSION_SHA256=%s\n' \
    "$key" "$version" "$(sha256_file "$proof")"
}

complete_global_b_asg_probe() {
  local operation=$1 receipt=$2 proof_status=$3
  jq -e '.cleanupComplete==true and .baselineTerminatedByTool==false and .baselineProtectionRestored==true and
    .baselineRetainedAndHealthy==true and .finalCapacity=={min:1,desired:1,max:1} and .r7OverallComplete==false' \
    "$receipt" >/dev/null || fail "B ASG baseline restoration is incomplete; exact recovery evidence is retained"
  if jq -e 'has("resume")' "$operation" >/dev/null; then
    [[ "$proof_status" -eq 1 ]] || fail "B ASG cleanup-only exit status is unexpected"
    jq -e --slurpfile operation "$operation" '.state=="OBSERVATION_FAILED_BASELINE_RESTORED" and
      .observationComplete==false and .resumedReceiptSha256==$operation[0].resume.receipt.sha256' \
      "$receipt" >/dev/null || fail "B ASG cleanup-only receipt differs from the exact prior execution"
    printf 'run_id=%s\nb_asg_probe_complete=false\nb_asg_cleanup_complete=true\nadditional_app_retained=false\nr7_overall_complete=false\n' "$run_id"
  else
    [[ "$proof_status" -eq 0 ]] || fail "B ASG observation failed; exact recovery evidence is retained"
    jq -e '.state=="OBSERVATION_FINISHED_BASELINE_RESTORED" and .observationComplete==true' \
      "$receipt" >/dev/null || fail "B ASG observation is incomplete"
    printf 'run_id=%s\nb_asg_probe_complete=true\nb_asg_cleanup_complete=true\nadditional_app_retained=false\nr7_overall_complete=false\n' "$run_id"
  fi
}

continue_global_b_asg_probe() {
  local original="$temp_dir/asg-source-operator.json" selected="$temp_dir/asg-operation.json"
  local operation_id evidence_root evidence_run proof_status=0 key version filename
  run_id=${RUN_ID:-}
  valid_run_id "$run_id" || fail "ASG probe requires the exact retained RUN_ID"
  printf '%s\n' "${B_ASG_PROBE_OPERATION_JSON:-}" > "$selected"
  read_run_manifest "$run_id" "$original"
  jq -e '(.globalBPrepareOnly==true or .globalBSnapshotRestoreOnly==true) and .mode=="performance" and
    .dnsMode=="direct-only" and .rdsEngineVersion=="8.4.11" and .loadGeneratorEnabled==false' "$original" >/dev/null \
    || fail "ASG probe requires the original B performance run"
  dataset_release=$(jq -er '.datasetRelease' "$original")
  [[ "${DATASET_RELEASE:-}" == "$dataset_release" ]] || fail "ASG probe dataset differs from its retained run"
  python3 - "$script_dir" "$selected" "$run_id" "$dataset_release" <<'AIRBOB_B_ASG_OPERATION'
import sys
sys.path.insert(0, sys.argv[1])
import growth_b_asg_controller as controller
controller.validate_operation(controller.service.read(sys.argv[2]), sys.argv[3], sys.argv[4])
AIRBOB_B_ASG_OPERATION
  operation_id=$(jq -er '.operationId' "$selected")
  global_b_service_release=$(jq -er '.serviceRelease' "$selected")
  resource_fencing_token=$(jq -er '.fencingToken' "$original"); expires_at=$(jq -er '.expiresAt' "$original")
  [[ "$resource_fencing_token" =~ ^[1-9][0-9]*$ && "$expires_at" =~ ^[1-9][0-9]{9}$ ]] || fail "Invalid retained ASG resource identity"
  validate_retained_global_b_execution_deadline "$original"
  mode=performance; policy=isolated-read; dns_mode=direct-only; load_generator_enabled=false
  database_bootstrap=$(jq -er '.databaseBootstrap' "$original")
  bundle_commit=$(jq -er '.bundleCommit' "$original")
  [[ -z "$(git -C "$repo_root" status --porcelain --untracked-files=all)" ]] || fail "ASG probe requires a clean reviewed execution commit"
  execution_commit=$(git -C "$repo_root" rev-parse HEAD); operator_tree_sha256=$(canonical_operator_tree_sha256)
  validate_operator_scope_for_action false; assert_b_source_not_retired
  evidence_root=${ASG_PROBE_EVIDENCE_DIR:-}
  [[ "$evidence_root" == /* && ! -L "$evidence_root" ]] || fail "ASG_PROBE_EVIDENCE_DIR must be a persistent private absolute directory"
  validate_workflow_deadline_budget; validate_up_credential_budget
  validate_retained_global_b_execution_deadline "$original"
  start_mutation_guard
  prepare_lab_backend; recover_prior_terraform_lock; assert_state_run_identity required
  evidence_run="$evidence_root/$run_id-$operation_id-$fencing_token"
  mkdir -p -m 700 "$evidence_root"; mkdir -m 700 "$evidence_run"
  cp "$original" "$evidence_run/operator.json"; cp "$selected" "$evidence_run/operation.json"
  write_current_lease_file "$evidence_run/lease.json"
  run_terraform_command "Terraform B ASG application identity" -chdir="$lab_root" output -json phase4_contract > "$evidence_run/phase4.json"
  run_terraform_command "Terraform B ASG service identity" -chdir="$lab_root" output -json global_b_service > "$evidence_run/service-state.json"
  python3 "$script_dir/growth_b_asg_controller.py" --operator "$evidence_run/operator.json" \
    --phase4 "$evidence_run/phase4.json" --service-state "$evidence_run/service-state.json" --operation "$evidence_run/operation.json" \
    --lease "$evidence_run/lease.json" --manifest-version "${DATASET_MANIFEST_VERSION_ID:-}" --output "$evidence_run"
  phase3=$(run_terraform_command "Terraform ASG original RDS class" -chdir="$lab_root" output -json phase3_contract)
  verify_retained_rds_class
  key="data-bootstrap/$run_id/asg-probe/$operation_id-$fencing_token"
  publish_immutable_json "$key/configuration.json" "$evidence_run/configuration.json"
  current_stage=b-asg-probe
  run_supervised_mutation "Observe one additional B app and restore its baseline" \
    python3 "$script_dir/growth_b_asg_probe.py" --config "$evidence_run/configuration.json" --output "$evidence_run/probe" \
    || proof_status=$?
  if [[ -f "$evidence_run/probe/asg-probe.json" ]]; then
    publish_immutable_json "$key/asg-probe.json" "$evidence_run/probe/asg-probe.json"
    for filename in configuration.json asg-probe.json; do
      version=$(aws s3api head-object --bucket "$evidence_bucket" --key "$key/$filename" \
        --query VersionId --output text --region "$AWS_REGION" --no-cli-pager)
      [[ "$version" =~ ^[A-Za-z0-9._~+/=-]+$ && "$version" != None && "$version" != null ]] || fail "ASG recovery evidence has no exact version"
      printf 'ASG_PROBE_EVIDENCE_KEY=%s\nASG_PROBE_EVIDENCE_VERSION_ID=%s\n' "$key/$filename" "$version"
    done
  fi
  complete_global_b_asg_probe "$selected" "$evidence_run/probe/asg-probe.json" "$proof_status"
}

publish_global_b_cdc_json() {
  local key=$1 source=$2 reference=$3 digest bytes version response readback
  [[ -f "$source" && ! -L "$source" ]] || fail "R4 public evidence is missing or linked"
  digest=$(sha256_file "$source"); bytes=$(wc -c < "$source" | tr -d ' ')
  publish_immutable_json "$key" "$source"
  version=$(aws s3api head-object --bucket "$evidence_bucket" --key "$key" \
    --query VersionId --output text --region "$AWS_REGION" --no-cli-pager)
  [[ "$version" =~ ^[A-Za-z0-9._~+/=-]+$ && "$version" != None && "$version" != null ]] \
    || fail "R4 evidence has no exact VersionId"
  response="$temp_dir/r4-version-$digest.json"; readback="$temp_dir/r4-readback-$digest.json"
  aws s3api get-object --bucket "$evidence_bucket" --key "$key" --version-id "$version" "$readback" \
    --region "$AWS_REGION" --no-cli-pager > "$response" || fail "Exact R4 evidence version is unreadable"
  [[ "$(jq -er '.VersionId' "$response")" == "$version" && "$(sha256_file "$readback")" == "$digest" &&
    "$(wc -c < "$readback" | tr -d ' ')" == "$bytes" && "$(sha256_file "$source")" == "$digest" ]] \
    || fail "R4 evidence exact version, bytes or source changed"
  jq -n --arg key "$key" --arg version "$version" --arg sha "$digest" --argjson bytes "$bytes" \
    '{key:$key,versionId:$version,sha256:$sha,bytes:$bytes}' > "$reference"
}

continue_global_b_cdc() {
  local original="$temp_dir/cdc-source-operator.json" selected="$temp_dir/cdc-operation.json"
  local operation_id evidence_root evidence_run context deadline proof_status=0 recovery_key recovery_sha public_dir filename digest expected
  run_id=${RUN_ID:-}
  valid_run_id "$run_id" || fail "Source R4 requires the exact retained RUN_ID"
  printf '%s\n' "${B_CDC_OPERATION_JSON:-}" > "$selected"
  python3 - "$script_dir" "$selected" <<'AIRBOB_B_CDC_OPERATION'
import sys
sys.path.insert(0, sys.argv[1])
import growth_b_cdc_supervisor as supervisor
supervisor.validate_operation(supervisor.service.read(sys.argv[2]))
AIRBOB_B_CDC_OPERATION
  read_run_manifest "$run_id" "$original"
  jq -e '.schemaVersion==2 and .globalBPrepareOnly==true and .databaseBootstrap=="dump" and
    .mode=="performance" and .dnsMode=="direct-only" and .rdsEngineVersion=="8.4.11" and
    .loadGeneratorEnabled==false and .cacheEnabled==false' "$original" >/dev/null \
    || fail "Source R4 requires the original cache-disabled B dump preparation run"
  dataset_release=$(jq -er '.datasetRelease' "$original")
  [[ "${DATASET_RELEASE:-}" == "$dataset_release" && "$(jq -er '.datasetId' "$selected")" == "$dataset_release" &&
    "$(jq -er '.runId' "$selected")" == "$run_id" ]] || fail "Source R4 operation differs from its retained dataset/run"
  operation_id=$(jq -er '.operationId' "$selected")
  global_b_service_release=$(jq -er '.serviceRelease' "$selected")
  resource_fencing_token=$(jq -er '.fencingToken' "$original"); expires_at=$(jq -er '.expiresAt' "$original")
  [[ "$resource_fencing_token" =~ ^[1-9][0-9]*$ && "$expires_at" =~ ^[1-9][0-9]{9}$ ]] || fail "Invalid retained R4 resource identity"
  validate_retained_global_b_execution_deadline "$original"
  mode=performance; policy=isolated-read; dns_mode=direct-only; load_generator_enabled=false; cache_enabled=false
  database_bootstrap=dump; bundle_commit=$(jq -er '.bundleCommit' "$original")
  [[ -z "$(git -C "$repo_root" status --porcelain --untracked-files=all)" ]] || fail "Source R4 requires a clean reviewed execution commit"
  execution_commit=$(git -C "$repo_root" rev-parse HEAD)
  [[ "$(jq -er '.executionCommit' "$selected")" == "$execution_commit" ]] || fail "Source R4 execution commit changed"
  operator_tree_sha256=$(canonical_operator_tree_sha256)
  validate_operator_scope_for_action false; assert_b_source_not_retired
  evidence_root=${CDC_EVIDENCE_DIR:-}
  [[ "$evidence_root" == /* && ! -L "$evidence_root" ]] || fail "CDC_EVIDENCE_DIR must be a persistent private absolute directory"
  validate_workflow_deadline_budget; validate_up_credential_budget
  validate_retained_global_b_execution_deadline "$original"
  start_mutation_guard
  deadline=$(($(date +%s) + COMMAND_DEADLINE_SECONDS - 60))
  (( deadline <= expires_at )) || deadline=$expires_at
  (( deadline <= approved_execution_deadline_epoch )) || deadline=$approved_execution_deadline_epoch
  if [[ -n "${AIRBOB_WORKFLOW_DEADLINE_EPOCH:-}" ]] && (( deadline > AIRBOB_WORKFLOW_DEADLINE_EPOCH )); then
    deadline=$AIRBOB_WORKFLOW_DEADLINE_EPOCH
  fi
  prepare_lab_backend; recover_prior_terraform_lock; assert_state_run_identity required
  evidence_run="$evidence_root/$run_id-$operation_id-$fencing_token"
  mkdir -p -m 700 "$evidence_root"; mkdir -m 700 "$evidence_run"
  cp "$original" "$evidence_run/operator.json"; cp "$selected" "$evidence_run/operation.json"
  write_current_lease_file "$evidence_run/lease.json"
  run_terraform_command "Terraform source R4 service hosts" -chdir="$lab_root" output -json phase2_contract > "$evidence_run/phase2.json"
  run_terraform_command "Terraform source R4 RDS identity" -chdir="$lab_root" output -json phase3_contract > "$evidence_run/phase3.json"
  phase3=$(cat "$evidence_run/phase3.json"); verify_retained_rds_class
  run_terraform_command "Terraform source R4 application identity" -chdir="$lab_root" output -json phase4_contract > "$evidence_run/phase4.json"
  run_terraform_command "Terraform source R4 selected service" -chdir="$lab_root" output -json global_b_service > "$evidence_run/service-state.json"
  context="$evidence_run/context.json"
  jq -n --arg commit "$execution_commit" --argjson approved "$approved_execution_deadline_epoch" --argjson deadline "$deadline" \
    --slurpfile original "$evidence_run/operator.json" --slurpfile lease "$evidence_run/lease.json" \
    --slurpfile phase2 "$evidence_run/phase2.json" --slurpfile phase3 "$evidence_run/phase3.json" \
    --slurpfile phase4 "$evidence_run/phase4.json" --slurpfile state "$evidence_run/service-state.json" \
    '{schemaVersion:1,kind:"global-b-aws-source-r4-context",executionCommit:$commit,
      approvedExecutionDeadlineEpoch:$approved,controllerDeadlineEpoch:$deadline,operator:$original[0],lease:$lease[0],
      phase2:$phase2[0],phase3:$phase3[0],phase4:$phase4[0],serviceState:$state[0]}' > "$context"
  python3 "$script_dir/growth_b_cdc_supervisor.py" validate-operation --operation "$selected" --context "$context" \
    --output "$evidence_run/validation.json"
  # This continuation never constructs tfvars or enters initial-up teardown.
  # Unknown remote commands retain their existing hosts and exact recovery record.
  current_stage=b-source-r4
  run_supervised_mutation "Verify source B service, CDC mutation and exact reset" \
    python3 "$script_dir/growth_b_cdc_supervisor.py" run --operation "$selected" --context "$context" \
    --directory "$evidence_run/supervisor" || proof_status=$?
  public_dir="$evidence_run/supervisor/public"
  if [[ -f "$public_dir/recovery.json" ]]; then
    recovery_sha=$(sha256_file "$public_dir/recovery.json")
    recovery_key="data-bootstrap/$run_id/$dataset_release-r4/$operation_id/recovery-$recovery_sha.json"
    publish_global_b_cdc_json "$recovery_key" "$public_dir/recovery.json" "$public_dir/recovery-reference.json"
  fi
  [[ "$proof_status" -eq 0 ]] || return "$proof_status"
  expected="$temp_dir/r4-completion-bindings.json"
  python3 - "$script_dir" "$selected" "$context" "$evidence_run/supervisor" > "$expected" <<'AIRBOB_B_CDC_COMPLETION'
import hashlib, json, pathlib, sys
sys.path.insert(0, sys.argv[1])
import growth_b_cdc_supervisor as supervisor
operation, context = (supervisor.service.read(path) for path in sys.argv[2:4])
directory = pathlib.Path(sys.argv[4])
for key, name in (('manifest', 'selected-service.json'), ('readiness', 'selected-readiness.json')):
    path = directory / name
    if not path.is_file() or path.is_symlink():
        raise SystemExit('Exact selected R4 input is missing or linked')
    raw = path.read_bytes()
    if len(raw) != operation[key]['bytes'] or hashlib.sha256(raw).hexdigest() != operation[key]['sha256']:
        raise SystemExit('Selected R4 input differs from its original manifest/readiness reference')
exports = {}
for name in ('restore-receipt.json', 'prepared-fingerprint.json', 'service-verified-and-reset.json'):
    path = directory / 'public' / name
    if not path.is_file() or path.is_symlink():
        raise SystemExit('Exact R4 producer export is missing or linked')
    raw = path.read_bytes()
    exports[name] = {'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)}
configurations = {}
for name in ('live', 'cdc'):
    path = directory / (name + '-configuration.json')
    if not path.is_file() or path.is_symlink():
        raise SystemExit('Original R4 configuration is missing or linked')
    raw = path.read_bytes()
    configurations[name] = {'sha256': hashlib.sha256(raw).hexdigest(),
        'journalBindingSha256': supervisor.cdc.journal_binding(supervisor.core.parse(raw))}
print(json.dumps({'operationSha256': supervisor.operation_binding(operation),
    'contextSha256': supervisor.sha(supervisor.encoded(context)), 'exports': exports, 'configurations': configurations}))
AIRBOB_B_CDC_COMPLETION
  jq -e --slurpfile context "$context" --slurpfile operation "$selected" --slurpfile expected "$expected" \
    --slurpfile producer "$public_dir/service-verified-and-reset.json" '
    $context[0] as $c | $operation[0] as $o | $expected[0] as $e | $producer[0] as $p |
    .schemaVersion==1 and .kind=="global-b-aws-source-r4-supervisor-receipt" and
    .state=="SOURCE_R4_VERIFIED_AND_RESET" and .phasePassed==true and .privateValuesIncluded==false and
    .fullServiceVerificationAndReset==true and .snapshotCreated==false and
    .operationId==$o.operationId and .runId==$o.runId and .datasetId==$o.datasetId and .executionCommit==$c.executionCommit and
    .operationSha256==$e.operationSha256 and .contextSha256==$e.contextSha256 and .sourceArchiveSha256==$o.sourceArchiveSha256 and
    .selected=={manifest:$o.manifest,readiness:$o.readiness} and .targetIdentity==$p.targetIdentity and
    .cdcConfigurationSha256==$e.configurations.cdc.sha256 and .liveConfigurationSha256==$e.configurations.live.sha256 and
    .cdcJournalBindingSha256==$e.configurations.cdc.journalBindingSha256 and
    .liveJournalBindingSha256==$e.configurations.live.journalBindingSha256 and
    .cdcJournalBindingSha256==$p.binding.configurationSha256 and .sharedBindingSha256==$p.binding.sharedBindingSha256 and
    .exports["restore-receipt.json"]==$e.exports["restore-receipt.json"] and
    .exports["prepared-fingerprint.json"]==$e.exports["prepared-fingerprint.json"] and
    .exports["service-verified-and-reset.json"]==$e.exports["service-verified-and-reset.json"]
  ' \
    "$public_dir/supervisor.json" >/dev/null \
    || fail "Source R4 supervisor did not verify the complete service/reset gate"
  jq -e --slurpfile context "$context" --slurpfile operation "$selected" \
    --slurpfile original "$public_dir/restore-receipt.json" \
    --slurpfile manifest "$evidence_run/supervisor/selected-service.json" \
    --arg originalSha "$(sha256_file "$public_dir/restore-receipt.json")" \
    --arg fingerprintSha "$(sha256_file "$public_dir/prepared-fingerprint.json")" '
    $context[0] as $c | $operation[0] as $o | $original[0] as $r | $manifest[0] as $m |
    .kind=="global-growth-b-aws-service-reset-verification" and .state=="SERVICE_VERIFIED_AND_RESET" and
    .datasetId==$o.datasetId and .mysql=={version:"8.4.11",flywayVersion:28,schema:"airbobdb"} and .writersStopped==true and .cdcStopped==true and
    .sourceOriginalsUnchanged==true and .snapshotCreated==false and .privateValuesIncluded==false and
    .databaseBusinessWritesPerformed==false and .preparedFingerprintSha256==$fingerprintSha and .restoreReceiptSha256==$originalSha and
    $r.kind=="global-growth-b-aws-restore-receipt" and $r.state=="DATABASE_INVENTORY_LOGIN_VERIFIED" and $r.datasetId==$o.datasetId and
    $originalSha==$m.preparation.restoreReceiptSha256 and .binding.restoreConfigSha256==$m.preparation.restoreConfigSha256 and
    .targetIdentity==$r.targetIdentity and .targetIdentity.identifier==$c.phase3.rds_instance_id and
    .targetIdentity.resourceId==$c.phase3.rds_resource_id and .targetIdentity.endpoint==$c.phase3.rds_endpoint and
    {identifier:.targetIdentity.identifier,resourceId:.targetIdentity.resourceId,serverUuid:.targetIdentity.serverUuid}==$m.rds and
    .application==$m.application and
    .application.mainCommit==$c.operator.bundleCommit and .application.image==$c.operator.appImageReference and
    .binding.operationId==$o.operationId and .binding.runId==$o.runId and .binding.datasetId==$o.datasetId and
    .binding.targetIdentity==.targetIdentity and .binding.lease==$c.lease and
    .binding.resourceFencingToken==$c.operator.fencingToken and .binding.expiresAt==($c.operator.expiresAt|tonumber) and
    .binding.approvedExecutionDeadlineEpoch==$c.approvedExecutionDeadlineEpoch and
    .binding.verificationDeadlineEpoch<=$c.controllerDeadlineEpoch and .binding.serviceManifest==$o.manifest and
    .binding.serviceReadiness==$o.readiness and .binding.restoreReceiptSha256==$originalSha and
    .service.representativeAccounts==3 and
    ([.service.readinessPassed,.service.normalLoginsPassed,.service.publicReadsPassed,.service.globalSearchPassed,
      .service.imagesSampled,.service.reservableDatesPassed,.service.domainApiMutationCdcEsPassed,.service.detailCacheDisabledVerified]|all(.==true)) and
    .reset.passed==true and .reset.testMutationRemoved==true and .reset.unchangedDomainAndDdl==true and
    .reset.remainingOutboxRows==0 and (.reset.ownerSha256BeforeAndAfter|type=="string" and test("^[0-9a-f]{64}$")) and
    .reset.ownerSha256BeforeAndAfter==$r.preparation.ownerSha256BeforeAndAfter
  ' "$public_dir/service-verified-and-reset.json" >/dev/null || fail "Source R4 complete producer evidence or current target binding differs"
  assert_lease
  for filename in restore-receipt prepared-fingerprint service-verified-and-reset; do
    digest=$(sha256_file "$public_dir/$filename.json")
    publish_global_b_cdc_json "data-bootstrap/$run_id/$dataset_release-r4/$operation_id/$filename-$digest.json" \
      "$public_dir/$filename.json" "$public_dir/$filename-reference.json"
  done
  printf 'run_id=%s\nb_source_r4_complete=true\nr7_overall_complete=false\nB_SOURCE_R4_RECEIPT_SHA256=%s\n' \
    "$run_id" "$(sha256_file "$public_dir/service-verified-and-reset.json")"
}

continue_global_b_snapshot_service() {
  local original="$temp_dir/snapshot-service-operator.json" selected="$temp_dir/snapshot-service-operation.json"
  local operation_id evidence_root evidence_run context deadline proof_status=0 public_dir digest filename prefix
  global_b_snapshot_service=true
  run_id=${RUN_ID:-}
  valid_run_id "$run_id" || fail "Snapshot target service verification requires the exact retained RUN_ID"
  printf '%s\n' "${B_SNAPSHOT_SERVICE_OPERATION_JSON:-}" > "$selected"
  python3 - "$script_dir" "$selected" <<'AIRBOB_B_SNAPSHOT_SERVICE_OPERATION'
import sys
sys.path.insert(0, sys.argv[1])
import growth_b_snapshot_service_verify as verifier
import growth_b_service as service
verifier.validate_operation(service.read(sys.argv[2]))
AIRBOB_B_SNAPSHOT_SERVICE_OPERATION
  read_run_manifest "$run_id" "$original"
  jq -e '.schemaVersion==2 and .globalBSnapshotRestoreOnly==true and .databaseBootstrap=="snapshot" and
    .mode=="performance" and .dnsMode=="direct-only" and .rdsEngineVersion=="8.4.11" and
    .loadGeneratorEnabled==false and .cacheEnabled==false' "$original" >/dev/null \
    || fail "Snapshot service reads require the original cache-disabled restored B target"
  dataset_release=$(jq -er '.datasetRelease' "$original")
  [[ "${DATASET_RELEASE:-}" == "$dataset_release" && "$(jq -er '.datasetId' "$selected")" == "$dataset_release" &&
    "$(jq -er '.runId' "$selected")" == "$run_id" ]] || fail "Snapshot service operation differs from the retained target"
  operation_id=$(jq -er '.operationId' "$selected")
  global_b_service_release=$(jq -er '.serviceRelease' "$selected")
  resource_fencing_token=$(jq -er '.fencingToken' "$original"); expires_at=$(jq -er '.expiresAt' "$original")
  [[ "$resource_fencing_token" =~ ^[1-9][0-9]*$ && "$expires_at" =~ ^[1-9][0-9]{9}$ ]] || fail "Invalid retained snapshot target identity"
  validate_retained_global_b_execution_deadline "$original"
  mode=performance; policy=isolated-read; dns_mode=direct-only; load_generator_enabled=false; cache_enabled=false
  database_bootstrap=snapshot; bundle_commit=$(jq -er '.bundleCommit' "$original")
  [[ -z "$(git -C "$repo_root" status --porcelain --untracked-files=all)" ]] || fail "Snapshot service reads require a clean reviewed execution commit"
  execution_commit=$(git -C "$repo_root" rev-parse HEAD)
  [[ "$(jq -er '.executionCommit' "$selected")" == "$execution_commit" ]] || fail "Snapshot service execution commit changed"
  operator_tree_sha256=$(canonical_operator_tree_sha256)
  validate_operator_scope_for_action false; assert_b_source_not_retired
  evidence_root=${SNAPSHOT_SERVICE_EVIDENCE_DIR:-}
  [[ "$evidence_root" == /* && ! -L "$evidence_root" ]] || fail "SNAPSHOT_SERVICE_EVIDENCE_DIR must be a private absolute directory"
  validate_workflow_deadline_budget; validate_up_credential_budget
  validate_retained_global_b_execution_deadline "$original"
  start_mutation_guard
  deadline=$(($(date +%s) + COMMAND_DEADLINE_SECONDS - 60))
  (( deadline <= expires_at )) || deadline=$expires_at
  (( deadline <= approved_execution_deadline_epoch )) || deadline=$approved_execution_deadline_epoch
  if [[ -n "${AIRBOB_WORKFLOW_DEADLINE_EPOCH:-}" ]] && (( deadline > AIRBOB_WORKFLOW_DEADLINE_EPOCH )); then
    deadline=$AIRBOB_WORKFLOW_DEADLINE_EPOCH
  fi
  prepare_lab_backend; recover_prior_terraform_lock; assert_state_run_identity required
  evidence_run="$evidence_root/$run_id-$operation_id-$fencing_token"
  mkdir -p -m 700 "$evidence_root"; mkdir -m 700 "$evidence_run"
  cp "$original" "$evidence_run/operator.json"; cp "$selected" "$evidence_run/operation.json"
  write_current_lease_file "$evidence_run/lease.json"
  run_terraform_command "Terraform snapshot target service hosts" -chdir="$lab_root" output -json phase2_contract > "$evidence_run/phase2.json"
  run_terraform_command "Terraform snapshot target RDS identity" -chdir="$lab_root" output -json phase3_contract > "$evidence_run/phase3.json"
  phase3=$(cat "$evidence_run/phase3.json"); verify_retained_rds_class
  run_terraform_command "Terraform snapshot target application identity" -chdir="$lab_root" output -json phase4_contract > "$evidence_run/phase4.json"
  run_terraform_command "Terraform snapshot target selected service" -chdir="$lab_root" output -json global_b_service > "$evidence_run/service-state.json"
  context="$evidence_run/context.json"
  jq -n --arg commit "$execution_commit" --argjson approved "$approved_execution_deadline_epoch" --argjson deadline "$deadline" \
    --slurpfile original "$evidence_run/operator.json" --slurpfile lease "$evidence_run/lease.json" \
    --slurpfile phase2 "$evidence_run/phase2.json" --slurpfile phase3 "$evidence_run/phase3.json" \
    --slurpfile phase4 "$evidence_run/phase4.json" --slurpfile state "$evidence_run/service-state.json" \
    '{schemaVersion:1,kind:"global-b-aws-snapshot-target-service-context",executionCommit:$commit,
      approvedExecutionDeadlineEpoch:$approved,controllerDeadlineEpoch:$deadline,operator:$original[0],lease:$lease[0],
      phase2:$phase2[0],phase3:$phase3[0],phase4:$phase4[0],serviceState:$state[0]}' > "$context"
  current_stage=b-snapshot-target-service-verification
  run_supervised_mutation "Verify restored target normal logins, public reads and warmup" \
    python3 "$script_dir/growth_b_snapshot_service_verify.py" run --operation "$selected" --context "$context" \
    --output "$evidence_run/verifier" || proof_status=$?
  public_dir="$evidence_run/verifier/public"
  prefix="data-bootstrap/$run_id/$dataset_release-snapshot-service/$operation_id"
  if [[ -f "$public_dir/recovery.json" ]]; then
    digest=$(sha256_file "$public_dir/recovery.json")
    publish_global_b_cdc_json "$prefix/recovery-$digest.json" "$public_dir/recovery.json" "$public_dir/recovery-reference.json"
  fi
  [[ "$proof_status" -eq 0 ]] || return "$proof_status"
  python3 "$script_dir/growth_b_snapshot_service_verify.py" validate-completion --operation "$selected" --context "$context" \
    --output "$evidence_run/verifier" || fail "Snapshot target service result or current input bindings differ"
  assert_lease
  for filename in snapshot-target-service-verification representative-http-reads warmup-receipt media-availability host-runtime-qualification; do
    digest=$(sha256_file "$public_dir/$filename.json")
    publish_global_b_cdc_json "$prefix/$filename-$digest.json" "$public_dir/$filename.json" "$public_dir/$filename-reference.json"
  done
  printf 'run_id=%s\nsnapshot_target_service_verified=true\nsource_r4_or_cdc_mutation_executed=false\n' "$run_id"
}

continue_global_b_native_search() {
  local original="$temp_dir/native-source-operator.json" selected="$temp_dir/native-search-operation.json"
  local operation_id evidence_root evidence_run context deadline proof_status=0 recovery_sha recovery_key
  local phase3 phase4 selected_state native_stage
  run_id=${RUN_ID:-}
  valid_run_id "$run_id" || fail "Native search requires the exact retained preparation RUN_ID"
  printf '%s\n' "${B_NATIVE_OPERATION_JSON:-}" > "$selected"
  python3 - "$script_dir" "$selected" <<'AIRBOB_B_NATIVE_OPERATION'
import sys
sys.path.insert(0, sys.argv[1])
import growth_b_search_controller as controller
controller.validate_operation(controller.read(sys.argv[2]))
AIRBOB_B_NATIVE_OPERATION
  read_run_manifest "$run_id" "$original"
  native_stage=$(jq -er '.stage' "$selected")
  database_bootstrap=$(jq -er '.databaseBootstrap' "$original")
  [[ "$native_stage:$database_bootstrap" == native-restore:dump || "$native_stage:$database_bootstrap" == native-snapshot-restore:snapshot ]] \
    || fail "Native search stage must retain the original database source"
  jq -e '.schemaVersion==2 and ((.globalBPrepareOnly==true and .databaseBootstrap=="dump") or
    (.globalBSnapshotRestoreOnly==true and .databaseBootstrap=="snapshot")) and
    .mode=="performance" and .dnsMode=="direct-only" and .rdsEngineVersion=="8.4.11" and
    .loadGeneratorEnabled==false and .cacheEnabled==false' "$original" >/dev/null \
    || fail "Native search requires the original cache-disabled B preparation run"
  manifest=$original
  dataset_release=$(jq -er '.datasetRelease' "$original")
  [[ "${DATASET_RELEASE:-}" == "$dataset_release" && "$(jq -er '.datasetId' "$selected")" == "$dataset_release" &&
    "$(jq -er '.runId' "$selected")" == "$run_id" ]] || fail "Native search operation differs from its retained dataset/run"
  operation_id=$(jq -er '.operationId' "$selected")
  global_b_service_release=$(jq -er '.serviceRelease' "$selected")
  resource_fencing_token=$(jq -er '.fencingToken' "$original"); expires_at=$(jq -er '.expiresAt' "$original")
  [[ "$resource_fencing_token" =~ ^[1-9][0-9]*$ && "$expires_at" =~ ^[1-9][0-9]{9}$ ]] || fail "Invalid retained native search resource identity"
  validate_retained_global_b_execution_deadline "$original"
  (( expires_at > $(date +%s) + LEASE_DEADLINE_SECONDS )) || fail "Original paid-resource TTL cannot cover native search; it is not extended"
  mode=performance; policy=isolated-read; dns_mode=direct-only; load_generator_enabled=false; cache_enabled=false
  bundle_commit=$(jq -er '.bundleCommit' "$original")
  [[ "${BUNDLE_COMMIT:-}" == "$bundle_commit" && "${IMAGE_DIGEST:-}" == "$(jq -er '.imageDigest' "$original")" ]] \
    || fail "Native search must retain the prepared application tuple"
  [[ -z "$(git -C "$repo_root" status --porcelain --untracked-files=all)" ]] || fail "Native search requires a clean reviewed execution commit"
  execution_commit=$(git -C "$repo_root" rev-parse HEAD)
  [[ "$(jq -er '.executionCommit' "$selected")" == "$execution_commit" ]] || fail "Native search execution commit changed"
  operator_tree_sha256=$(canonical_operator_tree_sha256)
  validate_operator_scope_for_action false; assert_b_source_not_retired
  evidence_root=${NATIVE_SEARCH_EVIDENCE_DIR:-}
  [[ "$evidence_root" == /* && ! -L "$evidence_root" ]] || fail "NATIVE_SEARCH_EVIDENCE_DIR must be a private absolute directory"
  validate_workflow_deadline_budget; validate_up_credential_budget
  validate_retained_global_b_execution_deadline "$original"
  start_mutation_guard
  deadline=$(($(date +%s) + COMMAND_DEADLINE_SECONDS - 60))
  (( deadline <= expires_at )) || deadline=$expires_at
  (( deadline <= approved_execution_deadline_epoch )) || deadline=$approved_execution_deadline_epoch
  if [[ -n "${AIRBOB_WORKFLOW_DEADLINE_EPOCH:-}" ]] && (( deadline > AIRBOB_WORKFLOW_DEADLINE_EPOCH )); then
    deadline=$AIRBOB_WORKFLOW_DEADLINE_EPOCH
  fi
  prepare_lab_backend; recover_prior_terraform_lock; assert_state_run_identity required
  verify_oci_authority before-b-native-search
  evidence_run="$evidence_root/$run_id-$operation_id-$fencing_token"
  mkdir -p -m 700 "$evidence_root"; mkdir -m 700 "$evidence_run"
  cp "$original" "$evidence_run/operator.json"; cp "$selected" "$evidence_run/operation.json"
  write_current_lease_file "$evidence_run/lease.json"
  # Project only the public admission fields. Never persist raw state or the
  # complete Terraform output object in the new controller's evidence directory.
  phase2=$(run_terraform_command "Terraform native search hosts" -chdir="$lab_root" output -json phase2_contract | jq '{run_id,fencing_token,vpc_id,services}')
  phase3=$(run_terraform_command "Terraform native search RDS identity" -chdir="$lab_root" output -json phase3_contract | jq '{dataset_release,database_bootstrap,rds_instance_id,rds_resource_id,rds_endpoint,rds_engine_version,rds_instance_class,rds_configured_storage_gib,rds_allocated_storage_gib}')
  verify_retained_rds_class
  phase4=$(run_terraform_command "Terraform native search writer capacity" -chdir="$lab_root" output -json phase4_contract | jq '{app_enabled,capacity,accommodation_detail_cache_enabled,load_generator_enabled,auto_scaling_group_name}')
  selected_state=$(run_terraform_command "Terraform native search selected service" -chdir="$lab_root" output -json global_b_service | jq '{selected,manifest_key,manifest_version_id,manifest_sha256,readiness_receipt}')
  context="$evidence_run/context.json"
  jq -n --arg commit "$execution_commit" --argjson approved "$approved_execution_deadline_epoch" --argjson deadline "$deadline" \
    --slurpfile original "$evidence_run/operator.json" --slurpfile lease "$evidence_run/lease.json" \
    --argjson phase2 "$phase2" --argjson phase3 "$phase3" --argjson phase4 "$phase4" --argjson state "$selected_state" \
    '{schemaVersion:1,kind:"global-b-aws-native-search-controller-context",executionCommit:$commit,
      approvedExecutionDeadlineEpoch:$approved,controllerDeadlineEpoch:$deadline,operator:$original[0],lease:$lease[0],
      phase2:$phase2,phase3:$phase3,phase4:$phase4,serviceState:$state}' > "$context"
  python3 "$script_dir/growth_b_search_controller.py" validate-operation --operation "$selected" --context "$context" \
    --output "$evidence_run/validation.json"
  # This stage never writes tfvars, applies a lab plan or enters initial-up teardown.
  current_stage=b-native-search
  run_supervised_mutation "Restore exact native B search on its ES host" \
    python3 "$script_dir/growth_b_search_controller.py" run --operation "$selected" --context "$context" \
    --directory "$evidence_run/controller" || proof_status=$?
  if [[ -f "$evidence_run/controller/public/recovery.json" ]]; then
    recovery_sha=$(sha256_file "$evidence_run/controller/public/recovery.json")
    recovery_key="data-bootstrap/$run_id/$dataset_release-native-search/$operation_id/recovery-$recovery_sha.json"
    publish_global_b_cdc_json "$recovery_key" "$evidence_run/controller/public/recovery.json" "$evidence_run/recovery-reference.json"
  fi
  [[ "$proof_status" -eq 0 ]] || return "$proof_status"
  assert_lease
  verify_oci_authority after-b-native-search
  jq -e '.state=="NATIVE_SEARCH_RESTORED_AND_SOURCE_VERIFIED" and .freshServiceManifestRequired==true' \
    "$evidence_run/controller/public/completion.json" >/dev/null || fail "Native search completion is not verified"
  publish_immutable_json "data-bootstrap/$run_id/$dataset_release-native-search/$operation_id/completion.json" \
    "$evidence_run/controller/public/completion.json"
  printf 'run_id=%s\nb_service_stage=%s\nexpires_at=%s\n' "$run_id" "$native_stage" "$expires_at"
}

retain_running_lab_power() {
  local output="$temp_dir/retained-lab-power-output.json" addresses="$temp_dir/retained-lab-power-addresses.txt"
  prepare_lab_backend
  run_terraform_command "Terraform retained power state" -chdir="$lab_root" output -json > "$output"
  if jq -e '.lab_power.value != null' "$output" >/dev/null; then
    jq -e '.lab_power.value.phase == "running" and .lab_power.value.request.phase == "running"' "$output" >/dev/null \
      || fail "Lab is paused or changing power state; use airbob-lab resume before services"
    lab_power_request=$(jq -ce '.lab_power.value.request' "$output")
  else
    run_terraform_command "Terraform power control presence" -chdir="$lab_root" state list > "$addresses"
    if grep -Eq '^aws_ec2_instance_state[.]power(\[|$)|^terraform_data[.]rds_power$' "$addresses"; then
      fail "Power controls exist without their retained output; inspect power status before services"
    fi
    lab_power_request=null
  fi
}

continue_global_b_services() {
  local stage=${B_SERVICE_STAGE:-dependencies} original="$temp_dir/b-prepared-operator.json"
  local receipt="$temp_dir/b-service-readiness.json" head="$temp_dir/b-service-readiness-head.json"
  local receipt_key receipt_version receipt_sha old_dataset
  retain_running_lab_power
  if [[ "$stage" == snapshot-verify ]]; then
    continue_global_b_snapshot_service
    return
  fi
  if [[ "$stage" == native-restore || "$stage" == native-snapshot-restore ]]; then
    continue_global_b_native_search
    return
  fi
  run_id=${RUN_ID:-}
  valid_run_id "$run_id" || fail "services requires the exact retained preparation RUN_ID"
  assert_b_source_not_retired
  [[ "$global_b_service_release" =~ ^[a-z0-9][a-z0-9-]{2,47}$ ]] || fail "B_SERVICE_RELEASE is required"
  case "$stage" in dependencies|bootstrap|application) ;; *) fail "B_SERVICE_STAGE must be dependencies, bootstrap, or application" ;; esac
  read_run_manifest "$run_id" "$original"
  jq -e '(.globalBPrepareOnly==true or .globalBSnapshotRestoreOnly==true) and .mode=="performance" and .dnsMode=="direct-only" and
    .rdsEngineVersion=="8.4.11" and (.databaseBootstrap=="dump" or .databaseBootstrap=="snapshot") and .loadGeneratorEnabled==false' "$original" >/dev/null \
    || fail "B services can continue only the retained B data-only run"
  manifest=$original
  resource_fencing_token=$(jq -er '.fencingToken' "$original")
  expires_at=$(jq -er '.expiresAt' "$original")
  [[ "$resource_fencing_token" =~ ^[1-9][0-9]*$ && "$expires_at" =~ ^[1-9][0-9]{9}$ ]] || fail "Retained resource identity is invalid"
  validate_retained_global_b_execution_deadline "$original"
  (( expires_at > $(date +%s) + LEASE_DEADLINE_SECONDS )) || fail "Original paid-resource TTL cannot cover the B service operation; it is not extended automatically"
  mode=performance; policy=integrated-smoke; dns_mode=direct-only; load_generator_enabled=false
  cache_enabled=$(jq -r '.cacheEnabled' "$original"); request_target=''
  ami_id=$(jq -er '.amiId' "$original"); oci_origin_ipv4=$(jq -er '.ociOriginIpv4' "$original")
  select_global_b_service_ingress "$original"
  database_bootstrap=$(jq -er '.databaseBootstrap' "$original"); rds_engine_version=8.4.11
  rds_snapshot_identifier=$(jq -r '.rdsSnapshotIdentifier // ""' "$original")
  rds_snapshot_source_run_id=$(jq -r '.rdsSnapshotSourceRunId // ""' "$original")
  rds_snapshot_source_resource_id=$(jq -r '.rdsSnapshotSourceResourceId // ""' "$original")
  global_b_snapshot_provenance=$(jq -c '.globalBSnapshotProvenance // null' "$original")
  global_b_snapshot_source_mode=$(jq -r '.globalBSnapshotSourceMode // "verified-global-b-snapshot"' "$original")
  old_dataset=$(jq -er '.datasetRelease' "$original")
  [[ "${DATASET_RELEASE:-}" == "$old_dataset" && "${BUNDLE_COMMIT:-}" == "$(jq -er '.bundleCommit' "$original")" &&
    "${IMAGE_DIGEST:-}" == "$(jq -er '.imageDigest' "$original")" ]] || fail "B continuation must retain the prepared dataset and application tuple"
  validate_operator_scope_for_action false
  current_stage=b-service-release-validation
  resolve_release_inputs
  if jq -e '.preparation.sourceMode == "mac-sql-postcheck"' "$temp_dir/dataset-manifest.json" >/dev/null; then
    jq -e '.globalBPrepareOnly==true and .globalBImportFromMac==true' "$original" >/dev/null \
      || fail "Mac service input cannot replace another preparation source"
    local selected_downsize
    selected_downsize=$(jq -c '.preparation.receipt' "$temp_dir/dataset-manifest.json")
    [[ -z "${B_MAC_DOWNSIZE_RECEIPT_JSON:-}" || "$(jq -cS . <<<"$B_MAC_DOWNSIZE_RECEIPT_JSON")" == "$(jq -cS . <<<"$selected_downsize")" ]] \
      || fail "Mac service and selected downsize references differ"
    B_MAC_DOWNSIZE_RECEIPT_JSON=$selected_downsize
    load_retained_rds_class "$original"
    fetch_b_class_evidence "$(jq -c '.preparation.sqlImportReceipt' "$temp_dir/dataset-manifest.json")" "$temp_dir/mac-sql-complete.json"
    fetch_b_class_evidence "$(jq -c '.preparation.postcheckReceipt' "$temp_dir/dataset-manifest.json")" "$temp_dir/mac-sql-postcheck.json"
    python3 - "$script_dir" "$temp_dir/dataset-manifest.json" "$original" "$rds_class_transition_file" \
      "$temp_dir/mac-sql-complete.json" "$temp_dir/mac-sql-postcheck.json" <<'AIRBOB_MAC_SERVICE_SOURCE' || fail "Mac SQL/postcheck/downsize source admission failed"
import sys
sys.path.insert(0, sys.argv[1])
import growth_b_service as service
import growth_b_mac_service as mac
mac.validate_source(service.read(sys.argv[2]), service.read(sys.argv[5]), service.read(sys.argv[6]),
    service.read(sys.argv[4]), original=service.read(sys.argv[3]), original_sha=service.sha(sys.argv[3]))
AIRBOB_MAC_SERVICE_SOURCE
  elif jq -e '.preparation.sourceMode == "mac-snapshot-counts-ddl"' "$temp_dir/dataset-manifest.json" >/dev/null; then
    [[ "$global_b_snapshot_source_mode" == mac-snapshot-counts-ddl && "$rds_instance_class" == db.t3.small ]] \
      || fail "Mac snapshot service must retain its new small RDS operation"
    python3 "$script_dir/growth_b_mac_snapshot_controller.py" validate-service --manifest "$temp_dir/dataset-manifest.json" \
      --original "$original" --output "$temp_dir/mac-snapshot-service-source" >/dev/null
  fi
  validate_workflow_deadline_budget; validate_up_credential_budget
  validate_retained_global_b_execution_deadline "$original"
  start_mutation_guard
  prepare_lab_backend; recover_prior_terraform_lock; assert_state_run_identity required
  retain_running_lab_power
  verify_oci_authority before-b-service
  phase2=$(run_terraform_command "Terraform retained B topology" -chdir="$lab_root" output -json phase2_contract)
  phase3=$(run_terraform_command "Terraform retained B RDS class" -chdir="$lab_root" output -json phase3_contract)
  verify_retained_rds_class
  probe_instance_id=$(jq -er '.expected_network_receipt_key|capture("/(?<probe>i-[0-9a-f]{8,17})\\.json$").probe' <<<"$phase2")
  global_b_service_bootstrap_enabled=false
  global_b_readiness_receipt=null
  if [[ "$stage" == bootstrap ]]; then
    global_b_service_bootstrap_enabled=true
    jq -e '.preparation.sourceMode == "mac-sql-postcheck" or .preparation.sourceMode == "mac-snapshot-counts-ddl" or .search.restoreReceipt != null' "$temp_dir/dataset-manifest.json" >/dev/null \
      || fail "B bootstrap requires its actual native S3 search restore receipt"
  elif [[ "$stage" == application ]]; then
    receipt_key="data-bootstrap/$run_id/$dataset_release-service-$global_b_service_release.json"
    receipt_version=${B_READINESS_VERSION_ID:-}; receipt_sha=${B_READINESS_SHA256:-}
    [[ "$receipt_version" =~ ^[A-Za-z0-9._~+/=-]+$ && "$receipt_sha" =~ ^[0-9a-f]{64}$ ]] || fail "Application admission needs exact B readiness VersionId and SHA"
    aws s3api get-object --bucket "$evidence_bucket" --key "$receipt_key" --version-id "$receipt_version" "$receipt" \
      --region "$AWS_REGION" --no-cli-pager > "$head" || fail "Pinned B service readiness is unavailable"
    [[ "$(jq -er '.VersionId' "$head")" == "$receipt_version" && "$(sha256_file "$receipt")" == "$receipt_sha" ]] \
      || fail "B readiness bytes/version differ"
    python3 "$script_dir/growth_b_service.py" validate-readiness --manifest "$temp_dir/dataset-manifest.json" \
      --sha256 "$dataset_manifest_sha256" --dataset-id "$dataset_release" --run-id "$run_id" --release "$global_b_service_release" \
      --receipt "$receipt" >/dev/null || fail "B dependency receipt does not admit this application"
    global_b_readiness_receipt=$(jq -n --arg key "$receipt_key" --arg version "$receipt_version" --arg sha "$receipt_sha" \
      --argjson bytes "$(wc -c < "$receipt" | tr -d ' ')" '{key:$key,version_id:$version,sha256:$sha,bytes:$bytes}')
  fi
  current_stage=b-services-$stage
  if [[ "$stage" == application ]]; then
    write_tfvars data-ready true "$probe_instance_id"
  else
    write_tfvars services false "$probe_instance_id"
  fi
  # No fresh network/RDS apply, no resource fence replacement, no TTL extension.
  # Existing failure teardown remains opt-in through the normal down machinery.
  apply_lab
  if [[ "$stage" == bootstrap ]]; then
    receipt_key="data-bootstrap/$run_id/$dataset_release-service-$global_b_service_release.json"
    receipt_version=$(aws s3api head-object --bucket "$evidence_bucket" --key "$receipt_key" \
      --query VersionId --output text --region "$AWS_REGION" --no-cli-pager)
    [[ "$receipt_version" =~ ^[A-Za-z0-9._~+/=-]+$ && "$receipt_version" != None && "$receipt_version" != null ]] \
      || fail "B bootstrap did not publish a versioned receipt"
    aws s3api get-object --bucket "$evidence_bucket" --key "$receipt_key" --version-id "$receipt_version" "$receipt" \
      --region "$AWS_REGION" --no-cli-pager > "$head"
    [[ "$(jq -er '.VersionId' "$head")" == "$receipt_version" ]] || fail "B bootstrap receipt version changed"
    receipt_sha=$(sha256_file "$receipt")
    python3 "$script_dir/growth_b_service.py" validate-readiness --manifest "$temp_dir/dataset-manifest.json" \
      --sha256 "$dataset_manifest_sha256" --dataset-id "$dataset_release" --run-id "$run_id" --release "$global_b_service_release" \
      --receipt "$receipt" >/dev/null || fail "Published B bootstrap receipt differs from its service contract"
    global_b_readiness_receipt=$(jq -n --arg key "$receipt_key" --arg version "$receipt_version" --arg sha "$receipt_sha" \
      --argjson bytes "$(wc -c < "$receipt" | tr -d ' ')" '{key:$key,version_id:$version,sha256:$sha,bytes:$bytes}')
    printf 'B_READINESS_VERSION_ID=%s\nB_READINESS_SHA256=%s\n' "$receipt_version" "$receipt_sha"
  fi
  if [[ "$stage" == application ]]; then
    phase4=$(run_terraform_command "Terraform B application output" -chdir="$lab_root" output -json phase4_contract)
    asg_name=$(jq -er '.auto_scaling_group_name' <<<"$phase4")
    target_group_arn=$(jq -er '.target_group_arn' <<<"$phase4")
    aws_alb_dns_name=$(jq -er '.alb_dns_name' <<<"$phase4")
    # First B startup may seed all published inventory before readiness opens.
    # The existing lease watchdog and immutable resource expiry still bound it.
    wait_for_application 18000
    curl --fail --silent --show-error --max-time 30 --connect-to "api.airbob.cloud:443:$aws_alb_dns_name:443" \
      https://api.airbob.cloud/actuator/health/readiness | jq -e '.status=="UP"' >/dev/null \
      || fail "B application ALB readiness did not open"
  fi
  write_terraform_output_evidence required
  jq -n --arg run "$run_id" --arg stage "$stage" --arg release "$global_b_service_release" \
    --arg sha "$dataset_manifest_sha256" --arg version "$dataset_manifest_version_id" --argjson readiness "$global_b_readiness_receipt" \
    --argjson resourceFence "$resource_fencing_token" --argjson leaseFence "$fencing_token" --argjson expires "$expires_at" \
    --arg ingress "$alb_ingress_cidr" --argjson approvedDeadline "$approved_execution_deadline_epoch" \
    '{schemaVersion:1,kind:"global-growth-b-service-transition",runId:$run,stage:$stage,serviceRelease:$release,
      manifestSha256:$sha,manifestVersionId:$version,readinessReceipt:$readiness,resourceFencingToken:$resourceFence,leaseFencingToken:$leaseFence,
      originalExpiresAt:$expires,approvedExecutionDeadlineEpoch:$approvedDeadline,albIngressCidr:$ingress,applicationAlbReadinessVerified:($stage=="application"),businessCycleVerified:false}' \
    > "$temp_dir/b-service-transition.json"
  publish_immutable_json "data-bootstrap/$run_id/b-service-transition-$global_b_service_release-$stage-$fencing_token.json" "$temp_dir/b-service-transition.json"
  printf 'run_id=%s\nb_service_stage=%s\nexpires_at=%s\nbusiness_cycle_verified=false\n' "$run_id" "$stage" "$expires_at"
}

case "$action" in
  status)
    show_status
    exit 0
    ;;
  up)
    if [[ -n "$global_b_snapshot_operation" ]]; then
      continue_global_b_snapshot_operation
      exit 0
    fi
    if [[ "$global_b_asg_probe" == true ]]; then
      continue_global_b_asg_probe
      exit 0
    fi
    if [[ "$global_b_cdc" == true ]]; then
      continue_global_b_cdc
      exit 0
    fi
    if [[ "$global_b_services" == true ]]; then
      continue_global_b_services
      exit 0
    fi
    mode=${MODE:-performance}
    policy=${POLICY:-isolated-read}
    dns_mode=${DNS_MODE:-direct-only}
    if [[ "$global_b_prepare_only" == true || "$global_b_snapshot_restore_only" == true ]]; then
      cache_enabled=${CACHE_ENABLED:-false}
      [[ "$cache_enabled" == false ]] || fail "Initial B preparation requires the reviewed cache-disabled service baseline"
    else
      cache_enabled=${CACHE_ENABLED:-true}
    fi
    request_target=${REQUEST_TARGET:-}
    load_generator_enabled=${LOAD_GENERATOR_ENABLED:-false}
    ami_id=${AMI_ID:-}
    oci_origin_ipv4=${OCI_ORIGIN_IPV4:-}
    requested_alb_ingress_cidr=${ALB_INGRESS_CIDR:-}
    database_bootstrap=${DATABASE_BOOTSTRAP:-dump}
    ttl_hours=${TTL_HOURS:-6}
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
    if [[ "$global_b_snapshot_restore_only" == true && "$global_b_snapshot_source_mode" == mac-snapshot-counts-ddl ]]; then
      [[ "$ttl_hours" =~ ^[1-9][0-9]{0,2}$ && "$ttl_hours" -le 168 ]] || fail "Mac snapshot TTL_HOURS must be 1-168"
    else
      [[ "$ttl_hours" =~ ^[1-9][0-9]?$ && "$ttl_hours" -le 24 ]] || fail "TTL_HOURS must be 1-24"
    fi
    [[ "$ttl_hours" -ge 6 ]] \
      || fail "initial qualification requires TTL_HOURS of at least 6"
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
    if [[ "$global_b_snapshot_restore_only" == true ]]; then
      [[ "$mode:$policy:$dns_mode:$database_bootstrap:$load_generator_enabled:$rds_engine_version" == \
        performance:isolated-read:direct-only:snapshot:false:8.4.11 ]] \
        || fail "B snapshot restore requires data-only performance/isolated-read/direct-only/snapshot and MySQL 8.4.11"
    elif [[ "$global_b_prepare_only" == true ]]; then
      [[ "$mode:$policy:$dns_mode:$database_bootstrap:$load_generator_enabled:$rds_engine_version" == \
        performance:isolated-read:direct-only:dump:false:8.4.11 ]] \
        || fail "B prepare requires performance/isolated-read/direct-only/dump, no load generator, and MySQL 8.4.11"
    else
      [[ "$rds_engine_version" =~ ^8\.0\.[0-9]+$ ]] || fail "RDS_ENGINE_VERSION is required and must be exact"
    fi
    [[ "$rds_instance_class" == db.t3.small || "$rds_instance_class" == db.m6i.large ]] || fail "Unreviewed RDS class"
    [[ "$rds_instance_class" == db.t3.small || "$global_b_prepare_only" == true || "$global_b_snapshot_restore_only" == true ]] \
      || fail "The larger class is a Global B initial selection only"
    [[ "$global_b_prepare_only" == true || "$global_b_snapshot_restore_only" == true || -z "${B_RDS_CLASS_REHEARSAL_JSON:-}" ]] \
      || fail "Classic actions cannot accept class qualification"
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
    if [[ "$global_b_prepare_only" == true || "$global_b_snapshot_restore_only" == true ]]; then
      set_global_b_execution_expiry "$now_epoch" "$ttl_hours"
    fi
    run_id=${RUN_ID:-lab-$(date -u +%Y%m%d%H%M%S)-${GITHUB_RUN_ID:-local}}
    run_id=$(printf '%.32s' "$run_id" | sed 's/-$//')
    valid_run_id "$run_id" || fail "generated RUN_ID is not canonical"
    current_stage=release-validation
    resolve_release_inputs
    validate_workflow_deadline_budget
    validate_up_credential_budget
    if [[ "$global_b_prepare_only" == true || "$global_b_snapshot_restore_only" == true ]]; then
      # Release verification is read-only and can take time. Recheck the common
      # deadline immediately before lease acquisition and the first mutation.
      set_global_b_execution_expiry "$(date +%s)" "$ttl_hours"
    fi
    start_mutation_guard
    if terraform_lock_object_present; then
      prepare_lab_backend
      recover_prior_terraform_lock
    fi
    resource_fencing_token=$fencing_token
    assert_reusable_or_absent_state
    if [[ "$global_b_snapshot_restore_only" == true && "$global_b_snapshot_source_mode" == mac-snapshot-counts-ddl ]]; then
      prepare_global_b_mac_snapshot_operation
    fi
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
    if [[ "$global_b_prepare_only" == true ]]; then
      jq --argjson macImport "$global_b_import_from_mac" '. + {globalBPrepareOnly:true,globalBImportFromMac:$macImport}' "$manifest" > "$temp_dir/operator-b.json"
      mv "$temp_dir/operator-b.json" "$manifest"
    fi
    if [[ "$global_b_snapshot_restore_only" == true ]]; then
      jq --argjson provenance "$global_b_snapshot_provenance" --arg sourceMode "$global_b_snapshot_source_mode" \
        '. + {globalBSnapshotRestoreOnly:true,globalBSnapshotProvenance:$provenance,globalBSnapshotSourceMode:$sourceMode}' \
        "$manifest" > "$temp_dir/operator-b-snapshot.json"
      mv "$temp_dir/operator-b-snapshot.json" "$manifest"
      if [[ "$global_b_snapshot_source_mode" == mac-snapshot-counts-ddl ]]; then
        jq --slurpfile operation "$temp_dir/mac-snapshot-operation.json" '. + {globalBMacSnapshotOperation:$operation[0]}' \
          "$manifest" > "$temp_dir/operator-b-mac-snapshot.json"
        mv "$temp_dir/operator-b-mac-snapshot.json" "$manifest"
      fi
    fi
    if [[ "$global_b_prepare_only" == true || "$global_b_snapshot_restore_only" == true ]]; then
      jq --argjson deadline "$approved_execution_deadline_epoch" --arg class "$rds_instance_class" \
        --argjson qualification "${B_RDS_CLASS_REHEARSAL_JSON:-null}" \
        '. + {approvedExecutionDeadlineEpoch:$deadline,rdsInstanceClass:$class,rdsClassRehearsal:$qualification}' \
        "$manifest" > "$temp_dir/operator-b-deadline.json"
      mv "$temp_dir/operator-b-deadline.json" "$manifest"
    fi
    rds_class_operator_file=$manifest
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
    if [[ "$global_b_snapshot_restore_only" == true ]]; then
      # Record live source/target absence under this lease immediately before
      # Terraform requests the sole snapshot target. No SQL import runs here.
      write_global_b_snapshot_admission
    fi
    write_tfvars services false "$probe_instance_id"
    apply_lab # deployment_phase=services
    if [[ "$global_b_snapshot_restore_only" == true && "$global_b_snapshot_source_mode" == mac-snapshot-counts-ddl ]]; then
      # Capture the first post-apply RDS observation before other RDS reads or
      # CloudTrail's eventual-consistency wait can inflate the restore timer.
      capture_global_b_mac_snapshot_restore
    fi
    phase2=$(run_terraform_command "Terraform Phase 2 services output" \
      -chdir="$lab_root" output -json phase2_contract)
    phase3=$(run_terraform_command "Terraform Phase 3 data output" \
      -chdir="$lab_root" output -json phase3_contract)
    verify_retained_rds_class
    if [[ "$global_b_import_from_mac" == true ]]; then
      current_stage=b-mac-import-target-ready
      run_terraform_command "Terraform Mac import target" \
        -chdir="$lab_root" output -json global_b_mac_import > "$temp_dir/mac-import-target.json"
      jq -e '.selected == true' "$temp_dir/mac-import-target.json" >/dev/null || fail "Mac import target is missing"
      write_terraform_output_evidence required
      up_in_progress=false
      printf 'run_id=%s\nfencing_token=%s\nexpires_at=%s\nmac_import_target_ready=true\nsql_import_complete=false\n' \
        "$run_id" "$fencing_token" "$expires_at"
      exit 0
    fi
    if [[ "$global_b_snapshot_restore_only" == true ]]; then
      current_stage=b-snapshot-target-created
      if [[ "$global_b_snapshot_source_mode" == mac-snapshot-counts-ddl ]]; then
        write_terraform_output_evidence required
        up_in_progress=false
        printf 'run_id=%s\nfencing_token=%s\nexpires_at=%s\nb_snapshot_target_created=true\nmac_counts_ddl_required=true\n' \
          "$run_id" "$fencing_token" "$expires_at"
        exit 0
      fi
      # Pin a public, minimal CloudTrail event before the separate preparation
      # operation is reviewed. That operation never issues another RDS restore.
      run_supervised_mutation "B actual snapshot restore event capture" \
        python3 "$script_dir/growth_b_snapshot_controller.py" capture-event \
        --manifest "$dataset_manifest" --sha256 "$dataset_manifest_sha256" \
        --admission "$temp_dir/b-snapshot-admission.json" --dataset-id "$dataset_release" --run-id "$run_id" \
        --output "$temp_dir/b-snapshot-restore-event"
      write_terraform_output_evidence required
      up_in_progress=false
      printf 'run_id=%s\nfencing_token=%s\nexpires_at=%s\nb_snapshot_target_created=true\nactual_restored_database_verified=false\n' \
        "$run_id" "$fencing_token" "$expires_at"
      exit 0
    fi
    debezium_instance_id=$(jq -er '.services.debezium' <<<"$phase2")
    if [[ "$global_b_prepare_only" == true ]]; then
      current_stage=b-data-only-receipt
      verify_global_b_receipt
      write_terraform_output_evidence required
      up_in_progress=false
      printf 'run_id=%s\nfencing_token=%s\nexpires_at=%s\npreparation_complete=true\ndeployment_ready=false\nprivate_accounts_usable=false\n' \
        "$run_id" "$fencing_token" "$expires_at"
      exit 0
    fi
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
    global_b_snapshot_restore_only=$(jq -r '.globalBSnapshotRestoreOnly // false' "$manifest")
    global_b_snapshot_source_mode=$(jq -r '.globalBSnapshotSourceMode // "verified-global-b-snapshot"' "$manifest")
    global_b_snapshot_provenance=$(jq -c '.globalBSnapshotProvenance // null' "$manifest")
    [[ "$global_b_snapshot_restore_only" == true || "$global_b_snapshot_restore_only" == false ]] || fail "Invalid B snapshot selector"
    [[ "$action:$global_b_snapshot_restore_only" != switch:true ]] || fail "B snapshot-only runs cannot switch DNS"
    global_b_prepare_only=$(jq -r '.globalBPrepareOnly // false' "$manifest")
    global_b_import_from_mac=$(jq -r '.globalBImportFromMac // false' "$manifest")
    [[ "$global_b_import_from_mac" == true || "$global_b_import_from_mac" == false ]] || fail "Invalid Mac import selector in run manifest"
    [[ "$global_b_import_from_mac" == false || "$global_b_prepare_only" == true ]] || fail "Mac import manifest requires preparation mode"
    [[ "$global_b_prepare_only" == true || "$global_b_prepare_only" == false ]] || fail "Invalid preparation selector in run manifest"
    [[ "$action:$global_b_prepare_only" != switch:true ]] || fail "B data-only runs have no DNS/application switch"
    dataset_manifest_version_id=$(jq -r '.datasetManifestVersionId // ""' "$manifest")
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
    if [[ "$global_b_prepare_only" == true || "$global_b_snapshot_restore_only" == true ]]; then
      load_retained_rds_class "$manifest"
    else
      [[ -z "${B_RDS_INSTANCE_CLASS:-}${B_RDS_CLASS_REHEARSAL_JSON:-}" ]] || fail "Classic retained actions cannot select a B class"
      rds_instance_class=db.t3.small
    fi
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
