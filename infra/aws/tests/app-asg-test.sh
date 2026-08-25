#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
lab_root="$repo_root/infra/aws/lab"

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

assert_contains() {
  local file=$1
  local expected=$2
  grep -Fq -- "$expected" "$file" || fail "$file does not contain: $expected"
}

assert_matches() {
  local file=$1
  local expected=$2
  grep -Eq -- "$expected" "$file" || fail "$file does not match: $expected"
}

required_files=(
  app.tf monitoring.tf load-generator.tf
  templates/start-app.sh.tftpl templates/load-generator-user-data.sh.tftpl
  modules/alb/main.tf modules/alb/variables.tf modules/alb/outputs.tf
  modules/app-asg/main.tf modules/app-asg/variables.tf modules/app-asg/outputs.tf
  modules/load-generator/main.tf modules/load-generator/variables.tf modules/load-generator/outputs.tf
)

for required_file in "${required_files[@]}"; do
  [[ -f "$lab_root/$required_file" && ! -L "$lab_root/$required_file" ]] \
    || fail "lab/$required_file is missing or unsafe"
done

embedded_command_bytes=$((
  $(wc -c <"$lab_root/templates/start-app.sh.tftpl") +
  $(wc -c <"$repo_root/infra/aws/scripts/verify-app-runtime-env.sh")
))
((embedded_command_bytes < 50000)) \
  || fail "the embedded SSM app bootstrap must retain headroom below the 64 KiB document limit"

assert_contains "$lab_root/variables.tf" 'variable "app_enabled"'
assert_contains "$lab_root/variables.tf" 'variable "request_count_per_target_per_minute"'
assert_contains "$lab_root/variables.tf" 'contains(["performance", "scaling"], var.mode)'
assert_matches "$lab_root/app.tf" 'instance_type[[:space:]]*=[[:space:]]*"c6i.large"'
assert_matches "$lab_root/app.tf" 'app_enabled[[:space:]]*=[[:space:]]*var.app_enabled'
assert_contains "$lab_root/modules/app-asg/main.tf" 'http_put_response_hop_limit = 2'
assert_matches "$lab_root/modules/app-asg/main.tf" 'default_instance_warmup[[:space:]]*=[[:space:]]*180'
assert_matches "$lab_root/modules/app-asg/main.tf" 'version[[:space:]]*=[[:space:]]*tostring\(aws_launch_template.app.latest_version\)'
assert_matches "$lab_root/modules/app-asg/main.tf" 'auto_rollback[[:space:]]*=[[:space:]]*true'
assert_matches "$lab_root/modules/app-asg/main.tf" 'predefined_metric_type[[:space:]]*=[[:space:]]*"ALBRequestCountPerTarget"'
assert_matches "$lab_root/modules/app-asg/main.tf" 'predefined_metric_type[[:space:]]*=[[:space:]]*"ASGAverageCPUUtilization"'
assert_contains "$lab_root/modules/app-asg/main.tf" 'var.mode == "scaling" && length(var.subnet_ids) == 2'
assert_contains "$lab_root/outputs.tf" 'app_availability_zones'
assert_matches "$lab_root/modules/alb/main.tf" 'port[[:space:]]*=[[:space:]]*443'
assert_matches "$lab_root/modules/alb/main.tf" 'protocol[[:space:]]*=[[:space:]]*"HTTPS"'
assert_matches "$lab_root/modules/alb/main.tf" 'enabled[[:space:]]*=[[:space:]]*false'
assert_contains "$lab_root/modules/load-generator/main.tf" 'associate_public_ip_address = true'
assert_contains "$lab_root/modules/load-generator/main.tf" 'instance_type               = "c6i.xlarge"'
assert_contains "$lab_root/templates/start-app.sh.tftpl" 'verify-app-runtime-env.sh'
assert_contains "$lab_root/templates/start-app.sh.tftpl" 'secretsmanager get-secret-value'
assert_contains "$lab_root/templates/start-app.sh.tftpl" 'docker compose'
assert_contains "$lab_root/monitoring.tf" 'AWS/ApplicationELB'
assert_contains "$lab_root/monitoring.tf" 'AWS/AutoScaling'
assert_contains "$lab_root/monitoring.tf" 'CPUCreditBalance'
assert_contains "$lab_root/monitoring.tf" 'CPUSurplusCreditBalance'
assert_contains "$lab_root/monitoring.tf" 'CPUSurplusCreditsCharged'

if grep -Eq 'target[[:space:]]*=[[:space:]]*"loadgen"' \
  "$lab_root/modules/security/main.tf"; then
  fail "the load generator security group must have no inbound rule"
fi

if grep -R -F 'associate_public_ip_address = true' \
  "$lab_root" --include='*.tf' \
  | grep -Fv '/modules/load-generator/main.tf:' >/dev/null; then
  fail "only the dedicated no-ingress load generator may receive an ephemeral public IPv4"
fi

if grep -R -Eq 'remote-exec|connection[[:space:]]*\{' \
  "$lab_root" --include='*.tf' --include='*.tftpl'; then
  fail "Phase 4 must use SSM bootstrap and must not add remote-exec"
fi

if grep -R -Eq 'AKIA[0-9A-Z]{16}|AWS_ACCESS_KEY_ID[[:space:]]*=|AWS_SECRET_ACCESS_KEY[[:space:]]*=' \
  "$lab_root" --include='*.tf' --include='*.tftpl'; then
  fail "Phase 4 must not put plaintext credentials in Terraform or user-data"
fi

printf '%s\n' 'app ASG static contract tests passed'
