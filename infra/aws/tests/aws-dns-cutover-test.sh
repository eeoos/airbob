#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
controller="$repo_root/infra/aws/scripts/aws-dns-controller.sh"
dns_root="$repo_root/infra/aws/dns"

fail() { printf '%s\n' "$1" >&2; exit 1; }
assert_contains() { grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"; }

[[ -x "$controller" && ! -L "$controller" ]] || fail "DNS controller is missing or unsafe"
assert_contains "$dns_root/variables.tf" 'variable "traffic_target"'
assert_contains "$dns_root/variables.tf" 'variable "run_id"'
assert_contains "$dns_root/variables.tf" 'variable "fencing_token"'
assert_contains "$dns_root/variables.tf" 'contains(["oci", "aws"], var.traffic_target)'
assert_contains "$dns_root/locals.tf" 'data.aws_lb.api[0].tags.RunId == var.run_id'
assert_contains "$dns_root/locals.tf" 'data.aws_lb.api[0].tags.FencingToken == tostring(var.fencing_token)'
assert_contains "$dns_root/records.tf" 'var.traffic_target == "oci" ? 100 : 0'
assert_contains "$dns_root/records.tf" 'var.traffic_target == "aws" ? 100 : 0'

assert_contains "$controller" '--resolve'
assert_contains "$controller" '--connect-to'
assert_contains "$controller" '"$lease_script" assert'
assert_contains "$controller" 'traffic_target=oci'
assert_contains "$controller" 'apply_dns "$traffic_target" true'
assert_contains "$controller" '"-var=run_id=$RUN_ID"'
assert_contains "$controller" '"-var=fencing_token=$ALB_FENCING_TOKEN"'
assert_contains "$controller" 'PUBLIC_DRAIN_SECONDS=120'
assert_contains "$controller" 'verify_public_oci_drain'
assert_contains "$controller" '"https://api.airbob.cloud/actuator/health"'
assert_contains "$controller" 'assumed-role/airbob-dns-controller/'
assert_contains "$controller" 'keep_on_failure'
assert_contains "$controller" 'if [[ -n "$aws_alb_arn" ]]'
assert_contains "$controller" 'apply_dns oci false'
assert_contains "$controller" '[[ "$answer" == "$OCI_ORIGIN_IPV4" ]]'
assert_contains "$controller" '! grep -Fqx "$OCI_ORIGIN_IPV4"'
assert_contains "$controller" 'verify)'
assert_contains "$controller" 'assert_lease'
assert_contains "$controller" 'verify_dns aws'
assert_contains "$controller" 'measurement verification requires the AWS target'

probe_oci_source=$(sed -n '/^probe_oci() {$/,/^}$/p' "$controller")
probe_aws_source=$(sed -n '/^probe_aws() {$/,/^}$/p' "$controller")
oci_drain_source=$(sed -n '/^verify_public_oci_drain() {$/,/^}$/p' "$controller")

printf '%s\n' "$probe_oci_source" | grep -Fq 'https://api.airbob.cloud/health' \
  || fail "direct OCI probe must use the public Nginx health endpoint"
printf '%s\n' "$probe_oci_source" | grep -Fq 'grep -Fqx '\''healthy'\''' \
  || fail "direct OCI probe must validate the exact Nginx health body"
if printf '%s\n' "$probe_oci_source" | grep -Fq '/actuator/health'; then
  fail "direct OCI probe must not request the blocked actuator endpoint"
fi
printf '%s\n' "$oci_drain_source" | grep -Fq 'https://api.airbob.cloud/health' \
  || fail "OCI drain verification must use the public Nginx health endpoint"
printf '%s\n' "$oci_drain_source" | grep -Fq 'grep -Fqx '\''healthy'\''' \
  || fail "OCI drain verification must validate the exact Nginx health body"
if printf '%s\n' "$oci_drain_source" | grep -Fq '/actuator/health'; then
  fail "OCI drain verification must not request the blocked actuator endpoint"
fi
printf '%s\n' "$probe_aws_source" | grep -Fq 'https://api.airbob.cloud/actuator/health' \
  || fail "direct AWS probe must retain the Spring actuator health endpoint"
printf '%s\n' "$probe_aws_source" | grep -Fq "jq -e '.status == \"UP\"'" \
  || fail "direct AWS probe must validate the Spring health JSON"

if grep -Eq 'route53 change-resource-record-sets' "$controller"; then
  fail "DNS changes must remain owned by the isolated Terraform DNS state"
fi

printf '%s\n' 'AWS DNS cutover contract tests passed'
