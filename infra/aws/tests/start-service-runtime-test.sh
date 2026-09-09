#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
template="$repo_root/infra/aws/lab/templates/start-service.sh.tftpl"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-start-service-runtime-test.XXXXXX")

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

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

assert_contains() {
  local text=$1
  local expected=$2
  grep -Fq -- "$expected" <<<"$text" || fail "output does not contain: $expected"
}

assert_not_contains() {
  local text=$1
  local rejected=$2
  if grep -Fq -- "$rejected" <<<"$text"; then
    fail "output contains rejected text: $rejected"
  fi
}

extract_function() {
  local function_name=$1

  awk -v signature="$function_name() {" '
    $0 == signature { capture=1 }
    capture { print }
    capture && $0 == "}" { exit }
  ' "$template"
}

[[ -f "$template" && ! -L "$template" ]] || fail "start-service template is missing or unsafe"

helper_file="$temp_dir/start-service-helpers.sh"
helper_functions=(
  diagnostic_timeout
  diagnostic_compose
  configure_monitoring_resource_override
  verify_monitoring_resource_contract
  report_failure
  redis_memory_metric
  verify_redis_memory_info
  wait_for_http_5s
)
{
  for function_name in "${helper_functions[@]}"; do
    extract_function "$function_name"
  done
} | sed 's/\$\${/${/g' > "$helper_file"

for function_name in "${helper_functions[@]}"; do
  grep -Fq "$function_name() {" "$helper_file" \
    || fail "failed to extract $function_name from the rendered template"
done
bash -n "$helper_file"

monitoring_override="$temp_dir/monitoring-resource.override.yml"
monitoring_output=$(
  HELPER_FILE="$helper_file" MONITORING_OVERRIDE="$monitoring_override" bash <<'CASE'
set -Eeuo pipefail
source "$HELPER_FILE"
compose_files=(-f /opt/airbob/release/infra/aws/bundles/monitoring/compose.yml)
configure_monitoring_resource_override "$MONITORING_OVERRIDE"
compose() {
  [[ "$*" == 'config --format json' ]]
  printf '%s\n' '{"services":{"grafana":{"mem_limit":536870912,"memswap_limit":536870912}}}'
}
verify_monitoring_resource_contract
printf 'compose_files=%s\n' "${compose_files[*]}"
CASE
)
assert_contains "$monitoring_output" 'Monitoring resource contract: grafana_memory_limit_bytes=536870912 grafana_memory_swap_limit_bytes=536870912'
assert_contains "$monitoring_output" "compose_files=-f /opt/airbob/release/infra/aws/bundles/monitoring/compose.yml -f $monitoring_override"
assert_contains "$(cat "$monitoring_override")" 'mem_limit: 512M'
assert_contains "$(cat "$monitoring_override")" 'memswap_limit: 512M'

set +e
drifted_monitoring_output=$(
  HELPER_FILE="$helper_file" bash <<'CASE' 2>&1
set -Eeuo pipefail
source "$HELPER_FILE"
compose() {
  printf '%s\n' '{"services":{"grafana":{"mem_limit":402653184,"memswap_limit":402653184}}}'
}
verify_monitoring_resource_contract
CASE
)
drifted_monitoring_status=$?
set -e
[[ "$drifted_monitoring_status" -eq 1 ]] \
  || fail "drifted monitoring memory contract exited $drifted_monitoring_status instead of 1"
assert_not_contains "$drifted_monitoring_output" 'Monitoring resource contract:'

immediate_log="$temp_dir/http-immediate.log"
immediate_output=$(
  HELPER_FILE="$helper_file" CURL_LOG="$immediate_log" bash <<'CASE'
set -Eeuo pipefail
source "$HELPER_FILE"
service=elasticsearch
curl_calls=0
curl() {
  curl_calls=$((curl_calls + 1))
  printf '%s\n' "$*" >> "$CURL_LOG"
  return 0
}
sleep() {
  printf '%s\n' 'unexpected sleep after immediate HTTP success' >&2
  return 99
}
deadline=$((SECONDS + 10))
wait_for_http_5s cluster-health http://127.0.0.1:9200/_cluster/health "$deadline"
printf 'curl_calls=%s\n' "$curl_calls"
CASE
)
assert_contains "$immediate_output" 'Service endpoint ready: service=elasticsearch check=cluster-health elapsed_seconds=0'
assert_contains "$immediate_output" 'curl_calls=1'
[[ "$(wc -l < "$immediate_log" | tr -d ' ')" == 1 ]] \
  || fail "immediate HTTP success did not make exactly one request"
assert_contains "$(cat "$immediate_log")" '--connect-timeout 5 --max-time 5 http://127.0.0.1:9200/_cluster/health'

retry_output=$(
  HELPER_FILE="$helper_file" bash <<'CASE'
set -Eeuo pipefail
source "$HELPER_FILE"
service=elasticsearch
curl_calls=0
sleep_calls=0
curl() {
  curl_calls=$((curl_calls + 1))
  [[ "$curl_calls" -ge 2 ]]
}
sleep() {
  sleep_calls=$((sleep_calls + 1))
  SECONDS=$((SECONDS + $1))
}
deadline=$((SECONDS + 20))
wait_for_http_5s cluster-health http://127.0.0.1:9200/_cluster/health "$deadline"
printf 'curl_calls=%s sleep_calls=%s\n' "$curl_calls" "$sleep_calls"
CASE
)
assert_contains "$retry_output" 'Service endpoint ready: service=elasticsearch check=cluster-health elapsed_seconds=5'
assert_contains "$retry_output" 'curl_calls=2 sleep_calls=1'

shared_deadline_log="$temp_dir/http-shared-deadline.log"
set +e
shared_deadline_output=$(
  HELPER_FILE="$helper_file" CURL_LOG="$shared_deadline_log" bash <<'CASE' 2>&1
set -Eeuo pipefail
source "$HELPER_FILE"
service=elasticsearch
cluster_calls=0
exporter_calls=0
sleep_total=0
curl() {
  local url="${*: -1}"
  printf '%s\n' "$*" >> "$CURL_LOG"
  if [[ "$url" == http://127.0.0.1:9200/_cluster/health ]]; then
    cluster_calls=$((cluster_calls + 1))
    [[ "$cluster_calls" -ge 2 ]]
    return
  fi
  exporter_calls=$((exporter_calls + 1))
  return 1
}
sleep() {
  sleep_total=$((sleep_total + $1))
  SECONDS=$((SECONDS + $1))
}
deadline=$((SECONDS + 7))
wait_for_http_5s cluster-health http://127.0.0.1:9200/_cluster/health "$deadline"
if wait_for_http_5s exporter-metrics http://127.0.0.1:9114/metrics "$deadline"; then
  printf '%s\n' 'exporter unexpectedly became ready' >&2
  exit 98
fi
printf 'cluster_calls=%s exporter_calls=%s sleep_total=%s\n' \
  "$cluster_calls" "$exporter_calls" "$sleep_total"
exit 37
CASE
)
shared_deadline_status=$?
set -e
[[ "$shared_deadline_status" -eq 37 ]] \
  || fail "shared deadline case exited $shared_deadline_status instead of 37"
assert_contains "$shared_deadline_output" 'Service endpoint ready: service=elasticsearch check=cluster-health elapsed_seconds=5'
assert_contains "$shared_deadline_output" 'Service endpoint did not become ready: service=elasticsearch check=exporter-metrics timeout_seconds=2'
assert_contains "$shared_deadline_output" 'cluster_calls=2 exporter_calls=1 sleep_total=7'
[[ "$(wc -l < "$shared_deadline_log" | tr -d ' ')" == 3 ]] \
  || fail "shared HTTP deadline did not bound the request count to three"
assert_contains "$(tail -n 1 "$shared_deadline_log")" '--connect-timeout 2 --max-time 2 http://127.0.0.1:9114/metrics'

valid_redis_output=$(
  HELPER_FILE="$helper_file" bash <<'CASE'
set -Eeuo pipefail
source "$HELPER_FILE"
stage=initialization
redis_info=$'used_memory:123\r\nused_memory_rss:456\r\nmem_fragmentation_ratio:1.75\r\nmem_fragmentation_bytes:-17\r\nallocator_frag_ratio:1.01\r\nallocator_frag_bytes:-3\r\n'
compose() {
  [[ "$*" == 'exec --no-TTY redis redis-cli INFO memory' ]]
  printf '%s' "$redis_info"
}
verify_redis_memory_info after-probe redis
printf 'stage=%s\n' "$stage"
CASE
)
assert_contains "$valid_redis_output" 'used_memory=123 used_memory_rss=456'
assert_contains "$valid_redis_output" 'mem_fragmentation_ratio=1.75 mem_fragmentation_bytes=-17'
assert_contains "$valid_redis_output" 'allocator_frag_ratio=1.01 allocator_frag_bytes=-3'
assert_contains "$valid_redis_output" 'stage=redis-after-probe-redis-memory-info'

set +e
missing_redis_output=$(
  HELPER_FILE="$helper_file" bash <<'CASE' 2>&1
set -Euo pipefail
source "$HELPER_FILE"
stage=initialization
redis_info=$'used_memory:123\r\nused_memory_rss:456\r\nmem_fragmentation_ratio:1.75\r\nmem_fragmentation_bytes:-17\r\nallocator_frag_ratio:1.01\r\n'
compose() { printf '%s' "$redis_info"; }
verify_redis_memory_info after-probe redis
CASE
)
missing_redis_status=$?
set -e
[[ "$missing_redis_status" -eq 1 ]] \
  || fail "missing Redis INFO metric exited $missing_redis_status instead of 1"
assert_contains "$missing_redis_output" 'allocator_frag_bytes=missing-or-nonnumeric'
assert_contains "$missing_redis_output" 'Redis memory INFO did not provide every required numeric field'

set +e
nonnumeric_redis_output=$(
  HELPER_FILE="$helper_file" bash <<'CASE' 2>&1
set -Euo pipefail
source "$HELPER_FILE"
stage=initialization
redis_info=$'used_memory:not-a-number\r\nused_memory_rss:456\r\nmem_fragmentation_ratio:1.75\r\nmem_fragmentation_bytes:-17\r\nallocator_frag_ratio:1.01\r\nallocator_frag_bytes:-3\r\n'
compose() { printf '%s' "$redis_info"; }
verify_redis_memory_info after-probe redis
CASE
)
nonnumeric_redis_status=$?
set -e
[[ "$nonnumeric_redis_status" -eq 1 ]] \
  || fail "nonnumeric Redis INFO metric exited $nonnumeric_redis_status instead of 1"
assert_contains "$nonnumeric_redis_output" 'used_memory=missing-or-nonnumeric'
assert_contains "$nonnumeric_redis_output" 'Redis memory INFO did not provide every required numeric field'

diagnostic_compose_file="$temp_dir/compose.yml"
diagnostic_log="$temp_dir/compose-bootstrap.log"
diagnostic_calls="$temp_dir/diagnostic-compose-calls.log"
diagnostic_docker_calls="$temp_dir/diagnostic-docker-calls.log"
diagnostic_timeout_calls="$temp_dir/diagnostic-timeout-calls.log"
sentinel_secret='AIRBOB_SENTINEL_SECRET_DO_NOT_PRINT'
printf 'password: %s\n' "$sentinel_secret" > "$diagnostic_compose_file"
printf '%s\n' 'safe detached startup progress' > "$diagnostic_log"
set +e
diagnostic_output=$(
  HELPER_FILE="$helper_file" \
  COMPOSE_FILE="$diagnostic_compose_file" \
  BOOTSTRAP_LOG="$diagnostic_log" \
  COMPOSE_CALLS="$diagnostic_calls" \
  DOCKER_CALLS="$diagnostic_docker_calls" \
  TIMEOUT_CALLS="$diagnostic_timeout_calls" \
  SENTINEL_SECRET="$sentinel_secret" \
  bash <<'CASE' 2>&1
set -Eeuo pipefail
source "$HELPER_FILE"
service=redis
stage=compose-start
compose_file=$COMPOSE_FILE
compose_bootstrap_log=$BOOTSTRAP_LOG
compose_files=(-f "$compose_file")
timeout() {
  [[ "$1" == --kill-after=2s && "$2" == 10s ]]
  printf '%s %s\n' "$1" "$2" >> "$TIMEOUT_CALLS"
  shift 2
  "$@"
}
docker() {
  printf '%s\n' "$*" >> "$DOCKER_CALLS"
  if [[ "$1" == inspect ]]; then
    printf '%s\n' 'Container runtime state: name=/redis status=restarting exit_code=137 oom_killed=true restart_count=3 memory_limit_bytes=536870912 memory_swap_limit_bytes=536870912'
  elif [[ "$1" == compose && "$*" == *' ps --quiet --all' ]]; then
    printf '%s\n' 'ps --quiet --all' >> "$COMPOSE_CALLS"
    printf '%s\n' 'diagnostic-container-id'
  elif [[ "$1" == compose && "$*" == *' ps --all' ]]; then
    printf '%s\n' 'ps --all' >> "$COMPOSE_CALLS"
    printf '%s\n' 'NAME STATUS' 'redis running'
  else
    printf 'unexpected secret-bearing Docker diagnostic: %s\n' "$SENTINEL_SECRET"
  fi
}
report_failure 37 4242
CASE
)
diagnostic_status=$?
set -e
[[ "$diagnostic_status" -eq 37 ]] \
  || fail "failure diagnostic exited $diagnostic_status instead of preserving exit code 37"
assert_contains "$diagnostic_output" 'Phase 2 service bootstrap failed: service=redis stage=compose-start exit=37 line=4242'
assert_contains "$diagnostic_output" 'Recent Docker Compose bootstrap output:'
assert_contains "$diagnostic_output" 'safe detached startup progress'
assert_contains "$diagnostic_output" 'Docker Compose container state:'
assert_contains "$diagnostic_output" 'redis running'
assert_contains "$diagnostic_output" 'Container runtime state: name=/redis status=restarting exit_code=137 oom_killed=true restart_count=3 memory_limit_bytes=536870912 memory_swap_limit_bytes=536870912'
assert_not_contains "$diagnostic_output" "$sentinel_secret"
[[ "$(cat "$diagnostic_calls")" == $'ps --quiet --all\nps --all' ]] \
  || fail "failure diagnostic invoked a secret-bearing Compose command"
assert_contains "$(cat "$diagnostic_docker_calls")" 'inspect --format'
assert_contains "$(cat "$diagnostic_docker_calls")" 'diagnostic-container-id'
[[ "$(wc -l < "$diagnostic_timeout_calls" | tr -d ' ')" == 3 ]] \
  || fail "failure diagnostics were not bounded by exactly three timeout calls"

printf '%s\n' 'start-service runtime helper tests passed'
