#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
lab_root="$repo_root/infra/aws/lab"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-lab-contract-test.XXXXXX")
export TF_DATA_DIR="$temp_dir/terraform-data"
mkdir -p "$TF_DATA_DIR"

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

[[ -d "$lab_root" && ! -L "$lab_root" ]] || fail "lab root is missing or unsafe"

dump_make_contract=$(make -s -n -C "$repo_root" aws-up)
grep -Fq 'TTL_HOURS="5"' <<<"$dump_make_contract" \
  || fail "make aws-up must default dump bootstrap to the safe five-hour TTL"
grep -Fq 'DATABASE_BOOTSTRAP="dump"' <<<"$dump_make_contract" \
  || fail "make aws-up must default to dump bootstrap"

snapshot_make_contract=$(make -s -n -C "$repo_root" aws-up DATABASE_BOOTSTRAP=snapshot)
grep -Fq 'TTL_HOURS="2"' <<<"$snapshot_make_contract" \
  || fail "make aws-up must pass the exact two-hour TTL for snapshot bootstrap"
grep -Fq 'DATABASE_BOOTSTRAP="snapshot"' <<<"$snapshot_make_contract" \
  || fail "make aws-up must preserve explicit snapshot bootstrap"

required_files=(
  network.tf security.tf iam.tf service-hosts.tf private-dns.tf ssm.tf checks.tf
  templates/nat-user-data.sh.tftpl templates/host-user-data.sh.tftpl templates/start-service.sh.tftpl
  tests/phase2.tftest.hcl
  modules/network/main.tf modules/network/variables.tf modules/network/outputs.tf
  modules/security/main.tf modules/security/variables.tf modules/security/outputs.tf
  modules/nat-instance/main.tf modules/nat-instance/variables.tf modules/nat-instance/outputs.tf
  modules/service-ec2/main.tf modules/service-ec2/variables.tf modules/service-ec2/outputs.tf
)

for required_file in "${required_files[@]}"; do
  [[ -f "$lab_root/$required_file" && ! -L "$lab_root/$required_file" ]] \
    || fail "lab/$required_file is missing or unsafe"
done

[[ -x "$repo_root/infra/aws/scripts/verify-network-egress.sh" ]] \
  || fail "network egress verifier is missing or not executable"
[[ -x "$repo_root/infra/aws/scripts/publish-service-bundles.sh" ]] \
  || fail "service-bundle publisher is missing or not executable"

assert_contains "$lab_root/variables.tf" 'validation {'
assert_contains "$lab_root/variables.tf" 'contains(["network", "probe-cleared", "services", "data-ready"], var.deployment_phase)'
assert_contains "$lab_root/providers.tf" 'ExpiresAt'
assert_contains "$lab_root/locals.tf" 'probe_enabled    = var.deployment_phase == "network"'
assert_contains "$lab_root/locals.tf" 'services_enabled = contains(["services", "data-ready"], var.deployment_phase)'
assert_contains "$lab_root/checks.tf" 'resource "terraform_data" "network_receipt_gate"'
assert_contains "$lab_root/checks.tf" 'resource "terraform_data" "probe_clearance_gate"'
assert_contains "$lab_root/checks.tf" 'resource "terraform_data" "service_release_gate"'
assert_contains "$lab_root/modules/network/main.tf" 'resource "aws_vpc_endpoint" "s3"'
assert_contains "$lab_root/modules/nat-instance/main.tf" 'source_dest_check'
assert_contains "$repo_root/infra/aws/foundation/lab-compute.tf" '"ec2:ModifyInstanceAttribute"'
assert_contains "$lab_root/modules/service-ec2/main.tf" 'http_put_response_hop_limit = 2'
assert_contains "$lab_root/modules/service-ec2/main.tf" 'delete_on_termination'
assert_contains "$lab_root/modules/service-ec2/main.tf" 'credit_specification'
assert_contains "$lab_root/modules/service-ec2/main.tf" 'cpu_credits = "unlimited"'
assert_not_contains "$lab_root/modules/app-asg" 'mixed_instances_policy|instance_requirements'
for instance_module in nat-instance service-ec2 load-generator; do
  instance_source="$lab_root/modules/$instance_module/main.tf"
  assert_contains "$instance_source" 'volume_tags = merge(var.tags'
  root_block_source=$(sed -n '/^[[:space:]]*root_block_device {$/,/^[[:space:]]*}$/p' "$instance_source")
  if grep -Eq '^[[:space:]]+tags[[:space:]]*=' <<<"$root_block_source"; then
    fail "$instance_module must tag its root volume in RunInstances instead of a post-create CreateTags call"
  fi
done
assert_contains "$lab_root/security.tf" 'module "security"'
assert_contains "$lab_root/modules/security/main.tf" 'tags              = var.tags'
assert_contains "$lab_root/iam.tf" 'resource "aws_iam_role_policy" "probe_egress"'
assert_contains "$lab_root/iam.tf" 'Action   = "s3:GetBucketLocation"'
assert_contains "$lab_root/private-dns.tf" 'redis-general.lab.airbob.internal'
assert_contains "$lab_root/private-dns.tf" 'redis-cache.lab.airbob.internal'
assert_contains "$lab_root/private-dns.tf" 'resource "aws_route53_zone_association" "private"'
if grep -Fq 'resource "aws_route53_zone" "private"' "$lab_root/private-dns.tf"; then
  fail "the ephemeral lab must not own the protected private hosted zone"
fi
assert_contains "$lab_root/templates/start-service.sh.tftpl" 'BGREWRITEAOF'
assert_contains "$lab_root/templates/start-service.sh.tftpl" 'vm.max_map_count=1048576'
assert_contains "$lab_root/templates/start-service.sh.tftpl" 'node-exporter-monitoring'

redis_services=$(grep -Ec '^[[:space:]]{2}redis(-cache)?:$' "$repo_root/infra/aws/bundles/redis/compose.yml")
redis_exporters=$(grep -Ec '^[[:space:]]{2}redis-exporter-(general|cache):$' "$repo_root/infra/aws/bundles/redis/compose.yml")
[[ "$redis_services" -eq 2 && "$redis_exporters" -eq 2 ]] \
  || fail "Redis bundle must remain exactly two Redis and two exporter services"

assert_not_contains "$lab_root" 'aws_nat_gateway'
assert_not_contains "$lab_root" 'remote-exec|connection[[:space:]]*\{'
assert_not_contains "$lab_root" '0\.0\.0\.0/0[^\n]*(22|8083|3000|9090)'
if grep -R -n -E 'associate_public_ip_address[[:space:]]*=[[:space:]]*true' \
  "$lab_root" --include='*.tf' \
  | grep -Fv '/modules/load-generator/main.tf:' >/dev/null; then
  fail "only the dedicated load generator may use an ephemeral public IPv4"
fi

terraform -chdir="$lab_root" fmt -check -recursive
terraform -chdir="$lab_root" init -backend=false -input=false -lockfile=readonly
terraform -chdir="$lab_root" validate
terraform -chdir="$lab_root" test

printf '%s\n' 'phase 2 lab contract tests passed'
