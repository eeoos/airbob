#!/bin/sh

set -eu

MYSQL_HOST="${MYSQL_HOST:-mysql}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-airbobdb}"

case "$MYSQL_HOST" in
  ''|*[!a-zA-Z0-9.-]*)
    echo "reservation inventory cutover preflight: invalid MySQL host" >&2
    exit 1
    ;;
esac
case "$MYSQL_PORT" in
  ''|*[!0-9]*)
    echo "reservation inventory cutover preflight: invalid MySQL port" >&2
    exit 1
    ;;
esac
case "$MYSQL_DATABASE" in
  ''|*[!a-zA-Z0-9_]*)
    echo "reservation inventory cutover preflight: invalid MySQL database" >&2
    exit 1
    ;;
esac
if [ -z "${MYSQL_USER:-}" ] || [ -z "${MYSQL_PASSWORD:-}" ]; then
  echo "reservation inventory cutover preflight: MySQL credentials are required" >&2
  exit 1
fi

mysql_scalar() {
  MYSQL_PWD="$MYSQL_PASSWORD" mysql \
    --protocol=TCP \
    --host="$MYSQL_HOST" \
    --port="$MYSQL_PORT" \
    --user="$MYSQL_USER" \
    --batch \
    --raw \
    --skip-column-names \
    "$MYSQL_DATABASE" \
    --execute="$1"
}

require_nonnegative_integer() {
  case "$2" in
    ''|*[!0-9]*)
      echo "reservation inventory cutover preflight: malformed $1 result" >&2
      exit 1
      ;;
  esac
}

inventory_table_count=$(mysql_scalar "
  SELECT COUNT(*)
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'accommodation_inventory_day'
    AND TABLE_TYPE = 'BASE TABLE';
")
require_nonnegative_integer "inventory table" "$inventory_table_count"

reservation_table_count=$(mysql_scalar "
  SELECT COUNT(*)
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'reservation'
    AND TABLE_TYPE = 'BASE TABLE';
")
require_nonnegative_integer "reservation table" "$reservation_table_count"

if [ "$inventory_table_count" -eq 0 ] && [ "$reservation_table_count" -eq 1 ]; then
  reservation_count=$(mysql_scalar "SELECT COUNT(*) FROM reservation;")
  require_nonnegative_integer "reservation row" "$reservation_count"
  if [ "$reservation_count" -ne 0 ]; then
    echo "reservation inventory cutover preflight: reservation must contain zero rows before V25" >&2
    exit 1
  fi
fi

accommodation_table_count=$(mysql_scalar "
  SELECT COUNT(*)
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'accommodation'
    AND TABLE_TYPE = 'BASE TABLE';
")
require_nonnegative_integer "accommodation table" "$accommodation_table_count"

if [ "$accommodation_table_count" -eq 1 ]; then
  timezone_column_count=$(mysql_scalar "
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'accommodation'
      AND COLUMN_NAME = 'time_zone_id';
  ")
  require_nonnegative_integer "accommodation timezone column" "$timezone_column_count"

  if [ "$timezone_column_count" -eq 0 ]; then
    invalid_timezone_count=$(mysql_scalar "
      SELECT COUNT(*)
      FROM accommodation
      WHERE status = 'PUBLISHED';
    ")
  else
    invalid_timezone_count=$(mysql_scalar "
      SELECT COUNT(*)
      FROM accommodation
      WHERE status = 'PUBLISHED'
        AND (
          time_zone_id IS NULL
          OR TRIM(time_zone_id) = ''
          OR time_zone_id NOT REGEXP '^[A-Za-z][A-Za-z0-9._+-]*(/[A-Za-z0-9._+-]+)*$'
        );
    ")
  fi
  require_nonnegative_integer "published accommodation timezone" "$invalid_timezone_count"
  if [ "$invalid_timezone_count" -ne 0 ]; then
    echo "reservation inventory cutover preflight: every PUBLISHED accommodation needs a plausible IANA time_zone_id" >&2
    exit 1
  fi
fi

echo "reservation inventory cutover preflight passed"
