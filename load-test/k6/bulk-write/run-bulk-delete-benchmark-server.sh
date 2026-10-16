#!/usr/bin/env bash
set -euo pipefail
if (( $# != 0 )); then
  printf 'The bulk-delete launcher does not accept extra Gradle arguments\n' >&2
  exit 2
fi
: "${BENCHMARK_BULK_WRITE_TOKEN:?BENCHMARK_BULK_WRITE_TOKEN is required}"
: "${BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA:?BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA is required}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/../../.." && pwd -P)"
cd -- "$repo_root"
if [[ -n "$(git status --porcelain --untracked-files=no)" \
  || -n "$(git ls-files --others --exclude-standard -- src/main)" ]]; then
  printf 'Commit application sources and tracked changes before measuring a versioned application\n' >&2
  exit 2
fi
export BENCHMARK_APP_COMMIT
BENCHMARK_APP_COMMIT="$(git rev-parse HEAD)"
export BENCHMARK_IMAGE_DIGEST=local
export BENCHMARK_BULK_WRITE_ENABLED=true
export JDBC_REWRITE_BATCHED_STATEMENTS="${JDBC_REWRITE_BATCHED_STATEMENTS:-true}"
exec ./gradlew bootRun -x test --args='--spring.profiles.active=dev,bulk-delete-benchmark'
