#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
controller="$repo_root/infra/aws/scripts/aws-dns-controller.sh"
dns_root="$repo_root/infra/aws/dns"

fail() { printf '%s\n' "$1" >&2; exit 1; }
assert_contains() { grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"; }
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-dns-test.XXXXXX")
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

# Execute a forced remove with hermetic dependencies. FORCE_DOWN may skip the
# public DNS drain wait, but it must never skip the direct OCI origin health
# probe that protects teardown safety.
fixture_repo="$temp_dir/repo"
fixture_scripts="$fixture_repo/infra/aws/scripts"
mkdir -p "$fixture_scripts" "$fixture_repo/infra/aws/dns" "$temp_dir/bin"
cp "$controller" "$fixture_scripts/aws-dns-controller.sh"
cp "$repo_root/infra/aws/toolchain.env" "$fixture_repo/infra/aws/toolchain.env"
cat > "$fixture_scripts/orchestration-lease.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'lease %s\n' "$*" >> "${FAKE_DNS_LOG:?}"
EOF
cat > "$fixture_scripts/prepare-terraform-backend.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'backend %s\n' "$*" >> "${FAKE_DNS_LOG:?}"
EOF
cat > "$temp_dir/bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >> "${FAKE_DNS_LOG:?}"
case " $* " in
  *' sts get-caller-identity '*)
    printf '%s\n' 'arn:aws:sts::942632789808:assumed-role/airbob-dns-controller/fixture'
    ;;
  *) exit 70 ;;
esac
EOF
cat > "$temp_dir/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'curl %s\n' "$*" >> "${FAKE_DNS_LOG:?}"
[[ " $* " == *' --resolve api.airbob.cloud:443:203.0.113.10 '* \
  && "$*" == *'https://api.airbob.cloud/health'* ]] || exit 71
printf '%s\n' healthy
EOF
cat > "$temp_dir/bin/dig" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'dig %s\n' "$*" >> "${FAKE_DNS_LOG:?}"
exit 72
EOF
cat > "$temp_dir/bin/terraform" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'terraform %s\n' "$*" >> "${FAKE_DNS_LOG:?}"
EOF
chmod 700 "$fixture_scripts"/*.sh "$temp_dir/bin"/*
: > "$temp_dir/forced-remove.log"
env PATH="$temp_dir/bin:$PATH" AWS_REGION=ap-northeast-2 \
  FAKE_DNS_LOG="$temp_dir/forced-remove.log" \
  AWS_DNS_CONTROLLER_ROLE_ARN=arn:aws:iam::942632789808:role/airbob-dns-controller \
  OCI_ORIGIN_IPV4=203.0.113.10 \
  AWS_ALB_ARN=arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-fixture/0123456789abcdef \
  AWS_ALB_DNS_NAME=airbob-fixture.ap-northeast-2.elb.amazonaws.com \
  ALB_FENCING_TOKEN=41 LEASE_TABLE=fixture-lease LEASE_LOCK_ID=fixture-lock \
  LEASE_OWNER=fixture/owner FENCING_TOKEN=42 RUN_ID=lab-forced-remove LEASE_COMMAND=down \
  FORCE_DOWN=true "$fixture_scripts/aws-dns-controller.sh" remove oci >/dev/null
grep -Fq 'curl --fail --silent --show-error --max-time 10 --resolve api.airbob.cloud:443:203.0.113.10 https://api.airbob.cloud/health' \
  "$temp_dir/forced-remove.log" \
  || fail "forced DNS remove skipped the direct OCI origin health probe"
[[ "$(grep -c '^curl ' "$temp_dir/forced-remove.log")" == 1 ]] \
  || fail "forced DNS remove ran an unexpected public or AWS health probe"
if grep -Eq '^dig ' "$temp_dir/forced-remove.log"; then
  fail "forced DNS remove unexpectedly entered the DNS drain wait"
fi
probe_line=$(grep -n -m1 '^curl ' "$temp_dir/forced-remove.log" | cut -d: -f1)
mutation_line=$(grep -n -m1 '^terraform .* plan ' "$temp_dir/forced-remove.log" | cut -d: -f1)
[[ -n "$probe_line" && -n "$mutation_line" && "$probe_line" -lt "$mutation_line" ]] \
  || fail "forced DNS remove did not prove OCI health before DNS mutation"

printf '%s\n' 'AWS DNS cutover contract tests passed'
