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
  *' ssm send-command '*) printf '%s\n' '11111111-2222-3333-4444-555555555555' ;;
  *' ssm wait command-executed '*) : ;;
  *' ssm get-command-invocation '*)
    if [[ "${FAKE_BAD_MARKER:-0}" == 1 ]]; then
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

run_verifier() {
  env \
    PATH="$fake_bin:/usr/bin:/bin" \
    AWS_REGION=ap-northeast-2 \
    FAKE_CALL_LOG="$temp_dir/aws-calls.log" \
    FAKE_RECEIPT_DIR="$receipt_dir" \
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
