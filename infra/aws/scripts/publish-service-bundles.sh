#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

[[ "$#" -eq 2 ]] || fail "usage: publish-service-bundles.sh <40-char-commit> <bundle-bucket>"
commit=$1
bundle_bucket=$2
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "commit must be exactly 40 lower-case hexadecimal characters"
[[ "$bundle_bucket" =~ ^airbob-performance-lab-bundles-[0-9]{12}$ ]] || fail "bundle bucket is outside the approved foundation boundary"

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
toolchain_contract="$repo_root/infra/aws/toolchain.env"
[[ -f "$toolchain_contract" && ! -L "$toolchain_contract" ]] || fail "toolchain contract is missing or unsafe"
# shellcheck disable=SC1090
source "$toolchain_contract"

command -v aws >/dev/null 2>&1 || fail "AWS CLI is required"
[[ "${AWS_REGION:-}" == "$AIRBOB_AWS_REGION" ]] || fail "AWS_REGION must equal $AIRBOB_AWS_REGION"
account_id=$(aws sts get-caller-identity --query Account --output text)
[[ "$account_id" == "$AIRBOB_AWS_ACCOUNT_ID" ]] || fail "active AWS account does not match the bundle foundation"
[[ "$bundle_bucket" == "airbob-performance-lab-bundles-$account_id" ]] || fail "bundle bucket does not belong to the active account"

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-bundle-publish.XXXXXX")
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

"$script_dir/package-service-bundles.sh" "$commit" "$temp_dir" >/dev/null

archive_name="airbob-service-bundles-$commit.tar.gz"
checksum_name="$archive_name.sha256"
manifest_name="airbob-service-bundles-$commit.manifest.json"
prefix="service-bundles/$commit"

publish_immutable() {
  local file_name=$1
  local content_type=$2
  local local_path="$temp_dir/$file_name"
  local key="$prefix/$file_name"
  local remote_copy="$temp_dir/remote-$file_name"

  if aws s3api head-object --bucket "$bundle_bucket" --key "$key" --no-cli-pager >/dev/null 2>&1; then
    aws s3api get-object --bucket "$bundle_bucket" --key "$key" "$remote_copy" --no-cli-pager >/dev/null
    cmp -s "$local_path" "$remote_copy" || fail "immutable bundle key already exists with different bytes"
    return
  fi

  if ! aws s3api put-object \
    --bucket "$bundle_bucket" \
    --key "$key" \
    --body "$local_path" \
    --content-type "$content_type" \
    --server-side-encryption AES256 \
    --metadata "commit=$commit" \
    --if-none-match '*' \
    --no-cli-pager >/dev/null
  then
    aws s3api get-object --bucket "$bundle_bucket" --key "$key" "$remote_copy" --no-cli-pager >/dev/null \
      || fail "bundle publication lost an immutable write race"
    cmp -s "$local_path" "$remote_copy" || fail "bundle publication raced with different bytes"
  fi

  aws s3api get-object --bucket "$bundle_bucket" --key "$key" "$remote_copy" --no-cli-pager >/dev/null
  cmp -s "$local_path" "$remote_copy" || fail "published bundle object failed byte verification"
}

publish_immutable "$archive_name" application/gzip
publish_immutable "$checksum_name" text/plain
publish_immutable "$manifest_name" application/json

printf 'bundle_manifest=s3://%s/%s/%s\n' "$bundle_bucket" "$prefix" "$manifest_name"
