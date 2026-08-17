#!/usr/bin/env bash

# The official MySQL entrypoint sources non-executable *.sh files, so
# docker_process_sql is available without exposing the root password.

require_value() {
  local name="$1"

  if [ -z "${!name:-}" ]; then
    echo "$name must be set before initializing MySQL" >&2
    exit 1
  fi
}

require_identifier() {
  local name="$1"
  local value="${!name}"

  if [[ ! "$value" =~ ^[A-Za-z0-9_]+$ ]]; then
    echo "$name must contain only letters, numbers, and underscores" >&2
    exit 1
  fi
}

require_password() {
  local name="$1"
  local value="${!name:-}"

  require_value "$name"
  if [[ "$value" == *$'\n'* || "$value" == *$'\r'* ]]; then
    echo "$name cannot contain newlines" >&2
    exit 1
  fi
}

sql_literal() {
  local value="$1"

  value="${value//\\/\\\\}"
  value="${value//\'/\'\'}"
  printf '%s' "$value"
}

DEBEZIUM_DATABASE_USER="${DEBEZIUM_DATABASE_USER:-debezium}"
LOGSTASH_JDBC_USER="${LOGSTASH_JDBC_USER:-logstash}"
LOGSTASH_JDBC_PASSWORD="${LOGSTASH_JDBC_PASSWORD:-logstash}"
MYSQL_DATABASE="${MYSQL_DATABASE:-airbobdb}"

require_password DEBEZIUM_DATABASE_PASSWORD
require_password LOGSTASH_JDBC_PASSWORD
require_identifier DEBEZIUM_DATABASE_USER
require_identifier LOGSTASH_JDBC_USER
require_identifier MYSQL_DATABASE

debezium_password="$(sql_literal "$DEBEZIUM_DATABASE_PASSWORD")"
logstash_password="$(sql_literal "$LOGSTASH_JDBC_PASSWORD")"

docker_process_sql --database=mysql <<-EOSQL
	CREATE USER IF NOT EXISTS '${DEBEZIUM_DATABASE_USER}'@'%' IDENTIFIED BY '${debezium_password}';
	ALTER USER '${DEBEZIUM_DATABASE_USER}'@'%' IDENTIFIED BY '${debezium_password}';
	GRANT SELECT ON \`${MYSQL_DATABASE}\`.* TO '${DEBEZIUM_DATABASE_USER}'@'%';
	GRANT RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '${DEBEZIUM_DATABASE_USER}'@'%';

	CREATE USER IF NOT EXISTS '${LOGSTASH_JDBC_USER}'@'%' IDENTIFIED BY '${logstash_password}';
	ALTER USER '${LOGSTASH_JDBC_USER}'@'%' IDENTIFIED BY '${logstash_password}';
	GRANT SELECT ON \`${MYSQL_DATABASE}\`.* TO '${LOGSTASH_JDBC_USER}'@'%';
EOSQL

unset debezium_password logstash_password
unset -f require_value require_identifier require_password sql_literal
