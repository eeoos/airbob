#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() { printf 'AWS read-model discovery failed: %s\n' "$1" >&2; exit 1; }

[[ "$#" -eq 0 ]] || fail 'positional arguments are not accepted'

script_dir=$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
lease_script="$repo_root/infra/aws/scripts/orchestration-lease.sh"
optimizer_sql="$repo_root/load-test/mysql/capture-optimizer-state.sql"
statement_sql="$repo_root/load-test/mysql/capture-statement-digests.sql"
explain_capture="$repo_root/load-test/mysql/capture-explain-analyze.sh"
aggregator="$script_dir/aggregate-read-model-observations.mjs"
artifact_root="$repo_root/build/k6/read-model"

mode=${READ_MODEL_DISCOVERY_MODE:-run}
run_id=${RUN_ID:-}
release_id=${RELEASE_ID:-}
clone_id=${CLONE_ID:-}
target_id=${TARGET_ID:-}
manifest_path=${BENCHMARK_DATASET_MANIFEST:-}
candidate_index=${CANDIDATE_INDEX:-}
candidate_inventory=${INVISIBLE_INDEX_INVENTORY:-}
index_variant=${INDEX_VARIANT:-after}
noise_limit=${AA_MAX_RELATIVE_DELTA:-0.10}

slug='^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$'
lower_slug='^[a-z0-9][a-z0-9-]{2,127}$'
run_slug='^[a-z0-9][a-z0-9-]{2,31}$'
[[ "$mode" == plan || "$mode" == run ]] || fail 'READ_MODEL_DISCOVERY_MODE must be plan or run'
[[ "$run_id" =~ $run_slug && "$run_id" != *--* && "$run_id" != *- ]] \
  || fail 'RUN_ID is invalid'
[[ "$release_id" =~ $slug ]] || fail 'RELEASE_ID is invalid'
[[ "$clone_id" =~ $slug ]] || fail 'CLONE_ID is invalid'
[[ "$target_id" =~ $lower_slug ]] || fail 'TARGET_ID is invalid'
[[ "$index_variant" == before || "$index_variant" == after ]] || fail 'INDEX_VARIANT is invalid'
[[ "$noise_limit" =~ ^0\.[0-9]{1,3}$ ]] || fail 'AA_MAX_RELATIVE_DELTA is invalid'
noise_millis=${noise_limit#0.}
while [[ "${#noise_millis}" -lt 3 ]]; do noise_millis+=0; done
((10#$noise_millis <= 100)) || fail 'AA_MAX_RELATIVE_DELTA must not exceed 0.10'

for legacy_name in \
  REVIEW_ACCOMMODATION_ID EXPECTED_REVIEW_COUNT BENCHMARK_EMAIL ADMIN_EMAIL \
  PAGE_SIZE EXPECTED_ROWS REVENUE_FROM REVENUE_TO DATASET_LABEL
do
  [[ -z "${!legacy_name:-}" ]] || fail "$legacy_name is forbidden; select TARGET_ID from read-model-v2"
done
unset BENCHMARK_READ_MODEL_TOKEN BENCHMARK_ACCOUNT_PASSWORD TEST_PASSWORD ADMIN_PASSWORD
unset NODE_OPTIONS NODE_PATH

for command_name in jq; do
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
done
[[ -f "$manifest_path" && ! -L "$manifest_path" ]] \
  || fail 'BENCHMARK_DATASET_MANIFEST must be a regular non-symbolic-link file'
if command -v sha256sum >/dev/null 2>&1; then
  selected_manifest_sha=$(sha256sum "$manifest_path" | awk '{print $1}')
else
  selected_manifest_sha=$(shasum -a 256 "$manifest_path" | awk '{print $1}')
fi
[[ "$selected_manifest_sha" =~ ^[0-9a-f]{64}$ ]] \
  || fail 'BENCHMARK_DATASET_MANIFEST SHA-256 is invalid'

target_query=$(jq -cer --arg target "$target_id" '
  select(.schemaVersion == 2 and .datasetVersion == "benchmark-dataset-v2")
  | .capsules[] | select(.capsuleId == "read-model-v2" and .mutability == "READ_ONLY")
  | .targets[] | select(.id == $target)
  | select(.query.kind == "REVIEW_SUMMARY_V1"
      or .query.kind == "WISHLIST_PAGE_V1"
      or .query.kind == "REVENUE_RANGE_V1")
  | select((.expectedRows | type) == "number" and .expectedRows >= 0
      and .expectedRows == (.expectedRows | floor))
  | select((.expectedResultHash | type) == "string"
      and (.expectedResultHash | test("^[0-9a-f]{64}$")))
' "$manifest_path") || fail 'TARGET_ID is not one typed read-model-v2 target'
[[ "$(jq -r '.id' <<<"$target_query")" == "$target_id" ]] \
  || fail 'TARGET_ID is ambiguous'
target_kind=$(jq -r '.query.kind' <<<"$target_query")

candidate_json=null
candidate_gate=none
candidate_requested=false
if [[ -n "$candidate_index" ]]; then
  [[ "$candidate_index" =~ ^[a-zA-Z_][a-zA-Z0-9_\$]{0,63}$ ]] \
    || fail 'CANDIDATE_INDEX is invalid'
  [[ -f "$candidate_inventory" && ! -L "$candidate_inventory" ]] \
    || fail 'INVISIBLE_INDEX_INVENTORY is required for a candidate'
  jq -se --arg candidate "$candidate_index" '
    length == 1 and (.[0] | type == "array" and length == 1 and
    .[0] == {name:$candidate,visible:false})
  ' "$candidate_inventory" >/dev/null \
    || fail 'one clone may contain exactly one matching invisible candidate'
  candidate_json=$(jq -Rn --arg value "$candidate_index" '$value')
  candidate_gate=one-invisible-candidate-per-clone
  candidate_requested=true
  aa_noise_artifact=${AA_NOISE_OBSERVATION:-}
  [[ -f "$aa_noise_artifact" && ! -L "$aa_noise_artifact" ]] \
    || fail 'AA_NOISE_OBSERVATION is required before candidate raw SQL evidence'
  node "$aggregator" --validate-aa "$aa_noise_artifact" >/dev/null \
    || fail 'AA noise artifact does not satisfy the exact six-window contract'
  jq -se --arg release "$release_id" --arg clone "$clone_id" --arg target "$target_id" \
    --arg queryKind "$target_kind" --arg expectedHash "$(jq -r '.expectedResultHash' <<<"$target_query")" \
    --argjson expectedRows "$(jq -r '.expectedRows' <<<"$target_query")" \
    --arg manifestSha "$selected_manifest_sha" \
    --argjson limit "$noise_limit" '
    length == 1 and .[0].schema_version == "read-model-observations-v1" and
    .[0].eligibility.status == "valid" and
    .[0].metadata.design == "AA_NOISE" and
    .[0].metadata.aa_max_relative_delta == $limit and
    .[0].metadata.release_tuple.release_id == $release and
    .[0].metadata.release_tuple.dataset_manifest_sha256 == $manifestSha and
    .[0].metadata.database.clone_id == $clone and
    .[0].metadata.database.pre_fingerprint_sha256 == .[0].metadata.database.post_fingerprint_sha256 and
    .[0].metadata.manifest_target.target_id == $target and
    .[0].metadata.manifest_target.query_kind == $queryKind and
    .[0].metadata.manifest_target.expected_rows == $expectedRows and
    .[0].metadata.manifest_target.expected_result_hash == $expectedHash and
    .[0].headline.kind == "AA_NOISE_ENVELOPE" and
    (.[0].headline.maximum_absolute_relative_delta | keys | sort)
      == ["p50","p95","p99"] and
    ([.[0].headline.maximum_absolute_relative_delta[]]
      | all(type == "number" and . >= 0 and . <= $limit))
  ' "$aa_noise_artifact" >/dev/null \
    || fail 'AA noise artifact is unbound, invalid, or exceeds the configured threshold'
elif [[ -n "$candidate_inventory" ]]; then
  jq -se 'length == 1 and (.[0] | type == "array" and length == 0)' \
    "$candidate_inventory" >/dev/null \
    || fail 'a read-model clone must not contain an invisible candidate'
fi

render_plan() {
  if [[ "$candidate_requested" != true ]]; then
    jq -n \
      --arg release "$release_id" --arg clone "$clone_id" --arg target "$target_id" \
      --argjson candidate "$candidate_json" --arg candidateGate "$candidate_gate" \
      --argjson noiseLimit "$noise_limit" '
      def window($design;$block;$window;$role;$variant;$order): {
        release_id:$release, clone_id:$clone, target_id:$target,
        design:$design, block_id:$block, window_id:$window,
        pair_role:$role, variant:$variant, run_order:$order,
        candidate_index:$candidate
      };
      {
        schema_version:"read-model-discovery-plan-v1",
        release_id:$release, clone_id:$clone, target_id:$target,
        candidate_index:$candidate, candidate_gate:$candidateGate,
        protocol:"AA-AA-AA-AB-BA-AB",
        maximum_aa_relative_delta:$noiseLimit,
        windows:[
          window("AA_NOISE";"aa-01";"aa-01-a";"AA_A";"after";1),
          window("AA_NOISE";"aa-01";"aa-01-b";"AA_B";"after";2),
          window("AA_NOISE";"aa-02";"aa-02-a";"AA_A";"after";3),
          window("AA_NOISE";"aa-02";"aa-02-b";"AA_B";"after";4),
          window("AA_NOISE";"aa-03";"aa-03-a";"AA_A";"after";5),
          window("AA_NOISE";"aa-03";"aa-03-b";"AA_B";"after";6),
          window("READ_MODEL_AB";"ab-01";"ab-01-before";"BEFORE";"before";7),
          window("READ_MODEL_AB";"ab-01";"ab-01-after";"AFTER";"after";8),
          window("READ_MODEL_AB";"ba-02";"ba-02-after";"AFTER";"after";9),
          window("READ_MODEL_AB";"ba-02";"ba-02-before";"BEFORE";"before";10),
          window("READ_MODEL_AB";"ab-03";"ab-03-before";"BEFORE";"before";11),
          window("READ_MODEL_AB";"ab-03";"ab-03-after";"AFTER";"after";12)
        ]
      }'
  else
    jq -n \
      --arg release "$release_id" --arg clone "$clone_id" --arg target "$target_id" \
      --arg variant "$index_variant" --arg queryKind "$target_kind" \
      --argjson candidate "$candidate_json" \
      --arg candidateGate "$candidate_gate" --argjson noiseLimit "$noise_limit" '
      def queryRole:
        if $queryKind == "WISHLIST_PAGE_V1" then "WISHLIST_PAGE_SELECT"
        elif $queryKind == "REVIEW_SUMMARY_V1" and $variant == "before" then "REVIEW_RAW_AGGREGATE"
        elif $queryKind == "REVIEW_SUMMARY_V1" then "REVIEW_SUMMARY_LOOKUP"
        elif $variant == "before" then "REVENUE_LEDGER_ROLLUP"
        else "REVENUE_STATS_ROLLUP" end;
      {
        schema_version:"read-model-discovery-plan-v1",
        release_id:$release, clone_id:$clone, target_id:$target,
        candidate_index:$candidate, candidate_gate:$candidateGate,
        protocol:"RAW-EXPLAIN-ONLY",
        requires_valid_aa_noise_artifact:true,
        maximum_aa_relative_delta:$noiseLimit,
        application_k6_latency_supported:false,
        application_performance_publication_supported:false,
        raw_evidence_publication_supported:true,
        application_performance_reason:"app-session-invisible-index-treatment-unavailable",
        raw_mysql_captures:[
          {window_id:"candidate-baseline",treatment:"index-baseline",variant:$variant,
           evidence_query_role:queryRole,
           optimizer_switch_use_invisible_indexes:false},
          {window_id:"candidate-enabled",treatment:"index-candidate",variant:$variant,
           evidence_query_role:queryRole,
           optimizer_switch_use_invisible_indexes:true}
        ]
      }'
  fi
}

if [[ "$mode" == plan ]]; then
  render_plan
  exit 0
fi

required_paths=("$lease_script" "$optimizer_sql" "$explain_capture" "$aggregator")
required_commands=(aws curl jq mysql node openssl)
if [[ "$candidate_requested" != true ]]; then
  required_paths+=("$statement_sql")
  required_commands+=(k6)
fi
for path in "${required_paths[@]}"; do
  [[ -f "$path" && ! -L "$path" ]] || fail 'a required immutable harness input is missing'
done
for command_name in "${required_commands[@]}"; do
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
done

base_url=${BASE_URL:-}
mysql_login_path=${MYSQL_LOGIN_PATH:-}
release_tuple_file=${RELEASE_TUPLE_JSON:-}
app_build_file=${APP_BUILD_JSON:-}
token_file=${BENCHMARK_TOKEN_FILE:-}
password_file=${BENCHMARK_ACCOUNT_PASSWORD_FILE:-}
evidence_bucket=${AWS_EVIDENCE_BUCKET:-}
expected_evidence_prefix="measurements/$run_id/read-model/$target_id"
evidence_prefix=${AWS_EVIDENCE_PREFIX:-$expected_evidence_prefix}
aws_region=${AWS_REGION:-ap-northeast-2}
rate=${RATE:-5}
warmup_duration=${WARMUP_DURATION:-30s}
measure_duration=${MEASURE_DURATION:-1m}

[[ -z "${LIFECYCLE_RECEIPT_JSON:-}" ]] \
  || fail 'LIFECYCLE_RECEIPT_JSON is obsolete; live runtime assertion is mandatory'
[[ "$mysql_login_path" =~ $slug ]] || fail 'MYSQL_LOGIN_PATH is invalid'
[[ "$evidence_bucket" =~ ^[a-z0-9][a-z0-9.-]{2,62}$ ]] || fail 'AWS_EVIDENCE_BUCKET is invalid'
[[ "$evidence_prefix" == "$expected_evidence_prefix" ]] \
  || fail 'AWS_EVIDENCE_PREFIX must match the load-generator IAM measurement fence'
[[ "$base_url" =~ ^https://[a-zA-Z0-9.-]+$ ]] || fail 'BASE_URL must be one HTTPS origin'
if [[ "$candidate_requested" != true ]]; then
  [[ "$rate" =~ ^[1-9][0-9]{0,5}$ ]] || fail 'RATE is invalid'
fi

regular_private_file() {
  [[ -f "$1" && ! -L "$1" ]] || return 1
  local permissions
  if stat -f '%Lp' "$1" >/dev/null 2>&1; then
    permissions=$(stat -f '%Lp' "$1")
  else
    permissions=$(stat -c '%a' "$1")
  fi
  [[ "$permissions" == 600 ]]
}

common_private_files=("$release_tuple_file" "$app_build_file" "$token_file")
for private_file in "${common_private_files[@]}"; do
  regular_private_file "$private_file" || fail 'run contract inputs must be regular mode-0600 files'
done
target_has_account=$(jq -r 'has("account")' <<<"$target_query")
if [[ "$candidate_requested" != true && "$target_has_account" == true ]]; then
  regular_private_file "$password_file" \
    || fail 'BENCHMARK_ACCOUNT_PASSWORD_FILE must be a regular mode-0600 file'
fi

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

sha256_text() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  else
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  fi
}

manifest_sha=$(sha256_file "$manifest_path")
[[ "$manifest_sha" == "$selected_manifest_sha" ]] \
  || fail 'BENCHMARK_DATASET_MANIFEST changed during preflight'
experiment_identity_sha=$(sha256_text "$run_id|$release_id|$clone_id|$target_id")
read_model_experiment_id="read-model-${experiment_identity_sha:0:24}"
jq -se --arg release "$release_id" --arg manifestSha "$manifest_sha" \
  --arg calibration "$(jq -r '.world.provenance.calibrationSha256' "$manifest_path")" \
  --arg spec "$(jq -r '.world.provenance.specSha256' "$manifest_path")" \
  --arg targetFingerprint "$(jq -r '.targetFingerprint' "$manifest_path")" '
  length == 1 and
  (.[0] | keys | sort) == ["dataset_manifest_sha256","dataset_version","dump_sha256",
    "production_skew_spec_sha256","release_id","schema_migration_sha256",
    "source_calibration_sha256","target_fingerprint_sha256","world_version"] and
  .[0].release_id == $release and
  .[0].dataset_version == "benchmark-dataset-v2" and .[0].world_version == "world-v2" and
  .[0].dataset_manifest_sha256 == $manifestSha and
  .[0].source_calibration_sha256 == $calibration and
  .[0].production_skew_spec_sha256 == $spec and
  .[0].target_fingerprint_sha256 == $targetFingerprint and
  ([.[0] | to_entries[] | select(.key | endswith("_sha256")) | .value]
    | all(type == "string" and test("^[0-9a-f]{64}$")))
' "$release_tuple_file" >/dev/null || fail 'RELEASE_TUPLE_JSON does not bind the selected manifest'
jq -se '
  length == 1 and (.[0] | keys | sort)
    == ["app_instance_id","build_id","commit_sha","image_digest","instance_count","resource_fencing_token_sha256","runtime_revision"] and
  (.[0].commit_sha | test("^[0-9a-f]{40}$")) and
  (.[0].image_digest | test("^sha256:[0-9a-f]{64}$")) and
  (.[0].runtime_revision | test("^[0-9a-f]{64}$")) and
  (.[0].resource_fencing_token_sha256 | test("^[0-9a-f]{64}$")) and
  (.[0].app_instance_id | test("^i-[0-9a-f]{8,17}$")) and
  .[0].instance_count == 1
' "$app_build_file" >/dev/null || fail 'APP_BUILD_JSON is invalid or not single-instance'
expected_runtime_revision=$(jq -r '.runtime_revision' "$app_build_file")
expected_app_instance_id=$(jq -r '.app_instance_id' "$app_build_file")
expected_resource_fencing_token_sha256=$(jq -r '.resource_fencing_token_sha256' "$app_build_file")
if [[ "$candidate_requested" == true ]]; then
  selected_parameter_sha=$(sha256_text "$(jq -cS '.query' <<<"$target_query")")
  jq -se --arg parameterSha "$selected_parameter_sha" --slurpfile app "$app_build_file" '
    length == 1 and .[0].metadata.app_build == $app[0] and
    .[0].metadata.manifest_target.parameter_hash_sha256 == $parameterSha
  ' "$aa_noise_artifact" >/dev/null \
    || fail 'AA noise artifact does not bind the selected app runtime and query parameters'
fi

ensure_private_directory() {
  local directory=$1
  if [[ -L "$directory" ]]; then
    fail 'artifact directories must not be symbolic links'
  fi
  if [[ -e "$directory" ]]; then
    [[ -d "$directory" ]] || fail 'artifact path is not a directory'
  else
    mkdir -m 700 "$directory"
  fi
  [[ -d "$directory" && ! -L "$directory" ]] \
    || fail 'artifact directory creation was not safe'
  [[ "$(CDPATH= cd -P -- "$directory" && pwd -P)" == "$directory" ]] \
    || fail 'artifact directory is outside the canonical repository path'
}

ensure_private_directory "$repo_root/build"
ensure_private_directory "$repo_root/build/k6"
ensure_private_directory "$artifact_root"
run_root="$artifact_root/$run_id-$target_id"
[[ ! -e "$run_root" && ! -L "$run_root" ]] || fail 'run artifact directory already exists'
mkdir -m 700 "$run_root"
plan_file="$run_root/plan.json"
render_plan > "$plan_file"
chmod 600 "$plan_file"

lease_table=${LEASE_TABLE:-airbob-performance-lab-orchestration-lease}
lease_lock_id=${LEASE_LOCK_ID:-airbob-performance-lab}
lease_owner=${LEASE_OWNER:-local/$(id -un):$$}
lease_ttl=${LEASE_TTL_SECONDS:-180}
lease_deadline=${LEASE_DEADLINE_SECONDS:-5400}
lease_acquired=false
heartbeat_pid=''

cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  if [[ -n "$heartbeat_pid" ]]; then
    kill "$heartbeat_pid" 2>/dev/null || true
    wait "$heartbeat_pid" 2>/dev/null || true
  fi
  if [[ "$lease_acquired" == true ]]; then
    "$lease_script" release "$lease_table" "$lease_lock_id" "$lease_owner" \
      "$fencing_token" "$run_id" measurement >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

token_output=$("$lease_script" acquire "$lease_table" "$lease_lock_id" "$lease_owner" \
  "$run_id" measurement "$lease_ttl" "$lease_deadline")
fencing_token=${token_output#fencing_token=}
[[ "$fencing_token" =~ ^[1-9][0-9]*$ ]] || fail 'orchestration lease returned an invalid fence'
lease_acquired=true
parent_pid=$$
(
  while sleep 60; do
    "$lease_script" heartbeat "$lease_table" "$lease_lock_id" "$lease_owner" \
      "$fencing_token" "$run_id" measurement "$lease_ttl" >/dev/null \
      || { kill -TERM "$parent_pid" 2>/dev/null || true; exit 1; }
  done
) &
heartbeat_pid=$!
assert_lease() {
  "$lease_script" assert "$lease_table" "$lease_lock_id" "$lease_owner" \
    "$fencing_token" "$run_id" measurement >/dev/null
}
measurement_fencing_token_sha256=$(sha256_text "$fencing_token")

capture_runtime_assertion() {
  local label=$1 output=$2 challenge challenge_sha request benchmark_token
  assert_lease
  challenge=$(openssl rand -hex 32)
  [[ "$challenge" =~ ^[0-9a-f]{64}$ ]] || fail 'runtime assertion challenge generation failed'
  challenge_sha=$(sha256_text "$challenge")
  unset challenge
  request="$run_root/$label-runtime-assertion-request.json"
  jq -n --arg runId "$run_id" --arg fence "$expected_resource_fencing_token_sha256" \
    --arg challenge "$challenge_sha" \
    '{run_id:$runId,resource_fencing_token_sha256:$fence,challenge_sha256:$challenge}' > "$request"
  chmod 600 "$request"
  benchmark_token=$(tr -d '\r\n' < "$token_file")
  [[ "$benchmark_token" =~ ^[0-9a-f]{64}$ ]] || fail 'benchmark token file is invalid'
  if printf 'header = "X-Benchmark-Token: %s"\n' "$benchmark_token" \
    | curl --config - --connect-timeout 5 --max-time 30 \
      --fail --silent --show-error --request POST \
      --header 'Content-Type: application/json' --data-binary "@$request" \
      "$base_url/api/v2/benchmark/read-model/runtime-assertion" > "$output"; then
    :
  else
    unset benchmark_token
    fail 'live isolated-read runtime assertion is unavailable'
  fi
  unset benchmark_token
  chmod 600 "$output"
  jq -se --arg runId "$run_id" --arg fence "$expected_resource_fencing_token_sha256" \
    --arg challenge "$challenge_sha" --arg revision "$expected_runtime_revision" \
    --arg instance "$expected_app_instance_id" '
    length == 1 and (.[0] | keys | sort) == [
      "active_profiles","app_instance_id","challenge_sha256","external_side_effects_enabled",
      "inventory_lifecycle_enabled","kafka_listener_enabled","resource_fencing_token_sha256","run_id",
      "runtime_revision","scheduler_enabled","schema_version"
    ] and .[0].schema_version == 1 and .[0].run_id == $runId and
    .[0].resource_fencing_token_sha256 == $fence and .[0].challenge_sha256 == $challenge and
    .[0].runtime_revision == $revision and .[0].app_instance_id == $instance and
    .[0].active_profiles == ["aws","read-model-benchmark","traffic-benchmark"] and
    .[0].scheduler_enabled == false and .[0].kafka_listener_enabled == false and
    .[0].inventory_lifecycle_enabled == false and
    .[0].external_side_effects_enabled == false
  ' "$output" >/dev/null || fail 'live isolated-read runtime assertion is invalid or replayed'
  sha256_file "$output"
}

mysql_exec() {
  mysql --login-path="$mysql_login_path" --database=airbobdb --batch --raw \
    --skip-column-names --connect-timeout=10 --execute "$1"
}

auto_recalc=$(mysql_exec 'SELECT @@GLOBAL.innodb_stats_auto_recalc')
[[ "$auto_recalc" == 0 || "$auto_recalc" == OFF ]] \
  || fail 'innodb_stats_auto_recalc must be disabled before ANALYZE TABLE'
analyze_receipt="$run_root/analyze-receipt.txt"
mysql_exec 'ANALYZE TABLE review,accommodation_review_summary,wishlist,wishlist_accommodation,accommodation_image,daily_revenue_stats,payment_transaction,reservation' \
  > "$analyze_receipt"
chmod 600 "$analyze_receipt"
analyze_receipt_sha=$(sha256_file "$analyze_receipt")

capture_fingerprint() {
  local boundary_id=$1 receipt
  [[ "$boundary_id" =~ ^[a-z0-9][a-z0-9-]{2,63}$ ]] \
    || fail 'database fingerprint boundary is invalid'
  receipt="$run_root/$boundary_id-database-checksum.tsv"
  [[ ! -e "$receipt" && ! -L "$receipt" ]] \
    || fail 'database fingerprint boundary was already captured'
  mysql_exec 'CHECKSUM TABLE accommodation_inventory_day,outbox,payment_transaction,reservation,review,accommodation_review_summary,wishlist,wishlist_accommodation,daily_revenue_stats EXTENDED' \
    > "$receipt"
  chmod 600 "$receipt"
  sha256_file "$receipt"
}

capture_optimizer() {
  output=$1
  snapshot_id=$2
  invisible_switch=${3:-off}
  [[ "$invisible_switch" == off || "$invisible_switch" == on ]] \
    || fail 'optimizer invisible-index switch is invalid'
  mysql --login-path="$mysql_login_path" --database=airbobdb --batch --raw \
    --skip-column-names --connect-timeout=10 \
    --init-command="SET @airbob_optimizer_snapshot_id='$snapshot_id'; SET SESSION optimizer_switch='use_invisible_indexes=$invisible_switch'" \
    < "$optimizer_sql" > "$output"
  chmod 600 "$output"
}

validate_optimizer_capture() {
  input=$1
  snapshot_id=$2
  invisible_switch=$3
  jq -se --arg snapshot "$snapshot_id" --arg switch "$invisible_switch" '
    length > 0 and all(.snapshotId == $snapshot) and
    ([.[] | select(.recordType == "server")] | length) == 1 and
    ([.[] | select(.recordType == "table-stat")] | length) > 0 and
    ([.[] | select(.recordType == "index-stat")] | length) > 0 and
    ([.[] | select(.recordType == "index-definition")] | length) > 0 and
    ([.[] | select(.recordType == "server")][0].optimizerSwitch
      | contains("use_invisible_indexes=" + $switch))
  ' "$input" >/dev/null || fail 'optimizer snapshot is incomplete or treatment-unbound'
}

optimizer_hashes() {
  input=$1
  prefix=$2
  jq -sc 'map(select(.recordType == "table-stat" or .recordType == "index-stat" or .recordType == "index-definition"))' \
    "$input" > "$prefix-statistics.json"
  jq -sc 'map(select(.recordType == "histogram"))' "$input" > "$prefix-histograms.json"
  printf '%s\t%s\t%s\n' "$(sha256_file "$input")" \
    "$(sha256_file "$prefix-statistics.json")" "$(sha256_file "$prefix-histograms.json")"
}

write_query() {
  variant=$1
  output=$2
  [[ "$output" == "$artifact_root/"* && ! -e "$output" && ! -L "$output" ]] \
    || fail 'query artifact path already exists or is unsafe'
  kind=$(jq -r '.query.kind' <<<"$target_query")
  case "$kind:$variant" in
    REVIEW_SUMMARY_V1:before)
      accommodation_id=$(jq -r '.query.accommodationId' <<<"$target_query")
      [[ "$accommodation_id" =~ ^[1-9][0-9]*$ ]] \
        || fail 'review accommodation parameter is invalid'
      printf "SELECT COUNT(*) total_count,COALESCE(ROUND(AVG(r.rating),2),0) average_rating FROM review r WHERE r.accommodation_id=%s AND r.status='PUBLISHED';\n" "$accommodation_id" > "$output"
      ;;
    REVIEW_SUMMARY_V1:after)
      accommodation_id=$(jq -r '.query.accommodationId' <<<"$target_query")
      [[ "$accommodation_id" =~ ^[1-9][0-9]*$ ]] \
        || fail 'review accommodation parameter is invalid'
      printf 'SELECT ars.* FROM accommodation_review_summary ars WHERE ars.accommodation_id=%s;\n' "$accommodation_id" > "$output"
      ;;
    WISHLIST_PAGE_V1:*)
      member_id=$(jq -r '.query.memberId' <<<"$target_query")
      size=$(jq -r '.query.size' <<<"$target_query")
      last_id=$(jq -r '.query.lastId' <<<"$target_query")
      last_created_at=$(jq -r '.query.lastCreatedAt' <<<"$target_query")
      [[ "$member_id" =~ ^[1-9][0-9]*$ && "$size" =~ ^[1-9][0-9]*$ ]] \
        || fail 'wishlist page parameters are invalid'
      page_limit=$((size + 1))
      if [[ "$last_id" == null && "$last_created_at" == null ]]; then
        cursor_predicate=''
      else
        [[ "$last_id" =~ ^[1-9][0-9]*$ \
          && "$last_created_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?$ ]] \
          || fail 'wishlist cursor parameters are invalid'
        cursor_predicate=" AND (w.created_at<'$last_created_at' OR (w.created_at='$last_created_at' AND w.id<$last_id))"
      fi
      printf "SELECT w.* FROM wishlist w WHERE w.member_id=%s AND w.status='ACTIVE'%s ORDER BY w.created_at DESC,w.id DESC LIMIT %s;\n" \
        "$member_id" "$cursor_predicate" "$page_limit" > "$output"
      ;;
    REVENUE_RANGE_V1:before)
      from=$(jq -r '.query.from' <<<"$target_query")
      to=$(jq -r '.query.to' <<<"$target_query")
      [[ "$from" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ \
        && "$to" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
        || fail 'revenue range parameters are invalid'
      printf '%s\n' \
        'SELECT t.bucket_date AS stat_date,SUM(t.gross) AS gross_amount,SUM(t.refund) AS refund_amount,' \
        'SUM(t.gross)-SUM(t.refund) AS net_amount,SUM(t.gcount) AS payment_count,SUM(t.rcount) AS refund_count' \
        'FROM (' \
        "SELECT DATE(pt.created_at) AS bucket_date,COALESCE(pt.amount,0) AS gross,0 AS refund,1 AS gcount,0 AS rcount FROM payment_transaction pt WHERE pt.transaction_type='CONFIRM' AND DATE(pt.created_at) BETWEEN '$from' AND '$to'" \
        'UNION ALL' \
        "SELECT DATE(COALESCE(pt.canceled_at,pt.created_at)) AS bucket_date,0,COALESCE(pt.cancel_amount,0),0,1 FROM payment_transaction pt WHERE pt.transaction_type IN ('CANCEL','PARTIAL_CANCEL') AND DATE(COALESCE(pt.canceled_at,pt.created_at)) BETWEEN '$from' AND '$to'" \
        ') t GROUP BY t.bucket_date ORDER BY t.bucket_date;' > "$output"
      ;;
    REVENUE_RANGE_V1:after)
      from=$(jq -r '.query.from' <<<"$target_query")
      to=$(jq -r '.query.to' <<<"$target_query")
      [[ "$from" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ \
        && "$to" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
        || fail 'revenue range parameters are invalid'
      printf "SELECT stat_date,SUM(gross_amount),SUM(refund_amount),SUM(net_amount),SUM(payment_count),SUM(refund_count) FROM daily_revenue_stats WHERE stat_date BETWEEN '%s' AND '%s' GROUP BY stat_date ORDER BY stat_date;\n" "$from" "$to" > "$output"
      ;;
    *) fail 'query kind/variant is unsupported' ;;
  esac
  chmod 600 "$output"
}

evidence_query_role() {
  kind=$(jq -r '.query.kind' <<<"$target_query")
  variant=$1
  case "$kind:$variant" in
    REVIEW_SUMMARY_V1:before) printf '%s' REVIEW_RAW_AGGREGATE ;;
    REVIEW_SUMMARY_V1:after) printf '%s' REVIEW_SUMMARY_LOOKUP ;;
    WISHLIST_PAGE_V1:*) printf '%s' WISHLIST_PAGE_SELECT ;;
    REVENUE_RANGE_V1:before) printf '%s' REVENUE_LEDGER_ROLLUP ;;
    REVENUE_RANGE_V1:after) printf '%s' REVENUE_STATS_ROLLUP ;;
  esac
}

digest_fragment() {
  kind=$(jq -r '.query.kind' <<<"$target_query")
  variant=$1
  case "$kind:$variant" in
    REVIEW_SUMMARY_V1:before) printf '%s' 'FROM[[:space:]]+`?REVIEW`?[[:space:]]' ;;
    REVIEW_SUMMARY_V1:after) printf '%s' 'FROM[[:space:]]+`?ACCOMMODATION_REVIEW_SUMMARY`?[[:space:]]' ;;
    WISHLIST_PAGE_V1:*) printf '%s' 'FROM[[:space:]]+`?WISHLIST`?[[:space:]]' ;;
    REVENUE_RANGE_V1:before) printf '%s' 'FROM[[:space:]]+`?PAYMENT_TRANSACTION`?[[:space:]]' ;;
    REVENUE_RANGE_V1:after) printf '%s' 'FROM[[:space:]]+`?DAILY_REVENUE_STATS`?[[:space:]]' ;;
  esac
}

run_candidate_raw_evidence() {
  assert_lease
  identity_sha=$(sha256_text "$run_id|$release_id|$clone_id|$target_id|$candidate_index|$index_variant")
  candidate_stem="candidate-${identity_sha:0:24}"
  query_relative="build/k6/read-model/$candidate_stem-query.sql"
  query_file="$repo_root/$query_relative"
  write_query "$index_variant" "$query_file"
  query_sha=$(sha256_file "$query_file")
  query_role=$(evidence_query_role "$index_variant")
  snapshot_id="candidate-${query_sha:0:24}"

  pre_fingerprint=$(capture_fingerprint candidate-start)
  aa_database_fingerprint=$(jq -r '.metadata.database.pre_fingerprint_sha256' "$aa_noise_artifact")
  [[ "$pre_fingerprint" == "$aa_database_fingerprint" ]] \
    || fail 'AA noise artifact does not bind the current database fingerprint'

  baseline_runtime_pre="$run_root/candidate-baseline-runtime-pre.json"
  baseline_runtime_pre_sha=$(capture_runtime_assertion candidate-baseline-pre "$baseline_runtime_pre")
  baseline_optimizer_pre="$run_root/candidate-baseline-optimizer-pre.jsonl"
  capture_optimizer "$baseline_optimizer_pre" "$snapshot_id" off
  read -r baseline_optimizer_pre_sha baseline_statistics_pre_sha baseline_histogram_pre_sha \
    < <(optimizer_hashes "$baseline_optimizer_pre" "$run_root/candidate-baseline-pre")
  validate_optimizer_capture "$baseline_optimizer_pre" "$snapshot_id" off

  baseline_explain_relative="build/k6/read-model/$candidate_stem-baseline-mysql-evidence.json"
  "$explain_capture" --login-path "$mysql_login_path" --clone-id "$clone_id" \
    --target-id "$target_id" --window-id candidate-baseline --treatment index-baseline \
    --candidate-index "$candidate_index" --sql-file "$query_relative" \
    --output "$baseline_explain_relative" >/dev/null
  baseline_explain_file="$repo_root/$baseline_explain_relative"
  jq -e --arg candidate "$candidate_index" '
    .metadata.treatment == "index-baseline" and
    .metadata.candidate_index == $candidate and
    .metadata.optimizer_switch_use_invisible_indexes == false and
    .explain.candidate_in_chosen_plan == false
  ' "$baseline_explain_file" >/dev/null \
    || fail 'baseline EXPLAIN did not keep the invisible candidate disabled'

  baseline_optimizer_post="$run_root/candidate-baseline-optimizer-post.jsonl"
  capture_optimizer "$baseline_optimizer_post" "$snapshot_id" off
  read -r baseline_optimizer_post_sha baseline_statistics_post_sha baseline_histogram_post_sha \
    < <(optimizer_hashes "$baseline_optimizer_post" "$run_root/candidate-baseline-post")
  validate_optimizer_capture "$baseline_optimizer_post" "$snapshot_id" off
  baseline_runtime_post="$run_root/candidate-baseline-runtime-post.json"
  baseline_runtime_post_sha=$(capture_runtime_assertion candidate-baseline-post "$baseline_runtime_post")

  candidate_runtime_pre="$run_root/candidate-enabled-runtime-pre.json"
  candidate_runtime_pre_sha=$(capture_runtime_assertion candidate-enabled-pre "$candidate_runtime_pre")
  candidate_optimizer_pre="$run_root/candidate-enabled-optimizer-pre.jsonl"
  capture_optimizer "$candidate_optimizer_pre" "$snapshot_id" on
  read -r candidate_optimizer_pre_sha candidate_statistics_pre_sha candidate_histogram_pre_sha \
    < <(optimizer_hashes "$candidate_optimizer_pre" "$run_root/candidate-enabled-pre")
  validate_optimizer_capture "$candidate_optimizer_pre" "$snapshot_id" on

  candidate_explain_relative="build/k6/read-model/$candidate_stem-enabled-mysql-evidence.json"
  "$explain_capture" --login-path "$mysql_login_path" --clone-id "$clone_id" \
    --target-id "$target_id" --window-id candidate-enabled --treatment index-candidate \
    --candidate-index "$candidate_index" --sql-file "$query_relative" \
    --output "$candidate_explain_relative" >/dev/null
  candidate_explain_file="$repo_root/$candidate_explain_relative"
  jq -e --arg candidate "$candidate_index" '
    .metadata.treatment == "index-candidate" and
    .metadata.candidate_index == $candidate and
    .metadata.optimizer_switch_use_invisible_indexes == true
  ' "$candidate_explain_file" >/dev/null \
    || fail 'candidate EXPLAIN did not bind the invisible-index treatment'

  candidate_optimizer_post="$run_root/candidate-enabled-optimizer-post.jsonl"
  capture_optimizer "$candidate_optimizer_post" "$snapshot_id" on
  read -r candidate_optimizer_post_sha candidate_statistics_post_sha candidate_histogram_post_sha \
    < <(optimizer_hashes "$candidate_optimizer_post" "$run_root/candidate-enabled-post")
  validate_optimizer_capture "$candidate_optimizer_post" "$snapshot_id" on
  candidate_runtime_post="$run_root/candidate-enabled-runtime-post.json"
  candidate_runtime_post_sha=$(capture_runtime_assertion candidate-enabled-post "$candidate_runtime_post")

  post_fingerprint=$(capture_fingerprint candidate-final)
  [[ "$post_fingerprint" == "$pre_fingerprint" ]] \
    || fail 'candidate raw evidence changed the database fingerprint'
  [[ "$baseline_optimizer_pre_sha" != "$candidate_optimizer_pre_sha" ]] \
    || fail 'optimizer treatment snapshots are indistinguishable'
  [[ "$baseline_statistics_pre_sha" == "$candidate_statistics_pre_sha" \
    && "$baseline_histogram_pre_sha" == "$candidate_histogram_pre_sha" ]] \
    || fail 'baseline and candidate optimizer data do not share fixed statistics'
  [[ "$baseline_optimizer_pre_sha" == "$baseline_optimizer_post_sha" \
    && "$candidate_optimizer_pre_sha" == "$candidate_optimizer_post_sha" \
    && "$baseline_statistics_pre_sha" == "$baseline_statistics_post_sha" \
    && "$candidate_statistics_pre_sha" == "$candidate_statistics_post_sha" \
    && "$baseline_histogram_pre_sha" == "$baseline_histogram_post_sha" \
    && "$candidate_histogram_pre_sha" == "$candidate_histogram_post_sha" ]] \
    || fail 'candidate optimizer statistics or histograms drifted during raw capture'
  final_auto_recalc=$(mysql_exec 'SELECT @@GLOBAL.innodb_stats_auto_recalc')
  [[ "$final_auto_recalc" == 0 || "$final_auto_recalc" == OFF ]] \
    || fail 'automatic statistics recalculation was enabled during candidate raw capture'

  canonical_query=$(jq -cS '.query' <<<"$target_query")
  parameter_sha=$(sha256_text "$canonical_query")
  manifest_target_file="$run_root/candidate-manifest-target.json"
  jq --arg parameterSha "$parameter_sha" '
    {
      capsule_id:"read-model-v2",target_id:.id,query_kind:.query.kind,
      parameter_hash_sha256:$parameterSha,expected_rows:.expectedRows,
      expected_result_hash:.expectedResultHash,
      account_ref:(if has("account") then
        ((.account.role | ascii_downcase) + "-" + (.account.memberId | tostring))
        else null end)
    }
  ' <<<"$target_query" > "$manifest_target_file"
  chmod 600 "$manifest_target_file"
  generated_at=$(date -u '+%Y-%m-%dT%H:%M:%S.000Z')
  candidate_chosen=$(jq -r '.explain.candidate_in_chosen_plan' "$candidate_explain_file")
  [[ "$candidate_chosen" == true || "$candidate_chosen" == false ]] \
    || fail 'candidate chosen-plan receipt is invalid'
  raw_artifact_relative="build/k6/read-model/$candidate_stem-raw-candidate-evidence.json"
  raw_artifact="$repo_root/$raw_artifact_relative"
  [[ ! -e "$raw_artifact" && ! -L "$raw_artifact" ]] \
    || fail 'candidate raw evidence output already exists or is unsafe'
  jq -n \
    --arg generatedAt "$generated_at" --arg clone "$clone_id" --arg candidate "$candidate_index" \
    --arg variant "$index_variant" --arg queryRole "$query_role" --arg querySha "$query_sha" \
    --arg measurementFence "$measurement_fencing_token_sha256" --arg evidenceRunId "$run_id" \
    --arg pre "$pre_fingerprint" --arg post "$post_fingerprint" \
    --arg analyze "$analyze_receipt_sha" --arg aaNoiseSha "$(sha256_file "$aa_noise_artifact")" \
    --arg baselineOptimizerPre "$baseline_optimizer_pre_sha" \
    --arg baselineOptimizerPost "$baseline_optimizer_post_sha" \
    --arg candidateOptimizerPre "$candidate_optimizer_pre_sha" \
    --arg candidateOptimizerPost "$candidate_optimizer_post_sha" \
    --arg statistics "$baseline_statistics_pre_sha" --arg histograms "$baseline_histogram_pre_sha" \
    --arg baselineRuntimePreSha "$baseline_runtime_pre_sha" \
    --arg baselineRuntimePostSha "$baseline_runtime_post_sha" \
    --arg candidateRuntimePreSha "$candidate_runtime_pre_sha" \
    --arg candidateRuntimePostSha "$candidate_runtime_post_sha" \
    --argjson chosen "$candidate_chosen" \
    --slurpfile release "$release_tuple_file" --slurpfile target "$manifest_target_file" \
    --slurpfile app "$app_build_file" \
    --slurpfile baselineRuntimePre "$baseline_runtime_pre" \
    --slurpfile baselineRuntimePost "$baseline_runtime_post" \
    --slurpfile candidateRuntimePre "$candidate_runtime_pre" \
    --slurpfile candidateRuntimePost "$candidate_runtime_post" \
    --slurpfile baselineExplain "$baseline_explain_file" \
    --slurpfile candidateExplain "$candidate_explain_file" \
    --slurpfile baselineOptimizerPreRaw "$baseline_optimizer_pre" \
    --slurpfile baselineOptimizerPostRaw "$baseline_optimizer_post" \
    --slurpfile candidateOptimizerPreRaw "$candidate_optimizer_pre" \
    --slurpfile candidateOptimizerPostRaw "$candidate_optimizer_post" '
    {
      schema_version:"read-model-candidate-raw-evidence-v1",
      metadata:{generated_at:$generatedAt,run_id:$evidenceRunId,
        release_tuple:$release[0],manifest_target:$target[0],app_build:$app[0],
        clone_id:$clone,candidate_index:$candidate,variant:$variant,
        evidence_query_role:$queryRole,query_sha256:$querySha,
        measurement_fencing_token_sha256:$measurementFence,
        aa_noise_observation_sha256:$aaNoiseSha,
        application_k6_latency_supported:false,performance_claim:null},
      validity:{status:"valid",reasons:[],database_fingerprint_stable:($pre == $post),
        automatic_statistics_recalculation_detected:false,
        lifecycle:{scheduler_enabled:false,kafka_listener_enabled:false,
          inventory_lifecycle_enabled:false,external_side_effects_enabled:false}},
      runtime_assertions:{
        baseline:{runtime_assertion_pre_sha256:$baselineRuntimePreSha,
          runtime_assertion_post_sha256:$baselineRuntimePostSha,
          pre:$baselineRuntimePre[0],post:$baselineRuntimePost[0]},
        candidate:{runtime_assertion_pre_sha256:$candidateRuntimePreSha,
          runtime_assertion_post_sha256:$candidateRuntimePostSha,
          pre:$candidateRuntimePre[0],post:$candidateRuntimePost[0]}},
      database:{pre_fingerprint_sha256:$pre,post_fingerprint_sha256:$post,
        analyze_receipt_sha256:$analyze,statistics_snapshot_sha256:$statistics,
        histogram_snapshot_sha256:$histograms,
        optimizer_snapshot_sha256:{baseline_pre:$baselineOptimizerPre,
          baseline_post:$baselineOptimizerPost,candidate_pre:$candidateOptimizerPre,
          candidate_post:$candidateOptimizerPost}},
      captures:{
        baseline:{treatment:"index-baseline",optimizer_pre_raw:$baselineOptimizerPreRaw,
          optimizer_post_raw:$baselineOptimizerPostRaw,explain:$baselineExplain[0]},
        candidate:{treatment:"index-candidate",optimizer_pre_raw:$candidateOptimizerPreRaw,
          optimizer_post_raw:$candidateOptimizerPostRaw,explain:$candidateExplain[0]}},
      eligibility:{status:(if $chosen then "candidate-ready" else "not-chosen" end),
        candidate_in_chosen_plan:$chosen,
        reasons:(if $chosen then [] else ["candidate-not-in-chosen-plan"] end)}
    }
  ' > "$raw_artifact"
  chmod 600 "$raw_artifact"
  jq -e '
    def exactReceipt($root):
      (keys | sort) == [
        "active_profiles","app_instance_id","challenge_sha256","external_side_effects_enabled",
        "inventory_lifecycle_enabled","kafka_listener_enabled","resource_fencing_token_sha256",
        "run_id","runtime_revision","scheduler_enabled","schema_version"
      ] and .schema_version == 1 and .run_id == $root.metadata.run_id and
      .resource_fencing_token_sha256 == $root.metadata.app_build.resource_fencing_token_sha256 and
      .runtime_revision == $root.metadata.app_build.runtime_revision and
      .app_instance_id == $root.metadata.app_build.app_instance_id and
      (.challenge_sha256 | test("^[0-9a-f]{64}$")) and
      .active_profiles == ["aws","read-model-benchmark","traffic-benchmark"] and
      .scheduler_enabled == false and .kafka_listener_enabled == false and
      .inventory_lifecycle_enabled == false and .external_side_effects_enabled == false;
    . as $root |
    .schema_version == "read-model-candidate-raw-evidence-v1" and
    .metadata.application_k6_latency_supported == false and
    .metadata.performance_claim == null and
    .validity.status == "valid" and
    ([.runtime_assertions[] |
      (.runtime_assertion_pre_sha256 | test("^[0-9a-f]{64}$")) and
      (.runtime_assertion_post_sha256 | test("^[0-9a-f]{64}$")) and
      .runtime_assertion_pre_sha256 != .runtime_assertion_post_sha256 and
      (.pre | exactReceipt($root)) and (.post | exactReceipt($root)) and
      .pre.challenge_sha256 != .post.challenge_sha256] | all) and
    .captures.baseline.explain.explain.candidate_in_chosen_plan == false and
    (.eligibility.candidate_in_chosen_plan | type) == "boolean"
  ' "$raw_artifact" >/dev/null || fail 'candidate raw evidence artifact is invalid'

  assert_lease
  aws s3api put-object --bucket "$evidence_bucket" \
    --key "$evidence_prefix/${raw_artifact_relative##*/}" --body "$raw_artifact" \
    --tagging Retention=raw --server-side-encryption AES256 --content-type application/json \
    --if-none-match '*' --region "$aws_region" --no-cli-pager >/dev/null
  printf 'run_id=%s\nrelease_id=%s\nclone_id=%s\ntarget_id=%s\n' \
    "$run_id" "$release_id" "$clone_id" "$target_id"
  printf 'raw_artifact=%s\ncandidate_in_chosen_plan=%s\n' \
    "$raw_artifact_relative" "$candidate_chosen"
  [[ "$candidate_chosen" == true ]] \
    || fail 'candidate raw evidence was preserved, but the candidate was not in the chosen plan'
}

if [[ "$candidate_requested" == true ]]; then
  run_candidate_raw_evidence
  exit 0
fi

read_private_value() {
  value=$(tr -d '\r\n' < "$1")
  [[ -n "$value" ]] || fail 'credential file is empty'
  printf '%s' "$value"
}

create_context() {
  output=$1 design=$2 block=$3 window=$4 role=$5 variant=$6 pre_fingerprint=$7 \
    post_fingerprint=$8 optimizer_sha=$9 statistics_sha=${10} histogram_sha=${11} \
    statement_file=${12} explain_file=${13} runtime_pre=${14} runtime_post=${15} \
    runtime_pre_sha=${16} runtime_post_sha=${17}
  jq -n \
    --arg runId "$run_id" --arg design "$design" \
    --arg experiment "$read_model_experiment_id" --arg block "$block" \
    --arg window "$window" --arg event "$window-event" --arg role "$role" \
    --arg target "$target_id" --arg pre "$pre_fingerprint" --arg post "$post_fingerprint" \
    --arg clone "$clone_id" --arg optimizer "$optimizer_sha" --arg statistics "$statistics_sha" \
    --arg histogram "$histogram_sha" --arg analyze "$analyze_receipt_sha" \
    --arg measurementFence "$measurement_fencing_token_sha256" \
    --slurpfile release "$release_tuple_file" --slurpfile app "$app_build_file" \
    --arg runtimePreSha "$runtime_pre_sha" --arg runtimePostSha "$runtime_post_sha" \
    --slurpfile runtimePre "$runtime_pre" --slurpfile runtimePost "$runtime_post" \
    --slurpfile statement "$statement_file" --slurpfile explain "$explain_file" '
    {
      schema_version:"read-model-run-context-v1", run_id:$runId, design:$design,
      experiment_id:$experiment, block_id:$block, window_id:$window,
      statement_event_id:$event, pair_role:$role,
      release_tuple:$release[0], app_build:$app[0],
      database:{
        clone_id:$clone, pre_fingerprint_sha256:$pre, post_fingerprint_sha256:$post,
        optimizer_snapshot_sha256:$optimizer, statistics_snapshot_sha256:$statistics,
        histogram_snapshot_sha256:$histogram, analyze_receipt_sha256:$analyze,
        mysql_version:$explain[0].metadata.mysql_version,
        auto_statistics_recalculation_detected:false
      },
      treatment:{kind:"READ_MODEL",candidate_index:null,candidate_visible:null,
        optimizer_switch_use_invisible_indexes:false},
      measurement_fencing_token_sha256:$measurementFence,
      lifecycle:{scheduler_enabled:false,kafka_listener_enabled:false,
        inventory_lifecycle_enabled:false,external_side_effects_enabled:false},
      runtime_assertion:{runtime_assertion_pre_sha256:$runtimePreSha,
        runtime_assertion_post_sha256:$runtimePostSha,
        pre:$runtimePre[0],post:$runtimePost[0]},
      mysql_evidence:{
        statement_event:$statement[0],
        optimizer_state:{snapshot_sha256:$optimizer,statistics_snapshot_sha256:$statistics,
          histogram_snapshot_sha256:$histogram,analyze_receipt_sha256:$analyze},
        explain:$explain[0].explain
      },
      target_id:$target
    }' > "$output"
  chmod 600 "$output"
}

comparison_script() {
  case "$(jq -r '.query.kind' <<<"$target_query")" in
    REVIEW_SUMMARY_V1) printf '%s' load-test/k6/read-model/review-summary-comparison.js ;;
    WISHLIST_PAGE_V1) printf '%s' load-test/k6/read-model/wishlist-comparison.js ;;
    REVENUE_RANGE_V1) printf '%s' load-test/k6/read-model/revenue-stats-comparison.js ;;
  esac
}

measure_window() {
  window_json=$1
  phase=$2
  bracket_start_fingerprint=$3
  assert_lease
  design=$(jq -r '.design' <<<"$window_json")
  block=$(jq -r '.block_id' <<<"$window_json")
  window=$(jq -r '.window_id' <<<"$window_json")
  role=$(jq -r '.pair_role' <<<"$window_json")
  variant=$(jq -r '.variant' <<<"$window_json")
  case "$phase:$design" in
    aa:AA_NOISE)
      bracket_start=run-start
      bracket_end=after-aa
      ;;
    ab:READ_MODEL_AB)
      bracket_start=after-aa
      bracket_end=final
      ;;
    *) fail 'window does not belong to its database fingerprint bracket' ;;
  esac
  runtime_pre="$run_root/$window-runtime-pre.json"
  runtime_pre_sha=$(capture_runtime_assertion "$window-pre" "$runtime_pre")
  window_stem="$run_id-$target_id-$window"
  query_relative="build/k6/read-model/$window_stem-query.sql"
  query_file="$repo_root/$query_relative"
  write_query "$variant" "$query_file"
  query_sha=$(sha256_file "$query_file")
  optimizer_snapshot_id="read-model-$phase-${experiment_identity_sha:0:16}"

  explain_relative="build/k6/read-model/$window_stem-mysql-evidence.json"
  "$explain_capture" --login-path "$mysql_login_path" --clone-id "$clone_id" \
    --target-id "$target_id" --window-id "$window" --treatment read-model \
    --candidate-index none --sql-file "$query_relative" \
    --output "$explain_relative" >/dev/null
  explain_file="$repo_root/$explain_relative"

  optimizer_pre="$run_root/$window-optimizer-pre.jsonl"
  capture_optimizer "$optimizer_pre" "$optimizer_snapshot_id" off
  validate_optimizer_capture "$optimizer_pre" "$optimizer_snapshot_id" off
  read -r optimizer_sha statistics_sha histogram_sha \
    < <(optimizer_hashes "$optimizer_pre" "$run_root/$window-pre")

  placeholder_statement="$run_root/$window-placeholder-statement.json"
  jq -n --arg window "$window" --arg digest "$query_sha" '
    {window_id:$window,event_id:($window+"-event"),digest:$digest,
     digest_text:"SELECT measurement placeholder",delta:{calls:1,timer_wait_ps:"1",
     rows_examined:"0",rows_sent:"0",errors:0}}' > "$placeholder_statement"
  placeholder_context="$run_root/$window-placeholder-context.json"
  create_context "$placeholder_context" "$design" "$block" "$window" "$role" "$variant" \
    "$bracket_start_fingerprint" "$bracket_start_fingerprint" \
    "$optimizer_sha" "$statistics_sha" \
    "$histogram_sha" "$placeholder_statement" "$explain_file" \
    "$runtime_pre" "$runtime_pre" "$runtime_pre_sha" "$runtime_pre_sha"

  timer_start=$(mysql_exec 'SELECT COALESCE(MAX(TIMER_END),1) FROM performance_schema.events_statements_history_long')
  measurement_relative="build/k6/read-model/$window_stem-measurement.json"
  measurement_file="$repo_root/$measurement_relative"
  [[ ! -e "$measurement_file" && ! -L "$measurement_file" ]] \
    || fail 'k6 measurement output already exists or is unsafe'
  benchmark_token=$(read_private_value "$token_file")
  benchmark_password=''
  [[ "$target_has_account" != true ]] || benchmark_password=$(read_private_value "$password_file")
  env BENCHMARK_READ_MODEL_TOKEN="$benchmark_token" \
    BENCHMARK_ACCOUNT_PASSWORD="$benchmark_password" \
    READ_MODEL_MODE=measure VARIANT="$variant" TARGET_ID="$target_id" \
    BENCHMARK_DATASET_MANIFEST="$manifest_path" READ_MODEL_EVIDENCE_CONTEXT="$placeholder_context" \
    K6_MEASUREMENT_PATH="$measurement_relative" BASE_URL="$base_url" RATE="$rate" \
    WARMUP_DURATION="$warmup_duration" MEASURE_DURATION="$measure_duration" \
    k6 run --address '' "$(comparison_script)" >/dev/null
  unset benchmark_token benchmark_password
  [[ -f "$measurement_file" && ! -L "$measurement_file" ]] \
    || fail 'k6 measurement summary is missing'
  timer_end=$(mysql_exec 'SELECT COALESCE(MAX(TIMER_END),1) FROM performance_schema.events_statements_history_long')
  [[ "$timer_start" =~ ^[0-9]+$ && "$timer_end" =~ ^[0-9]+$ \
    && "$timer_end" -gt "$timer_start" ]] || fail 'Performance Schema timer window is invalid'

  statement_events="$run_root/$window-statement-events.jsonl"
  mysql --login-path="$mysql_login_path" --database=airbobdb --batch --raw \
    --skip-column-names --connect-timeout=10 \
    --init-command="SET @airbob_evidence_window_id='$window',@airbob_evidence_target_id='$target_id',@airbob_evidence_timer_start=$timer_start,@airbob_evidence_timer_end=$timer_end,@airbob_evidence_thread_id=NULL,@airbob_evidence_event_floor=NULL" \
    < "$statement_sql" > "$statement_events"
  fragment=$(digest_fragment "$variant")
  measured_calls=$(jq -er '
    (.metrics.read_model_request_success.values.passes // 0) +
    (.metrics.read_model_request_success.values.fails // 0)
  ' "$measurement_file")
  [[ "$measured_calls" =~ ^[1-9][0-9]*$ ]] \
    || fail 'measurement summary contains no target requests'
  statement_file="$run_root/$window-statement.json"
  jq -sc --arg window "$window" --arg fragment "$fragment" \
    --argjson measuredCalls "$measured_calls" '
    map(select((.digestText | ascii_upcase | test($fragment)) and .errorNumber == 0))
    | group_by(.digest)
    | map(sort_by(.timerEnd|tonumber) | select(length >= $measuredCalls) | .[-$measuredCalls:])
    | select(length == 1) | .[0]
    | {digest:.[0].digest,digest_text:.[0].digestText,calls:length,
      timer_wait_ps:(map(.timerWait|tonumber)|add|floor|tostring),
      rows_examined:(map(.rowsExamined|tonumber)|add|floor|tostring),
      rows_sent:(map(.rowsSent|tonumber)|add|floor|tostring),errors:(map(.errorNumber)|add)}
    | select(. != null)
    | {window_id:$window,event_id:($window+"-event"),digest,digest_text,
       delta:{calls,timer_wait_ps,rows_examined,rows_sent,errors}}
  ' "$statement_events" > "$statement_file"
  jq -e --argjson measuredCalls "$measured_calls" \
    '.delta.calls == $measuredCalls and .delta.errors == 0' "$statement_file" >/dev/null \
    || fail 'no isolated statement event matched the selected target window'

  optimizer_post="$run_root/$window-optimizer-post.jsonl"
  capture_optimizer "$optimizer_post" "$optimizer_snapshot_id" off
  validate_optimizer_capture "$optimizer_post" "$optimizer_snapshot_id" off
  read -r post_optimizer post_statistics post_histogram \
    < <(optimizer_hashes "$optimizer_post" "$run_root/$window-post")
  [[ "$post_optimizer" == "$optimizer_sha" \
    && "$post_statistics" == "$statistics_sha" \
    && "$post_histogram" == "$histogram_sha" ]] \
    || fail 'optimizer statistics or histogram drift invalidates the window'
  [[ "$(mysql_exec 'SELECT @@GLOBAL.innodb_stats_auto_recalc')" == 0 ]] \
    || fail 'automatic statistics recalculation was enabled during measurement'
  runtime_post="$run_root/$window-runtime-post.json"
  runtime_post_sha=$(capture_runtime_assertion "$window-post" "$runtime_post")

  pending="$run_root/$window-pending.json"
  [[ ! -e "$pending" && ! -L "$pending" ]] \
    || fail 'pending window evidence already exists or is unsafe'
  jq -n \
    --arg phase "$phase" --arg design "$design" --arg block "$block" \
    --arg window "$window" --arg role "$role" --arg variant "$variant" \
    --arg bracketStart "$bracket_start" --arg bracketEnd "$bracket_end" \
    --arg bracketStartFingerprint "$bracket_start_fingerprint" \
    --arg optimizer "$optimizer_sha" --arg statistics "$statistics_sha" \
    --arg histogram "$histogram_sha" --arg statement "$statement_file" \
    --arg explain "$explain_file" --arg runtimePre "$runtime_pre" \
    --arg runtimePost "$runtime_post" --arg runtimePreSha "$runtime_pre_sha" \
    --arg runtimePostSha "$runtime_post_sha" --arg measurement "$measurement_relative" '
    {
      phase:$phase,design:$design,block_id:$block,window_id:$window,pair_role:$role,
      variant:$variant,
      fingerprint_bracket:{start_boundary:$bracketStart,end_boundary:$bracketEnd,
        start_fingerprint_sha256:$bracketStartFingerprint},
      optimizer_snapshot_sha256:$optimizer,statistics_snapshot_sha256:$statistics,
      histogram_snapshot_sha256:$histogram,statement_file:$statement,
      explain_file:$explain,runtime_pre_file:$runtimePre,runtime_post_file:$runtimePost,
      runtime_pre_sha256:$runtimePreSha,runtime_post_sha256:$runtimePostSha,
      measurement_relative_path:$measurement
    }' > "$pending"
  chmod 600 "$pending"
  printf '%s\n' "$pending"
}

finalize_window() {
  pending=$1
  bracket_start_fingerprint=$2
  bracket_end_fingerprint=$3
  [[ "$bracket_start_fingerprint" == "$bracket_end_fingerprint" ]] \
    || fail 'database fingerprint bracket drift invalidates its windows'
  [[ -f "$pending" && ! -L "$pending" ]] \
    || fail 'pending window evidence is missing or unsafe'
  jq -e --arg start "$bracket_start_fingerprint" '
    (keys | sort) == [
      "block_id","design","explain_file","fingerprint_bracket",
      "histogram_snapshot_sha256","measurement_relative_path",
      "optimizer_snapshot_sha256","pair_role","phase","runtime_post_file",
      "runtime_post_sha256","runtime_pre_file","runtime_pre_sha256",
      "statement_file","statistics_snapshot_sha256","variant","window_id"
    ] and
    (.fingerprint_bracket | keys | sort) == [
      "end_boundary","start_boundary","start_fingerprint_sha256"
    ] and .fingerprint_bracket.start_fingerprint_sha256 == $start
  ' "$pending" >/dev/null || fail 'pending window evidence contract is invalid'

  phase=$(jq -r '.phase' "$pending")
  bracket_start=$(jq -r '.fingerprint_bracket.start_boundary' "$pending")
  bracket_end=$(jq -r '.fingerprint_bracket.end_boundary' "$pending")
  case "$phase:$bracket_start:$bracket_end" in
    aa:run-start:after-aa|ab:after-aa:final) ;;
    *) fail 'pending window fingerprint bracket is invalid' ;;
  esac
  design=$(jq -r '.design' "$pending")
  block=$(jq -r '.block_id' "$pending")
  window=$(jq -r '.window_id' "$pending")
  role=$(jq -r '.pair_role' "$pending")
  variant=$(jq -r '.variant' "$pending")
  optimizer_sha=$(jq -r '.optimizer_snapshot_sha256' "$pending")
  statistics_sha=$(jq -r '.statistics_snapshot_sha256' "$pending")
  histogram_sha=$(jq -r '.histogram_snapshot_sha256' "$pending")
  statement_file=$(jq -r '.statement_file' "$pending")
  explain_file=$(jq -r '.explain_file' "$pending")
  runtime_pre=$(jq -r '.runtime_pre_file' "$pending")
  runtime_post=$(jq -r '.runtime_post_file' "$pending")
  runtime_pre_sha=$(jq -r '.runtime_pre_sha256' "$pending")
  runtime_post_sha=$(jq -r '.runtime_post_sha256' "$pending")
  measurement_relative=$(jq -r '.measurement_relative_path' "$pending")
  measurement_file="$repo_root/$measurement_relative"
  for captured_file in \
    "$statement_file" "$explain_file" "$runtime_pre" "$runtime_post" "$measurement_file"
  do
    [[ -f "$captured_file" && ! -L "$captured_file" ]] \
      || fail 'pending window capture is missing or unsafe'
  done

  assert_lease
  final_context="$run_root/$window-context.json"
  create_context "$final_context" "$design" "$block" "$window" "$role" "$variant" \
    "$bracket_start_fingerprint" "$bracket_end_fingerprint" \
    "$optimizer_sha" "$statistics_sha" "$histogram_sha" "$statement_file" \
    "$explain_file" "$runtime_pre" "$runtime_post" "$runtime_pre_sha" "$runtime_post_sha"
  window_stem="$run_id-$target_id-$window"
  evidence_relative="build/k6/read-model/$window_stem.json"
  evidence_file="$repo_root/$evidence_relative"
  [[ ! -e "$evidence_file" && ! -L "$evidence_file" ]] \
    || fail 'evidence output already exists or is unsafe'
  benchmark_token=$(read_private_value "$token_file")
  env BENCHMARK_READ_MODEL_TOKEN="$benchmark_token" READ_MODEL_MODE=assemble \
    VARIANT="$variant" TARGET_ID="$target_id" BENCHMARK_DATASET_MANIFEST="$manifest_path" \
    READ_MODEL_EVIDENCE_CONTEXT="$final_context" \
    READ_MODEL_MEASUREMENT_SUMMARY="$measurement_file" K6_RESULT_PATH="$evidence_relative" \
    BASE_URL="$base_url" RATE="$rate" WARMUP_DURATION="$warmup_duration" \
    MEASURE_DURATION="$measure_duration" k6 run --address '' "$(comparison_script)" >/dev/null
  unset benchmark_token
  [[ -f "$evidence_file" && ! -L "$evidence_file" ]] \
    || fail 'adapter-free evidence artifact is missing'
  printf '%s\n' "$evidence_relative"
}

windows=()
while IFS= read -r window; do
  windows+=("$window")
done < <(jq -c '.windows[]' "$plan_file")
evidence_sources=()
pending_windows=()
run_start_fingerprint=$(capture_fingerprint run-start)
for ((index = 0; index < 6; index += 1)); do
  pending_windows+=("$(measure_window "${windows[$index]}" aa "$run_start_fingerprint")")
done
after_aa_fingerprint=$(capture_fingerprint after-aa)
[[ "$after_aa_fingerprint" == "$run_start_fingerprint" ]] \
  || fail 'A/A database fingerprint drift invalidates the phase'
for pending in "${pending_windows[@]}"; do
  evidence_sources+=("$(finalize_window "$pending" \
    "$run_start_fingerprint" "$after_aa_fingerprint")")
done
aa_output="build/k6/read-model/$run_id-$target_id-aa-observations.json"
node "$aggregator" --output "$aa_output" --aa-max-relative-delta "$noise_limit" \
  "${evidence_sources[@]:0:6}"
jq -e --argjson limit "$noise_limit" '
  .eligibility.status == "valid" and .headline.kind == "AA_NOISE_ENVELOPE" and
  .metadata.aa_max_relative_delta == $limit and
  ([.headline.maximum_absolute_relative_delta[]] | all(. <= $limit))
' "$repo_root/$aa_output" >/dev/null || fail 'A/A noise exceeds the publication gate'
pending_windows=()
for ((index = 6; index < 12; index += 1)); do
  pending_windows+=("$(measure_window "${windows[$index]}" ab "$after_aa_fingerprint")")
done
final_fingerprint=$(capture_fingerprint final)
[[ "$final_fingerprint" == "$after_aa_fingerprint" ]] \
  || fail 'A/B database fingerprint drift invalidates the phase'
for pending in "${pending_windows[@]}"; do
  evidence_sources+=("$(finalize_window "$pending" \
    "$after_aa_fingerprint" "$final_fingerprint")")
done
comparison_output="build/k6/read-model/$run_id-$target_id-read-model-observations.json"
node "$aggregator" --output "$comparison_output" --aa-max-relative-delta "$noise_limit" \
  "${evidence_sources[@]:6:6}"
outputs=("$aa_output" "$comparison_output")

for artifact in "${outputs[@]}"; do
  jq -e --argjson limit "$noise_limit" '.metadata.aa_max_relative_delta == $limit' \
    "$repo_root/$artifact" >/dev/null \
    || fail 'read-model artifact lost its A/A publication threshold binding'
  assert_lease
  aws s3api put-object --bucket "$evidence_bucket" --key "$evidence_prefix/${artifact##*/}" \
    --body "$repo_root/$artifact" --tagging Retention=summary --server-side-encryption AES256 \
    --content-type application/json --if-none-match '*' --region "$aws_region" \
    --no-cli-pager >/dev/null
done

printf 'run_id=%s\nrelease_id=%s\nclone_id=%s\ntarget_id=%s\n' \
  "$run_id" "$release_id" "$clone_id" "$target_id"
printf 'artifact=%s\n' "${outputs[@]}"
