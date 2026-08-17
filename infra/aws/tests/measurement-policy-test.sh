#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
verifier="$repo_root/infra/aws/scripts/enforce-measurement-policy.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-policy-test.XXXXXX")

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
[[ -x "$verifier" && ! -L "$verifier" ]] || fail "measurement policy verifier is missing or unsafe"
mkdir -p "$temp_dir/bin" "$temp_dir/receipts"
cat > "$temp_dir/bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >> "${FAKE_CALL_LOG:?}"
case " $* " in
  *' sts get-caller-identity '*) printf '%s\n' 942632789808 ;;
  *' ssm send-command '*) printf '%s\n' '11111111-2222-3333-4444-555555555555' ;;
  *' ssm wait command-executed '*) : ;;
  *' ssm get-command-invocation '*)
    if [[ "$*" == *'i-0123456789abcdef0'* ]]; then
      printf '%s\n' '{"Status":"Success","StandardOutputContent":"AIRBOB_ISOLATED_DB_OK connector=PAUSED outbox=empty threads=idle"}'
    else
      printf '%s\n' '{"Status":"Success","StandardOutputContent":"AIRBOB_ISOLATED_KAFKA_OK offsets=stable"}'
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
    cp "$body" "${FAKE_RECEIPT_DIR:?}/$(basename "$key")"
    ;;
  *) printf 'unexpected fake AWS call: %s\n' "$*" >&2; exit 1 ;;
esac
EOF
chmod 700 "$temp_dir/bin/aws"

run_verifier() {
  env PATH="$temp_dir/bin:/usr/bin:/bin" AWS_REGION=ap-northeast-2 \
    FAKE_CALL_LOG="$temp_dir/aws.log" FAKE_RECEIPT_DIR="$temp_dir/receipts" \
    "$verifier" "$1" lab-policy-test i-0123456789abcdef0 i-1123456789abcdef0 \
    airbob-test.abcdefghijkl.ap-northeast-2.rds.amazonaws.com \
    arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-test \
    airbob-performance-lab-evidence-942632789808 9
}

: > "$temp_dir/aws.log"
run_verifier integrated-smoke >/dev/null
if grep -Fq 'ssm send-command' "$temp_dir/aws.log"; then
  fail "integrated smoke unexpectedly paused background processing"
fi
jq -e '.policy == "integrated-smoke" and .connectorState == "RUNNING" and .dbState == "integrated" and .kafkaState == "integrated"' \
  "$temp_dir/receipts/9.json" >/dev/null || fail "integrated policy receipt is invalid"

: > "$temp_dir/aws.log"
run_verifier isolated-read >/dev/null
[[ "$(grep -Fc 'ssm send-command' "$temp_dir/aws.log")" -eq 2 ]] \
  || fail "isolated read did not execute both Debezium/DB and Kafka gates"
grep -Fq 'airbob-outbox-connector/pause' "$temp_dir/aws.log" || fail "isolated read did not pause Debezium"
grep -Fq 'kafka-get-offsets.sh' "$temp_dir/aws.log" || fail "isolated read did not sample Kafka offsets"
jq -e '.policy == "isolated-read" and .connectorState == "PAUSED" and .dbState == "idle" and .kafkaState == "idle"' \
  "$temp_dir/receipts/9.json" >/dev/null || fail "isolated policy receipt is invalid"

printf '%s\n' 'measurement policy contract tests passed'
