#!/usr/bin/env bash
set -euo pipefail
umask 077

PUBLIC_DRAIN_SECONDS=120

fail() { printf '%s\n' "$1" >&2; exit 1; }

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

[[ "$#" -eq 2 ]] || fail "usage: aws-dns-controller.sh stage|switch|remove|verify oci|aws"
dns_action=$1
traffic_target=$2
[[ "$dns_action" == stage || "$dns_action" == switch || "$dns_action" == remove || "$dns_action" == verify ]] || fail "unsupported DNS action"
[[ "$traffic_target" == oci || "$traffic_target" == aws ]] || fail "traffic target must be oci or aws"
[[ "$dns_action:$traffic_target" != remove:aws ]] || fail "remove may only preserve the OCI target"
[[ "$dns_action:$traffic_target" != verify:oci ]] || fail "measurement verification requires the AWS target"

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
dns_root="$repo_root/infra/aws/dns"
lease_script="$script_dir/orchestration-lease.sh"
backend_helper="$script_dir/prepare-terraform-backend.sh"
toolchain_contract="$repo_root/infra/aws/toolchain.env"

for executable in "$lease_script" "$backend_helper"; do
  [[ -x "$executable" && ! -L "$executable" ]] || fail "required controller helper is missing or unsafe"
done
[[ -f "$toolchain_contract" && ! -L "$toolchain_contract" ]] || fail "toolchain contract is missing or unsafe"
# shellcheck disable=SC1090
. "$toolchain_contract"

required_environment=(
  AWS_DNS_CONTROLLER_ROLE_ARN OCI_ORIGIN_IPV4 ALB_FENCING_TOKEN
  LEASE_TABLE LEASE_LOCK_ID LEASE_OWNER FENCING_TOKEN RUN_ID LEASE_COMMAND
)
for name in "${required_environment[@]}"; do
  [[ -n "${!name:-}" ]] || fail "$name is required"
done
[[ "${AWS_REGION:-}" == "$AIRBOB_AWS_REGION" ]] || fail "AWS_REGION must equal $AIRBOB_AWS_REGION"
[[ "$AWS_DNS_CONTROLLER_ROLE_ARN" == "arn:aws:iam::$AIRBOB_AWS_ACCOUNT_ID:role/airbob-dns-controller" ]] \
  || fail "DNS controller role ARN is outside the foundation boundary"
valid_ipv4 "$OCI_ORIGIN_IPV4" || fail "OCI origin IPv4 is not canonical"
aws_alb_arn=${AWS_ALB_ARN:-}
aws_alb_dns_name=${AWS_ALB_DNS_NAME:-}
if [[ -n "$aws_alb_arn" || -n "$aws_alb_dns_name" || "$dns_action" != remove ]]; then
  [[ "$aws_alb_arn" =~ ^arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/ ]] \
    || fail "AWS ALB ARN is not canonical"
  [[ "$aws_alb_dns_name" =~ ^[A-Za-z0-9.-]+\.elb\.amazonaws\.com$ ]] || fail "AWS ALB DNS name is not canonical"
fi
[[ "$FENCING_TOKEN" =~ ^[1-9][0-9]*$ ]] || fail "fencing token is not canonical"
[[ "$ALB_FENCING_TOKEN" =~ ^[1-9][0-9]*$ ]] || fail "ALB fencing token is not canonical"

command -v aws >/dev/null 2>&1 || fail "AWS CLI is required"
command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v dig >/dev/null 2>&1 || fail "dig is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"
command -v terraform >/dev/null 2>&1 || fail "Terraform is required"

caller_arn=$(aws sts get-caller-identity --query Arn --output text --region "$AWS_REGION")
case "$caller_arn" in
  arn:aws:sts::$AIRBOB_AWS_ACCOUNT_ID:assumed-role/airbob-dns-controller/*) ;;
  *)
    credentials=$(aws sts assume-role \
      --role-arn "$AWS_DNS_CONTROLLER_ROLE_ARN" \
      --role-session-name "airbob-dns-${RUN_ID:0:20}-${FENCING_TOKEN}" \
      --duration-seconds 3600 \
      --tags "Key=RunId,Value=$RUN_ID" "Key=FencingToken,Value=$FENCING_TOKEN" "Key=Command,Value=$LEASE_COMMAND" \
      --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' \
      --output text \
      --region "$AWS_REGION") || fail "cannot assume the dedicated DNS controller role"
    read -r AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN <<EOF
$credentials
EOF
    export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
    caller_arn=$(aws sts get-caller-identity --query Arn --output text --region "$AWS_REGION")
    ;;
esac
[[ "$caller_arn" == arn:aws:sts::$AIRBOB_AWS_ACCOUNT_ID:assumed-role/airbob-dns-controller/* ]] \
  || fail "public DNS changes require assumed-role/airbob-dns-controller/ credentials"

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-dns-controller.XXXXXX")
dns_backend_prepared=false
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

assert_lease() {
  "$lease_script" assert "$LEASE_TABLE" "$LEASE_LOCK_ID" "$LEASE_OWNER" \
    "$FENCING_TOKEN" "$RUN_ID" "$LEASE_COMMAND" >/dev/null
}

probe_oci() {
  curl --fail --silent --show-error --max-time 10 \
    --resolve "api.airbob.cloud:443:$OCI_ORIGIN_IPV4" \
    "https://api.airbob.cloud/health" \
    | grep -Fqx 'healthy' \
    || fail "direct OCI origin health check failed"
}

probe_aws() {
  curl --fail --silent --show-error --max-time 10 \
    --connect-to "api.airbob.cloud:443:$aws_alb_dns_name:443" \
    "https://api.airbob.cloud/actuator/health" \
    | jq -e '.status == "UP"' >/dev/null \
    || fail "direct AWS ALB origin health check failed"
}

apply_dns() {
  local selected_target=$1 include_aws=$2
  local plan_file="$temp_dir/dns-$selected_target-$include_aws.tfplan"
  assert_lease
  if [[ "$dns_backend_prepared" == false ]]; then
    "$backend_helper" dns >/dev/null
    terraform -chdir="$dns_root" init -input=false -lockfile=readonly \
      -backend-config=backend.generated.hcl >/dev/null
    dns_backend_prepared=true
  fi
  terraform_arguments=(
    -input=false
    -lock-timeout=5m
    -out="$plan_file"
    "-var=oci_origin_ipv4=$OCI_ORIGIN_IPV4"
    "-var=traffic_target=$selected_target"
    "-var=run_id=$RUN_ID"
    "-var=fencing_token=$ALB_FENCING_TOKEN"
  )
  if [[ "$include_aws" == true ]]; then
    terraform_arguments+=("-var=aws_alb_arn=$aws_alb_arn")
  fi
  terraform -chdir="$dns_root" plan "${terraform_arguments[@]}" >/dev/null
  assert_lease
  terraform -chdir="$dns_root" apply -input=false -lock-timeout=5m -auto-approve "$plan_file" >/dev/null
}

resolver_has_target() {
  local resolver=$1 target=$2 answer expected
  answer=$(dig +time=3 +tries=1 +short "@$resolver" api.airbob.cloud A | sort -u)
  [[ -n "$answer" ]] || return 1
  if [[ "$target" == oci ]]; then
    [[ "$answer" == "$OCI_ORIGIN_IPV4" ]]
    return $?
  fi
  ! grep -Fqx "$OCI_ORIGIN_IPV4" <<<"$answer" || return 1
  expected=$(dig +time=3 +tries=1 +short "$aws_alb_dns_name" A | sort -u)
  [[ -n "$expected" ]] || return 1
  comm -12 <(printf '%s\n' "$answer") <(printf '%s\n' "$expected") | grep -q .
}

verify_dns() {
  local target=$1 deadline resolver zone_id name_servers
  zone_id=$(aws ssm get-parameter \
    --name /airbob/performance-lab/foundation/dns-contract \
    --query 'Parameter.Value' --output text --region "$AWS_REGION" \
    | jq -er '.zone_id')
  name_servers=$(aws route53 get-hosted-zone --id "$zone_id" \
    --query 'DelegationSet.NameServers' --output text --region "$AWS_REGION")
  deadline=$(($(date +%s) + 180))
  while [[ $(date +%s) -le "$deadline" ]]; do
    all_valid=true
    for resolver in $name_servers 1.1.1.1 8.8.8.8; do
      if ! resolver_has_target "$resolver" "$target"; then
        all_valid=false
        break
      fi
    done
    [[ "$all_valid" == true ]] && return 0
    sleep 5
  done
  fail "authoritative and public DNS did not converge on the selected origin"
}

verify_public_oci_drain() {
  local deadline now
  deadline=$(($(date +%s) + PUBLIC_DRAIN_SECONDS))
  while true; do
    curl --fail --silent --show-error --max-time 10 \
      "https://api.airbob.cloud/health" \
      | grep -Fqx 'healthy' \
      || fail "public API health failed during the OCI drain window"
    now=$(date +%s)
    [[ "$now" -ge "$deadline" ]] && return 0
    sleep 15
  done
}

keep_on_failure=${KEEP_ON_FAILURE:-false}
[[ "$keep_on_failure" == true || "$keep_on_failure" == false ]] || fail "keep_on_failure must be true or false"
force_down=${FORCE_DOWN:-false}
[[ "$force_down" == true || "$force_down" == false ]] || fail "FORCE_DOWN must be true or false"

case "$dns_action" in
  verify)
    assert_lease
    probe_aws
    verify_dns aws
    ;;
  stage)
    [[ "$traffic_target" == oci ]] || fail "AWS alias staging must retain OCI traffic"
    probe_oci
    probe_aws
    apply_dns oci true # traffic_target=oci
    verify_dns oci
    ;;
  switch)
    probe_oci
    if [[ "$traffic_target" == aws ]]; then
      probe_aws
    fi
    if ! apply_dns "$traffic_target" true; then
      [[ "$keep_on_failure" == true ]] || apply_dns oci true || true
      fail "weighted DNS switch failed"
    fi
    if ! verify_dns "$traffic_target"; then
      if [[ "$traffic_target" == aws ]]; then
        apply_dns oci true || true
        verify_dns oci || true
      fi
      fail "weighted DNS verification failed; OCI rollback was attempted"
    fi
    ;;
  remove)
    [[ "$force_down" == true ]] || probe_oci
    if [[ -n "$aws_alb_arn" ]]; then
      apply_dns oci true # traffic_target=oci
      if [[ "$force_down" == false ]]; then
        verify_dns oci
        verify_public_oci_drain
      fi
      apply_dns oci false
    else
      # A partially-created or already-removed lab has no ALB to drain. Route 53
      # still converges atomically to the sole OCI record before lab teardown.
      apply_dns oci false
    fi
    [[ "$force_down" == true ]] || verify_dns oci
    ;;
esac

printf 'dns_target=%s\n' "$traffic_target"
