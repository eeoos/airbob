#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
verifier="$repo_root/infra/aws/scripts/verify-network-egress.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-network-egress-test.XXXXXX")

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

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

[[ -x "$verifier" && ! -L "$verifier" ]] || fail "network verifier is missing or unsafe"
command -v jq >/dev/null 2>&1 || fail "jq is required"

fake_bin="$temp_dir/bin"
receipt_dir="$temp_dir/receipts"
mkdir -p "$fake_bin" "$receipt_dir"

cat > "$fake_bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >> "${FAKE_CALL_LOG:?}"

if [[ "${1:-}" == ec2 && "${2:-}" == describe-instances && "$*" == *'Tags[?Key'* ]]; then
  printf '%s\n' '["running","vpc-0123456789abcdef0","ami-0123456789abcdef0","egress-probe","phase2-test"]'
  exit 0
fi
if [[ "${1:-}" == ec2 && "${2:-}" == describe-instances ]]; then
  printf '%s\n' "${FAKE_CLEARED_STATE:-terminated}"
  exit 0
fi

case " $* " in
  *' sts get-caller-identity '*) printf '%s\n' "${FAKE_ACCOUNT:-942632789808}" ;;
  *' ec2 describe-route-tables '*) printf '%s\n' '1' ;;
  *' ec2 describe-vpc-endpoints '*) printf '%s\n' '1' ;;
  *' ec2 describe-vpcs '*) printf '%s\n' 'available' ;;
  *' ssm describe-instance-information '*)
    if [[ "${FAKE_SSM_OFFLINE_ONCE:-0}" == 1 && ! -f "${FAKE_SSM_REGISTRATION_STATE:?}" ]]; then
      printf '%s\n' offline > "$FAKE_SSM_REGISTRATION_STATE"
      printf '%s\n' 'ConnectionLost'
    else
      printf '%s\n' 'Online'
    fi
    ;;
  *' ssm send-command '*)
    previous_arg=''
    send_parameters=''
    for current_arg in "$@"; do
      if [[ "$previous_arg" == --parameters ]]; then
        send_parameters=$current_arg
        break
      fi
      previous_arg=$current_arg
    done
    if ! probe_command=$(jq -er '.commands | select(type == "array" and length == 1) | .[0]' \
      <<<"$send_parameters"); then
      printf '%s\n' 'probe command parameters are invalid' >&2
      exit 64
    fi
    readiness_contract=$(printf '%s\n' \
      'if [[ ! -f /var/lib/airbob/probe-ready ]]; then' \
      "  printf '%s\\n' 'AIRBOB_PROBE_NOT_READY' >&2" \
      '  exit 75' \
      'fi')
    if [[ "$probe_command" != *"$readiness_contract"* ]]; then
      printf '%s\n' 'probe command omitted the readiness marker contract' >&2
      exit 64
    fi
    if [[ "${FAKE_SEND_NOT_READY_ONCE:-0}" == 1 && ! -f "${FAKE_SEND_RETRY_STATE:?}" ]]; then
      printf '%s\n' not-ready > "$FAKE_SEND_RETRY_STATE"
      printf '%s\n' 'An error occurred (InvalidInstanceId) when calling the SendCommand operation' >&2
      exit 254
    elif [[ "${FAKE_SEND_API_FAILURE:-0}" == 1 ]]; then
      printf '%s\n' 'An error occurred (AccessDeniedException) when calling the SendCommand operation' >&2
      exit 254
    fi
    printf '%s\n' '11111111-2222-3333-4444-555555555555'
    ;;
  *' ssm get-command-invocation '*)
    if [[ "${FAKE_INVOCATION_MISSING_ONCE:-0}" == 1 && ! -f "${FAKE_INVOCATION_MISSING_STATE:?}" ]]; then
      printf '%s\n' missing > "$FAKE_INVOCATION_MISSING_STATE"
      printf '%s\n' 'An error occurred (InvocationDoesNotExist) when calling the GetCommandInvocation operation' >&2
      exit 254
    elif [[ "${FAKE_INVOCATION_API_FAILURE:-0}" == 1 ]]; then
      printf '%s\n' 'An error occurred (AccessDeniedException) when calling the GetCommandInvocation operation' >&2
      exit 254
    elif [[ "${FAKE_PENDING_ONCE:-0}" == 1 && ! -f "${FAKE_PENDING_STATE:?}" ]]; then
      printf '%s\n' pending > "$FAKE_PENDING_STATE"
      printf '%s\n' '{"Status":"Pending","ResponseCode":-1}'
    elif [[ "${FAKE_NOT_READY_ONCE:-0}" == 1 && ! -f "${FAKE_READY_RETRY_STATE:?}" ]]; then
      printf '%s\n' '{"Status":"Failed","ResponseCode":75,"StandardErrorContent":"AIRBOB_PROBE_NOT_READY"}'
      printf '%s\n' retried > "$FAKE_READY_RETRY_STATE"
    elif [[ -n "${FAKE_TERMINAL_STATUS:-}" ]]; then
      printf '{"Status":"%s","ResponseCode":1}\n' "$FAKE_TERMINAL_STATUS"
    elif [[ "${FAKE_BAD_MARKER:-0}" == 1 ]]; then
      printf '%s\n' '{"Status":"Success","StandardOutputContent":"incomplete"}'
    else
      printf '%s\n' '{"Status":"Success","StandardOutputContent":"AIRBOB_EGRESS_OK s3=verified ecr=verified ssm=verified secretsmanager=verified\n"}'
    fi
    ;;
  *' s3api put-object '*)
    body=''
    key=''
    while [[ "$#" -gt 0 ]]; do
      case "$1" in
        --body) body=$2; shift 2 ;;
        --key) key=$2; shift 2 ;;
        *) shift ;;
      esac
    done
    [[ -f "$body" && -n "$key" ]]
    target="${FAKE_RECEIPT_DIR:?}/$key"
    mkdir -p "${target%/*}"
    cp "$body" "$target"
    ;;
  *' ec2 create-tags '*) : ;;
  *) printf 'unexpected fake AWS call: %s\n' "$*" >&2; exit 1 ;;
esac
EOF
chmod 700 "$fake_bin/aws"

cat > "$fake_bin/sleep" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "${1:-}" >> "${FAKE_SLEEP_LOG:?}"
exit 0
EOF
chmod 700 "$fake_bin/sleep"

cat > "$fake_bin/date" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == '+%s' && -n "${FAKE_DATE_SEQUENCE:-}" ]]; then
  date_call=0
  [[ ! -f "${FAKE_DATE_STATE:?}" ]] || read -r date_call < "$FAKE_DATE_STATE"
  read -r -a date_values <<< "$FAKE_DATE_SEQUENCE"
  date_index=$date_call
  if ((date_index >= ${#date_values[@]})); then
    date_index=$((${#date_values[@]} - 1))
  fi
  printf '%s\n' "${date_values[$date_index]}"
  printf '%s\n' "$((date_call + 1))" > "$FAKE_DATE_STATE"
elif [[ "${1:-}" == '+%s' && -n "${FAKE_DEADLINE_PENDING_STATE:-}" ]]; then
  if [[ -s "${FAKE_SLEEP_LOG:?}" ]]; then
    printf '%s\n' 700
  elif [[ -f "$FAKE_DEADLINE_PENDING_STATE" ]]; then
    printf '%s\n' 695
  else
    printf '%s\n' 100
  fi
else
  exec /bin/date "$@"
fi
EOF
chmod 700 "$fake_bin/date"

run_verifier() {
  env \
    PATH="$fake_bin:/usr/bin:/bin" \
    AWS_REGION=ap-northeast-2 \
    FAKE_CALL_LOG="$temp_dir/aws-calls.log" \
    FAKE_RECEIPT_DIR="$receipt_dir" \
    FAKE_SLEEP_LOG="$temp_dir/sleep-calls.log" \
    "$@"
}

: > "$temp_dir/aws-calls.log"
egress_output=$(run_verifier "$verifier" egress \
  phase2-test \
  vpc-0123456789abcdef0 \
  rtb-0123456789abcdef0 \
  i-0123456789abcdef0 \
  ami-0123456789abcdef0 \
  airbob-performance-lab-evidence-942632789808)
[[ "$egress_output" == 'network_receipt=network-receipts/phase2-test/i-0123456789abcdef0.json' ]] \
  || fail "egress verifier did not report the exact receipt key"
egress_receipt="$receipt_dir/network-receipts/phase2-test/i-0123456789abcdef0.json"
jq -e '.schemaVersion == 1 and .s3Gateway == "verified" and .ecrApi == "verified" and .ssmApi == "verified" and .secretsManagerApi == "verified"' "$egress_receipt" >/dev/null \
  || fail "egress receipt does not attest every required path"
grep -Fq 'ec2 create-tags' "$temp_dir/aws-calls.log" || fail "verified probe was not tagged"
grep -Fq 'ssm send-command' "$temp_dir/aws-calls.log" || fail "active SSM egress command was not sent"
grep -Fq -- '--content-type application/json' "$temp_dir/aws-calls.log" \
  || fail "egress receipt was not published as human-readable JSON"
grep -Fq -- '--server-side-encryption AES256' "$temp_dir/aws-calls.log" \
  || fail "egress receipt did not request AES256 encryption"

: > "$temp_dir/aws-calls.log"
ssm_registration_state="$temp_dir/ssm-registration"
ssm_retry_output=$(run_verifier \
  FAKE_SSM_OFFLINE_ONCE=1 FAKE_SSM_REGISTRATION_STATE="$ssm_registration_state" \
  "$verifier" egress \
  phase2-test \
  vpc-0123456789abcdef0 \
  rtb-0123456789abcdef0 \
  i-0123456789abcdef0 \
  ami-0123456789abcdef0 \
  airbob-performance-lab-evidence-942632789808)
[[ "$ssm_retry_output" == 'network_receipt=network-receipts/phase2-test/i-0123456789abcdef0.json' ]] \
  || fail "egress verifier did not recover from delayed SSM registration"
[[ "$(grep -Fc 'ssm describe-instance-information' "$temp_dir/aws-calls.log")" -eq 2 ]] \
  || fail "egress verifier did not wait for the probe to become SSM-online"
[[ "$(grep -Fc 'ssm send-command' "$temp_dir/aws-calls.log")" -eq 1 ]] \
  || fail "egress verifier sent the command before the probe became SSM-online"

: > "$temp_dir/aws-calls.log"
: > "$temp_dir/sleep-calls.log"
send_retry_state="$temp_dir/send-command-retry"
send_retry_output=$(run_verifier \
  FAKE_SEND_NOT_READY_ONCE=1 FAKE_SEND_RETRY_STATE="$send_retry_state" \
  "$verifier" egress \
  phase2-test \
  vpc-0123456789abcdef0 \
  rtb-0123456789abcdef0 \
  i-0123456789abcdef0 \
  ami-0123456789abcdef0 \
  airbob-performance-lab-evidence-942632789808)
[[ "$send_retry_output" == 'network_receipt=network-receipts/phase2-test/i-0123456789abcdef0.json' ]] \
  || fail "egress verifier did not recover from transient SSM command dispatch readiness"
[[ "$(grep -Fc 'ssm send-command' "$temp_dir/aws-calls.log")" -eq 2 ]] \
  || fail "egress verifier did not retry the transient SSM command dispatch exactly once"
[[ "$(grep -Fc 'ssm get-command-invocation' "$temp_dir/aws-calls.log")" -eq 1 ]] \
  || fail "egress verifier polled an invocation for the rejected command dispatch"

: > "$temp_dir/aws-calls.log"
: > "$temp_dir/sleep-calls.log"
if run_verifier FAKE_SEND_API_FAILURE=1 \
  "$verifier" egress \
  phase2-test \
  vpc-0123456789abcdef0 \
  rtb-0123456789abcdef0 \
  i-0123456789abcdef0 \
  ami-0123456789abcdef0 \
  airbob-performance-lab-evidence-942632789808 >/dev/null 2>&1
then
  fail "egress verifier retried a non-transient SSM command dispatch failure"
fi
[[ "$(grep -Fc 'ssm send-command' "$temp_dir/aws-calls.log")" -eq 1 ]] \
  || fail "egress verifier did not fail immediately on a non-transient SSM command dispatch failure"
if grep -Fq 'ssm get-command-invocation' "$temp_dir/aws-calls.log"; then
  fail "egress verifier polled an invocation after a rejected command dispatch"
fi

: > "$temp_dir/aws-calls.log"
: > "$temp_dir/sleep-calls.log"
pending_state="$temp_dir/command-pending"
pending_output=$(run_verifier \
  FAKE_PENDING_ONCE=1 FAKE_PENDING_STATE="$pending_state" \
  "$verifier" egress \
  phase2-test \
  vpc-0123456789abcdef0 \
  rtb-0123456789abcdef0 \
  i-0123456789abcdef0 \
  ami-0123456789abcdef0 \
  airbob-performance-lab-evidence-942632789808)
[[ "$pending_output" == 'network_receipt=network-receipts/phase2-test/i-0123456789abcdef0.json' ]] \
  || fail "egress verifier did not wait for a pending command"
[[ "$(grep -Fc 'ssm send-command' "$temp_dir/aws-calls.log")" -eq 1 ]] \
  || fail "egress verifier resent a command that was still pending"
[[ "$(grep -Fc 'ssm get-command-invocation' "$temp_dir/aws-calls.log")" -eq 2 ]] \
  || fail "egress verifier did not poll the pending command through success"

: > "$temp_dir/aws-calls.log"
: > "$temp_dir/sleep-calls.log"
missing_state="$temp_dir/invocation-missing"
missing_output=$(run_verifier \
  FAKE_INVOCATION_MISSING_ONCE=1 FAKE_INVOCATION_MISSING_STATE="$missing_state" \
  "$verifier" egress \
  phase2-test \
  vpc-0123456789abcdef0 \
  rtb-0123456789abcdef0 \
  i-0123456789abcdef0 \
  ami-0123456789abcdef0 \
  airbob-performance-lab-evidence-942632789808)
[[ "$missing_output" == 'network_receipt=network-receipts/phase2-test/i-0123456789abcdef0.json' ]] \
  || fail "egress verifier did not recover from delayed command invocation visibility"
[[ "$(grep -Fc 'ssm send-command' "$temp_dir/aws-calls.log")" -eq 1 ]] \
  || fail "egress verifier resent a command whose invocation was not visible yet"

: > "$temp_dir/aws-calls.log"
: > "$temp_dir/sleep-calls.log"
deadline_pending_state="$temp_dir/deadline-command-pending"
if run_verifier \
  FAKE_PENDING_ONCE=1 FAKE_PENDING_STATE="$deadline_pending_state" \
  FAKE_DEADLINE_PENDING_STATE="$deadline_pending_state" \
  "$verifier" egress \
  phase2-test \
  vpc-0123456789abcdef0 \
  rtb-0123456789abcdef0 \
  i-0123456789abcdef0 \
  ami-0123456789abcdef0 \
  airbob-performance-lab-evidence-942632789808 >/dev/null 2>&1
then
  fail "egress verifier accepted a command that was still pending at the shared deadline"
fi
grep -Fxq 5 "$temp_dir/sleep-calls.log" \
  || fail "egress verifier did not cap its poll sleep to the remaining readiness budget"
[[ "$(grep -Fc 'ssm send-command' "$temp_dir/aws-calls.log")" -eq 1 ]] \
  || fail "egress verifier resent a pending command at the shared deadline"

: > "$temp_dir/aws-calls.log"
: > "$temp_dir/sleep-calls.log"
if run_verifier FAKE_INVOCATION_API_FAILURE=1 \
  "$verifier" egress \
  phase2-test \
  vpc-0123456789abcdef0 \
  rtb-0123456789abcdef0 \
  i-0123456789abcdef0 \
  ami-0123456789abcdef0 \
  airbob-performance-lab-evidence-942632789808 >/dev/null 2>&1
then
  fail "egress verifier retried a non-transient SSM API failure"
fi
[[ "$(grep -Fc 'ssm get-command-invocation' "$temp_dir/aws-calls.log")" -eq 1 ]] \
  || fail "egress verifier did not fail immediately on a non-transient SSM API failure"

: > "$temp_dir/aws-calls.log"
: > "$temp_dir/sleep-calls.log"
retry_state="$temp_dir/probe-ready-retry"
retry_output=$(run_verifier \
  FAKE_NOT_READY_ONCE=1 FAKE_READY_RETRY_STATE="$retry_state" \
  "$verifier" egress \
  phase2-test \
  vpc-0123456789abcdef0 \
  rtb-0123456789abcdef0 \
  i-0123456789abcdef0 \
  ami-0123456789abcdef0 \
  airbob-performance-lab-evidence-942632789808)
[[ "$retry_output" == 'network_receipt=network-receipts/phase2-test/i-0123456789abcdef0.json' ]] \
  || fail "egress verifier did not recover from the probe readiness race"
[[ "$(grep -Fc 'ssm send-command' "$temp_dir/aws-calls.log")" -eq 2 ]] \
  || fail "egress verifier did not retry exactly once after the probe readiness marker was absent"

for terminal_status in Failed Cancelled Cancelling TimedOut; do
  : > "$temp_dir/aws-calls.log"
  : > "$temp_dir/sleep-calls.log"
  if run_verifier FAKE_TERMINAL_STATUS="$terminal_status" \
    "$verifier" egress \
    phase2-test \
    vpc-0123456789abcdef0 \
    rtb-0123456789abcdef0 \
    i-0123456789abcdef0 \
    ami-0123456789abcdef0 \
    airbob-performance-lab-evidence-942632789808 >/dev/null 2>&1
  then
    fail "egress verifier accepted terminal SSM status: $terminal_status"
  fi
  [[ "$(grep -Fc 'ssm send-command' "$temp_dir/aws-calls.log")" -eq 1 ]] \
    || fail "egress verifier retried terminal SSM status: $terminal_status"
done

: > "$temp_dir/aws-calls.log"
: > "$temp_dir/sleep-calls.log"
shared_registration_state="$temp_dir/shared-deadline-registration"
shared_pending_state="$temp_dir/shared-deadline-pending"
shared_date_state="$temp_dir/shared-deadline-date-calls"
if run_verifier \
  FAKE_SSM_OFFLINE_ONCE=1 FAKE_SSM_REGISTRATION_STATE="$shared_registration_state" \
  FAKE_PENDING_ONCE=1 FAKE_PENDING_STATE="$shared_pending_state" \
  FAKE_DATE_STATE="$shared_date_state" \
  FAKE_DATE_SEQUENCE='100 100 690 690 690 690 690 690 690 695 695 700' \
  "$verifier" egress \
  phase2-test \
  vpc-0123456789abcdef0 \
  rtb-0123456789abcdef0 \
  i-0123456789abcdef0 \
  ami-0123456789abcdef0 \
  airbob-performance-lab-evidence-942632789808 >/dev/null 2>&1
then
  fail "egress verifier reset its readiness deadline after SSM registration"
fi
[[ "$(grep -Fc 'ssm describe-instance-information' "$temp_dir/aws-calls.log")" -eq 2 ]] \
  || fail "shared deadline test did not consume budget during SSM registration"
[[ "$(grep -Fc 'ssm send-command' "$temp_dir/aws-calls.log")" -eq 1 ]] \
  || fail "shared deadline test unexpectedly resent the pending command"
[[ "$(grep -Fc 'ssm get-command-invocation' "$temp_dir/aws-calls.log")" -eq 1 ]] \
  || fail "shared deadline test did not expire during command polling"
[[ "$(tr '\n' ' ' < "$temp_dir/sleep-calls.log")" == '10 5 ' ]] \
  || fail "shared deadline test did not spend one bounded budget across both readiness phases"

: > "$temp_dir/aws-calls.log"
cleared_output=$(run_verifier "$verifier" cleared \
  phase2-test \
  vpc-0123456789abcdef0 \
  i-0123456789abcdef0 \
  airbob-performance-lab-evidence-942632789808)
[[ "$cleared_output" == 'clearance_receipt=network-clearance/phase2-test/i-0123456789abcdef0.json' ]] \
  || fail "clearance verifier did not report the exact receipt key"
jq -e '.schemaVersion == 1 and .instanceState == "terminated"' \
  "$receipt_dir/network-clearance/phase2-test/i-0123456789abcdef0.json" >/dev/null \
  || fail "probe clearance receipt is invalid"
grep -Fq -- '--content-type application/json' "$temp_dir/aws-calls.log" \
  || fail "probe clearance receipt was not published as human-readable JSON"
grep -Fq -- '--server-side-encryption AES256' "$temp_dir/aws-calls.log" \
  || fail "probe clearance receipt did not request AES256 encryption"

if run_verifier FAKE_ACCOUNT=111111111111 "$verifier" cleared \
  phase2-test vpc-0123456789abcdef0 i-0123456789abcdef0 \
  airbob-performance-lab-evidence-942632789808 >/dev/null 2>&1
then
  fail "network verifier accepted the wrong AWS account"
fi

if run_verifier FAKE_BAD_MARKER=1 "$verifier" egress \
  phase2-test vpc-0123456789abcdef0 rtb-0123456789abcdef0 \
  i-0123456789abcdef0 ami-0123456789abcdef0 \
  airbob-performance-lab-evidence-942632789808 >/dev/null 2>&1
then
  fail "network verifier accepted an incomplete active-egress marker"
fi

printf '%s\n' 'network egress contract tests passed'
