#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
MANIFEST="${BENCHMARK_MANIFEST:-/Users/jaehoonchoi/study/CodeSquad/etl/etl/build/benchmark-fixture.json}"
MYSQL_CONTAINER="${BATCH_MYSQL_CONTAINER:-airbob-benchmark-mysql}"
APP_PORT="${BATCH_APP_PORT:-18080}"
MANAGEMENT_PORT="${BATCH_MANAGEMENT_PORT:-18081}"
REDIS_PORT="${BATCH_REDIS_PORT:-16379}"
BATCH_SIZE="${BATCH_SIZE:-100}"
REPEAT_INDEX="${BATCH_REPEAT_INDEX:-1}"
WARMUP_DURATION="${BATCH_WARMUP_DURATION:-60s}"
MEASURE_DURATION="${BATCH_MEASURE_DURATION:-45s}"
RATE="${BATCH_RATE:-3}"
STAGGER_MS="${BATCH_STAGGER_MS:-100}"
BASELINE_JSON="${BATCH_BASELINE_JSON:-}"
JAR_PATH="${BATCH_JAR_PATH:-$ROOT_DIR/build/libs/airbob.jar}"
STATE_DIR="$ROOT_DIR/.context/compound-engineering/ce-optimize/hibernate-batch-fetch-size"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$-${BATCH_SIZE}-${REPEAT_INDEX}"
RUN_DIR="$STATE_DIR/runs/$RUN_ID"
RECOVERY_MARKER="$STATE_DIR/recovery.marker"
LOCK_DIR="$STATE_DIR/exclusive.lock"
REDIS_CONTAINER="airbob-batch-redis-$RUN_ID"
RO_USER="batch_ro_$(printf '%s' "$RUN_ID" | shasum -a 256 | cut -c1-12)"
RO_PASSWORD="$(openssl rand -hex 24)"
TEST_PASSWORD="$(openssl rand -hex 24)"
MEASUREMENT_DEADLINE_SECONDS=270

APP_PID=""
RSS_PID=""
MEMBER_ID=""
ORIGINAL_HASH=""
TEMP_HASH=""
MYSQL_ROOT_PASSWORD=""
LOCK_HELD=0
CLEANED=0
MEASUREMENT_STARTED_AT=0
TIMEOUT_COMMAND=""

umask 077
mkdir -p "$RUN_DIR"

log() {
  printf '[batch-fetch] %s\n' "$*" >&2
}

fail() {
  log "ERROR: $*"
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

require_free_port() {
  local port="$1"
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    fail "TCP port $port is already in use"
  fi
}

is_bcrypt_hash() {
  [[ "${#1}" -eq 60 ]] || return 1
  case "$1" in
    '$2a$'*|'$2b$'*|'$2y$'*) return 0 ;;
    *) return 1 ;;
  esac
}

mysql_root() {
  docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
    mysql -uroot --batch --skip-column-names "$@"
}

data_hash() {
  docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
    mysqldump -uroot \
      --no-create-info \
      --skip-comments \
      --skip-triggers \
      --single-transaction \
      --skip-extended-insert \
      --order-by-primary \
      airbobdb 2>/dev/null \
    | shasum -a 256 \
    | awk '{print $1}'
}

acquire_lock() {
  if mkdir "$LOCK_DIR" 2>/dev/null; then
    printf '%s\n' "$$" >"$LOCK_DIR/pid"
    LOCK_HELD=1
    return 0
  fi

  local owner=""
  owner="$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null || true)"
  if [[ "$owner" =~ ^[0-9]+$ ]] && kill -0 "$owner" >/dev/null 2>&1; then
    fail "another batch-fetch benchmark is active (pid=$owner)"
  fi

  log "removing stale exclusive lock"
  rm -f "$LOCK_DIR/pid"
  rmdir "$LOCK_DIR" 2>/dev/null || fail "stale lock directory is not empty: $LOCK_DIR"
  mkdir "$LOCK_DIR" || fail "could not acquire exclusive benchmark lock"
  printf '%s\n' "$$" >"$LOCK_DIR/pid"
  LOCK_HELD=1
}

release_lock() {
  [[ "$LOCK_HELD" -eq 1 ]] || return 0
  rm -f "$LOCK_DIR/pid"
  if rmdir "$LOCK_DIR" 2>/dev/null; then
    LOCK_HELD=0
    return 0
  fi
  log "ERROR: could not release benchmark lock: $LOCK_DIR"
  return 1
}

stop_app() {
  if [[ -n "$RSS_PID" ]]; then
    kill "$RSS_PID" >/dev/null 2>&1 || true
    wait "$RSS_PID" >/dev/null 2>&1 || true
    RSS_PID=""
  fi

  if [[ -n "$APP_PID" ]]; then
    kill "$APP_PID" >/dev/null 2>&1 || true
    for _ in {1..30}; do
      if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
        break
      fi
      sleep 0.2
    done
    if kill -0 "$APP_PID" >/dev/null 2>&1; then
      kill -9 "$APP_PID" >/dev/null 2>&1 || true
    fi
    wait "$APP_PID" >/dev/null 2>&1 || true
    APP_PID=""
    rm -f "$RUN_DIR/app.pid"
  fi
}

stop_stale_app() {
  local stale_run_dir="$1"
  local stale_jar="$2"
  local stale_port="$3"
  local pid_file="$stale_run_dir/app.pid"
  [[ -f "$pid_file" ]] || return 0

  local stale_pid command_line
  stale_pid="$(sed -n '1p' "$pid_file")"
  [[ "$stale_pid" =~ ^[0-9]+$ ]] || return 1
  if ! kill -0 "$stale_pid" >/dev/null 2>&1; then
    rm -f "$pid_file"
    return 0
  fi

  command_line="$(ps -o command= -p "$stale_pid" 2>/dev/null || true)"
  if [[ "$command_line" != *"-jar $stale_jar"* || "$command_line" != *"--server.port=$stale_port"* ]]; then
    log "ERROR: refusing to kill PID $stale_pid because it does not match the benchmark marker"
    return 1
  fi

  kill "$stale_pid" >/dev/null 2>&1 || true
  for _ in {1..30}; do
    kill -0 "$stale_pid" >/dev/null 2>&1 || break
    sleep 0.2
  done
  if kill -0 "$stale_pid" >/dev/null 2>&1; then
    kill -9 "$stale_pid" >/dev/null 2>&1 || return 1
  fi
  rm -f "$pid_file"
}

recover_marker() {
  [[ -f "$RECOVERY_MARKER" ]] || return 0
  [[ -n "$MYSQL_ROOT_PASSWORD" ]] || return 1

  local saved_id saved_original saved_temp saved_user saved_redis saved_run_dir saved_jar saved_port
  local current_hash restored failed=0
  saved_id="$(sed -n '1p' "$RECOVERY_MARKER")"
  saved_original="$(sed -n '2p' "$RECOVERY_MARKER")"
  saved_temp="$(sed -n '3p' "$RECOVERY_MARKER")"
  saved_user="$(sed -n '4p' "$RECOVERY_MARKER")"
  saved_redis="$(sed -n '5p' "$RECOVERY_MARKER")"
  saved_run_dir="$(sed -n '6p' "$RECOVERY_MARKER")"
  saved_jar="$(sed -n '7p' "$RECOVERY_MARKER")"
  saved_port="$(sed -n '8p' "$RECOVERY_MARKER")"

  [[ "$saved_id" =~ ^[0-9]+$ ]] || return 1
  is_bcrypt_hash "$saved_original" || return 1
  is_bcrypt_hash "$saved_temp" || return 1
  [[ "$saved_user" =~ ^batch_ro_[a-f0-9]{12}$ ]] || return 1
  [[ "$saved_redis" =~ ^airbob-batch-redis-[A-Za-z0-9_-]+$ ]] || return 1
  [[ "$saved_run_dir" == "$STATE_DIR"/runs/* ]] || return 1
  [[ -f "$saved_jar" ]] || return 1
  [[ "$saved_port" =~ ^[0-9]+$ ]] || return 1

  stop_stale_app "$saved_run_dir" "$saved_jar" "$saved_port" || failed=1

  current_hash="$(mysql_root airbobdb -e "SELECT password FROM member WHERE id = ${saved_id};" 2>/dev/null || true)"
  if [[ "$current_hash" == "$saved_temp" ]]; then
    mysql_root airbobdb -e \
      "UPDATE member SET password = '${saved_original}' WHERE id = ${saved_id};" >/dev/null 2>&1 \
      || failed=1
  elif [[ "$current_hash" != "$saved_original" ]]; then
    log "ERROR: benchmark account hash differs from both marker values; refusing to overwrite it"
    failed=1
  fi
  restored="$(mysql_root airbobdb -e "SELECT password FROM member WHERE id = ${saved_id};" 2>/dev/null || true)"
  [[ "$restored" == "$saved_original" ]] || failed=1

  mysql_root -e "DROP USER IF EXISTS '${saved_user}'@'%';" >/dev/null 2>&1 || failed=1
  if docker container inspect "$saved_redis" >/dev/null 2>&1; then
    docker rm -f "$saved_redis" >/dev/null 2>&1 || failed=1
  fi

  if [[ "$failed" -eq 0 ]]; then
    rm -f "$RECOVERY_MARKER"
    return 0
  fi
  log "ERROR: durable recovery failed; marker retained at $RECOVERY_MARKER"
  return 1
}

cleanup() {
  local failed=0
  if [[ "$CLEANED" -eq 1 ]]; then
    return 0
  fi

  stop_app

  if [[ -f "$RECOVERY_MARKER" ]]; then
    recover_marker || failed=1
  fi
  release_lock || failed=1

  CLEANED=1
  return "$failed"
}

on_exit() {
  local status=$?
  trap - EXIT INT TERM HUP
  if ! cleanup; then
    status=1
  fi
  exit "$status"
}
trap on_exit EXIT INT TERM HUP

start_app() {
  local phase="$1"
  local log_file="$RUN_DIR/app-${phase}.log"

  require_free_port "$APP_PORT"
  require_free_port "$MANAGEMENT_PORT"

  (
    cd "$RUN_DIR"
    exec env \
      AWS_ACCESS_KEY_ID=benchmark-dummy \
      AWS_SECRET_ACCESS_KEY=benchmark-dummy \
      AWS_REGION=ap-northeast-2 \
      AWS_EC2_METADATA_DISABLED=true \
      GOOGLE_API_KEY=benchmark-dummy \
      IPINFO_API_TOKEN=benchmark-dummy \
      SLACK_WEBHOOK_URL=http://127.0.0.1:1/disabled \
      TOSS_SECRET_KEY=benchmark-dummy \
      SPRING_PROFILES_ACTIVE=dev \
      SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:13307/airbobdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
      SPRING_DATASOURCE_USERNAME="$RO_USER" \
      SPRING_DATASOURCE_PASSWORD="$RO_PASSWORD" \
      SPRING_DATASOURCE_HIKARI_READ_ONLY=true \
      SPRING_FLYWAY_ENABLED=false \
      SPRING_DATA_REDIS_HOST=127.0.0.1 \
      SPRING_DATA_REDIS_PORT="$REDIS_PORT" \
      SPRING_ELASTICSEARCH_URIS=http://127.0.0.1:1 \
      SPRING_DATA_ELASTICSEARCH_SKIP_REPOSITORY_INIT=true \
      SPRING_KAFKA_CONSUMER_BOOTSTRAP_SERVERS=127.0.0.1:1 \
      SPRING_KAFKA_PRODUCER_BOOTSTRAP_SERVERS=127.0.0.1:1 \
      SPRING_KAFKA_LISTENER_AUTO_STARTUP=false \
      MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=false \
      SLACK_NOTIFICATION_ENABLED=false \
      LOGGING_LEVEL_ROOT=WARN \
      LOGGING_LEVEL_KR_KRO_AIRBOB=WARN \
      LOGGING_LEVEL_ORG_HIBERNATE_SQL=OFF \
      java -Xms512m -Xmx1024m \
        -Dspring.jpa.properties.hibernate.default_batch_fetch_size="$BATCH_SIZE" \
        -jar "$JAR_PATH" \
        --server.address=127.0.0.1 \
        --server.port="$APP_PORT" \
        --management.server.address=127.0.0.1 \
        --management.server.port="$MANAGEMENT_PORT" \
        --spring.jpa.show-sql=false \
        --spring.jpa.properties.hibernate.show_sql=false \
        --spring.jpa.properties.hibernate.format_sql=false
  ) >"$log_file" 2>&1 &
  APP_PID=$!
  printf '%s\n' "$APP_PID" >"$RUN_DIR/app.pid"

  for _ in {1..180}; do
    if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
      tail -n 100 "$log_file" >&2 || true
      fail "application exited during $phase startup"
    fi
    if curl --silent --fail "http://127.0.0.1:${MANAGEMENT_PORT}/actuator/health" >/dev/null 2>&1 \
      && curl --silent --fail "http://127.0.0.1:${MANAGEMENT_PORT}/actuator/prometheus" >/dev/null 2>&1; then
      log "application ready for $phase (batch_size=$BATCH_SIZE)"
      return 0
    fi
    sleep 1
  done

  tail -n 100 "$log_file" >&2 || true
  fail "application did not become ready during $phase"
}

start_rss_sampler() {
  : >"$RUN_DIR/rss.samples"
  (
    while kill -0 "$APP_PID" >/dev/null 2>&1; do
      ps -o rss= -p "$APP_PID" 2>/dev/null | awk '{$1=$1; if ($1 != "") print $1}'
      sleep 0.5
    done
  ) >>"$RUN_DIR/rss.samples" &
  RSS_PID=$!
}

run_k6() {
  local size="$1"
  local mode="$2"
  local duration="$3"
  local output="$4"

  local remaining="$MEASUREMENT_DEADLINE_SECONDS"
  if [[ "$MEASUREMENT_STARTED_AT" -gt 0 ]]; then
    remaining="$(( MEASUREMENT_DEADLINE_SECONDS - ($(date +%s) - MEASUREMENT_STARTED_AT) ))"
    [[ "$remaining" -gt 0 ]] || fail "measurement exceeded the scheduler-isolation deadline"
  fi

  BASE_URL="http://127.0.0.1:${APP_PORT}" \
  BENCHMARK_MANIFEST="$MANIFEST" \
  TEST_PASSWORD="$TEST_PASSWORD" \
  SIZE="$size" \
  MODE="$mode" \
  DURATION="$duration" \
  RATE="$RATE" \
  STAGGER_MS="$STAGGER_MS" \
  K6_RESULT_PATH="$output" \
    "$TIMEOUT_COMMAND" "$remaining" \
      k6 run --quiet "$ROOT_DIR/load-test/k6/batch-fetch-size/core-get.js" >&2
}

run_round() {
  local size="$1"
  local mode="$2"
  local label="n${size}"
  local before="$RUN_DIR/${label}.before.prom"
  local after="$RUN_DIR/${label}.after.prom"
  local k6_result="$RUN_DIR/${label}.k6.json"

  curl --silent --show-error --fail \
    "http://127.0.0.1:${MANAGEMENT_PORT}/actuator/prometheus" >"$before"
  run_k6 "$size" "$mode" "$MEASURE_DURATION" "$k6_result"
  curl --silent --show-error --fail \
    "http://127.0.0.1:${MANAGEMENT_PORT}/actuator/prometheus" >"$after"
  ROUND_ARGS+=(--round "${size}:${before}:${after}:${k6_result}")
}

for command in docker jq curl openssl htpasswd shasum awk sed lsof k6 java ps python3; do
  require_command "$command"
done
TIMEOUT_COMMAND="$(command -v gtimeout || command -v timeout || true)"
[[ -n "$TIMEOUT_COMMAND" ]] || fail "timeout or gtimeout is required"

case "$BATCH_SIZE" in
  0|20|50|100) ;;
  *) fail "BATCH_SIZE must be one of 0, 20, 50, or 100" ;;
esac
[[ "$REPEAT_INDEX" =~ ^[1-9][0-9]*$ ]] || fail "BATCH_REPEAT_INDEX must be a positive integer"
[[ -f "$JAR_PATH" ]] || fail "application jar not found: $JAR_PATH"
[[ -f "$MANIFEST" ]] || fail "benchmark manifest not found: $MANIFEST"
[[ -f "$ROOT_DIR/load-test/k6/batch-fetch-size/core-get.js" ]] || fail "core-get.js is missing"
[[ -f "$ROOT_DIR/load-test/k6/batch-fetch-size/analyze.py" ]] || fail "analyze.py is missing"

jq -e '
  .datasetVersion == "nplus1-v1"
  and .account.email == "benchmark-nplus1@airbob.cloud"
  and .maxRequestedSize == 200
  and .requiredRows == 201
  and .review.reviewsWithImages == 201
  and .hostAccommodations.expectedRows == 201
  and .guestReservations.expectedRows == 201
  and .hostReservations.expectedRows == 201
  and .wishlists.expectedRows == 201
  and .wishlists.primaryWishlistAccommodationRows == 201
  and .recentlyViewed.maxRows == 100
  and (.recentlyViewed.accommodationIds | length) == 100
' "$MANIFEST" >/dev/null || fail "benchmark manifest does not match the approved nplus1-v1 fixture"

docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1 || fail "retained benchmark MySQL container not found"
[[ "$(docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER")" == "true" ]] \
  || fail "retained benchmark MySQL container is not running"

MYSQL_ROOT_PASSWORD="$(
  docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$MYSQL_CONTAINER" \
    | sed -n 's/^MYSQL_ROOT_PASSWORD=//p'
)"
[[ -n "$MYSQL_ROOT_PASSWORD" ]] || fail "could not read retained MySQL root credential"

acquire_lock
if [[ -f "$RECOVERY_MARKER" ]]; then
  log "recovering resources from a previously interrupted benchmark"
  recover_marker || fail "previous benchmark resources could not be recovered"
fi
require_free_port "$APP_PORT"
require_free_port "$MANAGEMENT_PORT"
require_free_port "$REDIS_PORT"

INITIAL_CONTAINER_SET="$(docker ps -a --format '{{.ID}} {{.Names}}' | sort)"
INITIAL_DATA_HASH="$(data_hash)"
[[ -n "$INITIAL_DATA_HASH" ]] || fail "could not hash retained benchmark database"

account_email="$(jq -r '.account.email' "$MANIFEST")"
member_row="$(mysql_root airbobdb -e \
  "SELECT id, password FROM member WHERE email = '${account_email}' AND status = 'ACTIVE';")"
[[ "$(printf '%s\n' "$member_row" | sed '/^$/d' | wc -l | tr -d ' ')" == "1" ]] \
  || fail "benchmark account must resolve to exactly one ACTIVE member"
IFS=$'\t' read -r MEMBER_ID ORIGINAL_HASH <<<"$member_row"
[[ "$MEMBER_ID" =~ ^[0-9]+$ ]] || fail "benchmark member id is invalid"
is_bcrypt_hash "$ORIGINAL_HASH" || fail "benchmark member BCrypt hash is invalid"

TEMP_HASH="$(htpasswd -bnBC 10 '' "$TEST_PASSWORD" | tr -d ':\n')"
# Apache emits the modern $2y$ marker; the application's jBCrypt 0.4 accepts $2a$.
if [[ "$TEMP_HASH" == '$2y$'* ]]; then
  TEMP_HASH="\$2a\$${TEMP_HASH:4}"
fi
is_bcrypt_hash "$TEMP_HASH" || fail "temporary BCrypt hash generation failed"
printf '%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n' \
  "$MEMBER_ID" \
  "$ORIGINAL_HASH" \
  "$TEMP_HASH" \
  "$RO_USER" \
  "$REDIS_CONTAINER" \
  "$RUN_DIR" \
  "$JAR_PATH" \
  "$APP_PORT" >"$RECOVERY_MARKER"
chmod 600 "$RECOVERY_MARKER"

mysql_root airbobdb -e \
  "UPDATE member SET password = '${TEMP_HASH}' WHERE id = ${MEMBER_ID};" >/dev/null
[[ "$(mysql_root airbobdb -e "SELECT password FROM member WHERE id = ${MEMBER_ID};")" == "$TEMP_HASH" ]] \
  || fail "temporary benchmark hash replacement could not be verified"

mysql_root -e \
  "CREATE USER '${RO_USER}'@'%' IDENTIFIED BY '${RO_PASSWORD}'; GRANT SELECT, SHOW VIEW ON airbobdb.* TO '${RO_USER}'@'%';" >/dev/null

docker run --rm -d \
  --name "$REDIS_CONTAINER" \
  -p "127.0.0.1:${REDIS_PORT}:6379" \
  redis:7.2-alpine \
  redis-server --save '' --appendonly no >/dev/null

for _ in {1..60}; do
  if docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -q '^PONG$'; then
    break
  fi
  sleep 0.5
done
docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -q '^PONG$' \
  || fail "isolated Redis did not become ready"

log "validating fixture contracts before measurement"
start_app preflight
BASE_URL="http://127.0.0.1:${APP_PORT}" \
BENCHMARK_MANIFEST="$MANIFEST" \
TEST_PASSWORD="$TEST_PASSWORD" \
DATASET_SIZE=100 \
  k6 run --quiet "$ROOT_DIR/load-test/k6/nplus1-fixture-smoke.js" >&2
stop_app

log "starting clean measurement JVM"
start_app measure
start_rss_sampler
MEASUREMENT_STARTED_AT="$(date +%s)"

log "warming JIT and caches for $WARMUP_DURATION"
run_k6 20 all "$WARMUP_DURATION" "$RUN_DIR/warmup.k6.json"

case "$(( (REPEAT_INDEX - 1) % 3 ))" in
  0) SIZE_ORDER=(1 20 50) ;;
  1) SIZE_ORDER=(20 50 1) ;;
  2) SIZE_ORDER=(50 1 20) ;;
esac

ROUND_ARGS=()
for size in "${SIZE_ORDER[@]}"; do
  log "measuring seven core GET APIs at N=$size"
  run_round "$size" all
done
log "measuring recently viewed at N=100"
run_round 100 recent_only
[[ "$(( $(date +%s) - MEASUREMENT_STARTED_AT ))" -lt "$MEASUREMENT_DEADLINE_SECONDS" ]] \
  || fail "measurement exceeded the scheduler-isolation deadline"

MAX_RSS_MB="$(awk 'BEGIN { max=0 } { if ($1 > max) max=$1 } END { printf "%.3f", max / 1024 }' "$RUN_DIR/rss.samples")"
[[ -n "$MAX_RSS_MB" ]] || MAX_RSS_MB=0

ANALYZE_ARGS=(
  --candidate "$BATCH_SIZE"
  --max-rss-mb "$MAX_RSS_MB"
  "${ROUND_ARGS[@]}"
)
if [[ -n "$BASELINE_JSON" ]]; then
  [[ -f "$BASELINE_JSON" ]] || fail "baseline JSON not found: $BASELINE_JSON"
  ANALYZE_ARGS+=(--baseline "$BASELINE_JSON")
fi

python3 "$ROOT_DIR/load-test/k6/batch-fetch-size/analyze.py" \
  "${ANALYZE_ARGS[@]}" >"$RUN_DIR/result.json"
jq -e . "$RUN_DIR/result.json" >/dev/null || fail "analyzer did not emit valid JSON"

if ! cleanup; then
  fail "isolated resource cleanup failed"
fi
trap - EXIT INT TERM HUP

FINAL_DATA_HASH="$(data_hash)"
[[ "$FINAL_DATA_HASH" == "$INITIAL_DATA_HASH" ]] \
  || fail "retained benchmark database changed during measurement"
FINAL_CONTAINER_SET="$(docker ps -a --format '{{.ID}} {{.Names}}' | sort)"
[[ "$FINAL_CONTAINER_SET" == "$INITIAL_CONTAINER_SET" ]] \
  || fail "Docker container set changed during measurement"

cat "$RUN_DIR/result.json"
