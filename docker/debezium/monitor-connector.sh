#!/bin/sh

set -eu

CONNECT_URL="${CONNECT_URL:-http://debezium:8083}"
CONNECTOR_NAME="${CONNECTOR_NAME:-airbob-outbox-connector}"
CONNECTOR_MONITOR_STATE_FILE="${CONNECTOR_MONITOR_STATE_FILE:-/tmp/airbob-connector-monitor/state}"
CONNECTOR_MONITOR_INTERVAL_SECONDS="${CONNECTOR_MONITOR_INTERVAL_SECONDS:-30}"
CONNECTOR_MONITOR_HTTP_CONNECT_TIMEOUT_SECONDS="${CONNECTOR_MONITOR_HTTP_CONNECT_TIMEOUT_SECONDS:-5}"
CONNECTOR_MONITOR_HTTP_MAX_TIME_SECONDS="${CONNECTOR_MONITOR_HTTP_MAX_TIME_SECONDS:-10}"
CONNECTOR_MONITOR_MAX_CHECKS="${CONNECTOR_MONITOR_MAX_CHECKS:-0}"
CURL_BIN="${CURL_BIN:-curl}"

require_positive_integer() {
  case "$2" in
    ''|*[!0-9]*)
      echo "$1 must be a positive integer" >&2
      exit 1
      ;;
    *[1-9]*) ;;
    *)
      echo "$1 must be a positive integer" >&2
      exit 1
      ;;
  esac
}

require_non_negative_integer() {
  case "$2" in
    ''|*[!0-9]*)
      echo "$1 must be a non-negative integer" >&2
      exit 1
      ;;
  esac
}

require_non_negative_integer \
  "CONNECTOR_MONITOR_INTERVAL_SECONDS" \
  "$CONNECTOR_MONITOR_INTERVAL_SECONDS"
require_positive_integer \
  "CONNECTOR_MONITOR_HTTP_CONNECT_TIMEOUT_SECONDS" \
  "$CONNECTOR_MONITOR_HTTP_CONNECT_TIMEOUT_SECONDS"
require_positive_integer \
  "CONNECTOR_MONITOR_HTTP_MAX_TIME_SECONDS" \
  "$CONNECTOR_MONITOR_HTTP_MAX_TIME_SECONDS"
require_non_negative_integer \
  "CONNECTOR_MONITOR_MAX_CHECKS" \
  "$CONNECTOR_MONITOR_MAX_CHECKS"

if [ "$CONNECTOR_MONITOR_INTERVAL_SECONDS" -eq 0 ] \
  && [ "$CONNECTOR_MONITOR_MAX_CHECKS" -eq 0 ]; then
  echo "CONNECTOR_MONITOR_INTERVAL_SECONDS must be positive for continuous monitoring" >&2
  exit 1
fi

if ! command -v "$CURL_BIN" >/dev/null 2>&1; then
  echo "curl executable not found" >&2
  exit 1
fi

state_directory="$(dirname "$CONNECTOR_MONITOR_STATE_FILE")"
mkdir -p "$state_directory"
last_state=""
check_count=0

write_state() {
  next_state="$1"
  temporary_state_file="${CONNECTOR_MONITOR_STATE_FILE}.tmp.$$"
  printf '%s\n' "$next_state" > "$temporary_state_file"
  mv "$temporary_state_file" "$CONNECTOR_MONITOR_STATE_FILE"

  if [ "$last_state" != "$next_state" ]; then
    echo "connector monitor state changed: $next_state"
    last_state="$next_state"
  fi
}

connector_and_tasks_are_running() {
  compact_status="$(printf '%s' "$1" | tr -d '[:space:]')"

  case "$compact_status" in
    *'"connector":{'*'"state":"RUNNING"'*'"tasks":['*) ;;
    *) return 1 ;;
  esac

  task_section="${compact_status#*\"tasks\":\[}"
  task_section="${task_section%%],\"type\"*}"
  case "$task_section" in
    *'"state":"RUNNING"'*) ;;
    *) return 1 ;;
  esac

  task_section_without_running_states="$(printf '%s' "$task_section" \
    | sed 's/"state":"RUNNING"//g')"
  case "$task_section_without_running_states" in
    *'"state":"'*) return 1 ;;
    *) return 0 ;;
  esac
}

while :; do
  connector_status_json="$("$CURL_BIN" --fail --silent --show-error \
    --connect-timeout "$CONNECTOR_MONITOR_HTTP_CONNECT_TIMEOUT_SECONDS" \
    --max-time "$CONNECTOR_MONITOR_HTTP_MAX_TIME_SECONDS" \
    "$CONNECT_URL/connectors/$CONNECTOR_NAME/status" 2>/dev/null || true)"

  if connector_and_tasks_are_running "$connector_status_json"; then
    write_state "RUNNING"
  else
    write_state "NOT_RUNNING"
  fi

  check_count=$((check_count + 1))
  if [ "$CONNECTOR_MONITOR_MAX_CHECKS" -gt 0 ] \
    && [ "$check_count" -ge "$CONNECTOR_MONITOR_MAX_CHECKS" ]; then
    if [ "$last_state" = "RUNNING" ]; then
      exit 0
    fi
    exit 1
  fi

  sleep "$CONNECTOR_MONITOR_INTERVAL_SECONDS"
done
