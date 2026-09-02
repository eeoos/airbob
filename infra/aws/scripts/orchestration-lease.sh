#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

usage() {
  fail "usage: orchestration-lease.sh acquire TABLE LOCK OWNER RUN COMMAND HEARTBEAT_TTL DEADLINE | assert|release TABLE LOCK OWNER TOKEN RUN COMMAND | heartbeat TABLE LOCK OWNER TOKEN RUN COMMAND HEARTBEAT_TTL | status TABLE LOCK"
}

[[ -n "${AWS_REGION:-}" ]] || fail "AWS_REGION is required"
[[ "$#" -ge 3 ]] || usage
action=$1
table=$2
lock_id=$3
shift 3

[[ "$table" =~ ^[A-Za-z0-9_.-]{3,255}$ ]] || fail "lease table is not canonical"
[[ "$lock_id" =~ ^[A-Za-z0-9_.:/-]{3,255}$ ]] || fail "lease lock id is not canonical"
command -v aws >/dev/null 2>&1 || fail "AWS CLI is required"

now_epoch=$(date +%s)
[[ "$now_epoch" =~ ^[1-9][0-9]{9}$ ]] || fail "current epoch is not canonical"

validate_identity() {
  local owner=$1 run_id=$2 command_name=$3
  [[ "$owner" =~ ^[A-Za-z0-9._:@/-]{3,128}$ ]] || fail "lease owner is not canonical"
  [[ "$run_id" =~ ^[a-z0-9][a-z0-9-]{2,31}$ && "$run_id" != *--* && "$run_id" != *- ]] \
    || fail "lease run id is not canonical"
  [[ "$command_name" == up || "$command_name" == switch || "$command_name" == down || "$command_name" == measurement || "$command_name" == dataset-promotion || "$command_name" == dataset-snapshot ]] \
    || fail "lease command is not approved"
}

key_json=$(printf '{"LockName":{"S":"%s"}}' "$lock_id")
name_json='{"#token":"FencingToken","#owner":"Owner","#run":"RunId","#command":"Command","#heartbeat":"HeartbeatAt","#expires":"ExpiresAt","#deadline":"CommandDeadline"}'
acquire_name_json='{"#token":"FencingToken","#owner":"Owner","#run":"RunId","#command":"Command","#acquired":"AcquiredAt","#heartbeat":"HeartbeatAt","#expires":"ExpiresAt","#deadline":"CommandDeadline"}'

read_item() {
  aws dynamodb get-item \
    --table-name "$table" \
    --key "$key_json" \
    --consistent-read \
    --query 'Item.[Owner.S,FencingToken.N,RunId.S,Command.S,AcquiredAt.N,HeartbeatAt.N,ExpiresAt.N,CommandDeadline.N]' \
    --output text \
    --region "$AWS_REGION" \
    --no-cli-pager
}

case "$action" in
  acquire)
    [[ "$#" -eq 5 ]] || usage
    owner=$1
    run_id=$2
    command_name=$3
    heartbeat_ttl=$4
    deadline_seconds=$5
    validate_identity "$owner" "$run_id" "$command_name"
    [[ "$heartbeat_ttl" =~ ^[1-9][0-9]{1,3}$ && "$heartbeat_ttl" -ge 60 && "$heartbeat_ttl" -le 900 ]] \
      || fail "heartbeat TTL must be 60-900 seconds"
    deadline_limit=5400
    [[ "$command_name" != dataset-snapshot ]] || deadline_limit=9000
    [[ "$command_name" != up ]] || deadline_limit=14400
    [[ "$deadline_seconds" =~ ^[1-9][0-9]{2,4}$ && "$deadline_seconds" -le "$deadline_limit" ]] \
      || fail "command deadline exceeds the approved limit"
    expires_epoch=$((now_epoch + heartbeat_ttl))
    deadline_epoch=$((now_epoch + deadline_seconds))
    value_json=$(printf '{":zero":{"N":"0"},":one":{"N":"1"},":released":{"S":"released"},":owner":{"S":"%s"},":run":{"S":"%s"},":command":{"S":"%s"},":now":{"N":"%s"},":expires":{"N":"%s"},":deadline":{"N":"%s"}}' \
      "$owner" "$run_id" "$command_name" "$now_epoch" "$expires_epoch" "$deadline_epoch")
    token=$(aws dynamodb update-item \
      --table-name "$table" \
      --key "$key_json" \
      --update-expression 'SET #token = if_not_exists(#token, :zero) + :one, #owner = :owner, #run = :run, #command = :command, #acquired = :now, #heartbeat = :now, #expires = :expires, #deadline = :deadline' \
      --condition-expression 'attribute_not_exists(#owner) OR #owner = :released OR (#expires < :now AND #deadline < :now)' \
      --expression-attribute-names "$acquire_name_json" \
      --expression-attribute-values "$value_json" \
      --return-values UPDATED_NEW \
      --query 'Attributes.FencingToken.N' \
      --output text \
      --region "$AWS_REGION" \
      --no-cli-pager) || fail "active orchestration lease rejected acquisition"
    [[ "$token" =~ ^[1-9][0-9]*$ ]] || fail "DynamoDB did not return a fencing token"
    printf 'fencing_token=%s\n' "$token"
    ;;
  assert)
    [[ "$#" -eq 4 ]] || usage
    owner=$1 token=$2 run_id=$3 command_name=$4
    validate_identity "$owner" "$run_id" "$command_name"
    [[ "$token" =~ ^[1-9][0-9]*$ ]] || fail "fencing token is not canonical"
    item=$(read_item) || fail "cannot read the orchestration lease"
    read -r actual_owner actual_token actual_run actual_command actual_acquired actual_heartbeat actual_expiry actual_deadline <<EOF
$item
EOF
    [[ "$actual_owner" == "$owner" && "$actual_token" == "$token" && "$actual_run" == "$run_id" && "$actual_command" == "$command_name" ]] \
      || fail "orchestration lease ownership or fencing token changed"
    [[ "$actual_acquired" =~ ^[0-9]+$ && "$actual_heartbeat" =~ ^[0-9]+$ && \
      "$actual_expiry" =~ ^[0-9]+$ && "$actual_deadline" =~ ^[0-9]+$ && \
      "$actual_expiry" -ge "$now_epoch" && "$actual_deadline" -ge "$now_epoch" ]] \
      || fail "orchestration lease expired"
    printf '%s\n' "lease_valid=true"
    ;;
  heartbeat)
    [[ "$#" -eq 5 ]] || usage
    owner=$1 token=$2 run_id=$3 command_name=$4 heartbeat_ttl=$5
    validate_identity "$owner" "$run_id" "$command_name"
    [[ "$token" =~ ^[1-9][0-9]*$ ]] || fail "fencing token is not canonical"
    [[ "$heartbeat_ttl" =~ ^[1-9][0-9]{1,3}$ && "$heartbeat_ttl" -ge 60 && "$heartbeat_ttl" -le 900 ]] \
      || fail "heartbeat TTL must be 60-900 seconds"
    expires_epoch=$((now_epoch + heartbeat_ttl))
    value_json=$(printf '{":owner":{"S":"%s"},":token":{"N":"%s"},":run":{"S":"%s"},":command":{"S":"%s"},":now":{"N":"%s"},":expires":{"N":"%s"}}' \
      "$owner" "$token" "$run_id" "$command_name" "$now_epoch" "$expires_epoch")
    aws dynamodb update-item \
      --table-name "$table" --key "$key_json" \
      --update-expression 'SET #heartbeat = :now, #expires = :expires' \
      --condition-expression '#owner = :owner AND #token = :token AND #run = :run AND #command = :command AND #expires >= :now AND #deadline >= :now' \
      --expression-attribute-names "$name_json" --expression-attribute-values "$value_json" \
      --region "$AWS_REGION" --no-cli-pager >/dev/null \
      || fail "orchestration lease heartbeat was fenced out"
    ;;
  release)
    [[ "$#" -eq 4 ]] || usage
    owner=$1 token=$2 run_id=$3 command_name=$4
    validate_identity "$owner" "$run_id" "$command_name"
    [[ "$token" =~ ^[1-9][0-9]*$ ]] || fail "fencing token is not canonical"
    value_json=$(printf '{":released":{"S":"released"},":owner":{"S":"%s"},":token":{"N":"%s"},":run":{"S":"%s"},":command":{"S":"%s"},":zero":{"N":"0"},":now":{"N":"%s"}}' \
      "$owner" "$token" "$run_id" "$command_name" "$now_epoch")
    aws dynamodb update-item \
      --table-name "$table" --key "$key_json" \
      --update-expression 'SET #owner = :released, #heartbeat = :now, #expires = :zero, #deadline = :zero' \
      --condition-expression '#owner = :owner AND #token = :token AND #run = :run AND #command = :command' \
      --expression-attribute-names "$name_json" --expression-attribute-values "$value_json" \
      --region "$AWS_REGION" --no-cli-pager >/dev/null \
      || fail "orchestration lease release was fenced out"
    ;;
  status)
    [[ "$#" -eq 0 ]] || usage
    read_item
    ;;
  *) usage ;;
esac
