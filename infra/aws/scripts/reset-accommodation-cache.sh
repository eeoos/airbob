#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() { printf '%s\n' "$1" >&2; exit 1; }

(( $# == 4 )) || fail 'usage: reset-accommodation-cache.sh MANIFEST_SHA RUN_LABEL CACHE_ENABLED VARIANT'
manifest_sha=$1
run_label=$2
cache_enabled=$3
variant=$4

[[ "$manifest_sha" =~ ^[0-9a-f]{64}$ ]] || fail 'manifest SHA-256 is invalid'
[[ "$run_label" =~ ^[a-z0-9][a-z0-9-]{2,79}$ ]] || fail 'run label is invalid'
[[ "$cache_enabled" == true || "$cache_enabled" == false ]] || fail 'cache enabled must be true or false'
[[ "$variant" == disabled || "$variant" == warm ]] || fail 'cache variant is invalid'
[[ ( "$variant" == warm && "$cache_enabled" == true ) \
  || ( "$variant" == disabled && "$cache_enabled" == false ) ]] \
  || fail 'cache enabled does not match the variant'

test_mode=${CACHE_RESET_TEST_MODE:-0}
[[ "$test_mode" == 0 || "$test_mode" == 1 ]] || fail 'CACHE_RESET_TEST_MODE must be 0 or 1'
if [[ "$test_mode" == 1 ]]; then
  images_env=${CACHE_RESET_IMAGES_ENV:?CACHE_RESET_IMAGES_ENV is required in test mode}
  docker_bin=${DOCKER_BIN:?DOCKER_BIN is required in test mode}
  generated_at=${CACHE_RESET_GENERATED_AT:-2026-08-26T12:00:00Z}
  command_timeout_seconds=${CACHE_RESET_COMMAND_TIMEOUT_SECONDS:-30}
else
  [[ -z "${CACHE_RESET_IMAGES_ENV+x}" && -z "${DOCKER_BIN+x}" \
    && -z "${CACHE_RESET_GENERATED_AT+x}" \
    && -z "${CACHE_RESET_COMMAND_TIMEOUT_SECONDS+x}" ]] \
    || fail 'cache reset executable overrides require test mode'
  images_env=/etc/airbob/images.env
  docker_bin=docker
  generated_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  command_timeout_seconds=30
fi
[[ "$command_timeout_seconds" =~ ^[1-9][0-9]*$ \
  && "$command_timeout_seconds" -le 300 ]] \
  || fail 'cache reset command timeout must be between 1 and 300 seconds'
command -v timeout >/dev/null 2>&1 || fail 'timeout executable is unavailable'
[[ -f "$images_env" && ! -L "$images_env" ]] || fail 'immutable image contract is unavailable'
redis_image=$(awk -F= '$1 == "REDIS_IMAGE" {print substr($0, index($0, "=") + 1)}' "$images_env")
[[ "$redis_image" =~ ^[a-zA-Z0-9./:_-]+@sha256:[0-9a-f]{64}$ ]] \
  || fail 'immutable Redis image is invalid'

redis_cli() {
  local output status
  if output=$(timeout --signal=TERM --kill-after=5s "${command_timeout_seconds}s" \
    "$docker_bin" run --rm --network host "$redis_image" redis-cli \
    --host redis-cache.lab.airbob.internal --port 6380 "$@"); then
    printf '%s\n' "$output"
    return 0
  else
    status=$?
  fi
  [[ "$status" != 124 && "$status" != 137 ]] \
    || fail "dedicated cache Redis command timed out after ${command_timeout_seconds}s"
  fail "dedicated cache Redis command failed with exit ${status}"
}

[[ "$(redis_cli FLUSHDB)" == OK ]] || fail 'dedicated cache Redis FLUSHDB failed'
db_size=$(redis_cli DBSIZE)
[[ "$db_size" == 0 ]] || fail 'dedicated cache Redis is not empty after reset'

jq -n \
  --arg manifestSha256 "$manifest_sha" \
  --arg runLabel "$run_label" \
  --arg variant "$variant" \
  --arg generatedAt "$generated_at" \
  --argjson cacheEnabled "$cache_enabled" \
  '{
    schemaVersion: 1,
    manifestSha256: $manifestSha256,
    capsuleId: "cache-detail-v1",
    action: "flush-dedicated-cache-redis",
    dbSizeAfter: 0,
    cacheEnabled: $cacheEnabled,
    variant: $variant,
    runLabel: $runLabel,
    generatedAt: $generatedAt
  }'
