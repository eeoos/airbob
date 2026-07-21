#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$test_dir/../../.." && pwd -P)"
launcher="$repo_root/load-test/k6/bulk-write/run-bulk-write-benchmark-server.sh"
temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/bulk-write-server-test.XXXXXX")"
trap 'rm -rf -- "$temp_dir"' EXIT

token='token-sentinel-0123456789abcdef0123456789'
schema='schema-credential-sentinel'

if output="$(
  BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA="$schema" \
    GRADLE_BIN="$temp_dir/not-invoked" \
    "$launcher" 2>&1
)"; then
  printf 'launcher accepted a missing BENCHMARK_BULK_WRITE_TOKEN\n' >&2
  exit 1
fi
if [[ "$output" == *"$schema"* ]]; then
  printf 'launcher disclosed the allowed schema while rejecting a missing token\n' >&2
  exit 1
fi

if output="$(
  BENCHMARK_BULK_WRITE_TOKEN="$token" \
    GRADLE_BIN="$temp_dir/not-invoked" \
    "$launcher" 2>&1
)"; then
  printf 'launcher accepted a missing BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA\n' >&2
  exit 1
fi
if [[ "$output" == *"$token"* ]]; then
  printf 'launcher disclosed the token while rejecting a missing schema\n' >&2
  exit 1
fi

cat >"$temp_dir/capture-gradle" <<'CAPTURE'
#!/usr/bin/env bash
set -euo pipefail

[[ -n "${BENCHMARK_BULK_WRITE_TOKEN:-}" ]]
[[ -n "${BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA:-}" ]]
printf 'enabled=%s\n' "${BENCHMARK_BULK_WRITE_ENABLED:-}"
printf '%s\n' "$$" >"$CAPTURE_PID_PATH"
while [[ ! -e "$CAPTURE_RELEASE_PATH" ]]; do
  sleep 0.01
done
for argument in "$@"; do
  printf 'arg=%s\n' "$argument"
done
CAPTURE
chmod +x "$temp_dir/capture-gradle"

cat >"$temp_dir/untrusted-gradle" <<'UNTRUSTED'
#!/usr/bin/env bash
set -euo pipefail

touch "$UNTRUSTED_MARKER"
UNTRUSTED
chmod +x "$temp_dir/untrusted-gradle"

if UNTRUSTED_MARKER="$temp_dir/untrusted-invoked" \
  BENCHMARK_BULK_WRITE_TOKEN="$token" \
  BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA="$schema" \
  GRADLE_BIN="$temp_dir/untrusted-gradle" \
  "$launcher" >"$temp_dir/untrusted-output" 2>&1; then
  printf 'launcher accepted GRADLE_BIN outside explicit test mode\n' >&2
  exit 1
fi
if [[ -e "$temp_dir/untrusted-invoked" ]]; then
  printf 'launcher invoked an environment-selected Gradle executable in normal mode\n' >&2
  exit 1
fi
if [[ "$(cat "$temp_dir/untrusted-output")" == *"$token"* \
  || "$(cat "$temp_dir/untrusted-output")" == *"$schema"* ]]; then
  printf 'launcher disclosed a credential while rejecting GRADLE_BIN\n' >&2
  exit 1
fi

output_path="$temp_dir/output"
CAPTURE_PID_PATH="$temp_dir/capture.pid" \
CAPTURE_RELEASE_PATH="$temp_dir/release" \
BENCHMARK_BULK_WRITE_TOKEN="$token" \
BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA="$schema" \
BULK_WRITE_BENCHMARK_TEST_MODE=1 \
GRADLE_BIN="$temp_dir/capture-gradle" \
  "$launcher" >"$output_path" 2>&1 &
launcher_pid=$!

for _ in {1..500}; do
  [[ -e "$temp_dir/capture.pid" ]] && break
  if ! kill -0 "$launcher_pid" 2>/dev/null; then
    break
  fi
  sleep 0.01
done

if [[ ! -e "$temp_dir/capture.pid" ]]; then
  wait "$launcher_pid" || true
  printf 'launcher did not invoke Gradle\n%s\n' "$(cat "$output_path")" >&2
  exit 1
fi
if [[ "$(cat "$temp_dir/capture.pid")" != "$launcher_pid" ]]; then
  touch "$temp_dir/release"
  wait "$launcher_pid" || true
  printf 'launcher must exec the Gradle process\n' >&2
  exit 1
fi

touch "$temp_dir/release"
wait "$launcher_pid"
output="$(cat "$output_path")"

safe_boot_args='--spring.profiles.active=dev,bulk-write-benchmark --spring.jpa.properties.hibernate.show_sql=false --spring.jpa.properties.hibernate.format_sql=false --logging.level.org.hibernate.SQL=OFF --logging.level.org.hibernate.orm.jdbc.bind=OFF --logging.level.org.hibernate.type.descriptor.sql=OFF --logging.level.kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBeforeBenchmarkService=OFF'
expected="$(printf '%s\n' \
  'enabled=true' \
  'arg=bootRun' \
  "arg=--args=$safe_boot_args")"

if [[ "$output" != "$expected" ]]; then
  printf 'unexpected server launcher invocation\nexpected:\n%s\nactual:\n%s\n' \
    "$expected" "$output" >&2
  exit 1
fi
if [[ "$output" == *"$token"* || "$output" == *"$schema"* ]]; then
  printf 'launcher disclosed a benchmark credential\n' >&2
  exit 1
fi

if BENCHMARK_BULK_WRITE_TOKEN="$token" \
  BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA="$schema" \
  BULK_WRITE_BENCHMARK_TEST_MODE=1 \
  GRADLE_BIN="$temp_dir/not-invoked" \
  "$launcher" --stacktrace >"$temp_dir/extra-output" 2>&1; then
  printf 'launcher accepted unscoped caller Gradle arguments\n' >&2
  exit 1
fi
if [[ "$(cat "$temp_dir/extra-output")" == *"$token"* \
  || "$(cat "$temp_dir/extra-output")" == *"$schema"* ]]; then
  printf 'launcher disclosed a credential while rejecting caller arguments\n' >&2
  exit 1
fi
