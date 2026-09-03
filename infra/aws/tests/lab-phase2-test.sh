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

sha256_file() {
  local output
  if command -v sha256sum >/dev/null 2>&1; then
    output=$(sha256sum "$1")
  elif command -v shasum >/dev/null 2>&1; then
    output=$(shasum -a 256 "$1")
  else
    fail "no supported SHA-256 command is available"
  fi
  printf '%s' "${output%% *}"
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
nat_lifecycle=$(sed -n '/^[[:space:]]*lifecycle {$/,/^[[:space:]]*}$/p' \
  "$lab_root/modules/nat-instance/main.tf")
grep -Fq 'ignore_changes = [associate_public_ip_address]' <<<"$nat_lifecycle" \
  || fail "the NAT instance must ignore the provider's EIP-backed public-address refresh drift"
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
assert_contains "$lab_root/iam.tf" 'toset(["nat", "probe"])'
assert_contains "$lab_root/private-dns.tf" 'redis-general.lab.airbob.internal'
assert_contains "$lab_root/private-dns.tf" 'redis-cache.lab.airbob.internal'
assert_contains "$lab_root/private-dns.tf" 'resource "aws_route53_zone_association" "private"'
if grep -Fq 'resource "aws_route53_zone" "private"' "$lab_root/private-dns.tf"; then
  fail "the ephemeral lab must not own the protected private hosted zone"
fi
start_service_template="$lab_root/templates/start-service.sh.tftpl"
assert_contains "$start_service_template" 'BGREWRITEAOF'
assert_contains "$start_service_template" 'vm.max_map_count=1048576'
assert_contains "$start_service_template" 'node-exporter-monitoring'
assert_contains "$start_service_template" 'set -Eeuo pipefail'
assert_contains "$start_service_template" 'trap '\''report_failure "$?" "$LINENO"'\'' ERR'
assert_contains "$start_service_template" 'compose pull --quiet > "$compose_bootstrap_log" 2>&1'
assert_contains "$start_service_template" 'compose up --detach --wait --wait-timeout 600 >> "$compose_bootstrap_log" 2>&1'
assert_contains "$start_service_template" 'Recent Docker Compose bootstrap output:'
assert_contains "$start_service_template" 'Container runtime state: name={{.Name}} status={{.State.Status}} exit_code={{.State.ExitCode}} oom_killed={{.State.OOMKilled}} restart_count={{.RestartCount}} memory_limit_bytes={{.HostConfig.Memory}} memory_swap_limit_bytes={{.HostConfig.MemorySwap}}'
assert_contains "$start_service_template" 'monitoring_resource_override=/etc/airbob/monitoring-resource.override.yml'
assert_contains "$start_service_template" 'mem_limit: 512M'
assert_contains "$start_service_template" 'memswap_limit: 512M'
assert_contains "$start_service_template" 'configure_monitoring_resource_override "$monitoring_resource_override"'
assert_contains "$start_service_template" 'verify_monitoring_resource_contract'
assert_contains "$start_service_template" "[[ \"\$(jq -er '.services.grafana.mem_limit' <<<\"\$effective_config\")\" == 536870912 ]]"
assert_contains "$start_service_template" "[[ \"\$(jq -er '.services.grafana.memswap_limit' <<<\"\$effective_config\")\" == 536870912 ]]"
assert_contains "$start_service_template" 'Monitoring resource contract: grafana_memory_limit_bytes=536870912 grafana_memory_swap_limit_bytes=536870912'
assert_contains "$start_service_template" 'Redis container memory: checkpoint=%s container=%s id=%s limit_bytes=%s peak_bytes=%s oom=%s oom_kill=%s docker_oom_killed=%s'
assert_contains "$start_service_template" 'used_memory=%s used_memory_rss=%s mem_fragmentation_ratio=%s mem_fragmentation_bytes=%s allocator_frag_ratio=%s allocator_frag_bytes=%s'
assert_contains "$start_service_template" 'missing-or-nonnumeric'
assert_contains "$start_service_template" 'mem_fragmentation_ratio '\''^[0-9]+([.][0-9]+)?$'\'''
assert_contains "$start_service_template" 'mem_fragmentation_bytes '\''^-?[0-9]+$'\'''
assert_contains "$start_service_template" 'allocator_frag_ratio '\''^[0-9]+([.][0-9]+)?$'\'''
assert_contains "$start_service_template" 'allocator_frag_bytes '\''^-?[0-9]+$'\'''
assert_contains "$start_service_template" 'kernel_log=$(journalctl --dmesg --no-pager)'
assert_contains "$start_service_template" 'redis_log=$(compose logs "$redis_service" 2>&1)'
assert_contains "$start_service_template" 'local retry_seconds=5'
assert_contains "$start_service_template" 'elasticsearch_readiness_deadline=$((SECONDS + 600))'
assert_contains "$start_service_template" 'wait_for_http_5s cluster-health http://127.0.0.1:9200/_cluster/health "$elasticsearch_readiness_deadline"'
assert_contains "$start_service_template" 'wait_for_http_5s exporter-metrics http://127.0.0.1:9114/metrics "$elasticsearch_readiness_deadline"'
if grep -Fq '$2 < 1 || $2 >= 2' "$start_service_template"; then
  fail "Redis fragmentation telemetry must not impose a synthetic pass/fail ratio range"
fi
redis_sysctl_block=$(sed -n '/^if \[\[ "$service" == redis \]\]; then$/,/^fi$/p' \
  "$start_service_template")
grep -Fq "printf '%s\\n' 'vm.overcommit_memory=1'" <<<"$redis_sysctl_block" \
  || fail "the Redis host must persist vm.overcommit_memory=1"
grep -Fq '[[ "$(sysctl -n vm.overcommit_memory)" == 1 ]]' <<<"$redis_sysctl_block" \
  || fail "the Redis host must verify vm.overcommit_memory=1"
[[ "$(grep -Fc 'vm.overcommit_memory' "$start_service_template")" -eq 2 ]] \
  || fail "vm.overcommit_memory may be configured and verified only in the Redis host block"
bash -n "$start_service_template"
"$repo_root/infra/aws/tests/start-service-runtime-test.sh"

rds_module=$(sed -n '/^module "rds" {$/,/^}$/p' "$lab_root/rds.tf")
grep -Fq 'aws_ssm_association.core_services,' <<<"$rds_module" \
  || fail "RDS creation must wait for successful core service associations"
rds_instance_source="$lab_root/modules/rds/main.tf"
if grep -Eq '^[[:space:]]+(iops|storage_throughput)[[:space:]]*=' "$rds_instance_source"; then
  fail "sub-400-GiB MySQL gp3 must use the implicit 3000-IOPS/125-MiBps baseline"
fi
ssm_contract="$lab_root/ssm.tf"
[[ "$(grep -Fc 'timeoutSeconds = "2400"' "$ssm_contract")" -eq 1 ]] \
  || fail "the shared Phase 2 service command must have exactly one 2400-second execution timeout"
[[ "$(grep -Fc 'wait_for_success_timeout_seconds = 2700' "$ssm_contract")" -eq 3 ]] \
  || fail "core, Debezium, and monitoring associations must allow the 2400-second command to report its result"
[[ "$(grep -Fc 'timeoutSeconds = "7200"' "$ssm_contract")" -eq 1 ]] \
  || fail "the data-bootstrap command timeout must remain unchanged"
[[ "$(grep -Fc 'wait_for_success_timeout_seconds = 7200' "$ssm_contract")" -eq 1 ]] \
  || fail "the data-bootstrap association timeout must remain unchanged"
host_user_data_template="$lab_root/templates/host-user-data.sh.tftpl"
assert_contains "$host_user_data_template" 'if ! command -v curl >/dev/null 2>&1; then'
assert_contains "$host_user_data_template" 'dnf install -y curl-minimal'
assert_contains "$host_user_data_template" 'dnf install -y jq tar gzip openssl'
assert_not_contains "$host_user_data_template" 'dnf install -y curl([[:space:]]|$)'
curl_install_line=$(grep -nF 'dnf install -y curl-minimal' "$host_user_data_template" | cut -d: -f1)
curl_guard_line=$(grep -nF 'if ! command -v curl >/dev/null 2>&1; then' "$host_user_data_template" | cut -d: -f1)
curl_guard_end_line=$(awk -v start="$curl_guard_line" 'NR > start && /^fi$/ { print NR; exit }' "$host_user_data_template")
probe_marker_line=$(grep -nF "printf '%s\\n' 'ready' > /var/lib/airbob/probe-ready" "$host_user_data_template" | cut -d: -f1)
probe_exit_line=$(grep -nF '  exit 0' "$host_user_data_template" | cut -d: -f1)
service_tools_line=$(grep -nF 'dnf install -y jq tar gzip openssl' "$host_user_data_template" | cut -d: -f1)
if ! ((curl_guard_line < curl_install_line \
  && curl_install_line < curl_guard_end_line \
  && curl_guard_end_line < probe_marker_line \
  && probe_marker_line < probe_exit_line \
  && probe_exit_line < service_tools_line)); then
  fail "probe bootstrap must conditionally install curl-minimal before publishing readiness and exit before service-only packages"
fi

release_permission_command='find "$release_root" -type d -exec chmod 755 {} +'
[[ "$(grep -Fxc "$release_permission_command" "$host_user_data_template")" -eq 1 ]] \
  || fail "service bundle bootstrap must normalize release directories exactly once"
bundle_extract_line=$(grep -nF 'tar -xzf "$staging/$archive_name" -C "$release_root"' "$host_user_data_template" | cut -d: -f1)
release_permission_line=$(grep -nFx "$release_permission_command" "$host_user_data_template" | cut -d: -f1)
images_env_line=$(grep -nF "cat >/etc/airbob/images.env <<'EOF'" "$host_user_data_template" | cut -d: -f1)
if ! ((bundle_extract_line < release_permission_line && release_permission_line < images_env_line)); then
  fail "release directory permissions must be normalized immediately after verified bundle extraction"
fi

permission_source="$temp_dir/release-permission-source"
permission_release="$temp_dir/release-permission-target"
permission_archive="$temp_dir/release-permission.tar.gz"
mkdir -p "$permission_source/monitoring/grafana/dashboards" "$permission_release"
permission_source_file="$permission_source/monitoring/grafana/dashboards/airbob.json"
permission_release_file="$permission_release/monitoring/grafana/dashboards/airbob.json"
printf '{"fixture":"airbob"}\n' > "$permission_source_file"
find "$permission_source" -type d -exec chmod 755 {} +
chmod 644 "$permission_source_file"
tar -czf "$permission_archive" -C "$permission_source" monitoring/grafana/dashboards/airbob.json
[[ "$(tar -tzf "$permission_archive")" == monitoring/grafana/dashboards/airbob.json ]] \
  || fail "release permission fixture must contain only the dashboard file entry"
(
  umask 077
  tar -pxzf "$permission_archive" -C "$permission_release"
)
[[ -n "$(find "$permission_release" -type d ! -perm 0755 -print -quit)" ]] \
  || fail "umask-077 extraction fixture did not reproduce private release directories"
permission_file_mode=$(LC_ALL=C ls -ld "$permission_release_file" | cut -c1-10)
[[ "$permission_file_mode" == -rw-r--r-- ]] \
  || fail "permission-preserving extraction did not retain the dashboard file's 0644 mode"
permission_source_sha256=$(sha256_file "$permission_source_file")
permission_before_sha256=$(sha256_file "$permission_release_file")
cmp -s "$permission_source_file" "$permission_release_file" \
  || fail "permission-preserving extraction changed the dashboard bytes"
[[ "$permission_before_sha256" == "$permission_source_sha256" ]] \
  || fail "permission-preserving extraction changed the dashboard SHA-256"
release_root="$permission_release" bash -c "$release_permission_command"
if [[ -n "$(find "$permission_release" -type d ! -perm 0755 -print -quit)" ]]; then
  fail "release permission normalization did not make every bundle directory readable and traversable"
fi
permission_file_mode=$(LC_ALL=C ls -ld "$permission_release_file" | cut -c1-10)
[[ "$permission_file_mode" == -rw-r--r-- ]] \
  || fail "release directory normalization changed the dashboard file's 0644 mode"
permission_after_sha256=$(sha256_file "$permission_release_file")
cmp -s "$permission_source_file" "$permission_release_file" \
  || fail "release directory normalization changed the dashboard bytes"
[[ "$permission_after_sha256" == "$permission_source_sha256" ]] \
  || fail "release directory normalization changed the dashboard SHA-256"

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
