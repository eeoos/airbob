#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  printf 'run-bulk-write-benchmark-server.sh does not accept caller Gradle arguments\n' >&2
  exit 2
fi

: "${BENCHMARK_BULK_WRITE_TOKEN:?BENCHMARK_BULK_WRITE_TOKEN is required}"
: "${BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA:?BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA is required}"

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/../../.." && pwd -P)"
test_mode="${BULK_WRITE_BENCHMARK_TEST_MODE:-0}"
if [[ "$test_mode" != '0' && "$test_mode" != '1' ]]; then
  printf 'BULK_WRITE_BENCHMARK_TEST_MODE must be 0 or 1\n' >&2
  exit 2
fi
if [[ "$test_mode" == '1' ]]; then
  gradle_bin="${GRADLE_BIN:-$repo_root/gradlew}"
else
  if [[ -n "${GRADLE_BIN+x}" ]]; then
    printf 'GRADLE_BIN requires explicit test mode\n' >&2
    exit 2
  fi
  gradle_bin="$repo_root/gradlew"
fi
if [[ "$test_mode" == '0' \
  && ( ! -f "$gradle_bin" || -L "$gradle_bin" || ! -x "$gradle_bin" ) ]]; then
  printf 'trusted Gradle wrapper is unavailable\n' >&2
  exit 2
fi
boot_args='--spring.profiles.active=dev,bulk-write-benchmark --spring.jpa.properties.hibernate.show_sql=false --spring.jpa.properties.hibernate.format_sql=false --logging.level.org.hibernate.SQL=OFF --logging.level.org.hibernate.orm.jdbc.bind=OFF --logging.level.org.hibernate.type.descriptor.sql=OFF --logging.level.kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBeforeBenchmarkService=OFF'

cd -- "$repo_root"
export BENCHMARK_BULK_WRITE_ENABLED=true
exec "$gradle_bin" bootRun -x test "--args=$boot_args"
