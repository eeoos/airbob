#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() { printf '%s\n' "$1" >&2; exit 1; }

[[ "$#" -eq 8 ]] || fail "usage: enforce-measurement-policy.sh POLICY RUN_ID DEBEZIUM_ID KAFKA_ID RDS_ENDPOINT RDS_SECRET_ARN EVIDENCE_BUCKET FENCING_TOKEN"
policy=$1
run_id=$2
debezium_id=$3
kafka_id=$4
rds_endpoint=$5
rds_secret_arn=$6
evidence_bucket=$7
fencing_token=$8

[[ "$policy" == integrated-smoke || "$policy" == isolated-read ]] || fail "measurement policy is invalid"
[[ "$run_id" =~ ^[a-z0-9][a-z0-9-]{2,31}$ ]] || fail "run id is invalid"
[[ "$debezium_id" =~ ^i-[0-9a-f]{8,17}$ && "$kafka_id" =~ ^i-[0-9a-f]{8,17}$ ]] || fail "service instance id is invalid"
[[ "$rds_endpoint" =~ ^[A-Za-z0-9.-]+\.rds\.amazonaws\.com$ ]] || fail "RDS endpoint is invalid"
[[ "$rds_secret_arn" =~ ^arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db- ]] || fail "RDS secret ARN is invalid"
[[ "$evidence_bucket" =~ ^airbob-performance-lab-evidence-[0-9]{12}$ ]] || fail "evidence bucket is invalid"
[[ "$fencing_token" =~ ^[1-9][0-9]*$ ]] || fail "fencing token is invalid"
[[ "${AWS_REGION:-}" == ap-northeast-2 ]] || fail "AWS_REGION must equal ap-northeast-2"
command -v aws >/dev/null 2>&1 || fail "AWS CLI is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"

account_id=$(aws sts get-caller-identity --query Account --output text --region "$AWS_REGION")
[[ "$account_id" == 942632789808 ]] || fail "active AWS account is outside the lab boundary"

send_and_wait() {
  local instance_id=$1 command_body=$2 marker=$3 comment=$4
  local parameters command_id invocation
  parameters=$(jq -nc --arg command "$command_body" '{commands:[$command]}')
  command_id=$(aws ssm send-command --instance-ids "$instance_id" \
    --document-name AWS-RunShellScript --comment "$comment" --parameters "$parameters" \
    --query 'Command.CommandId' --output text --region "$AWS_REGION" --no-cli-pager)
  [[ "$command_id" =~ ^[0-9a-f-]{36}$ ]] || fail "SSM did not return a command id"
  aws ssm wait command-executed --command-id "$command_id" --instance-id "$instance_id" \
    --region "$AWS_REGION" --no-cli-pager
  invocation=$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" \
    --output json --region "$AWS_REGION" --no-cli-pager)
  jq -e '.Status == "Success"' <<<"$invocation" >/dev/null || fail "measurement-policy SSM command failed"
  jq -er '.StandardOutputContent' <<<"$invocation" | grep -Fq "$marker" \
    || fail "measurement-policy SSM marker is missing"
}

connector_state=RUNNING
db_state=integrated
kafka_state=integrated
if [[ "$policy" == isolated-read ]]; then
  debezium_command=$(cat <<EOF
set -euo pipefail
curl --fail --silent --show-error --request PUT 'http://127.0.0.1:8083/connectors/airbob-outbox-connector/pause' >/dev/null
for attempt in \$(seq 1 30); do
  status=\$(curl --fail --silent --show-error 'http://127.0.0.1:8083/connectors/airbob-outbox-connector/status')
  if jq -e '.connector.state == "PAUSED" and (.tasks | length == 1) and all(.tasks[]; .state == "PAUSED")' <<<"\$status" >/dev/null; then
    break
  fi
  test "\$attempt" -lt 30
  sleep 2
done
secret_file=\$(mktemp /run/airbob-idle-secret.XXXXXX)
cleanup_secret() { rm -f "\$secret_file"; }
trap cleanup_secret EXIT HUP INT TERM
aws --region '$AWS_REGION' secretsmanager get-secret-value --secret-id '$rds_secret_arn' --query SecretString --output text > "\$secret_file"
chmod 600 "\$secret_file"
username=\$(jq -er '.username' "\$secret_file")
password=\$(jq -er '.password' "\$secret_file")
mysql_idle() {
  MYSQL_PWD="\$password" mysql --protocol=TCP --host='$rds_endpoint' --port=3306 --user="\$username" --ssl --batch --raw --skip-column-names airbobdb --execute="\$1"
}
test "\$(mysql_idle 'SELECT COUNT(*) FROM outbox')" = 0
threads_before=\$(mysql_idle "SHOW GLOBAL STATUS LIKE 'Threads_running'" | awk '{print \$2}')
test "\$threads_before" -le 1
sleep 15
test "\$(mysql_idle 'SELECT COUNT(*) FROM outbox')" = 0
threads_after=\$(mysql_idle "SHOW GLOBAL STATUS LIKE 'Threads_running'" | awk '{print \$2}')
test "\$threads_after" -le 1
printf '%s\n' 'AIRBOB_ISOLATED_DB_OK connector=PAUSED outbox=empty threads=idle'
EOF
)
  kafka_command=$(cat <<'EOF'
set -euo pipefail
container=$(docker ps --filter label=com.docker.compose.service=kafka --format '{{.ID}}')
test -n "$container"
before=$(mktemp /run/airbob-kafka-offsets-before.XXXXXX)
after=$(mktemp /run/airbob-kafka-offsets-after.XXXXXX)
cleanup_offsets() { rm -f "$before" "$after"; }
trap cleanup_offsets EXIT HUP INT TERM
docker exec "$container" /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --all-topics | sort > "$before"
test -s "$before"
sleep 15
docker exec "$container" /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --all-topics | sort > "$after"
cmp -s "$before" "$after"
printf '%s\n' 'AIRBOB_ISOLATED_KAFKA_OK offsets=stable'
EOF
)
  send_and_wait "$debezium_id" "$debezium_command" AIRBOB_ISOLATED_DB_OK "Airbob isolated-read Debezium and DB idle gate"
  send_and_wait "$kafka_id" "$kafka_command" AIRBOB_ISOLATED_KAFKA_OK "Airbob isolated-read Kafka idle gate"
  connector_state=PAUSED
  db_state=idle
  kafka_state=idle
fi

receipt=$(mktemp "${TMPDIR:-/tmp}/airbob-policy-receipt.XXXXXX")
cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  rm -f "$receipt"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
jq -n --arg runId "$run_id" --arg policy "$policy" --arg connectorState "$connector_state" \
  --arg dbState "$db_state" --arg kafkaState "$kafka_state" --argjson fencingToken "$fencing_token" \
  '{schemaVersion:1,runId:$runId,policy:$policy,connectorState:$connectorState,dbState:$dbState,kafkaState:$kafkaState,fencingToken:$fencingToken}' \
  > "$receipt"
aws s3api put-object --bucket "$evidence_bucket" --key "policy-controls/$run_id/$fencing_token.json" \
  --body "$receipt" --tagging Retention=summary --server-side-encryption AES256 \
  --content-type application/json --region "$AWS_REGION" --no-cli-pager >/dev/null
printf 'policy_control=verified policy=%s\n' "$policy"
