#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
dns_root="$repo_root/infra/aws/dns"
lab_root="$repo_root/infra/aws/lab"
toolchain_contract="$repo_root/infra/aws/toolchain.env"
backend_helper="$repo_root/infra/aws/scripts/prepare-terraform-backend.sh"

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

assert_contains() {
  local file=$1
  local expected=$2
  grep -Fq -- "$expected" "$file" || fail "$file does not contain: $expected"
}

assert_not_contains() {
  local path=$1
  local rejected=$2
  if find "$path" -type f ! -path '*/.terraform/*' -exec grep -Eq -- "$rejected" {} +; then
    fail "$path contains forbidden pattern: $rejected"
  fi
}

command -v terraform >/dev/null 2>&1 || fail "Terraform is required"
[[ -f "$toolchain_contract" && ! -L "$toolchain_contract" ]] \
  || fail "Terraform/AWS toolchain contract is missing or unsafe"
[[ -x "$backend_helper" && ! -L "$backend_helper" ]] \
  || fail "shared Terraform backend helper is missing or unsafe"

for root_name in dns lab; do
  root="$repo_root/infra/aws/$root_name"
  [[ -d "$root" && ! -L "$root" ]] || fail "$root_name root is missing or unsafe"
  for required_file in \
    backend.tf versions.tf providers.tf variables.tf data.tf locals.tf \
    outputs.tf README.md .terraform.lock.hcl "tests/$root_name.tftest.hcl"
  do
    [[ -f "$root/$required_file" && ! -L "$root/$required_file" ]] \
      || fail "$root_name/$required_file is missing or unsafe"
  done

  assert_contains "$root/backend.tf" 'backend "s3" {}'
  assert_not_contains "$root" 'terraform_remote_state'
  assert_not_contains "$root" 'AKIA[0-9A-Z]{16}'
  assert_not_contains "$root" 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY'

  terraform -chdir="$root" fmt -check -recursive
  terraform -chdir="$root" init -backend=false -input=false -lockfile=readonly
  terraform -chdir="$root" validate
  terraform -chdir="$root" test
done

for required_file in records.tf; do
  [[ -f "$dns_root/$required_file" && ! -L "$dns_root/$required_file" ]] \
    || fail "dns/$required_file is missing or unsafe"
done
[[ -f "$lab_root/tests/fixtures/lab-contract.json" && ! -L "$lab_root/tests/fixtures/lab-contract.json" ]] \
  || fail "lab contract test fixture is missing or unsafe"

dns_resource_count=$(grep -hE '^resource "aws_[^"]+" "[^"]+" \{' "$dns_root"/*.tf | wc -l | tr -d ' ')
lab_resource_count=$(grep -hE '^resource "aws_[^"]+" "[^"]+" \{' "$lab_root"/*.tf 2>/dev/null | wc -l | tr -d ' ' || true)
[[ "$dns_resource_count" -eq 2 ]] || fail "DNS state must declare exactly two weighted Route 53 record resources"
[[ "$lab_resource_count" -gt 0 ]] || fail "lab state must declare only its ephemeral Phase 2-4 resources"

assert_contains "$dns_root/data.tf" '/airbob/performance-lab/foundation/dns-contract'
assert_contains "$lab_root/data.tf" '/airbob/performance-lab/foundation/lab-contract'
grep -Eq 'Stack[[:space:]]*=[[:space:]]*"lab"' "$lab_root/providers.tf" \
  || fail "lab resources must carry the stable Stack=lab observer scope tag"
grep -Eq 'RunId[[:space:]]*=[[:space:]]*var.run_id' "$lab_root/providers.tf" \
  || fail "all taggable lab resources must inherit the exact run identity"
grep -Eq 'FencingToken[[:space:]]*=[[:space:]]*tostring\(var.fencing_token\)' "$lab_root/providers.tf" \
  || fail "all taggable lab resources must inherit the active fencing token"
oci_record_source=$(sed -n \
  '/^resource "aws_route53_record" "oci_api"/,/^resource "aws_route53_record" "aws_api"/p' \
  "$dns_root/records.tf" | sed '$d')
aws_record_source=$(sed -n '/^resource "aws_route53_record" "aws_api"/,$p' "$dns_root/records.tf")
printf '%s\n' "$oci_record_source" | grep -Fq 'prevent_destroy = true' \
  || fail "OCI Route 53 record must carry prevent_destroy"
if printf '%s\n' "$aws_record_source" | grep -Fq 'prevent_destroy'; then
  fail "removable AWS Route 53 record must not carry prevent_destroy"
fi
assert_not_contains "$dns_root" 'aws_route53_zone'
assert_not_contains "$lab_root" 'resource "aws_route53_zone"'
assert_not_contains "$lab_root" 'resource "aws_(s3_bucket|ecr_repository|dynamodb_table|acm_certificate)"'

assert_contains "$toolchain_contract" 'AIRBOB_STATE_KEY_DNS=airbob/dns/terraform.tfstate'
assert_contains "$toolchain_contract" 'AIRBOB_STATE_KEY_LAB=airbob/lab/terraform.tfstate'
assert_contains "$backend_helper" '1:dns'
assert_contains "$backend_helper" '1:lab'
assert_contains "$backend_helper" 'use_lockfile = true'
assert_not_contains "$backend_helper" 'dynamodb_table'

printf '%s\n' 'DNS and lab state boundary tests passed'
