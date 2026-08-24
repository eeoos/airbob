#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
foundation_root="$repo_root/infra/aws/foundation"
toolchain_contract="$repo_root/infra/aws/toolchain.env"
backend_helper="$repo_root/infra/aws/scripts/prepare-terraform-backend.sh"
bootstrap_script="$repo_root/infra/aws/scripts/bootstrap-state.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-foundation-test.XXXXXX")

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

assert_file_not_contains() {
  local file=$1
  local rejected=$2
  if grep -Eq -- "$rejected" "$file"; then
    fail "$file contains forbidden pattern: $rejected"
  fi
}

command -v terraform >/dev/null 2>&1 || fail "Terraform is required"
[[ -f "$toolchain_contract" && ! -L "$toolchain_contract" ]] \
  || fail "Terraform/AWS toolchain contract is missing or unsafe"
[[ -d "$foundation_root" && ! -L "$foundation_root" ]] \
  || fail "foundation root is missing or unsafe"

for required_file in \
  backend.tf versions.tf providers.tf variables.tf locals.tf data.tf \
  storage.tf ecr.tf imports.tf oidc.tf iam.tf lease.tf dns.tf dns-controller.tf contracts.tf \
  expiry-observer.tf lab-compute.tf outputs.tf README.md .terraform.lock.hcl \
  lambda/expiry_observer.py tests/test_expiry_observer.py tests/foundation.tftest.hcl
do
  [[ -f "$foundation_root/$required_file" && ! -L "$foundation_root/$required_file" ]] \
    || fail "foundation/$required_file is missing or unsafe"
done
[[ -x "$backend_helper" && ! -L "$backend_helper" ]] \
  || fail "shared Terraform backend helper is missing or unsafe"

assert_contains "$toolchain_contract" 'AIRBOB_TERRAFORM_VERSION=1.15.5'
assert_contains "$toolchain_contract" 'AIRBOB_AWS_PROVIDER_VERSION=6.55.0'
assert_contains "$toolchain_contract" 'AIRBOB_AWS_ACCOUNT_ID=942632789808'
assert_contains "$toolchain_contract" 'AIRBOB_DATASET_PUBLISHER_PRINCIPAL_ARN=arn:aws:iam::942632789808:user/admin-eeoos'
assert_contains "$toolchain_contract" 'AIRBOB_AWS_REGION=ap-northeast-2'
assert_contains "$toolchain_contract" 'AIRBOB_STATE_KEY_FOUNDATION=airbob/foundation/terraform.tfstate'

assert_contains "$foundation_root/backend.tf" 'backend "s3" {}'
assert_not_contains "$foundation_root/backend.tf" 'backend "local"'
assert_contains "$backend_helper" 'AIRBOB_STATE_KEY_FOUNDATION'
assert_contains "$backend_helper" 'AIRBOB_STATE_KEY_DNS'
assert_contains "$backend_helper" 'AIRBOB_STATE_KEY_LAB'
assert_contains "$backend_helper" 'use_lockfile = true'
assert_contains "$backend_helper" '"$bootstrap_preflight" status'
assert_not_contains "$backend_helper" 'dynamodb_table'
assert_contains "$foundation_root/iam.tf" 'resource "aws_iam_policy" "lab_operator" {'
assert_contains "$foundation_root/iam.tf" 'resource "aws_iam_role_policy_attachment" "lab_operator" {'
assert_contains "$foundation_root/iam.tf" 'policy_arn = aws_iam_policy.lab_operator[each.key].arn'
assert_contains "$foundation_root/iam.tf" 'resource "aws_iam_role" "dataset_publisher" {'
assert_contains "$foundation_root/iam.tf" 'resource "aws_iam_role_policy" "dataset_publisher" {'
assert_contains "$foundation_root/data.tf" 'data "aws_s3_objects" "dataset_snapshot_seal_plan" {'
assert_contains "$foundation_root/data.tf" 'data "aws_s3_objects" "dataset_snapshot_seal_apply" {'
assert_contains "$foundation_root/data.tf" 'apply_nonce = timestamp()'
assert_contains "$foundation_root/data.tf" 'postcondition {'
assert_contains "$foundation_root/data.tf" 'self.keys'
assert_contains "$foundation_root/data.tf" 'A sealed dataset release can never regain Elasticsearch snapshot writer permissions.'
assert_contains "$foundation_root/data.tf" 'A dataset release sealed during apply can never regain Elasticsearch snapshot writer permissions.'
[[ "$(grep -c '^[[:space:]]*postcondition {$' "$foundation_root/data.tf")" == 2 ]] \
  || fail "both plan-time and apply-time snapshot seal lookups must fail closed"
assert_contains "$foundation_root/iam.tf" 'depends_on = [data.aws_s3_objects.dataset_snapshot_seal_apply]'
assert_contains "$foundation_root/variables.tf" 'arn:aws:iam::942632789808:user/admin-eeoos'
assert_contains "$foundation_root/iam.tf" '"s3:if-none-match"'
assert_contains "$foundation_root/iam.tf" '"s3:x-amz-acl"'
assert_contains "$foundation_root/iam.tf" 'bucket-owner-full-control'
assert_contains "$foundation_root/storage.tf" 'DenyDatasetReleaseOverwrite'
assert_contains "$foundation_root/storage.tf" 'DenyDatasetReleaseDeletion'
assert_contains "$foundation_root/storage.tf" 'DenySnapshotSealOverwrite'
assert_contains "$foundation_root/storage.tf" 'DenySnapshotSealDeletion'
assert_file_not_contains "$foundation_root/iam.tf" '^resource "aws_iam_role_policy" "lab_operator"'
assert_file_not_contains "$foundation_root/lab-compute.tf" '^resource "aws_iam_role_policy" "lab_(compute|data_compute|app_compute)"'
assert_file_not_contains "$foundation_root/expiry-observer.tf" 'reserved_concurrent_executions'

resource_count=$(grep -hE '^resource "aws_[^"]+" "[^"]+" \{' "$foundation_root"/*.tf | wc -l | tr -d ' ')
prevent_destroy_count=$(grep -hE '^[[:space:]]+prevent_destroy = true$' "$foundation_root"/*.tf | wc -l | tr -d ' ')
[[ "$resource_count" -gt 0 ]] || fail "foundation must declare persistent AWS resources"
[[ "$resource_count" -eq $((prevent_destroy_count + 1)) ]] \
  || fail "every foundation AWS resource except the replaceable alert subscription must have prevent_destroy"
subscription_block=$(sed -n '/^resource "aws_sns_topic_subscription" "expiry_alert_email" {$/,/^}$/p' "$foundation_root/expiry-observer.tf")
[[ -n "$subscription_block" ]] || fail "replaceable expiry alert subscription is missing"
if printf '%s\n' "$subscription_block" | grep -Fq 'prevent_destroy'; then
  fail "the expiry alert subscription must remain replaceable for email rotation"
fi

assert_not_contains "$foundation_root" 'force_destroy[[:space:]]*=[[:space:]]*true'
assert_not_contains "$foundation_root" 'terraform_remote_state'
assert_not_contains "$foundation_root" 'aws_(instance|rds|lb|autoscaling|codebuild)'
assert_not_contains "$foundation_root" 'AKIA[0-9A-Z]{16}'
assert_not_contains "$foundation_root" 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY'
assert_not_contains "$foundation_root" 'AWS_SECRET_ACCESS_KEY[[:space:]]*='
assert_not_contains "$foundation_root" 'thumbprint_list'
assert_not_contains "$foundation_root/lease.tf" 'ttl[[:space:]]*\{'
assert_contains "$foundation_root/lease.tf" 'deletion_protection_enabled = true'
assert_contains "$foundation_root/imports.tf" 'to = aws_ecr_repository.application'
assert_contains "$foundation_root/imports.tf" 'id = "airbob-repo"'
assert_contains "$foundation_root/contracts.tf" '/airbob/performance-lab/foundation/dns-contract'
assert_contains "$foundation_root/contracts.tf" '/airbob/performance-lab/foundation/lab-contract'
assert_contains "$foundation_root/contracts.tf" 'schemaVersion'
assert_contains "$foundation_root/dns.tf" 'resource "aws_vpc" "private_dns_anchor"'
assert_contains "$foundation_root/dns.tf" 'resource "aws_route53_zone" "private"'
assert_contains "$foundation_root/dns.tf" 'name          = "lab.airbob.internal"'
assert_contains "$foundation_root/dns.tf" 'cidr_block           = "10.255.255.240/28"'
grep -Eq 'Stack[[:space:]]*=[[:space:]]*"foundation"' "$foundation_root/locals.tf" \
  || fail "foundation resources must remain outside the Stack=lab observer scope"
assert_file_not_contains "$foundation_root/iam.tf" 'iam:(PutRolePolicy|DeleteRolePolicy|UpdateAssumeRolePolicy|CreateRole|CreateOpenIDConnectProvider)'
assert_contains "$foundation_root/lab-compute.tf" 'resource "aws_iam_policy" "lab_host_boundary"'
assert_contains "$foundation_root/lab-compute.tf" '"iam:PermissionsBoundary"'
assert_contains "$foundation_root/lab-compute.tf" 'role/airbob-lab-host-*'
assert_file_not_contains "$foundation_root/lab-compute.tf" 'role/airbob-(foundation-admin|lab-operator|image-publisher|dataset-publisher)'
assert_file_not_contains "$foundation_root/lab-compute.tf" 'iam:(UpdateAssumeRolePolicy|PutRolePermissionsBoundary|DeleteRolePermissionsBoundary)'
assert_file_not_contains "$foundation_root/lab-compute.tf" 'route53:ChangeResourceRecordSetsNormalizedRecordNames.*api\.airbob\.cloud'
assert_file_not_contains "$foundation_root/lab-compute.tf" 'route53:(CreateHostedZone|DeleteHostedZone|ChangeTagsForResource)'
assert_file_not_contains "$foundation_root/iam.tf" 'Action[[:space:]]*=[[:space:]]*"(s3|ecr|dynamodb|route53|acm|ssm):\*"'
assert_file_not_contains "$foundation_root/iam.tf" 'github_oidc_subject_mode'
assert_not_contains "$foundation_root" 'token\.actions\.githubusercontent\.com:(repository|repository_id|repository_owner_id|ref|workflow|environment)'
grep -Eq 'CLEANUP_ENABLED[[:space:]]*=[[:space:]]*"false"' "$foundation_root/expiry-observer.tf" \
  || fail "expiry observer cleanup guard is missing"
assert_contains "$foundation_root/expiry-observer.tf" 'schedule_expression = "rate(15 minutes)"'
assert_contains "$foundation_root/expiry-observer.tf" 'resource "aws_kms_key" "expiry_alerts"'
assert_contains "$foundation_root/expiry-observer.tf" 'state               = local.expiry_alert_delivery_ready ? "ENABLED" : "DISABLED"'
assert_contains "$foundation_root/expiry-observer.tf" 'try(!aws_sns_topic_subscription.expiry_alert_email[0].pending_confirmation, false)'
[[ "$(grep -Fc 'actions_enabled     = local.expiry_alert_delivery_ready' "$foundation_root/expiry-observer.tf")" -eq 2 ]] \
  || fail "both expiry alarm families must use the shared delivery-ready gate"
assert_file_not_contains "$foundation_root/expiry-observer.tf" 'alias/aws/sns'
assert_file_not_contains "$foundation_root/lambda/expiry_observer.py" 'delete_|terminate_|stop_|change_resource|start_build|put_item|update_item'

lab_policy_source=$(sed -n '/^  lab_operator_policy = jsonencode({$/,/^  image_publisher_policy = jsonencode({$/p' "$foundation_root/iam.tf")
[[ -n "$lab_policy_source" ]] || fail "lab operator policy source is missing"
if printf '%s\n' "$lab_policy_source" | grep -Eq 'local\.state_keys\.foundation|airbob/foundation/terraform\.tfstate|FoundationState'; then
  fail "lab operator policy must not read the foundation Terraform state"
fi

fixture_root="$temp_dir/repo"
fake_bin="$temp_dir/bin"
mkdir -p \
  "$fixture_root/infra/aws/scripts" \
  "$fixture_root/infra/aws/bootstrap" \
  "$fixture_root/infra/aws/foundation" \
  "$fixture_root/infra/aws/dns" \
  "$fixture_root/infra/aws/lab" \
  "$fake_bin"
cp "$backend_helper" "$fixture_root/infra/aws/scripts/prepare-terraform-backend.sh"
cp "$bootstrap_script" "$fixture_root/infra/aws/scripts/bootstrap-state.sh"
cp "$toolchain_contract" "$fixture_root/infra/aws/toolchain.env"
cp "$repo_root/infra/aws/bootstrap/versions.tf" "$fixture_root/infra/aws/bootstrap/versions.tf"
cp "$repo_root/infra/aws/bootstrap/.terraform.lock.hcl" "$fixture_root/infra/aws/bootstrap/.terraform.lock.hcl"
for root_name in foundation dns lab; do
  printf '%s\n' 'terraform {' '  backend "s3" {}' '}' > "$fixture_root/infra/aws/$root_name/backend.tf"
done

cat > "$fake_bin/terraform" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == version ]]; then
  printf 'Terraform v%s\n' "${FAKE_TERRAFORM_VERSION:-1.15.5}"
  exit 0
fi
exit 0
EOF
chmod 700 "$fake_bin/terraform"

cat > "$fake_bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >> "${FAKE_CALL_LOG:?}"
if [[ "${1:-}" == --version ]]; then
  printf 'aws-cli/%s Python/3.14.5 Darwin/25.3.0 source/arm64\n' "${FAKE_AWS_CLI_VERSION:-2.34.64}"
  exit 0
fi
case " $* " in
  *' sts get-caller-identity '*) printf '%s\n' "${FAKE_AWS_ACCOUNT:-942632789808}" ;;
  *' s3api get-bucket-location '*) printf '%s\n' 'ap-northeast-2' ;;
  *' s3api get-bucket-versioning '*) printf '%s\n' 'Enabled' ;;
  *' s3api get-public-access-block '*) printf '%s\n' 'True True True True' ;;
  *' s3api get-bucket-encryption '*) printf '%s\n' 'AES256' ;;
  *' s3api get-bucket-ownership-controls '*) printf '%s\n' 'BucketOwnerEnforced' ;;
  *' s3api get-bucket-policy '*)
    printf '%s\n' '{"Sid":"DenyInsecureTransport","Effect":"Deny","Action":"s3:*","Resource":["arn:aws:s3:::airbob-performance-lab-tfstate-942632789808","arn:aws:s3:::airbob-performance-lab-tfstate-942632789808/*"],"Condition":{"Bool":{"aws:SecureTransport":"false"}}}'
    ;;
  *' s3api get-bucket-tagging '*)
    printf '%s\n' \
      'Project airbob' \
      'Environment performance-lab' \
      'ManagedBy terraform' \
      'Persistence persistent'
    ;;
esac
EOF
chmod 700 "$fake_bin/aws"

run_backend_helper() {
  local root_name=$1
  local output=$2
  shift 2
  env \
    PATH="$fake_bin:/usr/bin:/bin" \
    AWS_REGION=ap-northeast-2 \
    AWS_SECRET_ACCESS_KEY=hunter2 \
    FAKE_CALL_LOG="$temp_dir/backend-calls.log" \
    "$@" \
    "$fixture_root/infra/aws/scripts/prepare-terraform-backend.sh" "$root_name" \
    >"$output" 2>&1
  assert_file_not_contains "$output" 'hunter2'
}

run_backend_helper_failure() {
  local root_name=$1
  local output=$2
  shift 2
  if env \
    PATH="$fake_bin:/usr/bin:/bin" \
    AWS_REGION=ap-northeast-2 \
    AWS_SECRET_ACCESS_KEY=hunter2 \
    FAKE_CALL_LOG="$temp_dir/backend-calls.log" \
    "$@" \
    "$fixture_root/infra/aws/scripts/prepare-terraform-backend.sh" "$root_name" \
    >"$output" 2>&1
  then
    fail "backend helper accepted an unsafe preflight"
  fi
  assert_file_not_contains "$output" 'hunter2'
}

: > "$temp_dir/backend-calls.log"
for root_name in foundation dns lab; do
  backend_output="$temp_dir/backend-$root_name.log"
  run_backend_helper "$root_name" "$backend_output"
  assert_contains "$backend_output" 'backend_config='
  assert_contains "$fixture_root/infra/aws/$root_name/backend.generated.hcl" 'bucket       = "airbob-performance-lab-tfstate-942632789808"'
  assert_contains "$fixture_root/infra/aws/$root_name/backend.generated.hcl" "key          = \"airbob/$root_name/terraform.tfstate\""
  assert_contains "$fixture_root/infra/aws/$root_name/backend.generated.hcl" 'use_lockfile = true'
done
grep -Fq 'aws sts get-caller-identity' "$temp_dir/backend-calls.log" \
  || fail "backend helper did not execute the bootstrap identity preflight"

rm -f "$fixture_root/infra/aws/foundation/backend.generated.hcl"
wrong_account_output="$temp_dir/backend-wrong-account.log"
run_backend_helper_failure foundation "$wrong_account_output" FAKE_AWS_ACCOUNT=111111111111
assert_contains "$wrong_account_output" 'bootstrap state preflight failed'
[[ ! -e "$fixture_root/infra/aws/foundation/backend.generated.hcl" ]] \
  || fail "backend helper published config after a failed identity preflight"

terraform_version_output=$(terraform version 2>&1) || fail "Terraform version check failed"
terraform_version_line=${terraform_version_output%%$'\n'*}
terraform_version=${terraform_version_line#Terraform v}
[[ "$terraform_version" == "1.15.5" ]] \
  || fail "Terraform version mismatch: expected 1.15.5"

terraform -chdir="$foundation_root" fmt -check -recursive
terraform -chdir="$foundation_root" init -backend=false -input=false -lockfile=readonly
terraform -chdir="$foundation_root" validate
terraform -chdir="$foundation_root" test
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest "$foundation_root/tests/test_expiry_observer.py"

printf '%s\n' 'foundation contract tests passed'
