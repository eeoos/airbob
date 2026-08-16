#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
source_script="$repo_root/infra/aws/scripts/bootstrap-state.sh"
toolchain_contract="$repo_root/infra/aws/toolchain.env"
bootstrap_root="$repo_root/infra/aws/bootstrap"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-bootstrap-state-test.XXXXXX")

cleanup() {
  status=$?
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
  file=$1
  expected=$2
  grep -Fq -- "$expected" "$file" || fail "$file does not contain: $expected"
}

assert_not_contains() {
  file=$1
  rejected=$2
  if grep -Fq -- "$rejected" "$file"; then
    fail "$file unexpectedly contains: $rejected"
  fi
}

run_expect_failure() {
  description=$1
  output=$2
  shift 2
  if "$@" >"$output" 2>&1; then
    fail "expected $description to fail"
  fi
  assert_not_contains "$output" 'hunter2'
}

[[ -f "$toolchain_contract" ]] || fail "Terraform/AWS toolchain contract is missing"
[[ -x "$source_script" ]] || fail "bootstrap state script is missing or not executable"
for required_file in versions.tf variables.tf main.tf outputs.tf README.md; do
  [[ -f "$bootstrap_root/$required_file" ]] || fail "bootstrap/$required_file is missing"
done
[[ -f "$bootstrap_root/.terraform.lock.hcl" ]] || fail "bootstrap provider lock file is missing"

assert_contains "$toolchain_contract" 'AIRBOB_TERRAFORM_VERSION=1.15.5'
assert_contains "$toolchain_contract" 'AIRBOB_AWS_CLI_VERSION=2.34.64'
assert_contains "$toolchain_contract" 'AIRBOB_AWS_PROVIDER_VERSION=6.55.0'
assert_contains "$toolchain_contract" 'AIRBOB_AWS_ACCOUNT_ID=942632789808'
assert_contains "$toolchain_contract" 'AIRBOB_AWS_REGION=ap-northeast-2'
assert_contains "$toolchain_contract" 'AIRBOB_STATE_KEY_BOOTSTRAP=airbob/bootstrap/terraform.tfstate'
assert_contains "$toolchain_contract" 'AIRBOB_STATE_KEY_FOUNDATION=airbob/foundation/terraform.tfstate'
assert_contains "$toolchain_contract" 'AIRBOB_STATE_KEY_DNS=airbob/dns/terraform.tfstate'
assert_contains "$toolchain_contract" 'AIRBOB_STATE_KEY_LAB=airbob/lab/terraform.tfstate'

state_keys=$(awk -F= '/^AIRBOB_STATE_KEY_/ { print $2 }' "$toolchain_contract")
[[ "$(printf '%s\n' "$state_keys" | sed '/^$/d' | wc -l | tr -d ' ')" -eq 4 ]] \
  || fail "toolchain contract must declare exactly four state keys"
[[ "$(printf '%s\n' "$state_keys" | sed '/^$/d' | LC_ALL=C sort -u | wc -l | tr -d ' ')" -eq 4 ]] \
  || fail "bootstrap/foundation/dns/lab state keys must be distinct"

assert_not_contains "$bootstrap_root/versions.tf" 'backend "s3"'
assert_not_contains "$bootstrap_root/versions.tf" 'backend "local"'
assert_not_contains "$bootstrap_root/main.tf" 'dynamodb'
assert_contains "$bootstrap_root/main.tf" 'prevent_destroy = true'
assert_contains "$bootstrap_root/main.tf" 'object_ownership = "BucketOwnerEnforced"'
assert_contains "$bootstrap_root/main.tf" 'sse_algorithm = "AES256"'
assert_contains "$bootstrap_root/main.tf" 'aws:SecureTransport'
for setting in block_public_acls block_public_policy ignore_public_acls restrict_public_buckets; do
  grep -Eq "^[[:space:]]*$setting[[:space:]]*=[[:space:]]*true[[:space:]]*$" "$bootstrap_root/main.tf" \
    || fail "$bootstrap_root/main.tf does not enable $setting"
done
for tag_name in Project Environment ManagedBy Persistence; do
  assert_contains "$bootstrap_root/main.tf" "$tag_name"
done

git -C "$repo_root" check-ignore -q infra/aws/bootstrap/terraform.tfstate \
  || fail "Terraform state is not ignored"
git -C "$repo_root" check-ignore -q infra/aws/bootstrap/example.tfplan \
  || fail "Terraform plan files are not ignored"
git -C "$repo_root" check-ignore -q infra/aws/bootstrap/zz_backend.generated.tf \
  || fail "generated backend block is not ignored"
git -C "$repo_root" check-ignore -q infra/aws/bootstrap/backend.generated.hcl \
  || fail "generated backend config is not ignored"
if git -C "$repo_root" check-ignore -q infra/aws/bootstrap/.terraform.lock.hcl; then
  fail "provider lock file must remain tracked"
fi

fixture_root="$temp_dir/repo"
mkdir -p "$fixture_root/infra/aws/scripts" "$fixture_root/infra/aws/bootstrap"
cp "$source_script" "$fixture_root/infra/aws/scripts/bootstrap-state.sh"
cp "$toolchain_contract" "$fixture_root/infra/aws/toolchain.env"
cp "$bootstrap_root"/*.tf "$fixture_root/infra/aws/bootstrap/"
cp "$bootstrap_root/.terraform.lock.hcl" "$fixture_root/infra/aws/bootstrap/.terraform.lock.hcl"

fake_bin="$temp_dir/bin"
mkdir "$fake_bin"

cat > "$fake_bin/terraform" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'terraform %s\n' "$*" >> "${FAKE_CALL_LOG:?}"
if [[ "${1:-}" == version ]]; then
  printf 'Terraform v%s\n' "${FAKE_TERRAFORM_VERSION:-1.15.5}"
  exit 0
fi
if [[ " $* " == *' apply '* ]]; then
  for argument in "$@"; do
    case "$argument" in
      -chdir=*)
        touch "${argument#-chdir=}/terraform.tfstate"
        ;;
    esac
  done
fi
if [[ " $* " == *' plan '* && "${FAKE_TERRAFORM_PLAN_FAIL:-0}" == 1 ]]; then
  exit 1
fi
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
  *' sts get-caller-identity '*)
    printf '%s\n' "${FAKE_AWS_ACCOUNT:-942632789808}"
    ;;
  *' s3api get-bucket-location '*)
    printf '%s\n' "${FAKE_BUCKET_REGION:-ap-northeast-2}"
    ;;
  *' s3api get-bucket-versioning '*)
    printf '%s\n' 'Enabled'
    ;;
  *' s3api get-public-access-block '*)
    printf '%s\n' "${FAKE_PUBLIC_ACCESS:-True True True True}"
    ;;
  *' s3api get-bucket-encryption '*)
    printf '%s\n' 'AES256'
    ;;
  *' s3api get-bucket-ownership-controls '*)
    printf '%s\n' 'BucketOwnerEnforced'
    ;;
  *' s3api get-bucket-policy '*)
    printf '%s\n' "${FAKE_BUCKET_POLICY:-{\"Sid\":\"DenyInsecureTransport\",\"Effect\":\"Deny\",\"Action\":\"s3:*\",\"Resource\":[\"arn:aws:s3:::airbob-performance-lab-tfstate-942632789808\",\"arn:aws:s3:::airbob-performance-lab-tfstate-942632789808/*\"],\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"false\"}}}}"
    ;;
  *' s3api get-bucket-tagging '*)
    if [[ "${FAKE_INVALID_TAGS:-0}" == 1 ]]; then
      printf '%s\n' 'Project something-else'
    else
      printf '%s\n' \
        'Project airbob' \
        'Environment performance-lab' \
        'ManagedBy terraform' \
        'Persistence persistent'
    fi
    ;;
  *' s3api head-object '*)
    [[ "${FAKE_REMOTE_STATE_MISSING:-0}" != 1 ]]
    ;;
  *)
    ;;
esac
EOF
chmod 700 "$fake_bin/aws"

run_script() {
  command_name=$1
  output=$2
  shift 2
  env \
    PATH="$fake_bin:/usr/bin:/bin" \
    AWS_REGION=ap-northeast-2 \
    AWS_SECRET_ACCESS_KEY=hunter2 \
    STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808 \
    FAKE_CALL_LOG="$temp_dir/calls.log" \
    "$@" \
    "$fixture_root/infra/aws/scripts/bootstrap-state.sh" "$command_name" \
    >"$output" 2>&1
  assert_not_contains "$output" 'hunter2'
}

run_fake_failure() {
  local description=$1
  local output=$2
  local command_name=$3
  shift 3
  run_expect_failure "$description" "$output" env \
    PATH="$fake_bin:/usr/bin:/bin" \
    AWS_SECRET_ACCESS_KEY=hunter2 \
    FAKE_CALL_LOG="$temp_dir/calls.log" \
    "$@" \
    "$fixture_root/infra/aws/scripts/bootstrap-state.sh" "$command_name"
}

: > "$temp_dir/calls.log"
missing_bucket_output="$temp_dir/missing-bucket.log"
run_fake_failure 'a missing caller-owned state bucket name' "$missing_bucket_output" status \
  AWS_REGION=ap-northeast-2
assert_contains "$missing_bucket_output" 'STATE_BUCKET_NAME must be provided by the caller'

missing_region_output="$temp_dir/missing-region.log"
run_fake_failure 'a missing caller-owned AWS region' "$missing_region_output" status \
  STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808
assert_contains "$missing_region_output" 'AWS_REGION or AWS_DEFAULT_REGION must be provided by the caller'

wrong_account_output="$temp_dir/wrong-account.log"
run_fake_failure 'a different AWS account' "$wrong_account_output" status \
  AWS_REGION=ap-northeast-2 \
  STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808 \
  FAKE_AWS_ACCOUNT=111111111111
assert_contains "$wrong_account_output" 'AWS account mismatch'

wrong_region_output="$temp_dir/wrong-region.log"
run_fake_failure 'a different AWS region' "$wrong_region_output" status \
  AWS_REGION=us-east-1 \
  STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808
assert_contains "$wrong_region_output" 'AWS region mismatch'

wrong_terraform_output="$temp_dir/wrong-terraform.log"
run_fake_failure 'a different Terraform version' "$wrong_terraform_output" status \
  AWS_REGION=ap-northeast-2 \
  STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808 \
  FAKE_TERRAFORM_VERSION=1.15.4
assert_contains "$wrong_terraform_output" 'Terraform version mismatch'

wrong_cli_output="$temp_dir/wrong-cli.log"
run_fake_failure 'a different AWS CLI version' "$wrong_cli_output" status \
  AWS_REGION=ap-northeast-2 \
  STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808 \
  FAKE_AWS_CLI_VERSION=2.34.63
assert_contains "$wrong_cli_output" 'AWS CLI version mismatch'

wrong_bucket_output="$temp_dir/wrong-bucket.log"
run_fake_failure 'a non-canonical state bucket name' "$wrong_bucket_output" status \
  AWS_REGION=ap-northeast-2 \
  STATE_BUCKET_NAME=some-other-bucket
assert_contains "$wrong_bucket_output" 'state bucket name mismatch'

wrong_bucket_region_output="$temp_dir/wrong-bucket-region.log"
run_fake_failure 'a state bucket in a different region' "$wrong_bucket_region_output" status \
  AWS_REGION=ap-northeast-2 \
  STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808 \
  FAKE_BUCKET_REGION=us-east-1
assert_contains "$wrong_bucket_region_output" 'state bucket region mismatch'

public_access_output="$temp_dir/public-access.log"
run_fake_failure 'an incompletely blocked state bucket' "$public_access_output" status \
  AWS_REGION=ap-northeast-2 \
  STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808 \
  FAKE_PUBLIC_ACCESS='True True True False'
assert_contains "$public_access_output" 'state bucket public access is not fully blocked'

invalid_tags_output="$temp_dir/invalid-tags.log"
run_fake_failure 'an invalid persistence tag contract' "$invalid_tags_output" status \
  AWS_REGION=ap-northeast-2 \
  STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808 \
  FAKE_INVALID_TAGS=1
assert_contains "$invalid_tags_output" 'state bucket tag contract mismatch'

invalid_policy_output="$temp_dir/invalid-policy.log"
run_fake_failure 'a misleading insecure-transport policy' "$invalid_policy_output" status \
  AWS_REGION=ap-northeast-2 \
  STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808 \
  FAKE_BUCKET_POLICY='{\"Sid\":\"Other\",\"Effect\":\"Allow\",\"aws:SecureTransport\":\"true\",\"unrelated\":false}'
assert_contains "$invalid_policy_output" 'state bucket policy does not deny insecure transport'

migrate_output="$temp_dir/migrate.log"
touch "$fixture_root/infra/aws/bootstrap/terraform.tfstate"
run_script migrate "$migrate_output"
assert_contains "$migrate_output" 'bootstrap_state=remote'
assert_contains "$fixture_root/infra/aws/bootstrap/zz_backend.generated.tf" 'backend "s3" {}'
assert_contains "$fixture_root/infra/aws/bootstrap/backend.generated.hcl" 'use_lockfile = true'
assert_contains "$fixture_root/infra/aws/bootstrap/backend.generated.hcl" 'key          = "airbob/bootstrap/terraform.tfstate"'
assert_not_contains "$fixture_root/infra/aws/bootstrap/backend.generated.hcl" 'dynamodb'
assert_contains "$temp_dir/calls.log" 'init -input=false -migrate-state -force-copy'
assert_contains "$temp_dir/calls.log" 'plan -input=false -refresh=false -lock=true -lock-timeout=0s'

rm -f \
  "$fixture_root/infra/aws/bootstrap/terraform.tfstate" \
  "$fixture_root/infra/aws/bootstrap/zz_backend.generated.tf" \
  "$fixture_root/infra/aws/bootstrap/backend.generated.hcl"
: > "$temp_dir/calls.log"
create_output="$temp_dir/create.log"
run_script create "$create_output"
assert_contains "$create_output" 'bootstrap_state=remote'
assert_contains "$temp_dir/calls.log" 'init -backend=false -input=false'
assert_contains "$temp_dir/calls.log" 'apply -input=false -auto-approve'
create_apply_line=$(grep -n 'terraform .* apply ' "$temp_dir/calls.log" | head -n 1 | cut -d: -f1)
create_bucket_verify_line=$(grep -n 'aws s3api head-bucket ' "$temp_dir/calls.log" | head -n 1 | cut -d: -f1)
create_migrate_line=$(grep -n 'terraform .* init -input=false -migrate-state ' "$temp_dir/calls.log" | head -n 1 | cut -d: -f1)
[[ "$create_apply_line" -lt "$create_bucket_verify_line" && "$create_bucket_verify_line" -lt "$create_migrate_line" ]] \
  || fail "create must apply locally, verify the bucket, and only then migrate state"

calls_before_status=$(wc -l < "$temp_dir/calls.log" | tr -d ' ')
status_output="$temp_dir/status.log"
run_script status "$status_output"
assert_contains "$status_output" 'bootstrap_state=remote'
assert_contains "$status_output" 'native_lockfile=configured'
calls_after_status=$(wc -l < "$temp_dir/calls.log" | tr -d ' ')
[[ "$calls_after_status" -gt "$calls_before_status" ]] || fail "status did not inspect AWS state"
status_calls=$(sed -n "$((calls_before_status + 1)),${calls_after_status}p" "$temp_dir/calls.log")
if printf '%s\n' "$status_calls" | grep -Eq 'terraform .* (init|plan|apply)'; then
  fail "read-only status invoked a mutating or locking Terraform command"
fi

lock_failure_output="$temp_dir/lock-failure.log"
run_fake_failure 'an unavailable S3 native lock capability' "$lock_failure_output" migrate \
  AWS_REGION=ap-northeast-2 \
  STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808 \
  FAKE_TERRAFORM_PLAN_FAIL=1
assert_contains "$lock_failure_output" 'S3 native lockfile probe failed'

remote_missing_output="$temp_dir/remote-missing.log"
run_fake_failure 'a missing remote state object' "$remote_missing_output" migrate \
  AWS_REGION=ap-northeast-2 \
  STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808 \
  FAKE_REMOTE_STATE_MISSING=1
assert_contains "$remote_missing_output" 'remote bootstrap state object is missing'

printf '%s\n' 'caller-owned sentinel' > "$fixture_root/infra/aws/bootstrap/backend.generated.hcl"
generated_tamper_output="$temp_dir/generated-tamper.log"
run_fake_failure 'a pre-existing modified generated backend file' "$generated_tamper_output" migrate \
  AWS_REGION=ap-northeast-2 \
  STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808
assert_contains "$generated_tamper_output" 'generated backend bucket is invalid'
[[ "$(cat "$fixture_root/infra/aws/bootstrap/backend.generated.hcl")" == 'caller-owned sentinel' ]] \
  || fail "migrate overwrote a pre-existing modified generated backend file"

printf '%s\n' 'bootstrap state contract tests passed'
