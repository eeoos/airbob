#!/bin/sh

set -eu

CONNECT_URL="${CONNECT_URL:-http://debezium:8083}"
CONNECTOR_NAME="${CONNECTOR_NAME:-airbob-outbox-connector}"
CONNECTOR_CONFIG_FILE="${CONNECTOR_CONFIG_FILE:-/config/outbox-connector.json}"
CONNECTOR_REGISTRATION_ATTEMPTS="${CONNECTOR_REGISTRATION_ATTEMPTS:-10}"
CONNECTOR_STATUS_ATTEMPTS="${CONNECTOR_STATUS_ATTEMPTS:-60}"
CONNECTOR_STATUS_DELAY_SECONDS="${CONNECTOR_STATUS_DELAY_SECONDS:-2}"

if [ ! -r "$CONNECTOR_CONFIG_FILE" ]; then
  echo "Connector config is not readable: $CONNECTOR_CONFIG_FILE" >&2
  exit 1
fi

registration_attempt=1
while [ "$registration_attempt" -le "$CONNECTOR_REGISTRATION_ATTEMPTS" ]; do
  echo "Registering Kafka Connect connector with an idempotent PUT: $CONNECTOR_NAME"
  if curl --fail --silent --show-error \
    --request PUT \
    --header "Content-Type: application/json" \
    --data-binary "@$CONNECTOR_CONFIG_FILE" \
    "$CONNECT_URL/connectors/$CONNECTOR_NAME/config" >/dev/null; then
    break
  fi

  if [ "$registration_attempt" -eq "$CONNECTOR_REGISTRATION_ATTEMPTS" ]; then
    echo "Connector registration did not succeed: $CONNECTOR_NAME" >&2
    exit 1
  fi

  sleep "$CONNECTOR_STATUS_DELAY_SECONDS"
  registration_attempt=$((registration_attempt + 1))
done

attempt=1
while [ "$attempt" -le "$CONNECTOR_STATUS_ATTEMPTS" ]; do
  connector_status_json="$(curl --fail --silent --show-error \
    "$CONNECT_URL/connectors/$CONNECTOR_NAME/status" 2>/dev/null || true)"
  compact_status="$(printf '%s' "$connector_status_json" | tr -d '[:space:]')"

  case "$compact_status" in
    *'"connector":{'*'"state":"RUNNING"'*'"tasks":['*)
      task_section="${compact_status#*\"tasks\":\[}"
      task_section="${task_section%%],\"type\"*}"
      task_states="$(printf '%s' "$task_section" | grep -o '"state":"[^"]*"' || true)"
      non_running_tasks="$(printf '%s\n' "$task_states" \
        | grep -v '^"state":"RUNNING"$' || true)"

      if [ -n "$task_states" ] && [ -z "$non_running_tasks" ]; then
        echo "Connector and all connector tasks are RUNNING: $CONNECTOR_NAME"
        exit 0
      fi
      ;;
  esac

  if printf '%s' "$compact_status" | grep -Eq '"state":"(FAILED|PAUSED)"'; then
    echo "Connector entered FAILED or PAUSED state: $CONNECTOR_NAME" >&2
    exit 1
  fi

  sleep "$CONNECTOR_STATUS_DELAY_SECONDS"
  attempt=$((attempt + 1))
done

echo "Connector did not reach RUNNING with at least one RUNNING task: $CONNECTOR_NAME" >&2
exit 1
