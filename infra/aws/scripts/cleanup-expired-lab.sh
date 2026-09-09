#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() { printf '%s\n' "$1" >&2; exit 1; }

valid_run_id() {
  local candidate=$1
  [[ "$candidate" =~ ^lab-[a-z0-9][a-z0-9-]{0,27}$ && \
    "$candidate" != *--* && "$candidate" != *- ]]
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

sha256_text() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
  else
    shasum -a 256 | awk '{print $1}'
  fi
}

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
operator="$script_dir/aws-lab.sh"
[[ -x "$operator" && ! -L "$operator" ]] || fail "AWS lab operator is missing or unsafe"
[[ "${AWS_REGION:-}" == "ap-northeast-2" ]] || fail "AWS_REGION must equal ap-northeast-2"
command -v aws >/dev/null 2>&1 || fail "AWS CLI is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-expiry-cleanup.XXXXXX")
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

object_present() {
  local bucket=$1 key=$2 object
  object=$(aws s3api list-objects-v2 --bucket "$bucket" --prefix "$key" --max-items 1 \
    --query "Contents[?Key=='$key'].Key | [0]" --output text \
    --region "$AWS_REGION" --no-cli-pager) \
    || fail "cannot inspect fixed Lab object: s3://$bucket/$key"
  [[ "$object" == "$key" ]]
}

extract_state_run_identity() {
  local state_file=$1
  jq -ce '
    [.resources[]? |
      select(
        ((.module? // "") == "") and
        .mode == "managed" and .type == "terraform_data" and .name == "run_identity"
      )
    ] as $identities |
    select(($identities | length) == 1) |
    $identities[0] as $identity |
    select(
      $identity.provider == "provider[\"terraform.io/builtin/terraform\"]" and
      ($identity.instances | type == "array" and length == 1) and
      (($identity.instances[0].index_key? // null) == null) and
      $identity.instances[0].schema_version == 0 and
      $identity.instances[0].identity_schema_version == 0 and
      ($identity.instances[0].sensitive_attributes | type == "array" and length == 0) and
      ($identity.instances[0].attributes | keys | sort) ==
        ["id", "input", "output", "triggers_replace"] and
      ($identity.instances[0].attributes.id | type == "string" and length > 0) and
      $identity.instances[0].attributes.triggers_replace == null and
      $identity.instances[0].attributes.input.type ==
        ["object", {"resource_fencing_token":"number", "run_id":"string"}] and
      $identity.instances[0].attributes.output.type ==
        ["object", {"resource_fencing_token":"number", "run_id":"string"}] and
      $identity.instances[0].attributes.input.value ==
        $identity.instances[0].attributes.output.value and
      ($identity.instances[0].attributes.output.value.run_id |
        type == "string") and
      ($identity.instances[0].attributes.output.value.resource_fencing_token |
        type == "number") and
      $identity.instances[0].attributes.output.value.resource_fencing_token ==
        ($identity.instances[0].attributes.output.value.resource_fencing_token | floor) and
      $identity.instances[0].attributes.output.value.resource_fencing_token > 0
    ) |
    {
      runId: $identity.instances[0].attributes.output.value.run_id,
      resourceFencingToken:
        $identity.instances[0].attributes.output.value.resource_fencing_token
    }
  ' "$state_file"
}

load_recovery_candidate() {
  local classification=$1 state_identity=$2
  local run_id resource_fence manifest="$temp_dir/operator-manifest.json" expires_at
  run_id=$(jq -er '.runId' <<<"$state_identity") \
    || fail "fixed Lab state has no recoverable run identity"
  resource_fence=$(jq -er '.resourceFencingToken' <<<"$state_identity") \
    || fail "fixed Lab state has no recoverable resource fence"
  valid_run_id "$run_id" || fail "fixed Lab state run identity is invalid"
  [[ "$resource_fence" =~ ^[1-9][0-9]*$ ]] \
    || fail "fixed Lab state resource fence is invalid"
  aws s3api get-object --bucket "$evidence_bucket" \
    --key "runs/$run_id/operator.json" "$manifest" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "fixed Lab state has no matching immutable run manifest: $run_id"
  jq -e --arg run "$run_id" --argjson resourceFence "$resource_fence" '
    (.schemaVersion == 1 or .schemaVersion == 2) and
    .runId == $run and .fencingToken == $resourceFence and
    (.expiresAt | type == "string" and test("^[1-9][0-9]{9}$")) and
    (.dnsMode == "direct-only" or .dnsMode == "cutover")
  ' "$manifest" >/dev/null \
    || fail "fixed Lab state and immutable run manifest do not agree: $run_id"
  expires_at=$(jq -er '.expiresAt' "$manifest")
  jq -nc --arg classification "$classification" --arg runId "$run_id" \
    --arg expiresAt "$expires_at" \
    '{classification:$classification,runId:$runId,expiresAt:$expiresAt}'
}

validate_clean_state_receipt() {
  local receipt=$1 state_version=$2 state_sha=$3 version_hash=$4 run_id
  jq -e --arg stateKey "$lab_state_key" --arg version "$state_version" \
    --arg stateSha "$state_sha" --arg versionHash "$version_hash" '
      .schemaVersion == 1 and .status == "clean" and
      (.runId | type == "string") and
      (.resourceFencingToken | type == "number" and floor == . and . > 0) and
      (.dnsMode == "direct-only" or .dnsMode == "cutover") and
      .terraformState.key == $stateKey and
      .terraformState.versionId == $version and
      .terraformState.versionIdSha256 == $versionHash and
      .terraformState.objectSha256 == $stateSha and
      .terraformState.resourceCount == 0 and
      .teardownStart.key == ("measurements/" + .runId + "/teardown-start.json") and
      (.teardownStart.versionId | type == "string" and length > 0) and
      .teardownFinalize.key == ("measurements/" + .runId + "/teardown-finalize.json") and
      (.teardownFinalize.versionId | type == "string" and length > 0) and
      .ociAuthority.status == "verified" and
      .orphanScan.status == "clean" and .orphanScan.scope == "global" and
      .orphanScan.runId == .runId
    ' "$receipt" >/dev/null \
    || fail "fixed Lab backend clean receipt does not bind its exact empty state"
  run_id=$(jq -er '.runId' "$receipt")
  valid_run_id "$run_id" || fail "fixed Lab backend clean receipt has an invalid run identity"
}

inspect_fixed_lab_backend() {
  local lab_contract state_file="$temp_dir/current.tfstate" state_identity resource_count
  local state_head="$temp_dir/state-head.json" state_versions="$temp_dir/state-versions.json"
  local predecessor="$temp_dir/predecessor.tfstate" predecessor_version predecessor_identity
  local state_version state_sha version_hash clean_key clean_receipt="$temp_dir/state-clean.json"

  lab_contract=$(aws ssm get-parameter \
    --name /airbob/performance-lab/foundation/lab-contract \
    --query 'Parameter.Value' --output text --region "$AWS_REGION") \
    || fail "cannot read the fixed Lab backend contract"
  jq -e '
    .schemaVersion == 1 and .account_id == "942632789808" and
    .region == "ap-northeast-2" and
    .state_bucket_name == "airbob-performance-lab-tfstate-942632789808" and
    .lab_state_key == "airbob/lab/terraform.tfstate" and
    .evidence_bucket_name == "airbob-performance-lab-evidence-942632789808"
  ' <<<"$lab_contract" >/dev/null || fail "fixed Lab backend contract is invalid"
  state_bucket=$(jq -er '.state_bucket_name' <<<"$lab_contract")
  lab_state_key=$(jq -er '.lab_state_key' <<<"$lab_contract")
  evidence_bucket=$(jq -er '.evidence_bucket_name' <<<"$lab_contract")

  if ! object_present "$state_bucket" "$lab_state_key"; then
    printf '%s\n' '{"classification":"absent"}'
    return 0
  fi
  aws s3api head-object --bucket "$state_bucket" --key "$lab_state_key" \
    --query '{versionId:VersionId,contentLength:ContentLength}' --output json \
    --region "$AWS_REGION" --no-cli-pager > "$state_head" \
    || fail "cannot read the fixed Lab state object identity"
  state_version=$(jq -er \
    '.versionId | select(type == "string" and length > 0)' "$state_head") \
    || fail "fixed Lab state object has no immutable version identity"
  aws s3api get-object --bucket "$state_bucket" --key "$lab_state_key" \
    --version-id "$state_version" "$state_file" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "cannot read the exact fixed Lab state version"
  jq -e '
    .version == 4 and .terraform_version == "1.15.5" and
    (.lineage | type == "string" and length > 0) and
    (.serial | type == "number" and floor == . and . >= 0) and
    (.resources | type == "array")
  ' "$state_file" >/dev/null || fail "fixed Lab backend state envelope is invalid"
  resource_count=$(jq -er '.resources | length' "$state_file")

  if [[ "$resource_count" -gt 0 ]]; then
    state_identity=$(extract_state_run_identity "$state_file") \
      || fail "active fixed Lab state has no single canonical run identity; manual audit required"
    if [[ "$resource_count" -eq 1 ]]; then
      load_recovery_candidate identity-only "$state_identity"
    else
      load_recovery_candidate active "$state_identity"
    fi
    return 0
  fi

  state_sha=$(sha256_file "$state_file")
  version_hash=$(printf '%s' "$state_version" | sha256_text)
  clean_key="measurements/state-clean/$version_hash.json"
  if object_present "$evidence_bucket" "$clean_key"; then
    aws s3api get-object --bucket "$evidence_bucket" --key "$clean_key" "$clean_receipt" \
      --region "$AWS_REGION" --no-cli-pager >/dev/null \
      || fail "cannot read the exact fixed Lab clean receipt"
    validate_clean_state_receipt "$clean_receipt" "$state_version" "$state_sha" "$version_hash"
    printf '%s\n' '{"classification":"clean"}'
    return 0
  fi

  aws s3api list-object-versions --bucket "$state_bucket" --prefix "$lab_state_key" \
    --output json --region "$AWS_REGION" --no-cli-pager > "$state_versions" \
    || fail "cannot inspect fixed Lab state history for interrupted finalization"
  jq -e --arg key "$lab_state_key" --arg current "$state_version" '
    ([.Versions[]? | select(.Key == $key)] | .[0]) as $latest |
    $latest.VersionId == $current and $latest.IsLatest == true and
    ([.Versions[]? | select(.Key == $key and .VersionId == $current)] | length) == 1 and
    ([.Versions[]? | select(.Key == $key)] | length) >= 2 and
    ([.DeleteMarkers[]? | select(.Key == $key)] | length) == 0
  ' "$state_versions" >/dev/null \
    || fail "empty fixed Lab backend has no provable immediate state predecessor; manual audit required"
  predecessor_version=$(jq -er --arg key "$lab_state_key" \
    '[.Versions[]? | select(.Key == $key)][1].VersionId' "$state_versions")
  aws s3api get-object --bucket "$state_bucket" --key "$lab_state_key" \
    --version-id "$predecessor_version" "$predecessor" \
    --region "$AWS_REGION" --no-cli-pager >/dev/null \
    || fail "cannot read the exact predecessor of the empty fixed Lab state"
  predecessor_identity=$(extract_state_run_identity "$predecessor") \
    || fail "empty fixed Lab state predecessor is not one canonical run identity"
  jq -e --slurpfile predecessor "$predecessor" '
    .lineage == $predecessor[0].lineage and
    .serial == ($predecessor[0].serial + 1) and
    (.resources | length) == 0 and
    ($predecessor[0].resources | length) == 1
  ' "$state_file" >/dev/null \
    || fail "empty fixed Lab state is not the direct successor of its identity-only predecessor"
  load_recovery_candidate pending-finalization "$predecessor_identity"
}

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

recovery_state=tagged-inventory
if [[ "$facts" == null ]]; then
  backend_facts=$(inspect_fixed_lab_backend)
  recovery_state=$(jq -er '.classification' <<<"$backend_facts")
  case "$recovery_state" in
    absent|clean)
      printf 'cleanup_due=false reason=no-active-lab backend_state=%s\n' "$recovery_state"
      exit 0
      ;;
    active|identity-only|pending-finalization) facts=$backend_facts ;;
    *) fail "fixed Lab backend classification is invalid" ;;
  esac
fi
run_id=$(jq -er '.runId' <<<"$facts")
valid_run_id "$run_id" || fail "scheduled-cleanup candidate run ID is invalid"
expires_at=$(jq -er '.expiresAt | tonumber' <<<"$facts")
now_epoch=${AIRBOB_NOW_EPOCH:-$(date +%s)}
[[ "$now_epoch" =~ ^[1-9][0-9]{9}$ ]] || fail "current epoch is not canonical"
if [[ "$now_epoch" -lt "$expires_at" ]]; then
  printf 'cleanup_due=false run_id=%s recovery_state=%s\n' "$run_id" "$recovery_state"
  exit 0
fi

printf 'cleanup_due=true run_id=%s recovery_state=%s\n' "$run_id" "$recovery_state"
RUN_ID="$run_id" FORCE=true "$operator" down
