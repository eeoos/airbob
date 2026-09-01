#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
source_script="$repo_root/infra/aws/scripts/cleanup-expired-lab.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-expiry-cleanup-test.XXXXXX")

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

fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ -x "$source_script" && ! -L "$source_script" ]] || fail "expiry cleanup script is missing or unsafe"

fixture_scripts="$temp_dir/repo/infra/aws/scripts"
fixture_data="$temp_dir/fixtures"
mkdir -p "$fixture_scripts" "$temp_dir/bin" "$fixture_data"
cp "$source_script" "$fixture_scripts/cleanup-expired-lab.sh"
cat > "$fixture_scripts/aws-lab.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'operator %s run=%s force=%s\n' "$*" "${RUN_ID:-}" "${FORCE:-}" >> "${FAKE_OPERATOR_LOG:?}"
EOF

jq -nS '{
  schemaVersion: 1,
  account_id: "942632789808",
  region: "ap-northeast-2",
  state_bucket_name: "airbob-performance-lab-tfstate-942632789808",
  lab_state_key: "airbob/lab/terraform.tfstate",
  evidence_bucket_name: "airbob-performance-lab-evidence-942632789808"
}' > "$fixture_data/lab-contract.json"

write_identity_state() {
  local run_id=$1 resource_fence=$2 serial=$3 destination=$4
  jq -nS --arg run "$run_id" --argjson fence "$resource_fence" --argjson serial "$serial" '
    {
      version: 4,
      terraform_version: "1.15.5",
      serial: $serial,
      lineage: "11111111-2222-3333-4444-555555555555",
      outputs: {},
      resources: [{
        mode: "managed",
        type: "terraform_data",
        name: "run_identity",
        provider: "provider[\"terraform.io/builtin/terraform\"]",
        instances: [{
          schema_version: 0,
          identity_schema_version: 0,
          attributes: {
            id: "fixture-id",
            input: {
              value: {run_id: $run, resource_fencing_token: $fence},
              type: ["object", {run_id: "string", resource_fencing_token: "number"}]
            },
            output: {
              value: {run_id: $run, resource_fencing_token: $fence},
              type: ["object", {run_id: "string", resource_fencing_token: "number"}]
            },
            triggers_replace: null
          },
          sensitive_attributes: []
        }]
      }]
    }
  ' > "$destination"
}

write_identity_state lab-recovery-test 41 11 "$fixture_data/identity.tfstate"
jq '.resources += [{module:"module.network",mode:"managed",type:"aws_vpc",name:"this",provider:"provider[\"registry.terraform.io/hashicorp/aws\"]",instances:[{schema_version:1,attributes:{id:"vpc-0123456789abcdef0"},sensitive_attributes:[]}]}]' \
  "$fixture_data/identity.tfstate" > "$fixture_data/active.tfstate"
jq -nS '{
  version: 4,
  terraform_version: "1.15.5",
  serial: 12,
  lineage: "11111111-2222-3333-4444-555555555555",
  outputs: {},
  resources: []
}' > "$fixture_data/empty.tfstate"
jq '.serial = 13' "$fixture_data/empty.tfstate" > "$fixture_data/clean.tfstate"
jq '.serial = 9' "$fixture_data/identity.tfstate" > "$fixture_data/wrong-predecessor.tfstate"

if command -v sha256sum >/dev/null 2>&1; then
  clean_state_sha=$(sha256sum "$fixture_data/clean.tfstate" | awk '{print $1}')
  clean_version_hash=$(printf '%s' state-v-clean | sha256sum | awk '{print $1}')
else
  clean_state_sha=$(shasum -a 256 "$fixture_data/clean.tfstate" | awk '{print $1}')
  clean_version_hash=$(printf '%s' state-v-clean | shasum -a 256 | awk '{print $1}')
fi
clean_key="measurements/state-clean/$clean_version_hash.json"
jq -nS --arg stateSha "$clean_state_sha" --arg versionHash "$clean_version_hash" '{
  schemaVersion: 1,
  status: "clean",
  runId: "lab-clean-test",
  resourceFencingToken: 42,
  dnsMode: "direct-only",
  teardownStart: {
    key: "measurements/lab-clean-test/teardown-start.json",
    versionId: "teardown-start-v1"
  },
  teardownFinalize: {
    key: "measurements/lab-clean-test/teardown-finalize.json",
    versionId: "teardown-finalize-v1"
  },
  terraformState: {
    key: "airbob/lab/terraform.tfstate",
    versionId: "state-v-clean",
    versionIdSha256: $versionHash,
    objectSha256: $stateSha,
    resourceCount: 0
  },
  ociAuthority: {status: "verified"},
  orphanScan: {status: "clean", scope: "global", runId: "lab-clean-test"}
}' > "$fixture_data/state-clean.json"

cat > "$temp_dir/bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
command_line=" $* "
case "$command_line" in
  *' resourcegroupstaggingapi get-resources '*)
    case "${FAKE_INVENTORY_KIND:-empty}" in
      empty) printf '%s\n' '{"ResourceTagMappingList":[]}' ;;
      current) expires=1900000001 ;;
      at_expiry) expires=1900000000 ;;
      expired) expires=1899990000 ;;
      *) exit 1 ;;
    esac
    if [[ "${FAKE_INVENTORY_KIND:-empty}" != empty ]]; then
      printf '{"ResourceTagMappingList":[{"ResourceARN":"arn:aws:ec2:x","Tags":[{"Key":"Persistence","Value":"ephemeral"},{"Key":"RunId","Value":"lab-expiry-test"},{"Key":"ExpiresAt","Value":"%s"}]}]}\n' "$expires"
    fi
    ;;
  *' ssm get-parameter '*)
    jq -c . "${FAKE_FIXTURE_DIR:?}/lab-contract.json"
    ;;
  *' s3api list-objects-v2 '*)
    key=''
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" == --prefix ]]; then key=$2; shift 2; else shift; fi
    done
    if [[ "$key" == 'airbob/lab/terraform.tfstate' && \
      "${FAKE_BACKEND_KIND:-absent}" != absent ]]; then
      printf '%s\n' "$key"
    elif [[ "${FAKE_BACKEND_KIND:-absent}" == clean && "$key" == "${FAKE_CLEAN_KEY:?}" ]]; then
      printf '%s\n' "$key"
    else
      printf '%s\n' None
    fi
    ;;
  *' s3api head-object '*)
    case "${FAKE_BACKEND_KIND:-absent}" in
      active) version=state-v-active ;;
      identity) version=state-v-identity ;;
      empty) version=state-v-empty ;;
      clean) version=state-v-clean ;;
      *) exit 1 ;;
    esac
    printf '{"versionId":"%s","contentLength":128}\n' "$version"
    ;;
  *' s3api get-object '*)
    bucket='' key='' version='' destination=''
    while [[ "$#" -gt 0 ]]; do
      case "$1" in
        --bucket) bucket=$2; shift 2 ;;
        --key) key=$2; shift 2 ;;
        --version-id) version=$2; shift 2 ;;
        --query|--output|--region) shift 2 ;;
        --no-cli-pager|s3api|get-object) shift ;;
        --*) shift ;;
        *) destination=$1; shift ;;
      esac
    done
    [[ -n "$bucket" && -n "$key" && -n "$destination" ]] || exit 64
    case "$key:$version:${FAKE_BACKEND_KIND:-absent}" in
      airbob/lab/terraform.tfstate:state-v-predecessor:empty)
        if [[ "${FAKE_PREDECESSOR_KIND:-valid}" == valid ]]; then
          cp "${FAKE_FIXTURE_DIR:?}/identity.tfstate" "$destination"
        else
          cp "${FAKE_FIXTURE_DIR:?}/wrong-predecessor.tfstate" "$destination"
        fi
        ;;
      airbob/lab/terraform.tfstate:*:active)
        cp "${FAKE_FIXTURE_DIR:?}/active.tfstate" "$destination" ;;
      airbob/lab/terraform.tfstate:*:identity)
        cp "${FAKE_FIXTURE_DIR:?}/identity.tfstate" "$destination" ;;
      airbob/lab/terraform.tfstate:*:empty)
        cp "${FAKE_FIXTURE_DIR:?}/empty.tfstate" "$destination" ;;
      airbob/lab/terraform.tfstate:*:clean)
        cp "${FAKE_FIXTURE_DIR:?}/clean.tfstate" "$destination" ;;
      runs/lab-recovery-test/operator.json::*)
        jq -nS --arg expiresAt "${FAKE_MANIFEST_EXPIRES_AT:-1899990000}" '{
          schemaVersion: 2,
          runId: "lab-recovery-test",
          fencingToken: 41,
          expiresAt: $expiresAt,
          dnsMode: "direct-only"
        }' > "$destination"
        ;;
      "$FAKE_CLEAN_KEY"::* )
        cp "${FAKE_FIXTURE_DIR:?}/state-clean.json" "$destination" ;;
      *) exit 1 ;;
    esac
    printf '%s\n' '{"VersionId":"fixture-version"}'
    ;;
  *' s3api list-object-versions '*)
    [[ "${FAKE_VERSION_HISTORY:-valid}" != denied ]] || exit 77
    is_latest=true
    [[ "${FAKE_VERSION_HISTORY:-valid}" != stale-current ]] || is_latest=false
    delete_markers='[]'
    if [[ "${FAKE_VERSION_HISTORY:-valid}" == delete-marker ]]; then
      delete_markers='[{"Key":"airbob/lab/terraform.tfstate","VersionId":"delete-v1","IsLatest":false}]'
    fi
    extra_versions='[]'
    if [[ "${FAKE_VERSION_HISTORY:-valid}" == duplicate-current ]]; then
      extra_versions='[{"Key":"airbob/lab/terraform.tfstate","VersionId":"state-v-empty","IsLatest":false}]'
    fi
    jq -nc --argjson latest "$is_latest" --argjson deleteMarkers "$delete_markers" \
      --argjson extraVersions "$extra_versions" '{
      Versions: ([
        {Key:"airbob/lab/terraform.tfstate",VersionId:"state-v-empty",IsLatest:$latest},
        {Key:"airbob/lab/terraform.tfstate",VersionId:"state-v-predecessor",IsLatest:false},
        {Key:"airbob/lab/terraform.tfstate.unrelated",VersionId:"other-v1",IsLatest:true}
      ] + $extraVersions),
      DeleteMarkers: ($deleteMarkers + [
        {Key:"airbob/lab/terraform.tfstate.unrelated",VersionId:"other-delete",IsLatest:false}
      ])
    }'
    ;;
  *) exit 64 ;;
esac
EOF
chmod 700 "$fixture_scripts/aws-lab.sh" "$fixture_scripts/cleanup-expired-lab.sh" "$temp_dir/bin/aws"
: > "$temp_dir/operator.log"

jq_bin=$(command -v jq)
jq_dir=$(dirname "$jq_bin")

run_cleanup() {
  env PATH="$temp_dir/bin:$jq_dir:/usr/bin:/bin:/sbin" AWS_REGION=ap-northeast-2 \
    AIRBOB_NOW_EPOCH=1900000000 FAKE_OPERATOR_LOG="$temp_dir/operator.log" \
    FAKE_FIXTURE_DIR="$fixture_data" FAKE_CLEAN_KEY="$clean_key" "$@" \
    "$fixture_scripts/cleanup-expired-lab.sh"
}

run_cleanup FAKE_INVENTORY_KIND=empty FAKE_BACKEND_KIND=absent \
  | grep -Fq 'reason=no-active-lab backend_state=absent' \
  || fail "absent backend did not exit cleanly"
run_cleanup FAKE_INVENTORY_KIND=current | grep -Fq 'cleanup_due=false' \
  || fail "unexpired inventory triggered cleanup"
[[ ! -s "$temp_dir/operator.log" ]] || fail "operator ran before expiresAt"
run_cleanup FAKE_INVENTORY_KIND=at_expiry | grep -Fq 'cleanup_due=true' \
  || fail "inventory was not selected exactly at expiresAt"
grep -Fqx 'operator down run=lab-expiry-test force=true' "$temp_dir/operator.log" \
  || fail "expiry-bound cleanup did not invoke the shared forced-down path"
: > "$temp_dir/operator.log"
run_cleanup FAKE_INVENTORY_KIND=expired | grep -Fq 'cleanup_due=true' \
  || fail "expired inventory was not selected"
grep -Fqx 'operator down run=lab-expiry-test force=true' "$temp_dir/operator.log" \
  || fail "expired cleanup did not invoke the shared forced-down path"

: > "$temp_dir/operator.log"
run_cleanup FAKE_INVENTORY_KIND=empty FAKE_BACKEND_KIND=active \
  | grep -Fq 'cleanup_due=true run_id=lab-recovery-test recovery_state=active' \
  || fail "untagged active state did not recover its exact run identity"
grep -Fqx 'operator down run=lab-recovery-test force=true' "$temp_dir/operator.log" \
  || fail "untagged active state did not use the explicit-run forced-down path"

: > "$temp_dir/operator.log"
run_cleanup FAKE_INVENTORY_KIND=empty FAKE_BACKEND_KIND=identity \
  FAKE_MANIFEST_EXPIRES_AT=1900000001 \
  | grep -Fq 'cleanup_due=false run_id=lab-recovery-test recovery_state=identity-only' \
  || fail "identity-only cancellation ignored its immutable unexpired manifest"
[[ ! -s "$temp_dir/operator.log" ]] \
  || fail "identity-only cancellation invoked down before manifest expiry"
run_cleanup FAKE_INVENTORY_KIND=empty FAKE_BACKEND_KIND=identity \
  | grep -Fq 'cleanup_due=true run_id=lab-recovery-test recovery_state=identity-only' \
  || fail "identity-only cancellation was not selected after expiry"
grep -Fqx 'operator down run=lab-recovery-test force=true' "$temp_dir/operator.log" \
  || fail "identity-only recovery did not use the explicit-run forced-down path"

: > "$temp_dir/operator.log"
run_cleanup FAKE_INVENTORY_KIND=empty FAKE_BACKEND_KIND=empty \
  | grep -Fq 'cleanup_due=true run_id=lab-recovery-test recovery_state=pending-finalization' \
  || fail "empty state without a clean receipt did not recover its direct predecessor run"
grep -Fqx 'operator down run=lab-recovery-test force=true' "$temp_dir/operator.log" \
  || fail "empty-state finalization recovery did not use the explicit-run forced-down path"

: > "$temp_dir/operator.log"
run_cleanup FAKE_INVENTORY_KIND=empty FAKE_BACKEND_KIND=clean \
  | grep -Fq 'reason=no-active-lab backend_state=clean' \
  || fail "exact clean-state receipt did not classify the backend as clean"
[[ ! -s "$temp_dir/operator.log" ]] || fail "clean backend invoked the operator"

for unsafe_history in denied stale-current duplicate-current delete-marker; do
  : > "$temp_dir/operator.log"
  if run_cleanup FAKE_INVENTORY_KIND=empty FAKE_BACKEND_KIND=empty \
    FAKE_VERSION_HISTORY="$unsafe_history" > "$temp_dir/history-$unsafe_history.out" \
    2> "$temp_dir/history-$unsafe_history.err"; then
    fail "unsafe state history was accepted: $unsafe_history"
  fi
  [[ ! -s "$temp_dir/operator.log" ]] \
    || fail "unsafe state history reached the operator: $unsafe_history"
done

: > "$temp_dir/operator.log"
if run_cleanup FAKE_INVENTORY_KIND=empty FAKE_BACKEND_KIND=empty \
  FAKE_PREDECESSOR_KIND=wrong > "$temp_dir/wrong-predecessor.out" \
  2> "$temp_dir/wrong-predecessor.err"; then
  fail "empty state accepted a predecessor outside the direct lineage/serial chain"
fi
[[ ! -s "$temp_dir/operator.log" ]] \
  || fail "unproven empty-state predecessor reached the operator"

printf '%s\n' 'expiry cleanup contract tests passed'
