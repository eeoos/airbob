#!/usr/bin/env bash
set -euo pipefail

fail() { printf '%s\n' "$1" >&2; exit 1; }

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
operator="$script_dir/aws-lab.sh"
[[ -x "$operator" && ! -L "$operator" ]] || fail "AWS lab operator is missing or unsafe"
[[ "${AWS_REGION:-}" == "ap-northeast-2" ]] || fail "AWS_REGION must equal ap-northeast-2"
command -v aws >/dev/null 2>&1 || fail "AWS CLI is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"

inventory=$(aws resourcegroupstaggingapi get-resources \
  --tag-filters Key=Project,Values=airbob Key=Environment,Values=performance-lab Key=Stack,Values=lab \
  --output json --region "$AWS_REGION" --no-cli-pager)
facts=$(jq -cr '
  [.ResourceTagMappingList[] |
    (.Tags | from_entries) as $tags |
    select($tags.Persistence == "ephemeral") |
    {runId: $tags.RunId, expiresAt: $tags.ExpiresAt}] | unique |
  if length == 0 then null
  elif length == 1 and .[0].runId != null and (.[0].expiresAt | test("^[1-9][0-9]{9}$")) then .[0]
  else error("ephemeral inventory has mixed or invalid run identity") end
' <<<"$inventory") || fail "cannot identify one scheduled-cleanup candidate"

if [[ "$facts" == null ]]; then
  printf '%s\n' 'cleanup_due=false reason=no-active-lab'
  exit 0
fi
run_id=$(jq -er '.runId' <<<"$facts")
expires_at=$(jq -er '.expiresAt | tonumber' <<<"$facts")
now_epoch=${AIRBOB_NOW_EPOCH:-$(date +%s)}
[[ "$now_epoch" =~ ^[1-9][0-9]{9}$ ]] || fail "current epoch is not canonical"
if [[ "$now_epoch" -lt $((expires_at + 7200)) ]]; then
  printf 'cleanup_due=false run_id=%s\n' "$run_id"
  exit 0
fi

printf 'cleanup_due=true run_id=%s\n' "$run_id"
RUN_ID="$run_id" FORCE=true "$operator" down
