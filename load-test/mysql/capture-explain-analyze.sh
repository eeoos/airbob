#!/usr/bin/env bash

set -euo pipefail
umask 077

fail() {
  printf 'read-model EXPLAIN capture failed: %s\n' "$1" >&2
  exit 1
}

usage() {
  fail 'expected --login-path --clone-id --target-id --window-id --treatment --candidate-index --sql-file --output'
}

login_path=''
clone_id=''
target_id=''
window_id=''
treatment=''
candidate_index=''
sql_file=''
output=''

while [[ $# -gt 0 ]]; do
  [[ $# -ge 2 ]] || usage
  case "$1" in
    --login-path) [[ -z "$login_path" ]] || fail 'duplicate --login-path'; login_path=$2 ;;
    --clone-id) [[ -z "$clone_id" ]] || fail 'duplicate --clone-id'; clone_id=$2 ;;
    --target-id) [[ -z "$target_id" ]] || fail 'duplicate --target-id'; target_id=$2 ;;
    --window-id) [[ -z "$window_id" ]] || fail 'duplicate --window-id'; window_id=$2 ;;
    --treatment) [[ -z "$treatment" ]] || fail 'duplicate --treatment'; treatment=$2 ;;
    --candidate-index) [[ -z "$candidate_index" ]] || fail 'duplicate --candidate-index'; candidate_index=$2 ;;
    --sql-file) [[ -z "$sql_file" ]] || fail 'duplicate --sql-file'; sql_file=$2 ;;
    --output) [[ -z "$output" ]] || fail 'duplicate --output'; output=$2 ;;
    *) usage ;;
  esac
  shift 2
done

[[ -n "$login_path" && -n "$clone_id" && -n "$target_id" && -n "$window_id" \
  && -n "$treatment" && -n "$candidate_index" && -n "$sql_file" && -n "$output" ]] || usage

slug_pattern='^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$'
[[ "$login_path" =~ $slug_pattern ]] || fail 'login path is invalid'
[[ "$clone_id" =~ $slug_pattern ]] || fail 'clone id is invalid'
[[ "$target_id" =~ $slug_pattern ]] || fail 'target id is invalid'
[[ "$window_id" =~ $slug_pattern ]] || fail 'window id is invalid'

case "$treatment" in
  read-model)
    [[ "$candidate_index" == 'none' ]] || fail 'read-model treatment cannot name an index candidate'
    candidate_name=''
    candidate_visible_json='null'
    invisible_switch_json='false'
    optimizer_setting='use_invisible_indexes=off'
    ;;
  index-baseline)
    [[ "$candidate_index" =~ ^[a-zA-Z_][a-zA-Z0-9_\$]{0,63}$ ]] \
      || fail 'candidate index is invalid'
    candidate_name=$candidate_index
    candidate_visible_json='false'
    invisible_switch_json='false'
    optimizer_setting='use_invisible_indexes=off'
    ;;
  index-candidate)
    [[ "$candidate_index" =~ ^[a-zA-Z_][a-zA-Z0-9_\$]{0,63}$ ]] \
      || fail 'candidate index is invalid'
    candidate_name=$candidate_index
    candidate_visible_json='false'
    invisible_switch_json='true'
    optimizer_setting='use_invisible_indexes=on'
    ;;
  *) fail 'treatment is invalid' ;;
esac

[[ -z "${MYSQL_PWD:-}" ]] || fail 'MYSQL_PWD is forbidden; configure mysql_config_editor login-path credentials'

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
repo_root=$(cd "$script_dir/../.." && pwd -P)
artifact_root="$repo_root/build/k6/read-model"

for directory in "$repo_root/build" "$repo_root/build/k6" "$artifact_root"; do
  [[ -d "$directory" && ! -L "$directory" ]] || fail 'artifact directory is missing or unsafe'
done
[[ "$(cd "$artifact_root" && pwd -P)" == "$artifact_root" ]] \
  || fail 'artifact directory is not canonical'

if [[ ! "$sql_file" =~ ^build/k6/read-model/[a-zA-Z0-9][a-zA-Z0-9._-]{0,200}-query\.sql$ \
  && ! "$sql_file" =~ ^load-test/mysql/queries/[a-zA-Z0-9][a-zA-Z0-9._-]{0,200}\.sql$ ]]; then
  fail 'SQL file path is outside the allowlisted boundary'
fi
[[ "$output" =~ ^build/k6/read-model/[a-zA-Z0-9][a-zA-Z0-9._-]{0,180}-mysql-evidence\.json$ ]] \
  || fail 'output path is invalid'

sql_absolute="$repo_root/$sql_file"
output_absolute="$repo_root/$output"
[[ -f "$sql_absolute" && ! -L "$sql_absolute" ]] || fail 'SQL input must be a regular non-symbolic-link file'
[[ ! -e "$output_absolute" && ! -L "$output_absolute" ]] || fail 'output already exists'

sql_bytes=$(wc -c < "$sql_absolute" | tr -d '[:space:]')
[[ "$sql_bytes" =~ ^[0-9]+$ && "$sql_bytes" -gt 0 && "$sql_bytes" -le 65536 ]] \
  || fail 'SQL input is empty or too large'
query=$(sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' "$sql_absolute")
[[ "$query" =~ ^[Ss][Ee][Ll][Ee][Cc][Tt][[:space:]] ]] \
  || fail 'only one read-only SELECT is accepted'

query_without_final_semicolon=${query%;}
[[ "$query_without_final_semicolon" != *';'* ]] || fail 'SQL input must contain exactly one statement'
[[ "$query_without_final_semicolon" != *'--'* \
  && "$query_without_final_semicolon" != *'/*'* \
  && "$query_without_final_semicolon" != *'*/'* \
  && "$query_without_final_semicolon" != *'#'* ]] || fail 'SQL comments are forbidden'
query=$query_without_final_semicolon

upper_query=$(printf '%s' "$query" | tr '[:lower:]' '[:upper:]')
if [[ "$upper_query" =~ (^|[^A-Z0-9_])(INSERT|UPDATE|DELETE|REPLACE|MERGE|ALTER|CREATE|DROP|TRUNCATE|CALL|DO|HANDLER|LOAD|LOCK|UNLOCK|GRANT|REVOKE|INTO[[:space:]]+(OUTFILE|DUMPFILE))([^A-Z0-9_]|$) ]]; then
  fail 'SQL input is not read-only'
fi
if [[ "$upper_query" =~ (^|[^A-Z0-9_])(SLEEP|BENCHMARK|GET_LOCK|RELEASE_LOCK|LOAD_FILE)([^A-Z0-9_]|$) ]]; then
  fail 'side-effecting or timing SQL functions are forbidden'
fi
if [[ "$upper_query" =~ (^|[^A-Z0-9_])(EMAIL|PASSWORD|PAYMENT_KEY|ORDER_ID|VIRTUAL_ACCOUNT_NUMBER|VIRTUAL_CUSTOMER_NAME|CLIENT_IP)([^A-Z0-9_]|$) \
  || "$query" == *'@'* ]]; then
  fail 'SQL input contains sensitive or PII-bearing fields'
fi
string_literals=$(printf '%s' "$query" | grep -Eo "'([^']|'')*'" || true)
if [[ -n "$string_literals" ]]; then
  while IFS= read -r literal; do
    literal_value=${literal#\'}
    literal_value=${literal_value%\'}
    if [[ ! "$literal_value" =~ ^(PUBLISHED|ACTIVE|CONFIRM|CANCEL|PARTIAL_CANCEL)$ \
      && ! "$literal_value" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}([[:space:]T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?)?$ ]]; then
      fail 'SQL input contains an unsupported string literal that may carry PII'
    fi
  done <<< "$string_literals"
fi

mysql_bin=$(command -v mysql) || fail 'mysql client is required'
jq_bin=$(command -v jq) || fail 'jq is required'

mysql_exec() {
  "$mysql_bin" \
    --login-path="$login_path" \
    --database=airbobdb \
    --batch \
    --raw \
    --skip-column-names \
    --connect-timeout=10 \
    --execute "$1"
}

invisible_count=$(mysql_exec "SELECT COUNT(*) FROM (SELECT DISTINCT TABLE_NAME, INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'airbobdb' AND IS_VISIBLE = 'NO' AND INDEX_NAME <> 'PRIMARY') invisible_indexes") \
  || fail 'cannot inspect invisible indexes'
[[ "$invisible_count" =~ ^[0-9]+$ ]] || fail 'invisible index inventory is malformed'
if [[ "$treatment" == 'read-model' ]]; then
  [[ "$invisible_count" == '0' ]] || fail 'read-model treatment cannot share a clone with an index candidate'
else
  [[ "$invisible_count" == '1' ]] || fail 'index treatment requires exactly one invisible candidate in the clone'
  observed_candidate=$(mysql_exec "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'airbobdb' AND IS_VISIBLE = 'NO' AND INDEX_NAME <> 'PRIMARY' ORDER BY INDEX_NAME") \
    || fail 'cannot identify invisible index candidate'
  [[ "$observed_candidate" == "$candidate_name" ]] \
    || fail 'invisible index candidate does not match the requested candidate'
fi

mysql_version=$(mysql_exec 'SELECT VERSION()') || fail 'cannot capture MySQL version'
[[ "$mysql_version" =~ ^8\.0\.[0-9]+([-+][a-zA-Z0-9._-]+)?$ ]] \
  || fail 'MySQL exact patch version is invalid'
optimizer_switch=$(mysql_exec "SET SESSION optimizer_switch='$optimizer_setting'; SELECT @@SESSION.optimizer_switch") \
  || fail 'cannot configure invisible-index optimizer treatment'
expected_switch=${optimizer_setting#*=}
[[ ",$optimizer_switch," == *",use_invisible_indexes=$expected_switch,"* ]] \
  || fail 'optimizer switch receipt does not match the treatment'

if command -v shasum >/dev/null 2>&1; then
  query_sha256=$(shasum -a 256 "$sql_absolute" | awk '{print $1}')
elif command -v sha256sum >/dev/null 2>&1; then
  query_sha256=$(sha256sum "$sql_absolute" | awk '{print $1}')
else
  fail 'a SHA-256 tool is required'
fi
[[ "$query_sha256" =~ ^[0-9a-f]{64}$ ]] || fail 'query SHA-256 is invalid'

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-read-model-explain.XXXXXX") \
  || fail 'cannot create private EXPLAIN workspace'
json_raw_file="$work_dir/explain.json.raw"
tree_raw_file="$work_dir/explain.tree.raw"
temporary_output=''
cleanup() {
  if [[ -n "$temporary_output" && -e "$temporary_output" ]]; then
    rm -f "$temporary_output"
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT INT TERM

mysql_exec "SET SESSION optimizer_switch='$optimizer_setting'; EXPLAIN FORMAT=JSON $query" \
  > "$json_raw_file" || fail 'EXPLAIN FORMAT=JSON failed'
mysql_exec "SET SESSION optimizer_switch='$optimizer_setting'; EXPLAIN ANALYZE FORMAT=TREE $query" \
  > "$tree_raw_file" || fail 'EXPLAIN ANALYZE FORMAT=TREE failed'
chmod 600 "$json_raw_file" "$tree_raw_file"

for raw_file in "$json_raw_file" "$tree_raw_file"; do
  raw_bytes=$(wc -c < "$raw_file" | tr -d '[:space:]')
  [[ "$raw_bytes" =~ ^[0-9]+$ && "$raw_bytes" -gt 0 && "$raw_bytes" -le 524288 ]] \
    || fail 'raw EXPLAIN output is empty or too large'
  if LC_ALL=C grep -Eiq '([^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+)|(password|payment_key|order_id|virtual_account_number|virtual_customer_name|client_ip)[[:space:]]*[:=]' "$raw_file"; then
    fail 'raw EXPLAIN output contains sensitive or PII data'
  fi
done
"$jq_bin" -e 'type == "object"' "$json_raw_file" >/dev/null \
  || fail 'EXPLAIN FORMAT=JSON did not return one JSON object'
LC_ALL=C grep -Eq 'actual[[:space:]]+time[[:space:]]*=' "$tree_raw_file" \
  || fail 'EXPLAIN ANALYZE did not return iterator timing'

candidate_in_chosen_plan='false'
if [[ -n "$candidate_name" ]]; then
  if "$jq_bin" -e --arg candidate "$candidate_name" '
    [
      .. | objects | to_entries[]
      | select(.key == "key"
          or .key == "index"
          or .key == "index_name"
          or .key == "indexName"
          or .key == "chosen_index"
          or .key == "used_index")
      | .value
      | select(. == $candidate)
    ] | length > 0
  ' "$json_raw_file" >/dev/null; then
    candidate_in_chosen_plan='true'
  fi
fi

generated_at=$(date -u '+%Y-%m-%dT%H:%M:%S.000Z')
temporary_output=$(mktemp "$artifact_root/.mysql-explain-evidence.XXXXXX") \
  || fail 'cannot create atomic output file'
chmod 600 "$temporary_output"

candidate_json='null'
if [[ -n "$candidate_name" ]]; then
  candidate_json=$("$jq_bin" -Rn --arg value "$candidate_name" '$value')
fi

"$jq_bin" -n \
  --arg generatedAt "$generated_at" \
  --arg cloneId "$clone_id" \
  --arg targetId "$target_id" \
  --arg windowId "$window_id" \
  --arg treatment "$treatment" \
  --argjson candidateIndex "$candidate_json" \
  --argjson candidateVisible "$candidate_visible_json" \
  --argjson useInvisibleIndexes "$invisible_switch_json" \
  --arg querySha256 "$query_sha256" \
  --arg mysqlVersion "$mysql_version" \
  --arg optimizerSwitch "$optimizer_switch" \
  --rawfile explainJson "$json_raw_file" \
  --rawfile explainTree "$tree_raw_file" \
  --argjson candidateInChosenPlan "$candidate_in_chosen_plan" '
    {
      schema_version: "mysql-explain-evidence-v1",
      metadata: {
        generated_at: $generatedAt,
        clone_id: $cloneId,
        target_id: $targetId,
        window_id: $windowId,
        treatment: $treatment,
        candidate_index: $candidateIndex,
        candidate_visible: $candidateVisible,
        optimizer_switch_use_invisible_indexes: $useInvisibleIndexes,
        query_sha256: $querySha256,
        mysql_version: $mysqlVersion,
        optimizer_switch_raw: $optimizerSwitch
      },
      explain: {
        json_raw: $explainJson,
        tree_raw: $explainTree,
        candidate_in_chosen_plan: $candidateInChosenPlan
      }
    }
  ' > "$temporary_output" || fail 'cannot encode EXPLAIN evidence'

[[ ! -e "$output_absolute" && ! -L "$output_absolute" ]] || fail 'output already exists'
ln "$temporary_output" "$output_absolute" || fail 'cannot publish EXPLAIN evidence atomically'
rm -f "$temporary_output"
temporary_output=''
printf 'mysql_explain_evidence=%s\n' "$output"
