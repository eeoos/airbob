#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

case "$#:${1-}" in
  1:foundation|1:dns|1:lab) root_name=$1 ;;
  *) fail "usage: prepare-terraform-backend.sh <foundation|dns|lab>" ;;
esac

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
toolchain_contract="$repo_root/infra/aws/toolchain.env"
target_root="$repo_root/infra/aws/$root_name"
backend_block="$target_root/backend.tf"
generated_config="$target_root/backend.generated.hcl"
bootstrap_preflight="$repo_root/infra/aws/scripts/bootstrap-state.sh"
staged_config=''

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  [[ -z "$staged_config" ]] || rm -f -- "$staged_config" || true
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

[[ -f "$toolchain_contract" && ! -L "$toolchain_contract" ]] \
  || fail "toolchain contract is missing or unsafe"
[[ -d "$target_root" && ! -L "$target_root" ]] \
  || fail "Terraform root is missing or unsafe"
[[ -f "$backend_block" && ! -L "$backend_block" ]] \
  || fail "committed backend block is missing or unsafe"
[[ -x "$bootstrap_preflight" && ! -L "$bootstrap_preflight" ]] \
  || fail "bootstrap state preflight is missing or unsafe"
grep -Fqx '  backend "s3" {}' "$backend_block" \
  || fail "committed S3 backend block contract mismatch"

# Repository-owned constants only; credentials are never accepted or written.
# shellcheck disable=SC1090
. "$toolchain_contract"

required_contract_values=(
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

# Backend access occurs before the Terraform provider account guard. Reuse the
# read-only bootstrap status gate so tool versions, caller identity, region,
# bucket security posture, and remote bootstrap state are verified first.
STATE_BUCKET_NAME="$AIRBOB_STATE_BUCKET_NAME" \
  "$bootstrap_preflight" status >/dev/null \
  || fail "bootstrap state preflight failed"
aws s3api head-object \
  --bucket "$AIRBOB_STATE_BUCKET_NAME" \
  --key "$AIRBOB_STATE_KEY_BOOTSTRAP" \
  --region "$AIRBOB_AWS_REGION" >/dev/null 2>&1 \
  || fail "remote bootstrap state object is missing or inaccessible"

case "$root_name" in
  foundation) state_key=$AIRBOB_STATE_KEY_FOUNDATION ;;
  dns) state_key=$AIRBOB_STATE_KEY_DNS ;;
  lab) state_key=$AIRBOB_STATE_KEY_LAB ;;
esac

expected_config=$(printf '%s\n' \
  "bucket       = \"$AIRBOB_STATE_BUCKET_NAME\"" \
  "key          = \"$state_key\"" \
  "region       = \"$AIRBOB_AWS_REGION\"" \
  'encrypt      = true' \
  'use_lockfile = true')

if [[ -e "$generated_config" || -L "$generated_config" ]]; then
  [[ -f "$generated_config" && ! -L "$generated_config" ]] \
    || fail "generated backend config is unsafe"
  [[ "$(cat "$generated_config")" == "$expected_config" ]] \
    || fail "generated backend config differs from the canonical contract"
  chmod 600 "$generated_config"
  printf '%s\n' "backend_config=$generated_config"
  exit 0
fi

staged_config=$(mktemp "$target_root/.backend.generated.hcl.XXXXXX") \
  || fail "cannot stage backend config"
chmod 600 "$staged_config"
printf '%s\n' "$expected_config" > "$staged_config"
mv -- "$staged_config" "$generated_config" || fail "cannot publish backend config"
staged_config=''
chmod 600 "$generated_config"
printf '%s\n' "backend_config=$generated_config"
