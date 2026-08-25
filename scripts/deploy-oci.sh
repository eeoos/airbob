#!/bin/sh

set -eu

DEPLOY_DIR="${DEPLOY_DIR:-$HOME/airbob}"
ENV_FILE="${ENV_FILE:-$DEPLOY_DIR/.env.oci}"
COMPOSE_FILE="${COMPOSE_FILE:-$DEPLOY_DIR/docker-compose.oci.yml}"
IMAGE_REPO="${IMAGE_REPO:-ghcr.io/eeoos/airbob}"
IMAGE_TAG="${IMAGE_TAG:-${IMAGE_SHA7:-}}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
HEALTH_ATTEMPTS="${HEALTH_ATTEMPTS:-42}"
HEALTH_DELAY_SECONDS="${HEALTH_DELAY_SECONDS:-10}"

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

require_positive_integer "HEALTH_ATTEMPTS" "$HEALTH_ATTEMPTS"
require_positive_integer "HEALTH_DELAY_SECONDS" "$HEALTH_DELAY_SECONDS"

if [ ! -d "$DEPLOY_DIR" ]; then
  echo "deployment directory does not exist" >&2
  exit 1
fi
if [ ! -r "$ENV_FILE" ]; then
  echo "deployment environment file is not readable" >&2
  exit 1
fi
if [ ! -r "$COMPOSE_FILE" ]; then
  echo "OCI compose file is not readable" >&2
  exit 1
fi
if [ -z "$IMAGE_TAG" ]; then
  echo "IMAGE_TAG or IMAGE_SHA7 is required" >&2
  exit 1
fi
case "$IMAGE_TAG" in
  *[!a-zA-Z0-9_.-]*)
    echo "image tag contains unsupported characters" >&2
    exit 1
    ;;
esac
if ! command -v "$DOCKER_BIN" >/dev/null 2>&1; then
  echo "docker executable not found" >&2
  exit 1
fi

compose() {
  "$DOCKER_BIN" compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

wait_healthy() {
  container_name="$1"
  phase="$2"
  attempt=1

  while [ "$attempt" -le "$HEALTH_ATTEMPTS" ]; do
    container_snapshot="$("$DOCKER_BIN" inspect \
      --format='{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
      "$container_name" 2>/dev/null || true)"
    container_state=${container_snapshot%%|*}
    container_health=${container_snapshot#*|}
    if [ "$container_snapshot" = "$container_health" ]; then
      container_state=''
      container_health=''
    fi
    echo "$phase health: state=${container_state:-missing}, probe=${container_health:-missing} ($attempt/$HEALTH_ATTEMPTS)"

    case "$container_state" in
      exited|dead) return 1 ;;
    esac
    case "$container_health" in
      healthy) return 0 ;;
    esac

    sleep "$HEALTH_DELAY_SECONDS"
    attempt=$((attempt + 1))
  done

  return 1
}

wait_running() {
  container_name="$1"
  phase="$2"
  attempt=1

  while [ "$attempt" -le "$HEALTH_ATTEMPTS" ]; do
    container_status="$("$DOCKER_BIN" inspect \
      --format='{{.State.Status}}' \
      "$container_name" 2>/dev/null || true)"
    echo "$phase status: ${container_status:-missing} ($attempt/$HEALTH_ATTEMPTS)"

    case "$container_status" in
      running) return 0 ;;
      exited|dead) return 1 ;;
    esac

    sleep "$HEALTH_DELAY_SECONDS"
    attempt=$((attempt + 1))
  done

  return 1
}

stop_admission_and_require_roll_forward() {
  compose stop nginx app >/dev/null 2>&1 || true
  echo "Deployment crossed the Flyway/app-start boundary and did not become healthy." >&2
  echo "No pre-V25 binary rollback was attempted; public admission is stopped." >&2
  echo "Roll forward with the current binary that understands the V25 cutover, V26 inventory, and V27 index." >&2
  exit 1
}

stop_admission_before_inventory_cutover() {
  compose stop nginx app >/dev/null 2>&1 || true
  echo "Reservation inventory cutover preflight failed before Flyway; no migration was attempted." >&2
  echo "No automatic V24 restart was attempted; public admission is stopped for operator review." >&2
  exit 1
}

cd "$DEPLOY_DIR"

# These checks do not mutate live services. A failure leaves the admitted application untouched.
compose config --quiet
"$DOCKER_BIN" pull "$IMAGE_REPO:$IMAGE_TAG"
"$DOCKER_BIN" image tag "$IMAGE_REPO:$IMAGE_TAG" "$IMAGE_REPO:latest"

# Close public admission and the old event producer before applying the schema and
# connector contract. From here every failure requires a compatible roll-forward.
if ! compose stop nginx app; then
  echo "Unable to close public admission before migration; deployment was not started." >&2
  exit 1
fi

if ! compose up -d --wait --wait-timeout 240 mysql redis redis-cache elasticsearch kafka; then
  stop_admission_and_require_roll_forward
fi
if ! compose run --rm --no-deps reservation-inventory-cutover-preflight; then
  stop_admission_before_inventory_cutover
fi
if ! compose run --rm --no-deps kafka-topic-init; then
  stop_admission_and_require_roll_forward
fi
if ! compose stop debezium-connector-monitor debezium; then
  stop_admission_and_require_roll_forward
fi
if ! compose run --rm --no-deps flyway-migrate; then
  stop_admission_and_require_roll_forward
fi
if ! compose up -d --no-deps --force-recreate debezium; then
  stop_admission_and_require_roll_forward
fi
if ! wait_healthy debezium "debezium bootstrap"; then
  stop_admission_and_require_roll_forward
fi
if ! compose run --rm --no-deps debezium-connector-init; then
  stop_admission_and_require_roll_forward
fi
if ! compose up -d --no-deps --force-recreate debezium-connector-monitor; then
  stop_admission_and_require_roll_forward
fi
if ! wait_healthy airbob-debezium-connector-monitor "connector monitor bootstrap"; then
  stop_admission_and_require_roll_forward
fi

if ! compose up -d --no-deps --force-recreate --pull never app; then
  stop_admission_and_require_roll_forward
fi
if ! wait_healthy airbob-app "application"; then
  stop_admission_and_require_roll_forward
fi
if ! compose up -d --no-deps --force-recreate nginx; then
  stop_admission_and_require_roll_forward
fi
if ! wait_running nginx "nginx"; then
  stop_admission_and_require_roll_forward
fi
if ! "$DOCKER_BIN" exec nginx nginx -t >/dev/null 2>&1; then
  stop_admission_and_require_roll_forward
fi
if ! "$DOCKER_BIN" exec nginx \
  wget -q --spider http://app:8080/actuator/health >/dev/null 2>&1; then
  stop_admission_and_require_roll_forward
fi

echo "OCI deployment is healthy: $IMAGE_REPO:$IMAGE_TAG"
