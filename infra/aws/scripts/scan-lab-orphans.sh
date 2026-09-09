#!/usr/bin/env bash
set -euo pipefail

fail() { printf '%s\n' "$1" >&2; exit 1; }

[[ "$#" -eq 1 ]] || fail "usage: scan-lab-orphans.sh RUN_ID"
run_id=$1
[[ "$run_id" =~ ^lab-[a-z0-9][a-z0-9-]{0,27}$ && "$run_id" != *--* && "$run_id" != *- ]] \
  || fail "run id is not canonical"
[[ "${AWS_REGION:-}" == "ap-northeast-2" ]] || fail "AWS_REGION must equal ap-northeast-2"
command -v aws >/dev/null 2>&1 || fail "AWS CLI is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"
scan_scope=${AIRBOB_SCAN_SCOPE:-run}
[[ "$scan_scope" == run || "$scan_scope" == global ]] \
  || fail "AIRBOB_SCAN_SCOPE must be run or global"

if command -v sha1sum >/dev/null 2>&1; then
  run_hash=$(printf '%s' "$run_id" | sha1sum | awk '{print $1}')
else
  run_hash=$(printf '%s' "$run_id" | shasum -a 1 | awk '{print $1}')
fi
[[ "$run_hash" =~ ^[0-9a-f]{40}$ ]] || fail "cannot derive the bounded lab resource name"
bounded_prefix="airbob-${run_id:0:12}-${run_hash:0:6}"

if [[ "$scan_scope" == global ]]; then
  explicit_tag_filters=(
    "Name=tag:Project,Values=airbob"
    "Name=tag:Environment,Values=performance-lab"
    "Name=tag:Stack,Values=lab"
    "Name=tag:Persistence,Values=ephemeral"
  )
  rds_query="DBInstances[?starts_with(DBInstanceIdentifier, 'airbob-lab-')].DBInstanceIdentifier"
  load_balancer_query="LoadBalancers[?starts_with(LoadBalancerName, 'airbob-lab-')].LoadBalancerArn"
  target_group_query="TargetGroups[?starts_with(TargetGroupName, 'airbob-lab-')].TargetGroupArn"
  auto_scaling_group_query="AutoScalingGroups[?starts_with(AutoScalingGroupName, 'airbob-lab-')].AutoScalingGroupName"
  db_subnet_group_query="DBSubnetGroups[?starts_with(DBSubnetGroupName, 'airbob-lab-')].DBSubnetGroupName"
  db_parameter_group_query="DBParameterGroups[?starts_with(DBParameterGroupName, 'airbob-lab-')].DBParameterGroupName"
  scaling_policy_query="ScalingPolicies[?starts_with(PolicyName, 'airbob-lab-')].PolicyARN"
  iam_role_query="Roles[?starts_with(RoleName, 'airbob-lab-host-')].Arn"
  iam_instance_profile_query="InstanceProfiles[?starts_with(InstanceProfileName, 'airbob-lab-host-')].Arn"
  secret_query="SecretList[?starts_with(Name, 'airbob/lab-')].ARN"
  dashboard_prefix=airbob-lab-
  dashboard_query="DashboardEntries[?starts_with(DashboardName, 'airbob-lab-')].DashboardName"
  alarm_prefixes=(airbob-lab-)
  # SSM association ARNs contain only a generated UUID, so IAM cannot scope
  # create-time AddTagsToResource to the airbob-lab name namespace. Keep this
  # short-lived account surface exclusive: a clean global gate requires zero
  # associations of any name before a run and after teardown.
  association_query="Associations[].AssociationId"
  document_query="DocumentIdentifiers[?starts_with(Name, 'airbob-lab-')].Name"
else
  explicit_tag_filters=("Name=tag:RunId,Values=$run_id")
  rds_query="DBInstances[?contains(DBInstanceIdentifier, '$run_id')].DBInstanceIdentifier"
  load_balancer_query="LoadBalancers[?LoadBalancerName=='$bounded_prefix-alb'].LoadBalancerArn"
  target_group_query="TargetGroups[?TargetGroupName=='$bounded_prefix-app'].TargetGroupArn"
  auto_scaling_group_query="AutoScalingGroups[?AutoScalingGroupName=='airbob-$run_id-app'].AutoScalingGroupName"
  db_subnet_group_query="DBSubnetGroups[?DBSubnetGroupName=='airbob-$run_id'].DBSubnetGroupName"
  db_parameter_group_query="DBParameterGroups[?DBParameterGroupName=='airbob-$run_id'].DBParameterGroupName"
  scaling_policy_query="ScalingPolicies[?starts_with(PolicyName, 'airbob-$run_id-app-')].PolicyARN"
  iam_role_query="Roles[?starts_with(RoleName, 'airbob-lab-host-$run_id-')].Arn"
  iam_instance_profile_query="InstanceProfiles[?starts_with(InstanceProfileName, 'airbob-lab-host-$run_id-')].Arn"
  secret_query="SecretList[?Tags[?Key=='RunId' && Value=='$run_id']].ARN"
  dashboard_prefix="airbob-$run_id"
  dashboard_query="DashboardEntries[?DashboardName=='airbob-$run_id'].DashboardName"
  alarm_prefixes=("$bounded_prefix" "airbob-$run_id-app")
  association_query="Associations[?starts_with(AssociationName, 'airbob-$run_id')].AssociationId"
  document_query="DocumentIdentifiers[?starts_with(Name, 'airbob-$run_id')].Name"
fi

if ! account_id=$(aws sts get-caller-identity --query Account --output text --region "$AWS_REGION"); then
  fail "cannot verify the AWS account for the orphan scan"
fi
[[ "$account_id" == "942632789808" ]] || fail "active AWS account is outside the lab boundary"

tag_filters=(
  "Key=Project,Values=airbob"
  "Key=Environment,Values=performance-lab"
  "Key=Stack,Values=lab"
  "Key=Persistence,Values=ephemeral"
)
[[ "$scan_scope" == global ]] || tag_filters+=("Key=RunId,Values=$run_id")

assert_empty() {
  local label=$1 result=$2
  [[ -z "$result" || "$result" == None ]] \
    || fail "an explicit $label orphan scan found resources: $result"
}

assert_aws_empty() {
  local label=$1 result
  shift
  if ! result=$(aws "$@"); then
    fail "cannot complete the explicit $label orphan scan"
  fi
  assert_empty "$label" "$result"
}

assert_aws_empty EC2 ec2 describe-instances \
  --filters "${explicit_tag_filters[@]}" Name=instance-state-name,Values=pending,running,stopping,stopped \
  --query 'Reservations[].Instances[].InstanceId' --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty EBS ec2 describe-volumes \
  --filters "${explicit_tag_filters[@]}" \
  --query 'Volumes[].VolumeId' --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty EIP ec2 describe-addresses \
  --filters "${explicit_tag_filters[@]}" \
  --query 'Addresses[].AllocationId' --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty RDS rds describe-db-instances \
  --query "$rds_query" \
  --output text --region "$AWS_REGION" --no-cli-pager
if ! matching_load_balancers=$(aws elbv2 describe-load-balancers \
  --query "$load_balancer_query" \
  --output text --region "$AWS_REGION" --no-cli-pager); then
  fail "cannot complete the explicit ALB orphan scan"
fi
if [[ -n "$matching_load_balancers" && "$matching_load_balancers" != None ]]; then
  for load_balancer_arn in $matching_load_balancers; do
    assert_aws_empty ALB-listener elbv2 describe-listeners \
      --load-balancer-arn "$load_balancer_arn" --query 'Listeners[].ListenerArn' \
      --output text --region "$AWS_REGION" --no-cli-pager
  done
fi
assert_empty ALB "$matching_load_balancers"
assert_aws_empty target-group elbv2 describe-target-groups \
  --query "$target_group_query" \
  --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty ASG autoscaling describe-auto-scaling-groups \
  --query "$auto_scaling_group_query" \
  --output text --region "$AWS_REGION" --no-cli-pager

assert_aws_empty VPC ec2 describe-vpcs --filters "${explicit_tag_filters[@]}" \
  --query 'Vpcs[].VpcId' --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty subnet ec2 describe-subnets --filters "${explicit_tag_filters[@]}" \
  --query 'Subnets[].SubnetId' --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty route-table ec2 describe-route-tables --filters "${explicit_tag_filters[@]}" \
  --query 'RouteTables[].RouteTableId' --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty internet-gateway ec2 describe-internet-gateways --filters "${explicit_tag_filters[@]}" \
  --query 'InternetGateways[].InternetGatewayId' --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty network-interface ec2 describe-network-interfaces --filters "${explicit_tag_filters[@]}" \
  --query 'NetworkInterfaces[].NetworkInterfaceId' --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty security-group ec2 describe-security-groups --filters "${explicit_tag_filters[@]}" \
  --query 'SecurityGroups[].GroupId' --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty launch-template ec2 describe-launch-templates --filters "${explicit_tag_filters[@]}" \
  --query 'LaunchTemplates[].LaunchTemplateId' --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty VPC-endpoint ec2 describe-vpc-endpoints --filters "${explicit_tag_filters[@]}" \
  --query 'VpcEndpoints[].VpcEndpointId' --output text --region "$AWS_REGION" --no-cli-pager

assert_aws_empty DB-subnet-group rds describe-db-subnet-groups \
  --query "$db_subnet_group_query" \
  --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty DB-parameter-group rds describe-db-parameter-groups \
  --query "$db_parameter_group_query" \
  --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty scaling-policy autoscaling describe-policies \
  --query "$scaling_policy_query" \
  --output text --region "$AWS_REGION" --no-cli-pager

assert_aws_empty IAM-role iam list-roles \
  --query "$iam_role_query" \
  --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty IAM-instance-profile iam list-instance-profiles \
  --query "$iam_instance_profile_query" \
  --output text --region "$AWS_REGION" --no-cli-pager

assert_aws_empty secret secretsmanager list-secrets --include-planned-deletion \
  --query "$secret_query" \
  --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty dashboard cloudwatch list-dashboards --dashboard-name-prefix "$dashboard_prefix" \
  --query "$dashboard_query" \
  --output text --region "$AWS_REGION" --no-cli-pager
for alarm_prefix in "${alarm_prefixes[@]}"; do
  assert_aws_empty alarm cloudwatch describe-alarms --alarm-name-prefix "$alarm_prefix" \
    --query 'MetricAlarms[].AlarmName' --output text --region "$AWS_REGION" --no-cli-pager
done
assert_aws_empty SSM-association ssm list-associations \
  --query "$association_query" \
  --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty SSM-document ssm list-documents \
  --query "$document_query" \
  --output text --region "$AWS_REGION" --no-cli-pager
assert_aws_empty SSM-instance-association ssm describe-instance-information \
  --filters "${explicit_tag_filters[@]/Name=/Key=}" --query 'InstanceInformationList[].InstanceId' \
  --output text --region "$AWS_REGION" --no-cli-pager

if ! lab_contract=$(aws ssm get-parameter \
  --name /airbob/performance-lab/foundation/lab-contract \
  --query 'Parameter.Value' --output text --region "$AWS_REGION" --no-cli-pager); then
  fail "cannot read the exact foundation lab contract for the private DNS orphan scan"
fi
jq -e '
  .schemaVersion == 1 and
  (.private_dns_zone_id | type == "string" and test("^Z[A-Z0-9]+$")) and
  .private_dns_zone_name == "lab.airbob.internal"
' <<<"$lab_contract" >/dev/null || fail "foundation lab contract is invalid for the private DNS orphan scan"
private_dns_zone_id=$(jq -er '.private_dns_zone_id' <<<"$lab_contract")
if ! private_dns_anchor_vpc_id=$(aws ec2 describe-vpcs \
  --filters Name=tag:Name,Values=airbob-private-dns-anchor Name=cidr-block,Values=10.255.255.240/28 \
  --query 'Vpcs[].VpcId' --output text --region "$AWS_REGION" --no-cli-pager); then
  fail "cannot identify the protected private-DNS anchor VPC"
fi
[[ "$private_dns_anchor_vpc_id" =~ ^vpc-[0-9a-f]+$ ]] \
  || fail "private-DNS anchor VPC identity is missing or ambiguous"
if ! private_dns_zone=$(aws route53 get-hosted-zone \
  --id "$private_dns_zone_id" --output json --region "$AWS_REGION" --no-cli-pager); then
  fail "cannot inspect private-DNS VPC associations"
fi
jq -e --arg zone "$private_dns_zone_id" --arg vpc "$private_dns_anchor_vpc_id" \
  --arg region "$AWS_REGION" '
    (.HostedZone.Id | ltrimstr("/hostedzone/")) == $zone and
    (.VPCs | type == "array" and length == 1) and
    .VPCs[0].VPCId == $vpc and .VPCs[0].VPCRegion == $region
  ' <<<"$private_dns_zone" >/dev/null \
  || fail "private-DNS zone must retain only its protected anchor VPC association"
if ! private_dns_records=$(aws route53 list-resource-record-sets \
  --hosted-zone-id "$private_dns_zone_id" --output json \
  --region "$AWS_REGION" --no-cli-pager); then
  fail "cannot complete the explicit private-DNS orphan scan"
fi
private_dns_orphans=$(jq -er '
  select(.ResourceRecordSets | type == "array") |
  [
    "connect.lab.airbob.internal.",
    "elasticsearch.lab.airbob.internal.",
    "kafka.lab.airbob.internal.",
    "monitoring.lab.airbob.internal.",
    "redis-cache.lab.airbob.internal.",
    "redis-general.lab.airbob.internal."
  ] as $owned |
  [.ResourceRecordSets[]? | select(.Type == "A" and (.Name as $name | $owned | index($name)))] |
  map(.Name) | join("\t")
' <<<"$private_dns_records") || fail "private-DNS orphan response is invalid"
assert_empty private-DNS "$private_dns_orphans"

# GetResources can retain entries after EC2 deletion. Treat only an exact
# service-native NotFound (or the EC2 terminal instance state) as a tombstone;
# unknown ARN families and every live resource remain fail-closed.
ec2_resource_exists() {
  local not_found_code=$1 response
  shift
  if response=$(aws "$@" 2>&1); then
    return 0
  fi
  if [[ "$response" == *"($not_found_code)"* ]]; then
    return 1
  fi
  [[ -z "$response" ]] || printf '%s\n' "$response" >&2
  fail "cannot verify a tagged EC2 resource returned by the orphan scan"
}

tagged_resource_is_live() {
  local resource_arn=$1 resource_id instance_state
  resource_id=${resource_arn##*/}
  case "$resource_arn" in
    "arn:aws:ec2:$AWS_REGION:$account_id:instance/"i-*)
      if instance_state=$(aws ec2 describe-instances --instance-ids "$resource_id" \
        --query 'Reservations[0].Instances[0].State.Name' --output text \
        --region "$AWS_REGION" --no-cli-pager 2>&1); then
        case "$instance_state" in
          terminated|None|'') return 1 ;;
          pending|running|shutting-down|stopping|stopped) return 0 ;;
          *) fail "tagged EC2 instance returned an unexpected state" ;;
        esac
      fi
      [[ "$instance_state" == *'(InvalidInstanceID.NotFound)'* ]] && return 1
      [[ -z "$instance_state" ]] || printf '%s\n' "$instance_state" >&2
      fail "cannot verify a tagged EC2 instance returned by the orphan scan"
      ;;
    "arn:aws:ec2:$AWS_REGION:$account_id:volume/"vol-*)
      ec2_resource_exists InvalidVolume.NotFound ec2 describe-volumes --volume-ids "$resource_id" \
        --region "$AWS_REGION" --no-cli-pager
      ;;
    "arn:aws:ec2:$AWS_REGION:$account_id:subnet/"subnet-*)
      ec2_resource_exists InvalidSubnetID.NotFound ec2 describe-subnets --subnet-ids "$resource_id" \
        --region "$AWS_REGION" --no-cli-pager
      ;;
    "arn:aws:ec2:$AWS_REGION:$account_id:vpc-endpoint/"vpce-*)
      ec2_resource_exists InvalidVpcEndpointId.NotFound ec2 describe-vpc-endpoints \
        --vpc-endpoint-ids "$resource_id" --region "$AWS_REGION" --no-cli-pager
      ;;
    "arn:aws:ec2:$AWS_REGION:$account_id:security-group-rule/"sgr-*)
      ec2_resource_exists InvalidSecurityGroupRuleId.NotFound ec2 describe-security-group-rules \
        --security-group-rule-ids "$resource_id" --region "$AWS_REGION" --no-cli-pager
      ;;
    *) return 0 ;;
  esac
}

if ! resources=$(aws resourcegroupstaggingapi get-resources \
  --tag-filters "${tag_filters[@]}" \
  --query 'ResourceTagMappingList[].ResourceARN' \
  --output text \
  --region "$AWS_REGION" \
  --no-cli-pager); then
  fail "cannot complete the tagged-resource orphan scan"
fi

live_tagged_resources=()
if [[ -n "$resources" && "$resources" != None ]]; then
  while IFS= read -r resource_arn; do
    [[ -z "$resource_arn" ]] && continue
    if tagged_resource_is_live "$resource_arn"; then
      live_tagged_resources+=("$resource_arn")
    fi
  done < <(tr '\t' '\n' <<<"$resources")
fi
if ((${#live_tagged_resources[@]} > 0)); then
  printf '%s\n' "orphaned ephemeral resources remain for RunId=$run_id" >&2
  printf '%s\n' "${live_tagged_resources[@]}" >&2
  exit 1
fi

printf 'orphan_scan=clean run_id=%s scope=%s\n' "$run_id" "$scan_scope"
