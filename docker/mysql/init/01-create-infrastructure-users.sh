#!/usr/bin/env bash
set -euo pipefail
umask 077

# Docker Desktop bind mounts can report a mode-0644 script as executable to
# the MySQL entrypoint and then reject execve. Keep this script independently
# executable and pass the root password to mysql through the process environment,
# never argv or output.

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
require_password MYSQL_ROOT_PASSWORD
require_identifier DEBEZIUM_DATABASE_USER
require_identifier LOGSTASH_JDBC_USER
require_identifier MYSQL_DATABASE

debezium_password="$(sql_literal "$DEBEZIUM_DATABASE_PASSWORD")"
logstash_password="$(sql_literal "$LOGSTASH_JDBC_PASSWORD")"

MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
	--protocol=socket \
	--user=root \
	--database=mysql <<-EOSQL
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
