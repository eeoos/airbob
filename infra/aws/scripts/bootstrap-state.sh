#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

case "$#:${1-}" in
  1:create|1:migrate|1:status) operation=$1 ;;
  *) fail "usage: bootstrap-state.sh <create|migrate|status>" ;;
esac

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
bootstrap_root="$repo_root/infra/aws/bootstrap"
toolchain_contract="$repo_root/infra/aws/toolchain.env"
terraform_versions="$bootstrap_root/versions.tf"
provider_lock="$bootstrap_root/.terraform.lock.hcl"
generated_backend_block="$bootstrap_root/zz_backend.generated.tf"
generated_backend_config="$bootstrap_root/backend.generated.hcl"
backend_block_temp=''
backend_config_temp=''
lock_probe_plan=''

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  [[ -z "$lock_probe_plan" ]] || rm -f -- "$lock_probe_plan" || true
  [[ -z "$backend_config_temp" ]] || rm -f -- "$backend_config_temp" || true
  [[ -z "$backend_block_temp" ]] || rm -f -- "$backend_block_temp" || true
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

[[ -d "$bootstrap_root" && ! -L "$bootstrap_root" ]] || fail "bootstrap root is missing or unsafe"
[[ -f "$toolchain_contract" && ! -L "$toolchain_contract" ]] || fail "toolchain contract is missing or unsafe"

# This repository-owned file contains version and location contracts only. It
# never contains credentials and is intentionally safe to source.
# shellcheck disable=SC1090
. "$toolchain_contract"

required_contract_values=(
  AIRBOB_TERRAFORM_VERSION
  AIRBOB_AWS_CLI_VERSION
  AIRBOB_AWS_PROVIDER_VERSION
  AIRBOB_AWS_ACCOUNT_ID
  AIRBOB_AWS_REGION
  AIRBOB_STATE_BUCKET_NAME
  AIRBOB_STATE_KEY_BOOTSTRAP
  AIRBOB_STATE_KEY_FOUNDATION
  AIRBOB_STATE_KEY_DNS
  AIRBOB_STATE_KEY_LAB
)
for contract_name in "${required_contract_values[@]}"; do
  [[ -n "${!contract_name:-}" ]] || fail "toolchain contract is incomplete"
done

[[ -f "$terraform_versions" && ! -L "$terraform_versions" ]] \
  || fail "Terraform version configuration is missing or unsafe"
[[ -f "$provider_lock" && ! -L "$provider_lock" ]] \
  || fail "Terraform provider lock file is missing or unsafe"
grep -Fqx "  required_version = \"= $AIRBOB_TERRAFORM_VERSION\"" "$terraform_versions" \
  || fail "Terraform source version contract mismatch"
grep -Fqx "      version = \"= $AIRBOB_AWS_PROVIDER_VERSION\"" "$terraform_versions" \
  || fail "AWS provider source version contract mismatch"
grep -Fqx "  version     = \"$AIRBOB_AWS_PROVIDER_VERSION\"" "$provider_lock" \
  || fail "AWS provider lock version mismatch"
grep -Fqx "  constraints = \"$AIRBOB_AWS_PROVIDER_VERSION\"" "$provider_lock" \
  || fail "AWS provider lock constraint mismatch"

[[ -n "${STATE_BUCKET_NAME:-}" ]] || fail "STATE_BUCKET_NAME must be provided by the caller"
state_bucket_name=$STATE_BUCKET_NAME
[[ "$state_bucket_name" == "$AIRBOB_STATE_BUCKET_NAME" ]] || fail "state bucket name mismatch"

caller_region=${AWS_REGION:-${AWS_DEFAULT_REGION:-}}
[[ -n "$caller_region" ]] || fail "AWS_REGION or AWS_DEFAULT_REGION must be provided by the caller"
if [[ -n "${AWS_REGION:-}" && -n "${AWS_DEFAULT_REGION:-}" && "$AWS_REGION" != "$AWS_DEFAULT_REGION" ]]; then
  fail "AWS region environment variables disagree"
fi
[[ "$caller_region" == "$AIRBOB_AWS_REGION" ]] || fail "AWS region mismatch"

command -v terraform >/dev/null 2>&1 || fail "Terraform is required"
command -v aws >/dev/null 2>&1 || fail "AWS CLI is required"

terraform_version_output=$(terraform version 2>&1) || fail "Terraform version check failed"
terraform_version_line=${terraform_version_output%%$'\n'*}
terraform_version=${terraform_version_line#Terraform v}
[[ "$terraform_version" == "$AIRBOB_TERRAFORM_VERSION" ]] \
  || fail "Terraform version mismatch: expected $AIRBOB_TERRAFORM_VERSION"

aws_cli_version_output=$(aws --version 2>&1) || fail "AWS CLI version check failed"
aws_cli_version=${aws_cli_version_output#aws-cli/}
aws_cli_version=${aws_cli_version%% *}
[[ "$aws_cli_version" == "$AIRBOB_AWS_CLI_VERSION" ]] \
  || fail "AWS CLI version mismatch: expected $AIRBOB_AWS_CLI_VERSION"

aws_account_id=$(aws sts get-caller-identity \
  --region "$AIRBOB_AWS_REGION" \
  --query Account \
  --output text 2>/dev/null) || fail "AWS caller identity check failed"
[[ "$aws_account_id" == "$AIRBOB_AWS_ACCOUNT_ID" ]] || fail "AWS account mismatch"

state_keys=(
  "$AIRBOB_STATE_KEY_BOOTSTRAP"
  "$AIRBOB_STATE_KEY_FOUNDATION"
  "$AIRBOB_STATE_KEY_DNS"
  "$AIRBOB_STATE_KEY_LAB"
)
for state_index in 0 1 2 3; do
  [[ "${state_keys[$state_index]}" == airbob/*/terraform.tfstate ]] \
    || fail "state key contract is invalid"
  compare_index=$((state_index + 1))
  while [[ "$compare_index" -lt 4 ]]; do
    [[ "${state_keys[$state_index]}" != "${state_keys[$compare_index]}" ]] \
      || fail "state keys must be distinct"
    compare_index=$((compare_index + 1))
  done
done

aws_text() {
  local error_message=$1
  shift
  aws "$@" --region "$AIRBOB_AWS_REGION" --output text 2>/dev/null \
    || fail "$error_message"
}

verify_bucket_contract() {
  local bucket_region
  local versioning_status
  local public_access
  local encryption
  local ownership
  local bucket_policy
  local bucket_policy_compact
  local required_tags

  bucket_region=$(aws_text "state bucket region check failed" \
    s3api get-bucket-location \
    --bucket "$state_bucket_name" \
    --query LocationConstraint)
  [[ "$bucket_region" == "$AIRBOB_AWS_REGION" ]] || fail "state bucket region mismatch"

  versioning_status=$(aws_text "state bucket versioning check failed" \
    s3api get-bucket-versioning \
    --bucket "$state_bucket_name" \
    --query Status)
  [[ "$versioning_status" == Enabled ]] || fail "state bucket versioning is not Enabled"

  public_access=$(aws_text "state bucket public-access check failed" \
    s3api get-public-access-block \
    --bucket "$state_bucket_name" \
    --query 'PublicAccessBlockConfiguration.[BlockPublicAcls,BlockPublicPolicy,IgnorePublicAcls,RestrictPublicBuckets]')
  public_access=$(printf '%s' "$public_access" | tr '\t' ' ' | tr -s ' ')
  [[ "$public_access" == 'True True True True' ]] || fail "state bucket public access is not fully blocked"

  encryption=$(aws_text "state bucket encryption check failed" \
    s3api get-bucket-encryption \
    --bucket "$state_bucket_name" \
    --query 'ServerSideEncryptionConfiguration.Rules[0].ApplyServerSideEncryptionByDefault.SSEAlgorithm')
  [[ "$encryption" == AES256 ]] || fail "state bucket encryption is not SSE-S3 AES256"

  ownership=$(aws_text "state bucket ownership check failed" \
    s3api get-bucket-ownership-controls \
    --bucket "$state_bucket_name" \
    --query 'OwnershipControls.Rules[0].ObjectOwnership')
  [[ "$ownership" == BucketOwnerEnforced ]] || fail "state bucket ownership is not BucketOwnerEnforced"

  bucket_policy=$(aws_text "state bucket policy check failed" \
    s3api get-bucket-policy \
    --bucket "$state_bucket_name" \
    --query Policy)
  bucket_policy_compact=$(printf '%s' "$bucket_policy" | tr -d '[:space:]')
  [[ "$bucket_policy_compact" == *'"Sid":"DenyInsecureTransport"'* \
    && "$bucket_policy_compact" == *'"Effect":"Deny"'* \
    && "$bucket_policy_compact" == *'"Action":"s3:*"'* \
    && "$bucket_policy_compact" == *'"aws:SecureTransport":"false"'* \
    && "$bucket_policy_compact" == *"arn:aws:s3:::$state_bucket_name"* \
    && "$bucket_policy_compact" == *"arn:aws:s3:::$state_bucket_name/*"* ]] \
    || fail "state bucket policy does not deny insecure transport"

  required_tags=$(aws_text "state bucket tag check failed" \
    s3api get-bucket-tagging \
    --bucket "$state_bucket_name" \
    --query "TagSet[?Key=='Project' || Key=='Environment' || Key=='ManagedBy' || Key=='Persistence'].[Key,Value]")
  printf '%s\n' "$required_tags" | LC_ALL=C awk '
    $1 == "Project" && $2 == "airbob" { project++ }
    $1 == "Environment" && $2 == "performance-lab" { environment++ }
    $1 == "ManagedBy" && $2 == "terraform" { managed_by++ }
    $1 == "Persistence" && $2 == "persistent" { persistence++ }
    END {
      if (NR != 4 || project != 1 || environment != 1 || managed_by != 1 || persistence != 1) {
        exit 1
      }
    }
  ' || fail "state bucket tag contract mismatch"
}

write_generated_backend_files() {
  local generated_path

  if [[ -e "$generated_backend_block" || -e "$generated_backend_config" ]]; then
    verify_generated_backend_files
    printf '%s\n' "generated_backend_block=$generated_backend_block"
    printf '%s\n' "generated_backend_config=$generated_backend_config"
    return
  fi

  for generated_path in "$generated_backend_block" "$generated_backend_config"; do
    [[ ! -L "$generated_path" ]] || fail "generated backend path must not be a symlink"
    [[ ! -e "$generated_path" || -f "$generated_path" ]] \
      || fail "generated backend path must be a regular file"
  done

  backend_block_temp=$(mktemp "$bootstrap_root/.zz_backend.generated.tf.XXXXXX") \
    || fail "cannot stage generated backend block"
  backend_config_temp=$(mktemp "$bootstrap_root/.backend.generated.hcl.XXXXXX") \
    || {
      rm -f -- "$backend_block_temp"
      fail "cannot stage generated backend config"
    }
  chmod 600 "$backend_block_temp" "$backend_config_temp"

  {
    printf '%s\n' '# GENERATED by infra/aws/scripts/bootstrap-state.sh; do not edit or commit.'
    printf '%s\n' 'terraform {'
    printf '%s\n' '  backend "s3" {}'
    printf '%s\n' '}'
  } > "$backend_block_temp"

  {
    printf 'bucket       = "%s"\n' "$state_bucket_name"
    printf 'key          = "%s"\n' "$AIRBOB_STATE_KEY_BOOTSTRAP"
    printf 'region       = "%s"\n' "$AIRBOB_AWS_REGION"
    printf '%s\n' 'encrypt      = true'
    printf '%s\n' 'use_lockfile = true'
  } > "$backend_config_temp"

  mv -f -- "$backend_config_temp" "$generated_backend_config" \
    || fail "cannot publish generated backend config"
  backend_config_temp=''
  if ! mv -f -- "$backend_block_temp" "$generated_backend_block"; then
    rm -f -- "$generated_backend_config"
    fail "cannot publish generated backend block"
  fi
  backend_block_temp=''
  verify_generated_backend_files
  printf '%s\n' "generated_backend_block=$generated_backend_block"
  printf '%s\n' "generated_backend_config=$generated_backend_config"
}

verify_generated_backend_files() {
  [[ -f "$generated_backend_block" && ! -L "$generated_backend_block" ]] \
    || fail "generated S3 backend block is missing or unsafe"
  [[ -f "$generated_backend_config" && ! -L "$generated_backend_config" ]] \
    || fail "generated S3 backend config is missing or unsafe"
  grep -Fqx '  backend "s3" {}' "$generated_backend_block" \
    || fail "generated backend block is invalid"
  grep -Fqx "bucket       = \"$state_bucket_name\"" "$generated_backend_config" \
    || fail "generated backend bucket is invalid"
  grep -Fqx "key          = \"$AIRBOB_STATE_KEY_BOOTSTRAP\"" "$generated_backend_config" \
    || fail "generated backend key is invalid"
  grep -Fqx "region       = \"$AIRBOB_AWS_REGION\"" "$generated_backend_config" \
    || fail "generated backend region is invalid"
  grep -Fqx 'encrypt      = true' "$generated_backend_config" \
    || fail "generated backend encryption is invalid"
  grep -Fqx 'use_lockfile = true' "$generated_backend_config" \
    || fail "generated backend lock mode is invalid"
  if grep -Eqi 'dynamodb|access_key|secret_key|token|credential' "$generated_backend_config"; then
    fail "generated backend config contains forbidden fields"
  fi
}

verify_remote_state_object() {
  aws s3api head-object \
    --bucket "$state_bucket_name" \
    --key "$AIRBOB_STATE_KEY_BOOTSTRAP" \
    --region "$AIRBOB_AWS_REGION" >/dev/null 2>&1 \
    || fail "remote bootstrap state object is missing"
}

probe_native_lockfile() {
  lock_probe_plan=$(mktemp "${TMPDIR:-/tmp}/airbob-bootstrap-lock-probe.XXXXXX.tfplan") \
    || fail "cannot create lock probe plan"
  if ! terraform -chdir="$bootstrap_root" plan \
    -input=false \
    -refresh=false \
    -lock=true \
    -lock-timeout=0s \
    -out="$lock_probe_plan" >/dev/null 2>&1
  then
    rm -f -- "$lock_probe_plan"
    lock_probe_plan=''
    fail "S3 native lockfile probe failed"
  fi
  rm -f -- "$lock_probe_plan"
  lock_probe_plan=''
}

print_remote_state() {
  local lock_status=$1
  printf '%s\n' 'bootstrap_state=remote'
  printf '%s\n' "state_bucket=$state_bucket_name"
  printf '%s\n' "state_key=$AIRBOB_STATE_KEY_BOOTSTRAP"
  printf '%s\n' "native_lockfile=$lock_status"
}

migrate_state() {
  verify_bucket_contract
  write_generated_backend_files

  terraform -chdir="$bootstrap_root" init \
    -input=false \
    -migrate-state \
    -force-copy \
    -backend-config="$generated_backend_config" >/dev/null \
    || fail "bootstrap local-state migration failed"

  verify_remote_state_object
  probe_native_lockfile
  print_remote_state verified
}

create_state_bucket() {
  if [[ -f "$generated_backend_block" || -f "$generated_backend_config" ]]; then
    verify_generated_backend_files
    verify_bucket_contract
    verify_remote_state_object
    probe_native_lockfile
    print_remote_state verified
    return
  fi

  terraform -chdir="$bootstrap_root" init -backend=false -input=false >/dev/null \
    || fail "bootstrap local initialization failed"
  terraform -chdir="$bootstrap_root" apply \
    -input=false \
    -auto-approve \
    -var="account_id=$AIRBOB_AWS_ACCOUNT_ID" \
    -var="aws_region=$AIRBOB_AWS_REGION" \
    -var="state_bucket_name=$state_bucket_name" >/dev/null \
    || fail "bootstrap state bucket creation failed"

  migrate_state
}

show_status() {
  verify_bucket_contract
  if [[ -f "$generated_backend_block" || -f "$generated_backend_config" ]]; then
    verify_generated_backend_files
    verify_remote_state_object
    print_remote_state configured
  elif [[ -f "$bootstrap_root/terraform.tfstate" ]]; then
    printf '%s\n' 'bootstrap_state=local'
    printf '%s\n' "state_bucket=$state_bucket_name"
    printf '%s\n' 'migration_required=true'
  else
    printf '%s\n' 'bootstrap_state=uninitialized'
    printf '%s\n' "state_bucket=$state_bucket_name"
    printf '%s\n' 'migration_required=true'
  fi
}

case "$operation" in
  create) create_state_bucket ;;
  migrate) migrate_state ;;
  status) show_status ;;
esac
