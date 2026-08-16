#!/usr/bin/env bash
set -euo pipefail

fail() { printf '%s\n' "$1" >&2; exit 1; }

[[ "$#" -eq 1 ]] || fail "usage: scan-lab-orphans.sh RUN_ID"
run_id=$1
[[ "$run_id" =~ ^[a-z0-9][a-z0-9-]{2,31}$ && "$run_id" != *--* && "$run_id" != *- ]] \
  || fail "run id is not canonical"
[[ "${AWS_REGION:-}" == "ap-northeast-2" ]] || fail "AWS_REGION must equal ap-northeast-2"
command -v aws >/dev/null 2>&1 || fail "AWS CLI is required"

if command -v sha1sum >/dev/null 2>&1; then
  run_hash=$(printf '%s' "$run_id" | sha1sum | awk '{print $1}')
else
  run_hash=$(printf '%s' "$run_id" | shasum -a 1 | awk '{print $1}')
fi
[[ "$run_hash" =~ ^[0-9a-f]{40}$ ]] || fail "cannot derive the bounded lab resource name"
bounded_prefix="airbob-${run_id:0:12}-${run_hash:0:6}"

account_id=$(aws sts get-caller-identity --query Account --output text --region "$AWS_REGION")
[[ "$account_id" == "942632789808" ]] || fail "active AWS account is outside the lab boundary"

resources=$(aws resourcegroupstaggingapi get-resources \
  --tag-filters \
    "Key=Project,Values=airbob" \
    "Key=Environment,Values=performance-lab" \
    "Key=Stack,Values=lab" \
    "Key=Persistence,Values=ephemeral" \
    "Key=RunId,Values=$run_id" \
  --query 'ResourceTagMappingList[].ResourceARN' \
  --output text \
  --region "$AWS_REGION" \
  --no-cli-pager)

if [[ -n "$resources" && "$resources" != None ]]; then
  printf '%s\n' "orphaned ephemeral resources remain for RunId=$run_id" >&2
  tr '\t' '\n' <<<"$resources" >&2
  exit 1
fi

assert_empty() {
  local result=$1
  [[ -z "$result" || "$result" == None ]] \
    || fail "an explicit EC2/RDS/ALB/EBS/EIP/ASG orphan scan found resources"
}

assert_empty "$(aws ec2 describe-instances \
  --filters "Name=tag:RunId,Values=$run_id" Name=instance-state-name,Values=pending,running,stopping,stopped \
  --query 'Reservations[].Instances[].InstanceId' --output text --region "$AWS_REGION" --no-cli-pager)"
assert_empty "$(aws ec2 describe-volumes \
  --filters "Name=tag:RunId,Values=$run_id" \
  --query 'Volumes[].VolumeId' --output text --region "$AWS_REGION" --no-cli-pager)"
assert_empty "$(aws ec2 describe-addresses \
  --filters "Name=tag:RunId,Values=$run_id" \
  --query 'Addresses[].AllocationId' --output text --region "$AWS_REGION" --no-cli-pager)"
assert_empty "$(aws rds describe-db-instances \
  --query "DBInstances[?contains(DBInstanceIdentifier, '$run_id')].DBInstanceIdentifier" \
  --output text --region "$AWS_REGION" --no-cli-pager)"
assert_empty "$(aws elbv2 describe-load-balancers \
  --query "LoadBalancers[?LoadBalancerName=='$bounded_prefix-alb'].LoadBalancerArn" \
  --output text --region "$AWS_REGION" --no-cli-pager)"
assert_empty "$(aws autoscaling describe-auto-scaling-groups \
  --query "AutoScalingGroups[?AutoScalingGroupName=='airbob-$run_id-app'].AutoScalingGroupName" \
  --output text --region "$AWS_REGION" --no-cli-pager)"

printf 'orphan_scan=clean run_id=%s\n' "$run_id"
