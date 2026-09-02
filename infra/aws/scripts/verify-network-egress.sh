#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

[[ "$#" -ge 1 ]] || fail "usage: verify-network-egress.sh <egress|cleared> ..."
action=$1
shift

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
toolchain_contract="$repo_root/infra/aws/toolchain.env"
[[ -f "$toolchain_contract" && ! -L "$toolchain_contract" ]] || fail "toolchain contract is missing or unsafe"
# shellcheck disable=SC1090
source "$toolchain_contract"

command -v aws >/dev/null 2>&1 || fail "AWS CLI is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"
[[ "${AWS_REGION:-}" == "$AIRBOB_AWS_REGION" ]] || fail "AWS_REGION must equal $AIRBOB_AWS_REGION"
account_id=$(aws sts get-caller-identity --query Account --output text)
[[ "$account_id" == "$AIRBOB_AWS_ACCOUNT_ID" ]] || fail "active AWS account does not match the lab contract"

validate_run_id() {
  [[ "$1" =~ ^[a-z0-9][a-z0-9-]{2,31}$ ]] || fail "run id is invalid"
}

validate_vpc_id() {
  [[ "$1" =~ ^vpc-[0-9a-f]{8,17}$ ]] || fail "VPC id is invalid"
}

validate_probe_id() {
  [[ "$1" =~ ^i-[0-9a-f]{8,17}$ ]] || fail "probe instance id is invalid"
}

validate_bucket() {
  [[ "$1" =~ ^airbob-performance-lab-evidence-[0-9]{12}$ ]] || fail "evidence bucket is invalid"
}

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-network-receipt.XXXXXX")
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

put_receipt() {
  local bucket=$1
  local key=$2
  local body=$3
  aws s3api put-object \
    --bucket "$bucket" \
    --key "$key" \
    --body "$body" \
    --tagging Retention=summary \
    --no-cli-pager >/dev/null
}

case "$action" in
  egress)
    [[ "$#" -eq 6 ]] || fail "usage: verify-network-egress.sh egress <run-id> <vpc-id> <private-route-table-id> <probe-instance-id> <ami-id> <evidence-bucket>"
    run_id=$1
    vpc_id=$2
    route_table_id=$3
    probe_instance_id=$4
    ami_id=$5
    evidence_bucket=$6
    validate_run_id "$run_id"
    validate_vpc_id "$vpc_id"
    [[ "$route_table_id" =~ ^rtb-[0-9a-f]{8,17}$ ]] || fail "route table id is invalid"
    validate_probe_id "$probe_instance_id"
    [[ "$ami_id" =~ ^ami-[0-9a-f]{8,17}$ ]] || fail "AMI id is invalid"
    validate_bucket "$evidence_bucket"

    instance_facts=$(aws ec2 describe-instances \
      --instance-ids "$probe_instance_id" \
      --query 'Reservations[0].Instances[0].[State.Name,VpcId,ImageId,Tags[?Key==`Service`].Value|[0],Tags[?Key==`RunId`].Value|[0]]' \
      --output json)
    jq -e \
      --arg vpc "$vpc_id" \
      --arg ami "$ami_id" \
      --arg run "$run_id" \
      '.[0] == "running" and .[1] == $vpc and .[2] == $ami and .[3] == "egress-probe" and .[4] == $run' \
      <<<"$instance_facts" >/dev/null || fail "probe identity, VPC, AMI, state, or tags do not match"

    aws ec2 describe-route-tables \
      --route-table-ids "$route_table_id" \
      --filters "Name=vpc-id,Values=$vpc_id" \
      --query 'RouteTables[0].Routes[?DestinationCidrBlock==`0.0.0.0/0` && State==`active`] | length(@)' \
      --output text | grep -Fxq 1 || fail "private route table has no active default route"
    aws ec2 describe-vpc-endpoints \
      --filters "Name=vpc-id,Values=$vpc_id" "Name=service-name,Values=com.amazonaws.$AWS_REGION.s3" "Name=vpc-endpoint-type,Values=Gateway" \
      --query 'VpcEndpoints[?State==`available`] | length(@)' \
      --output text | grep -Fxq 1 || fail "S3 gateway endpoint is not available"

    remote_command=$(cat <<EOF
set -euo pipefail
if [[ ! -f /var/lib/airbob/probe-ready ]]; then
  printf '%s\n' 'AIRBOB_PROBE_NOT_READY' >&2
  exit 75
fi
aws --region '$AWS_REGION' s3api get-bucket-location --bucket '$evidence_bucket' >/dev/null
probe_api() {
  code=\$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' --connect-timeout 5 --max-time 15 "\$1")
  test "\$code" != 000
}
probe_api 'https://api.ecr.$AWS_REGION.amazonaws.com/'
probe_api 'https://ssm.$AWS_REGION.amazonaws.com/'
probe_api 'https://secretsmanager.$AWS_REGION.amazonaws.com/'
printf '%s\n' 'AIRBOB_EGRESS_OK s3=verified ecr=verified ssm=verified secretsmanager=verified'
EOF
)
    parameters=$(jq -nc --arg command "$remote_command" '{commands: [$command]}')
    probe_ready_deadline=$(($(date +%s) + 600))

    probe_deadline_open() {
      local now
      now=$(date +%s)
      ((now < probe_ready_deadline))
    }

    sleep_with_probe_budget() {
      local requested_seconds=$1 now remaining_seconds
      now=$(date +%s)
      remaining_seconds=$((probe_ready_deadline - now))
      ((remaining_seconds > 0)) || return 1
      if ((requested_seconds > remaining_seconds)); then
        requested_seconds=$remaining_seconds
      fi
      sleep "$requested_seconds"
    }

    ssm_online=false
    while probe_deadline_open; do
      if ! ssm_ping_status=$(aws ssm describe-instance-information \
        --filters "Key=InstanceIds,Values=$probe_instance_id" \
        --query 'InstanceInformationList[0].PingStatus' \
        --output text); then
        fail "could not query probe SSM registration"
      fi
      probe_deadline_open || break
      case "$ssm_ping_status" in
        Online)
          ssm_online=true
          break
          ;;
        None|ConnectionLost|Inactive|'')
          sleep_with_probe_budget 10 || break
          ;;
        *) fail "probe returned an unexpected SSM ping status" ;;
      esac
    done
    [[ "$ssm_online" == true ]] || fail "probe did not register with SSM within 10 minutes"

    invocation=''
    command_succeeded=false
    while probe_deadline_open; do
      send_error_file="$temp_dir/ssm-send-error"
      if command_id=$(aws ssm send-command \
        --instance-ids "$probe_instance_id" \
        --document-name AWS-RunShellScript \
        --comment "Airbob Phase 2 egress verification" \
        --parameters "$parameters" \
        --query 'Command.CommandId' \
        --output text 2>"$send_error_file"); then
        :
      else
        send_error=$(<"$send_error_file")
        if [[ "$send_error" == *'(InvalidInstanceId)'* || \
          "$send_error" == *'(TargetNotConnected)'* ]]; then
          sleep_with_probe_budget 5 || break
          continue
        fi
        [[ -z "$send_error" ]] || printf '%s\n' "$send_error" >&2
        fail "could not send probe egress command"
      fi
      [[ "$command_id" =~ ^[0-9a-f-]{36}$ ]] || fail "SSM did not return a command id"
      probe_deadline_open || break

      resend_for_probe_marker=false
      while probe_deadline_open; do
        invocation_error_file="$temp_dir/ssm-invocation-error"
        if invocation=$(aws ssm get-command-invocation \
          --command-id "$command_id" --instance-id "$probe_instance_id" \
          --output json 2>"$invocation_error_file"); then
          probe_deadline_open || break
          if ! invocation_status=$(jq -er '.Status' <<<"$invocation"); then
            fail "SSM returned an invalid command invocation"
          fi
          case "$invocation_status" in
            Pending|InProgress|Delayed)
              sleep_with_probe_budget 5 || break
              ;;
            Success)
              command_succeeded=true
              break
              ;;
            Failed)
              if jq -e '
                .ResponseCode == 75 and
                ((.StandardErrorContent // "") | contains("AIRBOB_PROBE_NOT_READY"))
              ' <<<"$invocation" >/dev/null; then
                invocation=''
                resend_for_probe_marker=true
                break
              fi
              fail "egress verification command failed"
              ;;
            Cancelled|Cancelling|TimedOut)
              fail "egress verification command failed"
              ;;
            *) fail "SSM returned an unexpected command status" ;;
          esac
        else
          invocation_error=$(<"$invocation_error_file")
          if [[ "$invocation_error" == *'(InvocationDoesNotExist)'* ]]; then
            sleep_with_probe_budget 5 || break
            continue
          fi
          [[ -z "$invocation_error" ]] || printf '%s\n' "$invocation_error" >&2
          fail "could not read probe command invocation"
        fi
      done

      [[ "$command_succeeded" == false ]] || break
      if [[ "$resend_for_probe_marker" == true ]]; then
        sleep_with_probe_budget 10 || break
        continue
      fi
      break
    done
    [[ "$command_succeeded" == true ]] || fail "probe did not become ready within 10 minutes"
    jq -e '.Status == "Success"' <<<"$invocation" >/dev/null || fail "egress verification command failed"
    marker=$(jq -r '.StandardOutputContent' <<<"$invocation")
    [[ "$marker" == *'AIRBOB_EGRESS_OK s3=verified ecr=verified ssm=verified secretsmanager=verified'* ]] \
      || fail "egress verification marker is incomplete"

    verified_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
    receipt="$temp_dir/network-receipt.json"
    jq -n \
      --arg runId "$run_id" \
      --arg vpcId "$vpc_id" \
      --arg primaryRouteTableId "$route_table_id" \
      --arg probeInstanceId "$probe_instance_id" \
      --arg amiId "$ami_id" \
      --arg verifiedAt "$verified_at" \
      '{schemaVersion: 1, runId: $runId, vpcId: $vpcId, primaryRouteTableId: $primaryRouteTableId, probeInstanceId: $probeInstanceId, amiId: $amiId, s3Gateway: "verified", ecrApi: "verified", ssmApi: "verified", secretsManagerApi: "verified", verifiedAt: $verifiedAt}' \
      > "$receipt"
    put_receipt "$evidence_bucket" "network-receipts/$run_id/$probe_instance_id.json" "$receipt"
    aws ec2 create-tags \
      --resources "$probe_instance_id" \
      --tags Key=AirbobEgressVerified,Value=true Key=AirbobEgressVerifiedAt,Value="$verified_at"
    printf 'network_receipt=network-receipts/%s/%s.json\n' "$run_id" "$probe_instance_id"
    ;;
  cleared)
    [[ "$#" -eq 4 ]] || fail "usage: verify-network-egress.sh cleared <run-id> <vpc-id> <probe-instance-id> <evidence-bucket>"
    run_id=$1
    vpc_id=$2
    probe_instance_id=$3
    evidence_bucket=$4
    validate_run_id "$run_id"
    validate_vpc_id "$vpc_id"
    validate_probe_id "$probe_instance_id"
    validate_bucket "$evidence_bucket"

    instance_state=$(aws ec2 describe-instances \
      --instance-ids "$probe_instance_id" \
      --query 'Reservations[0].Instances[0].State.Name' \
      --output text)
    [[ "$instance_state" == terminated ]] || fail "probe must be terminated by the probe-cleared Terraform apply"
    aws ec2 describe-vpcs --vpc-ids "$vpc_id" --query 'Vpcs[0].State' --output text \
      | grep -Fxq available || fail "verified VPC is no longer available"

    cleared_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
    receipt="$temp_dir/probe-clearance.json"
    jq -n \
      --arg runId "$run_id" \
      --arg vpcId "$vpc_id" \
      --arg probeInstanceId "$probe_instance_id" \
      --arg clearedAt "$cleared_at" \
      '{schemaVersion: 1, runId: $runId, vpcId: $vpcId, probeInstanceId: $probeInstanceId, instanceState: "terminated", clearedAt: $clearedAt}' \
      > "$receipt"
    put_receipt "$evidence_bucket" "network-clearance/$run_id/$probe_instance_id.json" "$receipt"
    printf 'clearance_receipt=network-clearance/%s/%s.json\n' "$run_id" "$probe_instance_id"
    ;;
  *)
    fail "action must be egress or cleared"
    ;;
esac
