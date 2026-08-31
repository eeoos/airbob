#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

CURL_BIN="${CURL_BIN:-curl}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
JQ_BIN="${JQ_BIN:-jq}"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_DIR/docker-compose.yml}"
INDEX_DEFINITION="${INDEX_DEFINITION:-$PROJECT_DIR/src/main/resources/elasticsearch/accommodations-index.json}"
ELASTICSEARCH_URL="${ELASTICSEARCH_URL:-http://localhost:9200}"
ELASTICSEARCH_URL="${ELASTICSEARCH_URL%/}"
ES_ALIAS="${ES_ALIAS:-accommodations}"
ES_TARGET_INDEX="${ES_TARGET_INDEX:-${ES_ALIAS}-v$(date -u +%Y%m%d%H%M%S)}"
MYSQL_SERVICE="${MYSQL_SERVICE:-mysql}"
MYSQL_DATABASE="${MYSQL_DATABASE:-airbobdb}"
MYSQL_SOURCE_MODE="${MYSQL_SOURCE_MODE:-compose}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"
GIT_BIN="${GIT_BIN:-git}"
MYSQL_HOST="${MYSQL_HOST:-}"
MYSQL_PORT="${MYSQL_PORT:-}"
REINDEX_SOURCE_COMMIT="${REINDEX_SOURCE_COMMIT:-}"
LOGSTASH_JDBC_URL="${LOGSTASH_JDBC_URL:-}"
LOGSTASH_JDBC_USER="${LOGSTASH_JDBC_USER:-logstash}"
LOGSTASH_JDBC_PASSWORD="${LOGSTASH_JDBC_PASSWORD:-logstash}"
ELASTICSEARCH_CONNECT_TIMEOUT_SECONDS="${ELASTICSEARCH_CONNECT_TIMEOUT_SECONDS:-5}"
ELASTICSEARCH_MAX_TIME_SECONDS="${ELASTICSEARCH_MAX_TIME_SECONDS:-30}"
LOGSTASH_MAX_RUNTIME_SECONDS="${LOGSTASH_MAX_RUNTIME_SECONDS:-3600}"
LOGSTASH_POLL_INTERVAL_SECONDS="${LOGSTASH_POLL_INTERVAL_SECONDS:-2}"

fail() {
	printf 'ERROR: %s\n' "$*" >&2
	exit 1
}

info() {
	printf '%s\n' "$*"
}

command -v "$CURL_BIN" >/dev/null 2>&1 || fail "curl executable not found: $CURL_BIN"
command -v "$DOCKER_BIN" >/dev/null 2>&1 || fail "docker executable not found: $DOCKER_BIN"
command -v "$JQ_BIN" >/dev/null 2>&1 || fail "jq executable not found: $JQ_BIN"

[[ "${CONFIRM_INDEXING_CONSUMER_PAUSED:-false}" == "true" ]] ||
	fail "set CONFIRM_INDEXING_CONSUMER_PAUSED=true after every accommodation indexing consumer is stopped"
[[ -f "$COMPOSE_FILE" ]] || fail "compose file not found: $COMPOSE_FILE"
[[ -f "$INDEX_DEFINITION" ]] || fail "index definition not found: $INDEX_DEFINITION"
[[ "$ES_ALIAS" =~ ^[a-z0-9][a-z0-9._-]*$ ]] || fail "invalid Elasticsearch alias: $ES_ALIAS"
[[ "$ELASTICSEARCH_CONNECT_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] ||
	fail "ELASTICSEARCH_CONNECT_TIMEOUT_SECONDS must be a positive integer"
[[ "$ELASTICSEARCH_MAX_TIME_SECONDS" =~ ^[1-9][0-9]*$ ]] ||
	fail "ELASTICSEARCH_MAX_TIME_SECONDS must be a positive integer"
[[ "$LOGSTASH_MAX_RUNTIME_SECONDS" =~ ^[1-9][0-9]*$ ]] ||
	fail "LOGSTASH_MAX_RUNTIME_SECONDS must be a positive integer"
[[ "$LOGSTASH_POLL_INTERVAL_SECONDS" =~ ^[0-9]+([.][0-9]+)?$ &&
	"$LOGSTASH_POLL_INTERVAL_SECONDS" =~ [1-9] ]] ||
	fail "LOGSTASH_POLL_INTERVAL_SECONDS must be greater than zero"
case "$MYSQL_SOURCE_MODE" in
	compose) ;;
	external)
		[[ "${CONFIRM_MYSQL_SOURCE_QUIESCED:-false}" == "true" ]] ||
			fail "set CONFIRM_MYSQL_SOURCE_QUIESCED=true for an attested external MySQL source"
		[[ "$MYSQL_DATABASE" == airbobdb ]] || fail "external dataset source must use airbobdb"
		[[ "$MYSQL_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}$ ]] ||
			fail "MYSQL_HOST is invalid for external source mode"
		[[ "$MYSQL_PORT" =~ ^[0-9]{1,5}$ ]] || fail "MYSQL_PORT is invalid for external source mode"
		((10#$MYSQL_PORT >= 1 && 10#$MYSQL_PORT <= 65535)) ||
			fail "MYSQL_PORT is invalid for external source mode"
		[[ "$LOGSTASH_JDBC_URL" =~ ^jdbc:mysql://[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}:[0-9]{1,5}/airbobdb\?serverTimezone=UTC\&useSSL=false\&allowPublicKeyRetrieval=true$ ]] ||
			fail "LOGSTASH_JDBC_URL must name the container-reachable external airbobdb source"
		command -v "$MYSQL_BIN" >/dev/null 2>&1 || fail "mysql executable not found: $MYSQL_BIN"
		command -v "$GIT_BIN" >/dev/null 2>&1 || fail "git executable not found: $GIT_BIN"
		[[ "$REINDEX_SOURCE_COMMIT" =~ ^[0-9a-f]{40}$ ]] ||
			fail "REINDEX_SOURCE_COMMIT must be one reviewed full Airbob commit"
		"$GIT_BIN" -C "$PROJECT_DIR" cat-file -e "$REINDEX_SOURCE_COMMIT^{commit}" 2>/dev/null ||
			fail "REINDEX_SOURCE_COMMIT is not available in this repository"
		[[ "$("$GIT_BIN" -C "$PROJECT_DIR" rev-parse HEAD)" == "$REINDEX_SOURCE_COMMIT" ]] ||
			fail "external dataset reindex must run from REINDEX_SOURCE_COMMIT"
		"$GIT_BIN" -C "$PROJECT_DIR" diff --quiet "$REINDEX_SOURCE_COMMIT" -- \
			scripts/reindex-accommodations.sh docker-compose.yml \
			logstash/pipeline/airbob.conf logstash/config/logstash.yml \
			logstash/config/jdbc/mysql-connector-j-8.0.33.jar \
			src/main/resources/elasticsearch/accommodations-index.json ||
			fail "external dataset reindex inputs differ from REINDEX_SOURCE_COMMIT"
		;;
	*) fail "MYSQL_SOURCE_MODE must be compose or external" ;;
esac
target_prefix="${ES_ALIAS}-v"
target_version="${ES_TARGET_INDEX#"$target_prefix"}"
[[ "$ES_TARGET_INDEX" == "$target_prefix"* && "$target_version" =~ ^[0-9]{14}$ ]] ||
	fail "target index must match ${ES_ALIAS}-vYYYYMMDDhhmmss"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/airbob-reindex.XXXXXX")"
LOGSTASH_CONTAINER_ID=

cleanup() {
	local exit_code=$?
	trap - EXIT
	if [[ -n "$LOGSTASH_CONTAINER_ID" ]]; then
		"$DOCKER_BIN" rm -f "$LOGSTASH_CONTAINER_ID" >/dev/null 2>&1 || true
	fi
	rm -rf "$WORK_DIR"
	exit "$exit_code"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
CURL_CONFIG_FILE=
MYSQL_CLIENT_CONFIG="$WORK_DIR/mysql-client.cnf"
REQUEST_NUMBER=0
HTTP_STATUS=
HTTP_BODY=

validate_config_value() {
	local value="$1"
	[[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || fail "credentials must not contain newlines"
}

escape_config_value() {
	local value="$1"
	value="${value//\\/\\\\}"
	value="${value//\"/\\\"}"
	printf '%s' "$value"
}

if [[ -n "${ELASTICSEARCH_USERNAME:-}" || -n "${ELASTICSEARCH_PASSWORD:-}" ]]; then
	[[ -n "${ELASTICSEARCH_USERNAME:-}" && -n "${ELASTICSEARCH_PASSWORD:-}" ]] ||
		fail "both ELASTICSEARCH_USERNAME and ELASTICSEARCH_PASSWORD are required"
	[[ "$ELASTICSEARCH_USERNAME" != *:* ]] || fail "ELASTICSEARCH_USERNAME must not contain a colon"
	validate_config_value "$ELASTICSEARCH_USERNAME"
	validate_config_value "$ELASTICSEARCH_PASSWORD"
	CURL_CONFIG_FILE="$WORK_DIR/curl-auth.conf"
	printf 'user = "%s:%s"\n' \
		"$(escape_config_value "$ELASTICSEARCH_USERNAME")" \
		"$(escape_config_value "$ELASTICSEARCH_PASSWORD")" > "$CURL_CONFIG_FILE"
	chmod 600 "$CURL_CONFIG_FILE"
fi

validate_config_value "$LOGSTASH_JDBC_USER"
validate_config_value "$LOGSTASH_JDBC_PASSWORD"
validate_config_value "$LOGSTASH_JDBC_URL"
printf '[client]\nuser="%s"\npassword="%s"\n' \
	"$(escape_config_value "$LOGSTASH_JDBC_USER")" \
	"$(escape_config_value "$LOGSTASH_JDBC_PASSWORD")" > "$MYSQL_CLIENT_CONFIG"
chmod 600 "$MYSQL_CLIENT_CONFIG"
if [[ "$MYSQL_SOURCE_MODE" == external ]]; then
	export LOGSTASH_JDBC_URL LOGSTASH_JDBC_USER LOGSTASH_JDBC_PASSWORD
fi

api_request() {
	local method="$1"
	local path="$2"
	local data="${3:-}"
	local response_file
	local -a arguments

	REQUEST_NUMBER=$((REQUEST_NUMBER + 1))
	response_file="$WORK_DIR/response-$REQUEST_NUMBER.json"
	arguments=(
		-sS
		--connect-timeout "$ELASTICSEARCH_CONNECT_TIMEOUT_SECONDS"
		--max-time "$ELASTICSEARCH_MAX_TIME_SECONDS"
		-X "$method"
		-o "$response_file"
		-w "%{http_code}"
	)
	if [[ -n "$CURL_CONFIG_FILE" ]]; then
		arguments+=(--config "$CURL_CONFIG_FILE")
	fi
	arguments+=(-H "Content-Type: application/json")
	if [[ -n "$data" ]]; then
		arguments+=(--data-binary "$data")
	fi
	arguments+=("$ELASTICSEARCH_URL$path")

	HTTP_STATUS="$("$CURL_BIN" "${arguments[@]}")" || fail "Elasticsearch request failed: $method $path"
	HTTP_BODY="$(<"$response_file")"
}

expect_success() {
	local operation="$1"
	[[ "$HTTP_STATUS" =~ ^2[0-9][0-9]$ ]] ||
		fail "$operation failed with Elasticsearch HTTP $HTTP_STATUS"
}

expect_acknowledged() {
	local operation="$1"
	printf '%s' "$HTTP_BODY" | "$JQ_BIN" -e '.acknowledged == true and (.errors // false) == false' >/dev/null ||
		fail "$operation was not acknowledged"
}

alias_indices() {
	printf '%s' "$HTTP_BODY" | "$JQ_BIN" -r 'keys[]'
}

read_current_index() {
	local indices
	api_request GET "/_alias/$ES_ALIAS"
	if [[ "$HTTP_STATUS" == "404" ]]; then
		api_request HEAD "/$ES_ALIAS"
		if [[ "$HTTP_STATUS" =~ ^2[0-9][0-9]$ ]]; then
			fail "found a concrete index named $ES_ALIAS; remove or migrate it explicitly before alias bootstrap"
		fi
		[[ "$HTTP_STATUS" == "404" ]] || fail "could not inspect legacy index $ES_ALIAS (HTTP $HTTP_STATUS)"
		printf ''
		return
	fi
	expect_success "read alias $ES_ALIAS"
	indices="$(alias_indices)"
	[[ "$(printf '%s\n' "$indices" | sed '/^$/d' | wc -l | tr -d ' ')" == "1" ]] ||
		fail "alias $ES_ALIAS must point to exactly one index"
	printf '%s' "$indices"
}

mysql_published_count() {
	local count
	if [[ "$MYSQL_SOURCE_MODE" == external ]]; then
		count="$(
			"$MYSQL_BIN" --defaults-extra-file="$MYSQL_CLIENT_CONFIG" \
				--protocol=TCP --host="$MYSQL_HOST" --port="$MYSQL_PORT" \
				--batch --skip-column-names "$MYSQL_DATABASE" \
				--execute="SELECT COUNT(*) FROM accommodation WHERE status = 'PUBLISHED' AND accommodation_uid IS NOT NULL;"
		)"
	else
		count="$(
			"$DOCKER_BIN" compose -f "$COMPOSE_FILE" exec -T "$MYSQL_SERVICE" \
				mysql --defaults-extra-file=/dev/stdin --batch --skip-column-names "$MYSQL_DATABASE" \
				-e "SELECT COUNT(*) FROM accommodation WHERE status = 'PUBLISHED' AND accommodation_uid IS NOT NULL;" \
				< "$MYSQL_CLIENT_CONFIG"
		)"
	fi
	count="$(printf '%s' "$count" | tr -d '[:space:]')"
	[[ "$count" =~ ^[0-9]+$ ]] || fail "could not read published accommodation count from MySQL"
	printf '%s' "$count"
}

remove_logstash_container() {
	local container_id="$LOGSTASH_CONTAINER_ID"
	[[ -n "$container_id" ]] || return 0
	"$DOCKER_BIN" rm -f "$container_id" >/dev/null || return 1
	LOGSTASH_CONTAINER_ID=
}

print_logstash_logs() {
	local container_id="$1"
	"$DOCKER_BIN" logs "$container_id" ||
		printf 'WARNING: could not collect Logstash logs for %s\n' "$container_id" >&2
}

run_logstash_with_watchdog() {
	local container_id
	local started_at
	local now
	local running
	local exit_code

	container_id="$(
		"$DOCKER_BIN" compose -f "$COMPOSE_FILE" --profile reindex run --no-deps -d \
			-e "LOGSTASH_TARGET_INDEX=$ES_TARGET_INDEX" logstash
	)" || fail "could not start Logstash reindex container"
	container_id="$(printf '%s' "$container_id" | tr -d '[:space:]')"
	[[ "$container_id" =~ ^[a-f0-9]{12,64}$ ]] ||
		fail "Docker returned an invalid Logstash container ID"
	LOGSTASH_CONTAINER_ID="$container_id"

	started_at="$(date +%s)"
	while true; do
		running="$(
			"$DOCKER_BIN" inspect --format '{{.State.Running}}' "$LOGSTASH_CONTAINER_ID"
		)" || fail "could not inspect Logstash container $LOGSTASH_CONTAINER_ID"
		case "$running" in
			false) break ;;
			true) ;;
			*) fail "Docker returned an invalid running state for Logstash container $LOGSTASH_CONTAINER_ID" ;;
		esac

		now="$(date +%s)"
		if ((now - started_at >= LOGSTASH_MAX_RUNTIME_SECONDS)); then
			print_logstash_logs "$LOGSTASH_CONTAINER_ID"
			"$DOCKER_BIN" stop -t 10 "$LOGSTASH_CONTAINER_ID" >/dev/null 2>&1 || true
			remove_logstash_container ||
				fail "could not remove timed-out Logstash container $LOGSTASH_CONTAINER_ID"
			fail "Logstash exceeded maximum runtime of $LOGSTASH_MAX_RUNTIME_SECONDS seconds; target index $ES_TARGET_INDEX was retained"
		fi

		sleep "$LOGSTASH_POLL_INTERVAL_SECONDS"
	done

	exit_code="$(
		"$DOCKER_BIN" inspect --format '{{.State.ExitCode}}' "$LOGSTASH_CONTAINER_ID"
	)" || fail "could not read Logstash exit code from container $LOGSTASH_CONTAINER_ID"
	[[ "$exit_code" =~ ^[0-9]+$ ]] ||
		fail "Docker returned an invalid Logstash exit code for container $LOGSTASH_CONTAINER_ID"
	print_logstash_logs "$LOGSTASH_CONTAINER_ID"
	remove_logstash_container || fail "could not remove Logstash container $LOGSTASH_CONTAINER_ID"
	[[ "$exit_code" == "0" ]] ||
		fail "Logstash exited with code $exit_code; target index $ES_TARGET_INDEX was retained"
}

current_index="$(read_current_index)"
api_request HEAD "/$ES_TARGET_INDEX"
if [[ "$HTTP_STATUS" =~ ^2[0-9][0-9]$ ]]; then
	fail "target index already exists: $ES_TARGET_INDEX"
fi
[[ "$HTTP_STATUS" == "404" ]] ||
	fail "could not inspect target index $ES_TARGET_INDEX (HTTP $HTTP_STATUS)"

published_before="$(mysql_published_count)"
info "Creating version index $ES_TARGET_INDEX"
api_request PUT "/$ES_TARGET_INDEX" "@$INDEX_DEFINITION"
expect_success "create target index"
expect_acknowledged "create target index"

info "Loading MySQL projection into $ES_TARGET_INDEX"
run_logstash_with_watchdog

api_request POST "/$ES_TARGET_INDEX/_refresh"
expect_success "refresh target index"
published_after="$(mysql_published_count)"
[[ "$published_before" == "$published_after" ]] ||
	fail "published accommodation count changed while reindexing ($published_before -> $published_after); inspect and rerun"

api_request GET "/$ES_TARGET_INDEX/_count"
expect_success "count target index"
target_count="$(printf '%s' "$HTTP_BODY" | "$JQ_BIN" -r '.count')"
[[ "$target_count" =~ ^[0-9]+$ ]] || fail "target index returned an invalid document count"
[[ "$target_count" == "$published_after" ]] ||
	fail "document count mismatch: MySQL=$published_after Elasticsearch=$target_count"

observed_index="$(read_current_index)"
[[ "$observed_index" == "$current_index" ]] ||
	fail "alias changed while reindexing: expected=${current_index:-<none>} actual=${observed_index:-<none>}"

if [[ -n "$current_index" ]]; then
	alias_actions="$("$JQ_BIN" -cn \
		--arg old "$current_index" \
		--arg target "$ES_TARGET_INDEX" \
		--arg alias "$ES_ALIAS" \
		'{actions:[{remove:{index:$old,alias:$alias,must_exist:true}},{add:{index:$target,alias:$alias,is_write_index:true}}]}')"
else
	alias_actions="$("$JQ_BIN" -cn \
		--arg target "$ES_TARGET_INDEX" \
		--arg alias "$ES_ALIAS" \
		'{actions:[{add:{index:$target,alias:$alias,is_write_index:true}}]}')"
fi

info "Switching alias $ES_ALIAS to $ES_TARGET_INDEX"
api_request POST "/_aliases" "$alias_actions"
expect_success "switch alias"
expect_acknowledged "switch alias"

api_request GET "/_alias/$ES_ALIAS"
expect_success "verify alias"
verified_index="$(alias_indices)"
[[ "$verified_index" == "$ES_TARGET_INDEX" ]] ||
	fail "alias verification failed: expected=$ES_TARGET_INDEX actual=${verified_index:-<none>}"
printf '%s' "$HTTP_BODY" | "$JQ_BIN" -e \
	--arg target "$ES_TARGET_INDEX" --arg alias "$ES_ALIAS" \
	'.[$target].aliases[$alias].is_write_index == true' >/dev/null ||
	fail "alias write-index verification failed"

api_request GET "/$ES_ALIAS/_count"
expect_success "count alias"
alias_count="$(printf '%s' "$HTTP_BODY" | "$JQ_BIN" -r '.count')"
[[ "$alias_count" == "$target_count" ]] || fail "alias document count does not match target index"

info "Reindex complete: $ES_ALIAS -> $ES_TARGET_INDEX ($target_count documents)"
if [[ -n "$current_index" ]]; then
	info "Rollback index retained: $current_index"
fi
info "Resume the accommodation indexing consumer and verify its Kafka lag returns to zero."
