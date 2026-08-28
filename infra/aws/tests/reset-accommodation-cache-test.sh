#!/usr/bin/env bash
set -euo pipefail

test_dir=$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$test_dir/../../.." && pwd -P)
script="$repo_root/infra/aws/scripts/reset-accommodation-cache.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/cache-reset-test.XXXXXX")
trap 'rm -rf -- "$temp_dir"' EXIT

printf 'REDIS_IMAGE=redis.example/redis@sha256:%064d\n' 0 > "$temp_dir/images.env"
cat > "$temp_dir/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$CACHE_RESET_DOCKER_LOG"
if [[ "${CACHE_RESET_BLOCK:-0}" == 1 ]]; then
  sleep 30
  exit 99
fi
case "${*: -1}" in
  FLUSHDB) printf 'OK\n' ;;
  DBSIZE) printf '%s\n' "${CACHE_RESET_DB_SIZE:-0}" ;;
  *) exit 1 ;;
esac
DOCKER
chmod 700 "$temp_dir/docker"

manifest_sha=$(printf 'a%.0s' {1..64})
CACHE_RESET_TEST_MODE=1 \
CACHE_RESET_IMAGES_ENV="$temp_dir/images.env" \
DOCKER_BIN="$temp_dir/docker" \
CACHE_RESET_DOCKER_LOG="$temp_dir/docker.log" \
CACHE_RESET_GENERATED_AT=2026-08-26T12:00:00Z \
  "$script" "$manifest_sha" cache-warm-r1 true warm > "$temp_dir/receipt.json"

jq -e --arg sha "$manifest_sha" '
  .schemaVersion == 1 and
  .manifestSha256 == $sha and
  .capsuleId == "cache-detail-v1" and
  .action == "flush-dedicated-cache-redis" and
  .dbSizeAfter == 0 and
  .cacheEnabled == true and
  .variant == "warm" and
  .runLabel == "cache-warm-r1"
' "$temp_dir/receipt.json" >/dev/null
[[ "$(wc -l < "$temp_dir/docker.log" | tr -d '[:space:]')" == 2 ]]
[[ "$(grep -Fc -- '--host redis-cache.lab.airbob.internal' "$temp_dir/docker.log")" == 2 ]]
grep -F -- '--port 6380 FLUSHDB' "$temp_dir/docker.log" >/dev/null
grep -F -- '--port 6380 DBSIZE' "$temp_dir/docker.log" >/dev/null
if grep -F -- '--host redis-general.lab.airbob.internal' "$temp_dir/docker.log" >/dev/null; then
  printf 'cache reset touched the general Redis hostname\n' >&2
  exit 1
fi
if grep -F -- '--port 6379' "$temp_dir/docker.log" >/dev/null; then
  printf 'cache reset touched the general Redis port\n' >&2
  exit 1
fi

if CACHE_RESET_TEST_MODE=1 \
  CACHE_RESET_IMAGES_ENV="$temp_dir/images.env" \
  DOCKER_BIN="$temp_dir/docker" \
  CACHE_RESET_DOCKER_LOG="$temp_dir/failure.log" \
  CACHE_RESET_DB_SIZE=1 \
  "$script" "$manifest_sha" cache-warm-r2 true warm > "$temp_dir/failure.json" 2>/dev/null; then
  printf 'cache reset accepted a non-empty dedicated Redis\n' >&2
  exit 1
fi
[[ ! -s "$temp_dir/failure.json" ]]

SECONDS=0
set +e
CACHE_RESET_TEST_MODE=1 \
  CACHE_RESET_IMAGES_ENV="$temp_dir/images.env" \
  DOCKER_BIN="$temp_dir/docker" \
  CACHE_RESET_DOCKER_LOG="$temp_dir/blocking.log" \
  CACHE_RESET_BLOCK=1 \
  CACHE_RESET_COMMAND_TIMEOUT_SECONDS=1 \
  "$script" "$manifest_sha" cache-warm-r3 true warm \
  > "$temp_dir/blocking.json" 2> "$temp_dir/blocking.stderr"
blocking_status=$?
set -e
blocking_elapsed=$SECONDS
[[ "$blocking_status" -ne 0 ]]
[[ "$blocking_elapsed" -lt 10 ]]
[[ ! -s "$temp_dir/blocking.json" ]]
grep -F -- 'dedicated cache Redis command timed out after 1s' \
  "$temp_dir/blocking.stderr" >/dev/null
[[ "$(wc -l < "$temp_dir/blocking.log" | tr -d '[:space:]')" == 1 ]]
grep -F -- '--host redis-cache.lab.airbob.internal --port 6380 FLUSHDB' \
  "$temp_dir/blocking.log" >/dev/null

set +e
CACHE_RESET_COMMAND_TIMEOUT_SECONDS=1 \
  "$script" "$manifest_sha" cache-warm-r4 true warm \
  > "$temp_dir/override.json" 2> "$temp_dir/override.stderr"
override_status=$?
set -e
[[ "$override_status" -ne 0 ]]
[[ ! -s "$temp_dir/override.json" ]]
grep -F -- 'cache reset executable overrides require test mode' \
  "$temp_dir/override.stderr" >/dev/null
