#!/usr/bin/env bash
set -euo pipefail
umask 077

test_dir=$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$test_dir/../../.." && pwd -P)
runner="$repo_root/load-test/k6/read-model/run-aws-read-model-discovery.sh"
fixture="$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v2.json"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-read-model-runner-test.XXXXXX")

cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  rm -rf -- "$temp_dir"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

fail() { printf '%s\n' "$1" >&2; exit 1; }
assert_contains() {
  if ! grep -Fq -- "$2" "$1"; then
    sed -n '1,40p' "$1" >&2
    fail "$1 does not contain: $2"
  fi
}
assert_rejected() {
  description=$1
  shift
  if "$@" >"$temp_dir/rejected.out" 2>&1; then
    fail "runner accepted $description"
  fi
}

[[ -x "$runner" && ! -L "$runner" ]] || fail 'AWS read-model runner is missing or unsafe'
bash -n "$runner"

for contract in \
  'orchestration-lease.sh' \
  'capture-optimizer-state.sql' \
  'capture-statement-digests.sql' \
  'capture-explain-analyze.sh' \
  'aggregate-read-model-observations.mjs' \
  'READ_MODEL_MODE=measure' \
  'READ_MODEL_MODE=assemble' \
  'ANALYZE TABLE' \
  'innodb_stats_auto_recalc' \
  'pre_fingerprint_sha256' \
  'post_fingerprint_sha256' \
  "--if-none-match '*'" \
  'measurements/$run_id/read-model/$target_id' \
  'run_candidate_raw_evidence' \
  '/api/v2/benchmark/read-model/runtime-assertion' \
  'resource_fencing_token_sha256' \
  'measurement_fencing_token_sha256' \
  'challenge_sha256' \
  'runtime_assertion_pre_sha256' \
  'runtime_assertion_post_sha256' \
  'active_profiles' \
  'read-model-candidate-raw-evidence-v1' \
  'application_k6_latency_supported:false' \
  'Retention=raw' \
  '.query.lastId' \
  '.query.lastCreatedAt' \
  'page_limit=$((size + 1))' \
  'SELECT w.* FROM wishlist w' \
  'ROUND(AVG(r.rating),2)' \
  'WISHLIST_PAGE_SELECT' \
  'UNION ALL' \
  "'CANCEL','PARTIAL_CANCEL'" \
  'REVENUE_LEDGER_ROLLUP' \
  'ensure_private_directory' \
  'test($fragment)' \
  'select(length == 1)'
do
  assert_contains "$runner" "$contract"
done

if grep -Eiq '(^|[^A-Z])(ALTER[[:space:]]+TABLE|CREATE[[:space:]]+(UNIQUE[[:space:]]+)?INDEX|DROP[[:space:]]+INDEX)([^A-Z]|$)' "$runner"; then
  fail 'read-model discovery runner must not decide or execute index DDL'
fi
if grep -Eq 'put-object.*(PASSWORD|TOKEN)|--body.*(PASSWORD|TOKEN)' "$runner"; then
  fail 'read-model discovery runner must not publish credentials'
fi
if grep -Fq 'mkdir -p "$repo_root/build"' "$runner"; then
  fail 'artifact parents must be checked before creation instead of following symlinks'
fi

common_environment=(
  READ_MODEL_DISCOVERY_MODE=plan
  RUN_ID=read-model-test
  RELEASE_ID=production-seed-20260827t000000z
  CLONE_ID=clone-a
  TARGET_ID=review-hot
  BENCHMARK_DATASET_MANIFEST="$fixture"
)

env "${common_environment[@]}" "$runner" > "$temp_dir/plan.json"
jq -e '
  .schema_version == "read-model-discovery-plan-v1" and
  .release_id == "production-seed-20260827t000000z" and
  .clone_id == "clone-a" and
  .target_id == "review-hot" and
  .candidate_index == null and
  .maximum_aa_relative_delta == 0.10 and
  (.windows | length) == 12 and
  ([.windows[].window_id] | unique | length) == 12 and
  [.windows[].design] == [
    "AA_NOISE", "AA_NOISE", "AA_NOISE", "AA_NOISE", "AA_NOISE", "AA_NOISE",
    "READ_MODEL_AB", "READ_MODEL_AB", "READ_MODEL_AB", "READ_MODEL_AB",
    "READ_MODEL_AB", "READ_MODEL_AB"
  ] and
  [.windows[].variant] == [
    "after", "after", "after", "after", "after", "after",
    "before", "after", "after", "before", "before", "after"
  ] and
  [.windows[].pair_role] == [
    "AA_A", "AA_B", "AA_A", "AA_B", "AA_A", "AA_B",
    "BEFORE", "AFTER", "AFTER", "BEFORE", "BEFORE", "AFTER"
  ] and
  ([.windows[] | .release_id] | unique) == ["production-seed-20260827t000000z"] and
  ([.windows[] | .clone_id] | unique) == ["clone-a"] and
  ([.windows[] | .target_id] | unique) == ["review-hot"]
' "$temp_dir/plan.json" >/dev/null

assert_rejected 'an A/A threshold above the fixed policy cap' \
  env "${common_environment[@]}" AA_MAX_RELATIVE_DELTA=0.101 "$runner"
assert_contains "$temp_dir/rejected.out" 'must not exceed 0.10'

printf '%s\n' '[{"name":"idx_review_status_accommodation","visible":false}]' \
  > "$temp_dir/one-candidate.json"
if command -v sha256sum >/dev/null 2>&1; then
  fixture_manifest_sha=$(sha256sum "$fixture" | awk '{print $1}')
else
  fixture_manifest_sha=$(shasum -a 256 "$fixture" | awk '{print $1}')
fi
runtime_revision=$(printf '%064d' 0 | tr 0 e)
app_instance_id=i-0123456789abcdef0
resource_fence_sha=$(printf resource-fence | { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; } | awk '{print $1}')
measurement_fence_sha=$(printf measurement-fence | { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; } | awk '{print $1}')
aa_fixture_repo="$temp_dir/aa-fixture-repo"
mkdir -p "$aa_fixture_repo/load-test/k6/read-model" "$aa_fixture_repo/build/k6/read-model"
cp "$repo_root/load-test/k6/read-model/aggregate-read-model-observations.mjs" \
  "$aa_fixture_repo/load-test/k6/read-model/"

write_valid_aa() {
  output=$1 clone=$2 fingerprint=$3 target=$4
  target_json=$(jq -cer --arg target "$target" '
    .capsules[]|select(.capsuleId=="read-model-v2")|.targets[]|select(.id==$target)
  ' "$fixture")
  query_kind=$(jq -r '.query.kind' <<<"$target_json")
  expected_rows=$(jq -r '.expectedRows' <<<"$target_json")
  expected_hash=$(jq -r '.expectedResultHash' <<<"$target_json")
  parameter_sha=$(printf '%s' "$(jq -cS '.query' <<<"$target_json")" \
    | { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; } \
    | awk '{print $1}')
  case "$query_kind" in
    REVIEW_SUMMARY_V1) domain=review ;;
    WISHLIST_PAGE_V1) domain=wishlist ;;
    REVENUE_RANGE_V1) domain=revenue ;;
  esac
  account_ref=$(jq -r '
    if has("account") then ((.account.role|ascii_downcase)+"-"+(.account.memberId|tostring))
    else "null" end
  ' <<<"$target_json")
  stem="fixture-$target-$clone-${fingerprint:0:12}"
  sources=()
  for index in 1 2 3 4 5 6; do
    pair=$(((index + 1) / 2))
    if ((index % 2 == 1)); then role=AA_A; else role=AA_B; fi
    role_lower=$(printf '%s' "$role" | tr '[:upper:]' '[:lower:]')
    source_relative="build/k6/read-model/$stem-$index.json"
    source_path="$aa_fixture_repo/$source_relative"
    jq -n \
      --arg generated "2026-08-27T00:00:0${index}.000Z" --arg block "aa-0$pair" \
      --arg window "aa-0$pair-$role_lower" --arg event "aa-event-$index" --arg role "$role" \
      --arg domain "$domain" --arg target "$target" --arg queryKind "$query_kind" \
      --arg release production-seed-20260827t000000z --arg manifest "$fixture_manifest_sha" \
      --arg calibration "$(jq -r '.world.provenance.calibrationSha256' "$fixture")" \
      --arg spec "$(jq -r '.world.provenance.specSha256' "$fixture")" \
      --arg targetFingerprint "$(jq -r '.targetFingerprint' "$fixture")" \
      --arg parameter "$parameter_sha" --arg expectedHash "$expected_hash" \
      --arg accountRef "$account_ref" --argjson expectedRows "$expected_rows" \
      --arg clone "$clone" --arg fingerprint "$fingerprint" \
      --arg revision "$runtime_revision" --arg instance "$app_instance_id" \
      --arg resourceFence "$resource_fence_sha" --arg measurementFence "$measurement_fence_sha" \
      --arg challengePre "$(printf "pre-$target-$clone-$index" | { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; } | awk '{print $1}')" \
      --arg challengePost "$(printf "post-$target-$clone-$index" | { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; } | awk '{print $1}')" \
      --argjson latency "$((20 + index))" '
      def receipt($challenge): {
        schema_version:1,run_id:"fixture-run",resource_fencing_token_sha256:$resourceFence,
        challenge_sha256:$challenge,runtime_revision:$revision,app_instance_id:$instance,
        active_profiles:["aws","read-model-benchmark","traffic-benchmark"],
        scheduler_enabled:false,kafka_listener_enabled:false,
        inventory_lifecycle_enabled:false,external_side_effects_enabled:false
      };
      {
        schema_version:"read-model-evidence-v1",
        metadata:{generated_at:$generated,run_id:"fixture-run",
          design:"AA_NOISE",experiment_id:"fixture-aa",
          block_id:$block,window_id:$window,statement_event_id:$event,domain:$domain,
          target_class:$target,variant:"after",pair_role:$role,phase:"measure",
          release_tuple:{release_id:$release,dataset_version:"benchmark-dataset-v2",world_version:"world-v2",
            source_calibration_sha256:$calibration,production_skew_spec_sha256:$spec,
            dataset_manifest_sha256:$manifest,dump_sha256:("d"*64),
            schema_migration_sha256:("f"*64),target_fingerprint_sha256:$targetFingerprint},
          manifest_target:{capsule_id:"read-model-v2",target_id:$target,query_kind:$queryKind,
            parameter_hash_sha256:$parameter,expected_rows:$expectedRows,
            expected_result_hash:$expectedHash,account_ref:(if $accountRef=="null" then null else $accountRef end)},
          app_build:{commit_sha:("1"*40),image_digest:("sha256:"+("2"*64)),
            build_id:"read-model-build",instance_count:1,runtime_revision:$revision,
            app_instance_id:$instance,resource_fencing_token_sha256:$resourceFence},
          database:{clone_id:$clone,pre_fingerprint_sha256:$fingerprint,
            post_fingerprint_sha256:$fingerprint,optimizer_snapshot_sha256:("3"*64),
            statistics_snapshot_sha256:("4"*64),histogram_snapshot_sha256:("5"*64),
            analyze_receipt_sha256:("6"*64),mysql_version:"8.0.42",
            auto_statistics_recalculation_detected:false},
          treatment:{kind:"READ_MODEL",candidate_index:null,candidate_visible:null,
            optimizer_switch_use_invisible_indexes:false}},
        validity:{status:"valid",reasons:[],errors:0,dropped_iterations:0},
        parity:{verified:true,expected_rows:$expectedRows,observed_rows:$expectedRows,
          expected_result_hash:$expectedHash,before_result_hash:$expectedHash,
          after_result_hash:$expectedHash},
        performance:{headline_scope:"measure-only",excluded_phases:["setup","login","analyze","explain"],
          requests:{attempted:100,successful:100,failed:0,dropped_iterations:0},
          latency_ms:{count:100,min:10,p50:$latency,p95:($latency+5),p99:($latency+10),max:($latency+15)}},
        measurement_fencing_token_sha256:$measurementFence,
        runtime_assertion:{runtime_assertion_pre_sha256:("7"*64),
          runtime_assertion_post_sha256:("8"*64),pre:receipt($challengePre),post:receipt($challengePost)},
        mysql_evidence:{statement_event:{window_id:$window,event_id:$event,digest:("9"*64),
          digest_text:"SELECT benchmark",delta:{calls:100,timer_wait_ps:"100",rows_examined:"100",rows_sent:"100",errors:0}},
          optimizer_state:{snapshot_sha256:("3"*64),statistics_snapshot_sha256:("4"*64),
            histogram_snapshot_sha256:("5"*64),analyze_receipt_sha256:("6"*64)},
          explain:{json_raw:"{\"query_block\":{}}",
            tree_raw:"-> Table scan (actual time=0.01..1 rows=1 loops=1)",candidate_in_chosen_plan:false}}
      }' > "$source_path"
    sources+=("$source_relative")
  done
  aggregate_relative="build/k6/read-model/$stem-observations.json"
  (cd "$aa_fixture_repo" && node load-test/k6/read-model/aggregate-read-model-observations.mjs \
    --output "$aggregate_relative" "${sources[@]}")
  cp "$aa_fixture_repo/$aggregate_relative" "$output"
}

write_valid_aa "$temp_dir/valid-aa.json" clone-a "$(printf '%064d' 0 | tr 0 a)" review-hot
env "${common_environment[@]}" \
  CANDIDATE_INDEX=idx_review_status_accommodation \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
  AA_NOISE_OBSERVATION="$temp_dir/valid-aa.json" \
  "$runner" > "$temp_dir/candidate-plan.json"
jq -e '
  .candidate_index == "idx_review_status_accommodation" and
  .candidate_gate == "one-invisible-candidate-per-clone" and
  .requires_valid_aa_noise_artifact == true and
  .maximum_aa_relative_delta == 0.10 and
  .protocol == "RAW-EXPLAIN-ONLY" and
  .application_k6_latency_supported == false and
  .application_performance_publication_supported == false and
  .raw_evidence_publication_supported == true and
  .application_performance_reason == "app-session-invisible-index-treatment-unavailable" and
  (has("windows") | not) and
  [.raw_mysql_captures[].window_id] == ["candidate-baseline", "candidate-enabled"] and
  ([.raw_mysql_captures[].evidence_query_role] | unique) == ["REVIEW_SUMMARY_LOOKUP"] and
  [.raw_mysql_captures[].treatment] == ["index-baseline", "index-candidate"] and
  [.raw_mysql_captures[].optimizer_switch_use_invisible_indexes] == [false, true]
' "$temp_dir/candidate-plan.json" >/dev/null

assert_rejected 'an A/A artifact captured under a different threshold' \
  env "${common_environment[@]}" AA_MAX_RELATIVE_DELTA=0.05 \
  CANDIDATE_INDEX=idx_review_status_accommodation \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
  AA_NOISE_OBSERVATION="$temp_dir/valid-aa.json" \
  "$runner"
assert_contains "$temp_dir/rejected.out" 'AA noise artifact is unbound'

wishlist_digest_pattern='FROM[[:space:]]+`?WISHLIST`?[[:space:]]'
jq -ne --arg pattern "$wishlist_digest_pattern" '
  [
    "SELECT w.id FROM wishlist w ORDER BY w.created_at DESC",
    "SELECT wa.wishlist_id FROM wishlist_accommodation wa GROUP BY wa.wishlist_id",
    "SELECT ranked.wishlist_id FROM (SELECT wa.wishlist_id FROM wishlist_accommodation wa) ranked"
  ]
  | map(select(ascii_upcase | test($pattern)))
  | length == 1
' >/dev/null || fail 'wishlist digest pattern did not isolate WISHLIST_PAGE_SELECT'

write_valid_aa "$temp_dir/wishlist-deep-aa.json" clone-a \
  "$(printf '%064d' 0 | tr 0 a)" wishlist-hot-deep
env "${common_environment[@]}" TARGET_ID=wishlist-hot-deep \
  CANDIDATE_INDEX=idx_review_status_accommodation \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
  AA_NOISE_OBSERVATION="$temp_dir/wishlist-deep-aa.json" \
  "$runner" > "$temp_dir/wishlist-deep-plan.json"
jq -e '
  .target_id == "wishlist-hot-deep" and
  ([.raw_mysql_captures[].evidence_query_role] | unique) == ["WISHLIST_PAGE_SELECT"]
' "$temp_dir/wishlist-deep-plan.json" >/dev/null

write_valid_aa "$temp_dir/revenue-refund-aa.json" clone-a \
  "$(printf '%064d' 0 | tr 0 a)" revenue-refund-boundary
env "${common_environment[@]}" TARGET_ID=revenue-refund-boundary INDEX_VARIANT=before \
  CANDIDATE_INDEX=idx_review_status_accommodation \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
  AA_NOISE_OBSERVATION="$temp_dir/revenue-refund-aa.json" \
  "$runner" > "$temp_dir/revenue-refund-plan.json"
jq -e '
  .target_id == "revenue-refund-boundary" and
  ([.raw_mysql_captures[].evidence_query_role] | unique) == ["REVENUE_LEDGER_ROLLUP"]
' "$temp_dir/revenue-refund-plan.json" >/dev/null

jq '.headline.maximum_absolute_relative_delta.p95 = 0.11' \
  "$temp_dir/valid-aa.json" > "$temp_dir/high-noise-aa.json"
assert_rejected 'a candidate plan with a high-noise A/A artifact' \
  env "${common_environment[@]}" \
  CANDIDATE_INDEX=idx_review_status_accommodation \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
  AA_NOISE_OBSERVATION="$temp_dir/high-noise-aa.json" \
  "$runner"
assert_contains "$temp_dir/rejected.out" 'does not satisfy the exact six-window contract'

jq 'del(.observations)' "$temp_dir/valid-aa.json" > "$temp_dir/shallow-aa.json"
assert_rejected 'a shallow self-authored A/A summary without six observations' \
  env "${common_environment[@]}" \
  CANDIDATE_INDEX=idx_review_status_accommodation \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
  AA_NOISE_OBSERVATION="$temp_dir/shallow-aa.json" \
  "$runner"
assert_contains "$temp_dir/rejected.out" 'does not satisfy the exact six-window contract'

write_valid_aa "$temp_dir/foreign-clone-aa.json" clone-foreign \
  "$(printf '%064d' 0 | tr 0 a)" review-hot
assert_rejected 'an exact A/A artifact captured from another clone' \
  env "${common_environment[@]}" \
  CANDIDATE_INDEX=idx_review_status_accommodation \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
  AA_NOISE_OBSERVATION="$temp_dir/foreign-clone-aa.json" \
  "$runner"
assert_contains "$temp_dir/rejected.out" 'AA noise artifact is unbound'

printf '%s\n' '[
  {"name":"idx_review_status_accommodation","visible":false},
  {"name":"idx_review_created","visible":false}
]' > "$temp_dir/two-candidates.json"
assert_rejected 'two invisible candidates in one clone' env "${common_environment[@]}" \
  CANDIDATE_INDEX=idx_review_status_accommodation \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/two-candidates.json" \
  AA_NOISE_OBSERVATION="$temp_dir/valid-aa.json" \
  "$runner"

assert_rejected 'a candidate name that differs from the clone inventory' \
  env "${common_environment[@]}" \
  CANDIDATE_INDEX=idx_other \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
  AA_NOISE_OBSERVATION="$temp_dir/valid-aa.json" \
  "$runner"

fake_bin="$temp_dir/fake-bin"
mkdir -m 700 "$fake_bin"
for command_name in aws k6 mysql; do
  printf '%s\n' '#!/bin/sh' 'exit 99' > "$fake_bin/$command_name"
  chmod 700 "$fake_bin/$command_name"
done
assert_rejected 'an S3 prefix outside the load-generator IAM fence' \
  env "${common_environment[@]}" READ_MODEL_DISCOVERY_MODE=run \
  PATH="$fake_bin:$PATH" BASE_URL=https://app.lab.airbob.internal \
  MYSQL_LOGIN_PATH=airbob-benchmark AWS_EVIDENCE_BUCKET=airbob-evidence \
  AWS_EVIDENCE_PREFIX="read-model/read-model-test/review-hot" \
  "$runner"
assert_contains "$temp_dir/rejected.out" \
  'AWS_EVIDENCE_PREFIX must match the load-generator IAM measurement fence'

mock_repo="$temp_dir/mock-repo"
raw_bin="$temp_dir/raw-bin"
mkdir -p \
  "$mock_repo/load-test/k6/read-model" \
  "$mock_repo/load-test/mysql" \
  "$mock_repo/infra/aws/scripts" \
  "$raw_bin"
cp "$runner" "$mock_repo/load-test/k6/read-model/run-aws-read-model-discovery.sh"
cp "$repo_root/load-test/k6/read-model/aggregate-read-model-observations.mjs" \
  "$mock_repo/load-test/k6/read-model/"
cp "$repo_root/load-test/mysql/capture-optimizer-state.sql" "$mock_repo/load-test/mysql/"
cp "$repo_root/load-test/mysql/capture-statement-digests.sql" "$mock_repo/load-test/mysql/"
cp "$repo_root/load-test/mysql/capture-explain-analyze.sh" "$mock_repo/load-test/mysql/"
cp "$repo_root/infra/aws/scripts/orchestration-lease.sh" "$mock_repo/infra/aws/scripts/"
chmod 700 \
  "$mock_repo/load-test/k6/read-model/run-aws-read-model-discovery.sh" \
  "$mock_repo/load-test/mysql/capture-explain-analyze.sh" \
  "$mock_repo/infra/aws/scripts/orchestration-lease.sh"

cat > "$raw_bin/aws" <<'AWS'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_AWS_LOG"
while [[ "${1:-}" == --cli-connect-timeout || "${1:-}" == --cli-read-timeout ]]; do
  [[ "$#" -ge 2 ]] || exit 1
  shift 2
done
service=$1
operation=$2
if [[ "$service:$operation" == dynamodb:update-item ]]; then
  if [[ " $* " == *' --return-values UPDATED_NEW '* ]]; then
    printf '%s\n' 1
  fi
  exit 0
fi
if [[ "$service:$operation" == dynamodb:get-item ]]; then
  now=$(date +%s)
  future=$((now + 3600))
  printf '%s\t1\t%s\tmeasurement\t%s\t%s\t%s\t%s\n' \
    "$FAKE_LEASE_OWNER" "$FAKE_RUN_ID" "$now" "$now" "$future" "$future"
  exit 0
fi
if [[ "$service:$operation" == s3api:put-object ]]; then
  body=''
  previous=''
  for argument in "$@"; do
    [[ "$previous" != --body ]] || body=$argument
    previous=$argument
  done
  [[ -n "$body" ]]
  cp "$body" "$FAKE_PUBLISHED_ARTIFACT"
  printf '%s\n' '{}'
  exit 0
fi
exit 99
AWS

cat > "$raw_bin/mysql" <<'MYSQL'
#!/usr/bin/env bash
set -euo pipefail
query=''
init_command=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --execute) shift; query=$1 ;;
    --init-command=*) init_command=$(printf '%s' "$1" | sed 's/^--init-command=//') ;;
  esac
  shift
done
if [[ -z "$query" ]]; then
  if [[ "$init_command" == *'@airbob_evidence_window_id='* ]]; then
    window=$(printf '%s' "$init_command" \
      | sed -n "s/.*@airbob_evidence_window_id='\([^']*\)'.*/\1/p")
    statement_table=accommodation_review_summary
    [[ "$window" != *-before ]] || statement_table=review
    printf '%s\n' \
      "{\"schemaVersion\":1,\"schemaName\":\"airbobdb\",\"windowId\":\"$window\",\"targetId\":\"review-hot\",\"threadId\":\"1\",\"eventId\":\"1\",\"digest\":\"9999999999999999999999999999999999999999999999999999999999999999\",\"digestText\":\"SELECT ars.* FROM $statement_table ars WHERE accommodation_id = ?\",\"timerStart\":\"101\",\"timerEnd\":\"199\",\"timerWait\":\"100\",\"lockTime\":\"0\",\"rowsAffected\":\"0\",\"rowsSent\":\"1\",\"rowsExamined\":\"1\",\"createdTmpDiskTables\":\"0\",\"noIndexUsed\":0,\"noGoodIndexUsed\":0,\"errorNumber\":0}"
    exit 0
  fi
  switch=off
  [[ "$init_command" != *use_invisible_indexes=on* ]] || switch=on
  snapshot=$(printf '%s' "$init_command" \
    | sed -n "s/.*snapshot_id='\([^']*\)'.*/\1/p")
  printf '%s\n' \
    "{\"schemaVersion\":1,\"recordType\":\"server\",\"snapshotId\":\"$snapshot\",\"optimizerSwitch\":\"index_merge=on,use_invisible_indexes=$switch\"}" \
    "{\"schemaVersion\":1,\"recordType\":\"table-stat\",\"snapshotId\":\"$snapshot\",\"tableName\":\"review\",\"rowCount\":\"100\"}" \
    "{\"schemaVersion\":1,\"recordType\":\"index-stat\",\"snapshotId\":\"$snapshot\",\"tableName\":\"review\",\"indexName\":\"idx_review_status_accommodation\"}" \
    "{\"schemaVersion\":1,\"recordType\":\"index-definition\",\"snapshotId\":\"$snapshot\",\"tableName\":\"review\",\"indexName\":\"idx_review_status_accommodation\",\"isVisible\":\"NO\"}" \
    "{\"schemaVersion\":1,\"recordType\":\"histogram\",\"snapshotId\":\"$snapshot\",\"tableName\":\"review\",\"columnName\":\"status\",\"histogramSha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}"
  exit 0
fi
case "$query" in
  *innodb_stats_auto_recalc*) printf '%s\n' 0 ;;
  'ANALYZE TABLE '*) printf '%s\n' $'review\tanalyze\tstatus\tOK' ;;
  'CHECKSUM TABLE '*)
    checksum_count=1
    if [[ -n "${FAKE_CHECKSUM_STATE:-}" ]]; then
      [[ ! -f "$FAKE_CHECKSUM_STATE" ]] || checksum_count=$(( $(cat "$FAKE_CHECKSUM_STATE") + 1 ))
      printf '%s\n' "$checksum_count" > "$FAKE_CHECKSUM_STATE"
    fi
    [[ -z "${FAKE_CHECKSUM_LOG:-}" ]] \
      || printf '%s\n' "$checksum_count" >> "$FAKE_CHECKSUM_LOG"
    checksum=12345
    [[ "${FAKE_CHECKSUM_DRIFT_AT:-}" != "$checksum_count" ]] || checksum=54321
    printf 'airbobdb.review\t%s\n' "$checksum"
    ;;
  *'SELECT COALESCE(MAX(TIMER_END),1)'*)
    timer_count=1
    if [[ -n "${FAKE_TIMER_STATE:-}" ]]; then
      [[ ! -f "$FAKE_TIMER_STATE" ]] || timer_count=$(( $(cat "$FAKE_TIMER_STATE") + 1 ))
      printf '%s\n' "$timer_count" > "$FAKE_TIMER_STATE"
    fi
    if ((timer_count % 2 == 1)); then printf '%s\n' 100; else printf '%s\n' 200; fi
    ;;
  *'SELECT COUNT(*) FROM (SELECT DISTINCT TABLE_NAME, INDEX_NAME'*)
    printf '%s\n' "${FAKE_INVISIBLE_INDEX_COUNT:-1}" ;;
  *'SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS'*)
    printf '%s\n' "$FAKE_CANDIDATE_INDEX" ;;
  'SELECT VERSION()') printf '%s\n' '8.0.42' ;;
  *'SELECT @@SESSION.optimizer_switch'*)
    if [[ "$query" == *use_invisible_indexes=on* ]]; then
      printf '%s\n' 'index_merge=on,use_invisible_indexes=on'
    else
      printf '%s\n' 'index_merge=on,use_invisible_indexes=off'
    fi
    ;;
  *'EXPLAIN FORMAT=JSON'*)
    if [[ "$query" == *use_invisible_indexes=on* \
      && "${FAKE_CANDIDATE_CHOSEN:-true}" == true ]]; then
      printf '{"query_block":{"table":{"key":"%s"}}}\n' "$FAKE_CANDIDATE_INDEX"
    else
      printf '%s\n' '{"query_block":{"table":{"key":"PRIMARY"}}}'
    fi
    ;;
  *'EXPLAIN ANALYZE FORMAT=TREE'*)
    printf '%s\n' '-> Index lookup (actual time=1.0e-2..2.5E+1 rows=1.20K loops=1)'
    ;;
  *) printf 'unexpected fake mysql query: %s\n' "$query" >&2; exit 98 ;;
esac
MYSQL

cat > "$raw_bin/k6" <<'K6'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' invoked >> "$FAKE_K6_LOG"
case "${READ_MODEL_MODE:-}" in
  measure)
    output="$FAKE_REPO_ROOT/$K6_MEASUREMENT_PATH"
    jq -n '{metrics:{read_model_request_success:{values:{passes:1,fails:0}}}}' > "$output"
    ;;
  assemble)
    output="$FAKE_REPO_ROOT/$K6_RESULT_PATH"
    context="$READ_MODEL_EVIDENCE_CONTEXT"
    target=$(jq -cer --arg target "$TARGET_ID" '
      .capsules[] | select(.capsuleId == "read-model-v2")
      | .targets[] | select(.id == $target)
    ' "$BENCHMARK_DATASET_MANIFEST")
    canonical_query=$(jq -cS '.query' <<<"$target")
    if command -v sha256sum >/dev/null 2>&1; then
      parameter_sha=$(printf '%s' "$canonical_query" | sha256sum | awk '{print $1}')
    else
      parameter_sha=$(printf '%s' "$canonical_query" | shasum -a 256 | awk '{print $1}')
    fi
    query_kind=$(jq -r '.query.kind' <<<"$target")
    expected_rows=$(jq -r '.expectedRows' <<<"$target")
    expected_hash=$(jq -r '.expectedResultHash' <<<"$target")
    case "$query_kind" in
      REVIEW_SUMMARY_V1) domain=review ;;
      WISHLIST_PAGE_V1) domain=wishlist ;;
      REVENUE_RANGE_V1) domain=revenue ;;
      *) exit 96 ;;
    esac
    account_ref=$(jq -r '
      if has("account") then ((.account.role | ascii_downcase) + "-" + (.account.memberId | tostring))
      else "null" end
    ' <<<"$target")
    role=$(jq -r '.pair_role' "$context")
    case "$role" in
      BEFORE) latency=30 ;;
      AFTER) latency=20 ;;
      AA_A|AA_B) latency=20 ;;
      *) exit 95 ;;
    esac
    jq -n \
      --arg generated '2026-08-28T00:00:00.000Z' --arg domain "$domain" \
      --arg queryKind "$query_kind" --arg parameter "$parameter_sha" \
      --arg expectedHash "$expected_hash" --arg accountRef "$account_ref" \
      --argjson expectedRows "$expected_rows" --argjson latency "$latency" \
      --slurpfile context "$context" '
      ($context[0]) as $ctx |
      {
        schema_version:"read-model-evidence-v1",
        metadata:{generated_at:$generated,run_id:$ctx.run_id,design:$ctx.design,
          experiment_id:$ctx.experiment_id,block_id:$ctx.block_id,window_id:$ctx.window_id,
          statement_event_id:$ctx.statement_event_id,domain:$domain,target_class:$ctx.target_id,
          variant:(if $ctx.pair_role == "BEFORE" then "before" else "after" end),
          pair_role:$ctx.pair_role,phase:"measure",release_tuple:$ctx.release_tuple,
          manifest_target:{capsule_id:"read-model-v2",target_id:$ctx.target_id,
            query_kind:$queryKind,parameter_hash_sha256:$parameter,
            expected_rows:$expectedRows,expected_result_hash:$expectedHash,
            account_ref:(if $accountRef == "null" then null else $accountRef end)},
          app_build:$ctx.app_build,database:$ctx.database,treatment:$ctx.treatment},
        validity:{status:"valid",reasons:[],errors:0,dropped_iterations:0},
        parity:{verified:true,expected_rows:$expectedRows,observed_rows:$expectedRows,
          expected_result_hash:$expectedHash,before_result_hash:$expectedHash,
          after_result_hash:$expectedHash},
        performance:{headline_scope:"measure-only",
          excluded_phases:["setup","login","analyze","explain"],
          requests:{attempted:1,successful:1,failed:0,dropped_iterations:0},
          latency_ms:{count:1,min:$latency,p50:$latency,p95:$latency,p99:$latency,max:$latency}},
        measurement_fencing_token_sha256:$ctx.measurement_fencing_token_sha256,
        runtime_assertion:$ctx.runtime_assertion,mysql_evidence:$ctx.mysql_evidence
      }
    ' > "$output"
    ;;
  *) exit 97 ;;
esac
K6

cat > "$raw_bin/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
config=''
request=''
connect_timeout=''
max_time=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)
      shift
      [[ "$1" == - ]] || exit 91
      config=$(cat)
      ;;
    --data-binary)
      shift
      request=${1#@}
      ;;
    --connect-timeout)
      shift
      connect_timeout=$1
      ;;
    --max-time)
      shift
      max_time=$1
      ;;
  esac
  shift
done
[[ -f "$request" ]]
[[ "$connect_timeout" == 5 ]]
[[ "$max_time" == 30 ]]
grep -Fq "X-Benchmark-Token: $FAKE_BENCHMARK_TOKEN" <<<"$config"
response=$(jq -c \
  --arg revision "$FAKE_RUNTIME_REVISION" --arg instance "$FAKE_APP_INSTANCE_ID" '
  {
    schema_version:1,run_id:.run_id,
    resource_fencing_token_sha256:.resource_fencing_token_sha256,
    challenge_sha256:.challenge_sha256,runtime_revision:$revision,app_instance_id:$instance,
    active_profiles:["aws","read-model-benchmark","traffic-benchmark"],
    scheduler_enabled:false,kafka_listener_enabled:false,
    inventory_lifecycle_enabled:false,external_side_effects_enabled:false
  }' "$request")
case "${FAKE_RUNTIME_ASSERTION_MODE:-valid}" in
  valid) ;;
  unavailable) exit 93 ;;
  wrong-run) response=$(jq -c '.run_id="other-run"' <<<"$response") ;;
  wrong-fence) response=$(jq -c '.resource_fencing_token_sha256=("f"*64)' <<<"$response") ;;
  wrong-challenge) response=$(jq -c '.challenge_sha256=("e"*64)' <<<"$response") ;;
  wrong-revision) response=$(jq -c '.runtime_revision=("d"*64)' <<<"$response") ;;
  wrong-instance) response=$(jq -c '.app_instance_id="i-fffffffffffffffff"' <<<"$response") ;;
  wrong-profile) response=$(jq -c '.active_profiles=["aws","read-model-benchmark"]' <<<"$response") ;;
  writer-enabled) response=$(jq -c '.scheduler_enabled=true' <<<"$response") ;;
  replay)
    if [[ -s "$FAKE_ASSERTION_CACHE" ]]; then
      cat "$FAKE_ASSERTION_CACHE"
      exit 0
    fi
    printf '%s\n' "$response" > "$FAKE_ASSERTION_CACHE"
    ;;
  *) exit 92 ;;
esac
printf '%s\n' "$response"
CURL
chmod 700 "$raw_bin/aws" "$raw_bin/mysql" "$raw_bin/k6" "$raw_bin/curl"

jq -n \
  --arg release production-seed-20260827t000000z \
  --arg manifest "$fixture_manifest_sha" \
  --arg calibration "$(jq -r '.world.provenance.calibrationSha256' "$fixture")" \
  --arg spec "$(jq -r '.world.provenance.specSha256' "$fixture")" \
  --arg targetFingerprint "$(jq -r '.targetFingerprint' "$fixture")" '
  {
    release_id:$release,dataset_version:"benchmark-dataset-v2",world_version:"world-v2",
    source_calibration_sha256:$calibration,production_skew_spec_sha256:$spec,
    dataset_manifest_sha256:$manifest,dump_sha256:("d"*64),
    schema_migration_sha256:("e"*64),target_fingerprint_sha256:$targetFingerprint
  }' > "$temp_dir/release-tuple.json"
runtime_revision=$(printf '%064d' 0 | tr 0 e)
app_instance_id=i-0123456789abcdef0
jq -n --arg revision "$runtime_revision" --arg instance "$app_instance_id" \
  --arg resourceFence "$(printf resource-fence | { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; } | awk '{print $1}')" '
  {commit_sha:("1"*40),image_digest:("sha256:"+("2"*64)),build_id:"read-model-build",
   instance_count:1,runtime_revision:$revision,app_instance_id:$instance,
   resource_fencing_token_sha256:$resourceFence}' \
  > "$temp_dir/app-build.json"
printf '%064d\n' 3 > "$temp_dir/benchmark-token"
chmod 600 "$temp_dir/release-tuple.json" "$temp_dir/app-build.json" \
  "$temp_dir/benchmark-token"
raw_database_fingerprint=$(printf 'airbobdb.review\t12345\n' \
  | { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; } \
  | awk '{print $1}')
write_valid_aa "$temp_dir/raw-aa.json" clone-candidate "$raw_database_fingerprint" review-hot

raw_runner="$mock_repo/load-test/k6/read-model/run-aws-read-model-discovery.sh"
symlink_target="$temp_dir/symlink-target"
mkdir -m 700 "$symlink_target"
ln -s "$symlink_target" "$mock_repo/build"
assert_rejected 'a symbolic-link artifact parent' env \
  READ_MODEL_DISCOVERY_MODE=run \
  RUN_ID=raw-symlink-test \
  RELEASE_ID=production-seed-20260827t000000z \
  CLONE_ID=clone-candidate \
  TARGET_ID=review-hot \
  BENCHMARK_DATASET_MANIFEST="$fixture" \
  CANDIDATE_INDEX=idx_review_status_accommodation \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
  AA_NOISE_OBSERVATION="$temp_dir/raw-aa.json" \
  MYSQL_LOGIN_PATH=airbob-benchmark \
  RELEASE_TUPLE_JSON="$temp_dir/release-tuple.json" \
  APP_BUILD_JSON="$temp_dir/app-build.json" \
  BENCHMARK_TOKEN_FILE="$temp_dir/benchmark-token" \
  BASE_URL=https://app.lab.airbob.internal \
  AWS_EVIDENCE_BUCKET=airbob-evidence \
  AWS_REGION=ap-northeast-2 \
  PATH="$raw_bin:$PATH" \
  "$raw_runner"
assert_contains "$temp_dir/rejected.out" 'artifact directories must not be symbolic links'
[[ -z "$(find "$symlink_target" -mindepth 1 -print -quit)" ]] \
  || fail 'symlink parent rejection wrote outside the mock repository'
rm -- "$mock_repo/build"

env \
  READ_MODEL_DISCOVERY_MODE=run \
  RUN_ID=raw-candidate-test \
  RELEASE_ID=production-seed-20260827t000000z \
  CLONE_ID=clone-candidate \
  TARGET_ID=review-hot \
  BENCHMARK_DATASET_MANIFEST="$fixture" \
  CANDIDATE_INDEX=idx_review_status_accommodation \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
  AA_NOISE_OBSERVATION="$temp_dir/raw-aa.json" \
  MYSQL_LOGIN_PATH=airbob-benchmark \
  RELEASE_TUPLE_JSON="$temp_dir/release-tuple.json" \
  APP_BUILD_JSON="$temp_dir/app-build.json" \
  BENCHMARK_TOKEN_FILE="$temp_dir/benchmark-token" \
  BASE_URL=https://app.lab.airbob.internal \
  AWS_EVIDENCE_BUCKET=airbob-evidence \
  AWS_REGION=ap-northeast-2 \
  LEASE_OWNER=test-owner \
  PATH="$raw_bin:$PATH" \
  FAKE_AWS_LOG="$temp_dir/aws.log" \
  FAKE_LEASE_OWNER=test-owner \
  FAKE_RUN_ID=raw-candidate-test \
  FAKE_CANDIDATE_INDEX=idx_review_status_accommodation \
  FAKE_CANDIDATE_CHOSEN=true \
  FAKE_PUBLISHED_ARTIFACT="$temp_dir/published-candidate.json" \
  FAKE_K6_LOG="$temp_dir/k6.log" \
  FAKE_BENCHMARK_TOKEN="$(tr -d '\n' < "$temp_dir/benchmark-token")" \
  FAKE_RUNTIME_REVISION="$runtime_revision" \
  FAKE_APP_INSTANCE_ID="$app_instance_id" \
  FAKE_ASSERTION_CACHE="$temp_dir/assertion-cache.json" \
  "$raw_runner" > "$temp_dir/raw-run.out"

[[ ! -e "$temp_dir/k6.log" ]] || fail 'candidate raw run invoked k6'
assert_contains "$temp_dir/aws.log" 'Retention=raw'
assert_contains "$temp_dir/aws.log" 'measurements/raw-candidate-test/read-model/review-hot/'
jq -e '
  .schema_version == "read-model-candidate-raw-evidence-v1" and
  .metadata.run_id == "raw-candidate-test" and
  .metadata.app_build.resource_fencing_token_sha256
    == .runtime_assertions.baseline.pre.resource_fencing_token_sha256 and
  .metadata.app_build.resource_fencing_token_sha256
    != .metadata.measurement_fencing_token_sha256 and
  .metadata.evidence_query_role == "REVIEW_SUMMARY_LOOKUP" and
  .metadata.application_k6_latency_supported == false and
  .metadata.performance_claim == null and
  .validity.status == "valid" and
  .database.pre_fingerprint_sha256 == .database.post_fingerprint_sha256 and
  .captures.baseline.treatment == "index-baseline" and
  .captures.candidate.treatment == "index-candidate" and
  .captures.baseline.explain.explain.candidate_in_chosen_plan == false and
  .captures.candidate.explain.explain.candidate_in_chosen_plan == true and
  .eligibility.status == "candidate-ready" and
  (has("performance") | not)
' "$temp_dir/published-candidate.json" >/dev/null
assert_contains "$temp_dir/raw-run.out" 'candidate_in_chosen_plan=true'
candidate_checksum_receipts=$(find \
  "$mock_repo/build/k6/read-model/raw-candidate-test-review-hot" -maxdepth 1 -type f \
  -name '*-database-checksum.tsv' -exec basename {} \; | sort | tr '\n' ' ')
[[ "$candidate_checksum_receipts" == \
  'candidate-final-database-checksum.tsv candidate-start-database-checksum.tsv ' ]] \
  || fail 'candidate raw mode did not retain its exact pre/post checksum fence'

run_normal_discovery() {
  normal_run_id=$1
  checksum_drift_at=${2:-}
  checksum_state="$temp_dir/$normal_run_id-checksum-state"
  checksum_log="$temp_dir/$normal_run_id-checksum.log"
  timer_state="$temp_dir/$normal_run_id-timer-state"
  aws_log="$temp_dir/$normal_run_id-aws.log"
  published="$temp_dir/$normal_run_id-published.json"
  k6_log="$temp_dir/$normal_run_id-k6.log"
  assertion_cache="$temp_dir/$normal_run_id-assertion-cache.json"
  (
    cd "$mock_repo"
    env \
      READ_MODEL_DISCOVERY_MODE=run \
      RUN_ID="$normal_run_id" \
      RELEASE_ID=production-seed-20260827t000000z \
      CLONE_ID=clone-normal \
      TARGET_ID=review-hot \
      BENCHMARK_DATASET_MANIFEST="$fixture" \
      CANDIDATE_INDEX= \
      INVISIBLE_INDEX_INVENTORY= \
      MYSQL_LOGIN_PATH=airbob-benchmark \
      RELEASE_TUPLE_JSON="$temp_dir/release-tuple.json" \
      APP_BUILD_JSON="$temp_dir/app-build.json" \
      BENCHMARK_TOKEN_FILE="$temp_dir/benchmark-token" \
      BASE_URL=https://app.lab.airbob.internal \
      AWS_EVIDENCE_BUCKET=airbob-evidence \
      AWS_REGION=ap-northeast-2 \
      LEASE_OWNER=test-owner \
      PATH="$raw_bin:$PATH" \
      FAKE_AWS_LOG="$aws_log" \
      FAKE_LEASE_OWNER=test-owner \
      FAKE_RUN_ID="$normal_run_id" \
      FAKE_INVISIBLE_INDEX_COUNT=0 \
      FAKE_PUBLISHED_ARTIFACT="$published" \
      FAKE_K6_LOG="$k6_log" \
      FAKE_REPO_ROOT="$mock_repo" \
      FAKE_BENCHMARK_TOKEN="$(tr -d '\n' < "$temp_dir/benchmark-token")" \
      FAKE_RUNTIME_REVISION="$runtime_revision" \
      FAKE_APP_INSTANCE_ID="$app_instance_id" \
      FAKE_ASSERTION_CACHE="$assertion_cache" \
      FAKE_CHECKSUM_STATE="$checksum_state" \
      FAKE_CHECKSUM_LOG="$checksum_log" \
      FAKE_CHECKSUM_DRIFT_AT="$checksum_drift_at" \
      FAKE_TIMER_STATE="$timer_state" \
      "$raw_runner"
  )
}

run_normal_discovery boundary-stable > "$temp_dir/boundary-stable.out"
stable_run_root="$mock_repo/build/k6/read-model/boundary-stable-review-hot"
[[ "$(wc -l < "$temp_dir/boundary-stable-checksum.log" | tr -d '[:space:]')" == 3 ]] \
  || fail 'normal discovery did not use exactly three full database checksums'
[[ "$(wc -l < "$temp_dir/boundary-stable-k6.log" | tr -d '[:space:]')" == 24 ]] \
  || fail 'normal discovery did not measure and assemble all twelve windows'
[[ "$(find "$stable_run_root" -maxdepth 1 -type f -name '*-optimizer-pre.jsonl' | wc -l | tr -d '[:space:]')" == 12 \
  && "$(find "$stable_run_root" -maxdepth 1 -type f -name '*-optimizer-post.jsonl' | wc -l | tr -d '[:space:]')" == 12 \
  && "$(find "$stable_run_root" -maxdepth 1 -type f -name '*-runtime-pre.json' | wc -l | tr -d '[:space:]')" == 12 \
  && "$(find "$stable_run_root" -maxdepth 1 -type f -name '*-runtime-post.json' | wc -l | tr -d '[:space:]')" == 12 ]] \
  || fail 'per-window optimizer or runtime pre/post evidence was not retained'
boundary_receipts=$(find "$stable_run_root" -maxdepth 1 -type f \
  -name '*-database-checksum.tsv' -exec basename {} \; | sort | tr '\n' ' ')
[[ "$boundary_receipts" == \
  'after-aa-database-checksum.tsv final-database-checksum.tsv run-start-database-checksum.tsv ' ]] \
  || fail 'normal discovery created a per-window or missing boundary checksum receipt'

if command -v sha256sum >/dev/null 2>&1; then
  start_fingerprint=$(sha256sum "$stable_run_root/run-start-database-checksum.tsv" | awk '{print $1}')
  after_aa_fingerprint=$(sha256sum "$stable_run_root/after-aa-database-checksum.tsv" | awk '{print $1}')
  final_fingerprint=$(sha256sum "$stable_run_root/final-database-checksum.tsv" | awk '{print $1}')
else
  start_fingerprint=$(shasum -a 256 "$stable_run_root/run-start-database-checksum.tsv" | awk '{print $1}')
  after_aa_fingerprint=$(shasum -a 256 "$stable_run_root/after-aa-database-checksum.tsv" | awk '{print $1}')
  final_fingerprint=$(shasum -a 256 "$stable_run_root/final-database-checksum.tsv" | awk '{print $1}')
fi
jq -e --arg start "$start_fingerprint" --arg end "$after_aa_fingerprint" '
  .metadata.database.pre_fingerprint_sha256 == $start and
  .metadata.database.post_fingerprint_sha256 == $end and
  .metadata.observation_count == 6
' "$mock_repo/build/k6/read-model/boundary-stable-review-hot-aa-observations.json" \
  >/dev/null || fail 'A/A windows are not bound to the run-start/after-AA bracket'
jq -e --arg start "$after_aa_fingerprint" --arg end "$final_fingerprint" '
  .metadata.database.pre_fingerprint_sha256 == $start and
  .metadata.database.post_fingerprint_sha256 == $end and
  .metadata.observation_count == 6
' "$mock_repo/build/k6/read-model/boundary-stable-review-hot-read-model-observations.json" \
  >/dev/null || fail 'A/B windows are not bound to the after-AA/final bracket'
jq -se --arg start "$start_fingerprint" --arg afterAa "$after_aa_fingerprint" '
  length == 12 and
  ([.[] | select(.phase == "aa")] | length) == 6 and
  ([.[] | select(.phase == "ab")] | length) == 6 and
  ([.[] | select(.phase == "aa") |
    .fingerprint_bracket == {start_boundary:"run-start",end_boundary:"after-aa",
      start_fingerprint_sha256:$start}] | length) == 6 and
  ([.[] | select(.phase == "ab") |
    .fingerprint_bracket == {start_boundary:"after-aa",end_boundary:"final",
      start_fingerprint_sha256:$afterAa}] | length) == 6
' "$stable_run_root"/*-pending.json >/dev/null \
  || fail 'one or more windows were assigned to the wrong fingerprint bracket'

assert_rejected 'database drift at the after-AA boundary' \
  run_normal_discovery boundary-after-aa-drift 2
assert_contains "$temp_dir/rejected.out" 'A/A database fingerprint drift invalidates the phase'
[[ "$(wc -l < "$temp_dir/boundary-after-aa-drift-checksum.log" | tr -d '[:space:]')" == 2 ]] \
  || fail 'after-AA drift did not stop at its boundary'
[[ "$(wc -l < "$temp_dir/boundary-after-aa-drift-k6.log" | tr -d '[:space:]')" == 6 ]] \
  || fail 'A/A evidence was assembled before its boundary passed'
[[ ! -e "$mock_repo/build/k6/read-model/boundary-after-aa-drift-review-hot-aa-observations.json" ]] \
  || fail 'A/A observations were assembled after boundary drift'

assert_rejected 'database drift at the final boundary' \
  run_normal_discovery boundary-final-drift 3
assert_contains "$temp_dir/rejected.out" 'A/B database fingerprint drift invalidates the phase'
[[ "$(wc -l < "$temp_dir/boundary-final-drift-checksum.log" | tr -d '[:space:]')" == 3 ]] \
  || fail 'final drift did not stop at its boundary'
[[ "$(wc -l < "$temp_dir/boundary-final-drift-k6.log" | tr -d '[:space:]')" == 18 ]] \
  || fail 'A/B evidence was assembled before its final boundary passed'
if grep -Fq 's3api put-object' "$temp_dir/boundary-final-drift-aws.log"; then
  fail 'a boundary-drifted run published summary evidence'
fi

write_valid_aa "$temp_dir/wrong-current-fingerprint-aa.json" clone-candidate \
  "$(printf '%064d' 0 | tr 0 b)" review-hot
assert_rejected 'an exact A/A artifact from a different current database state' env \
  READ_MODEL_DISCOVERY_MODE=run \
  RUN_ID=raw-fingerprint-drift \
  RELEASE_ID=production-seed-20260827t000000z \
  CLONE_ID=clone-candidate \
  TARGET_ID=review-hot \
  BENCHMARK_DATASET_MANIFEST="$fixture" \
  CANDIDATE_INDEX=idx_review_status_accommodation \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
  AA_NOISE_OBSERVATION="$temp_dir/wrong-current-fingerprint-aa.json" \
  MYSQL_LOGIN_PATH=airbob-benchmark \
  RELEASE_TUPLE_JSON="$temp_dir/release-tuple.json" \
  APP_BUILD_JSON="$temp_dir/app-build.json" \
  BENCHMARK_TOKEN_FILE="$temp_dir/benchmark-token" \
  BASE_URL=https://app.lab.airbob.internal \
  AWS_EVIDENCE_BUCKET=airbob-evidence \
  AWS_REGION=ap-northeast-2 \
  LEASE_OWNER=test-owner \
  PATH="$raw_bin:$PATH" \
  FAKE_AWS_LOG="$temp_dir/fingerprint-drift-aws.log" \
  FAKE_LEASE_OWNER=test-owner \
  FAKE_RUN_ID=raw-fingerprint-drift \
  FAKE_CANDIDATE_INDEX=idx_review_status_accommodation \
  "$raw_runner"
assert_contains "$temp_dir/rejected.out" \
  'AA noise artifact does not bind the current database fingerprint'

assert_runtime_rejected() {
  assertion_mode=$1
  assertion_run=$2
  assertion_description=$3
  assertion_failure=${4:-live isolated-read runtime assertion is invalid or replayed}
  assert_rejected "$assertion_description" env \
    READ_MODEL_DISCOVERY_MODE=run \
    RUN_ID="$assertion_run" \
    RELEASE_ID=production-seed-20260827t000000z \
    CLONE_ID=clone-candidate \
    TARGET_ID=review-hot \
    BENCHMARK_DATASET_MANIFEST="$fixture" \
    CANDIDATE_INDEX=idx_review_status_accommodation \
    INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
    AA_NOISE_OBSERVATION="$temp_dir/raw-aa.json" \
    MYSQL_LOGIN_PATH=airbob-benchmark \
    RELEASE_TUPLE_JSON="$temp_dir/release-tuple.json" \
    APP_BUILD_JSON="$temp_dir/app-build.json" \
    BENCHMARK_TOKEN_FILE="$temp_dir/benchmark-token" \
    BASE_URL=https://app.lab.airbob.internal \
    AWS_EVIDENCE_BUCKET=airbob-evidence \
    AWS_REGION=ap-northeast-2 \
    LEASE_OWNER=test-owner \
    PATH="$raw_bin:$PATH" \
    FAKE_AWS_LOG="$temp_dir/$assertion_run-aws.log" \
    FAKE_LEASE_OWNER=test-owner \
    FAKE_RUN_ID="$assertion_run" \
    FAKE_CANDIDATE_INDEX=idx_review_status_accommodation \
    FAKE_CANDIDATE_CHOSEN=true \
    FAKE_PUBLISHED_ARTIFACT="$temp_dir/$assertion_run-published.json" \
    FAKE_K6_LOG="$temp_dir/$assertion_run-k6.log" \
    FAKE_BENCHMARK_TOKEN="$(tr -d '\n' < "$temp_dir/benchmark-token")" \
    FAKE_RUNTIME_REVISION="$runtime_revision" \
    FAKE_APP_INSTANCE_ID="$app_instance_id" \
    FAKE_RUNTIME_ASSERTION_MODE="$assertion_mode" \
    FAKE_ASSERTION_CACHE="$temp_dir/$assertion_run-assertion-cache.json" \
    "$raw_runner"
  assert_contains "$temp_dir/rejected.out" "$assertion_failure"
}

assert_runtime_rejected unavailable assertion-unavailable \
  'a direct-MySQL candidate run with no live app assertion' \
  'live isolated-read runtime assertion is unavailable'
assert_runtime_rejected wrong-run assertion-wrong-run \
  'a live assertion for another provisioning run'
assert_runtime_rejected wrong-fence assertion-wrong-fence \
  'a live assertion for another resource fence'
assert_runtime_rejected wrong-challenge assertion-wrong-challenge \
  'a live assertion that does not answer the fresh challenge'
assert_runtime_rejected wrong-revision assertion-wrong-revision \
  'a live assertion from another runtime revision'
assert_runtime_rejected wrong-instance assertion-wrong-instance \
  'a live assertion from another app instance'
assert_runtime_rejected wrong-profile assertion-wrong-profile \
  'a live assertion with profile drift'
assert_runtime_rejected writer-enabled assertion-writer-enabled \
  'a live assertion with an enabled writer lifecycle'
assert_runtime_rejected replay assertion-replay \
  'a stale assertion replayed after the first fresh challenge'

assert_rejected 'a legacy static lifecycle receipt' env \
  READ_MODEL_DISCOVERY_MODE=run \
  RUN_ID=assertion-static-receipt \
  RELEASE_ID=production-seed-20260827t000000z \
  CLONE_ID=clone-candidate \
  TARGET_ID=review-hot \
  BENCHMARK_DATASET_MANIFEST="$fixture" \
  CANDIDATE_INDEX=idx_review_status_accommodation \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
  AA_NOISE_OBSERVATION="$temp_dir/raw-aa.json" \
  MYSQL_LOGIN_PATH=airbob-benchmark \
  RELEASE_TUPLE_JSON="$temp_dir/release-tuple.json" \
  APP_BUILD_JSON="$temp_dir/app-build.json" \
  BENCHMARK_TOKEN_FILE="$temp_dir/benchmark-token" \
  BASE_URL=https://app.lab.airbob.internal \
  AWS_EVIDENCE_BUCKET=airbob-evidence \
  PATH="$raw_bin:$PATH" \
  LIFECYCLE_RECEIPT_JSON="$temp_dir/raw-aa.json" \
  "$raw_runner"
assert_contains "$temp_dir/rejected.out" \
  'LIFECYCLE_RECEIPT_JSON is obsolete; live runtime assertion is mandatory'

assert_rejected 'a candidate that is absent from the chosen plan' env \
  READ_MODEL_DISCOVERY_MODE=run \
  RUN_ID=raw-not-chosen-test \
  RELEASE_ID=production-seed-20260827t000000z \
  CLONE_ID=clone-candidate \
  TARGET_ID=review-hot \
  BENCHMARK_DATASET_MANIFEST="$fixture" \
  CANDIDATE_INDEX=idx_review_status_accommodation \
  INVISIBLE_INDEX_INVENTORY="$temp_dir/one-candidate.json" \
  AA_NOISE_OBSERVATION="$temp_dir/raw-aa.json" \
  MYSQL_LOGIN_PATH=airbob-benchmark \
  RELEASE_TUPLE_JSON="$temp_dir/release-tuple.json" \
  APP_BUILD_JSON="$temp_dir/app-build.json" \
  BENCHMARK_TOKEN_FILE="$temp_dir/benchmark-token" \
  BASE_URL=https://app.lab.airbob.internal \
  AWS_EVIDENCE_BUCKET=airbob-evidence \
  AWS_REGION=ap-northeast-2 \
  LEASE_OWNER=test-owner \
  PATH="$raw_bin:$PATH" \
  FAKE_AWS_LOG="$temp_dir/not-chosen-aws.log" \
  FAKE_LEASE_OWNER=test-owner \
  FAKE_RUN_ID=raw-not-chosen-test \
  FAKE_CANDIDATE_INDEX=idx_review_status_accommodation \
  FAKE_CANDIDATE_CHOSEN=false \
  FAKE_PUBLISHED_ARTIFACT="$temp_dir/not-chosen-candidate.json" \
  FAKE_K6_LOG="$temp_dir/not-chosen-k6.log" \
  FAKE_BENCHMARK_TOKEN="$(tr -d '\n' < "$temp_dir/benchmark-token")" \
  FAKE_RUNTIME_REVISION="$runtime_revision" \
  FAKE_APP_INSTANCE_ID="$app_instance_id" \
  FAKE_ASSERTION_CACHE="$temp_dir/not-chosen-assertion-cache.json" \
  "$raw_runner"
[[ ! -e "$temp_dir/not-chosen-k6.log" ]] || fail 'not-chosen raw run invoked k6'
assert_contains "$temp_dir/rejected.out" 'candidate was not in the chosen plan'
jq -e '
  .eligibility.status == "not-chosen" and
  .eligibility.candidate_in_chosen_plan == false and
  .eligibility.reasons == ["candidate-not-in-chosen-plan"] and
  .metadata.performance_claim == null
' "$temp_dir/not-chosen-candidate.json" >/dev/null

assert_rejected 'a non-read-model target' env \
  READ_MODEL_DISCOVERY_MODE=plan \
  RUN_ID=read-model-test \
  RELEASE_ID=production-seed-20260827t000000z \
  CLONE_ID=clone-a \
  TARGET_ID=search-medium \
  BENCHMARK_DATASET_MANIFEST="$fixture" \
  "$runner"

assert_rejected 'legacy manual accommodation input' env "${common_environment[@]}" \
  REVIEW_ACCOMMODATION_ID=101 "$runner"

printf '%s\n' 'AWS read-model discovery runner contract tests passed'
