#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() {
  printf 'mysql init script contract test failed: %s\n' "$1" >&2
  exit 1
}

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../../.." && pwd)
init_script="$repo_root/docker/mysql/init/01-create-infrastructure-users.sh"
ci_workflow="$repo_root/.github/workflows/ci.yml"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-mysql-init-test.XXXXXX")

cleanup() {
  cleanup_exit=$?
  trap - EXIT HUP INT TERM
  rm -rf -- "$temp_dir"
  exit "$cleanup_exit"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

[[ -f "$init_script" && ! -L "$init_script" ]] \
  || fail 'init script is missing or unsafe'
[[ -x "$init_script" ]] \
  || fail 'init script must be executable instead of relying on entrypoint sourcing'
grep -Fq 'bash infra/aws/tests/mysql-init-script-test.sh' "$ci_workflow" \
  || fail 'CI does not execute the MySQL init contract test'

mock_bin="$temp_dir/bin"
mock_state="$temp_dir/state"
mkdir -m 700 "$mock_bin" "$mock_state"
cat > "$mock_bin/mysql" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
: "${MYSQL_MOCK_STATE:?}"
printf '%s\n' "$@" > "$MYSQL_MOCK_STATE/argv"
printf '%s' "${MYSQL_PWD:-}" > "$MYSQL_MOCK_STATE/password"
cat > "$MYSQL_MOCK_STATE/stdin.sql"
MOCK
chmod 755 "$mock_bin/mysql"

root_password='root-contract-secret'
debezium_password='debezium-contract-secret'
logstash_password='logstash-contract-secret'
output="$temp_dir/output"
if ! PATH="$mock_bin:/usr/bin:/bin" \
  MYSQL_MOCK_STATE="$mock_state" \
  MYSQL_ROOT_PASSWORD="$root_password" \
  MYSQL_DATABASE=airbobdb \
  DEBEZIUM_DATABASE_USER=debezium \
  DEBEZIUM_DATABASE_PASSWORD="$debezium_password" \
  LOGSTASH_JDBC_USER=logstash \
  LOGSTASH_JDBC_PASSWORD="$logstash_password" \
  "$init_script" > "$output" 2>&1
then
  sed 's/^/[init] /' "$output" >&2
  fail 'standalone execution failed'
fi

[[ "$(tr '\n' ' ' < "$mock_state/argv")" == '--protocol=socket --user=root --database=mysql ' ]] \
  || fail 'standalone execution did not use the approved local socket arguments'
[[ "$(<"$mock_state/password")" == "$root_password" ]] \
  || fail 'standalone execution did not pass the root password through MYSQL_PWD'
expected_sql_fragments=(
  "CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY '$debezium_password';"
  "ALTER USER 'debezium'@'%' IDENTIFIED BY '$debezium_password';"
  "GRANT SELECT ON \`airbobdb\`.* TO 'debezium'@'%';"
  "GRANT RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';"
  "CREATE USER IF NOT EXISTS 'logstash'@'%' IDENTIFIED BY '$logstash_password';"
  "ALTER USER 'logstash'@'%' IDENTIFIED BY '$logstash_password';"
  "GRANT SELECT ON \`airbobdb\`.* TO 'logstash'@'%';"
)
for expected_sql_fragment in "${expected_sql_fragments[@]}"; do
  grep -Fq "$expected_sql_fragment" "$mock_state/stdin.sql" \
    || fail "infrastructure-user SQL is missing: $expected_sql_fragment"
done
if grep -Fq "$root_password" "$mock_state/argv" \
  || grep -Fq "$root_password" "$mock_state/stdin.sql" \
  || grep -Fq "$root_password" "$output"
then
  fail 'root password leaked to argv, SQL, or output'
fi

printf '%s\n' 'mysql init script contract tests passed'
