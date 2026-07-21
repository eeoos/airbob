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
gradle_bin="${GRADLE_BIN:-$repo_root/gradlew}"
boot_args='--spring.profiles.active=dev,bulk-write-benchmark --spring.jpa.properties.hibernate.show_sql=false --spring.jpa.properties.hibernate.format_sql=false --logging.level.org.hibernate.SQL=OFF --logging.level.org.hibernate.orm.jdbc.bind=OFF --logging.level.org.hibernate.type.descriptor.sql=OFF --logging.level.kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBeforeBenchmarkService=OFF'

cd -- "$repo_root"
export BENCHMARK_BULK_WRITE_ENABLED=true
exec "$gradle_bin" bootRun "--args=$boot_args"
