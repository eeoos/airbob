#!/usr/bin/env bash
set -euo pipefail

# The same k6 scenario runs locally or against an AWS test app through BASE_URL.
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_root=$(cd "$script_dir/../.." && pwd)
: "${BENCHMARK_MANIFEST:?Set BENCHMARK_MANIFEST to a fixture manifest from the target database}"
: "${TEST_PASSWORD:?Set TEST_PASSWORD to the benchmark account password}"
: "${BENCHMARK_READ_MODEL_TOKEN:?Set BENCHMARK_READ_MODEL_TOKEN to the benchmark token configured on the app}"
[[ -f "$BENCHMARK_MANIFEST" ]] || { echo 'Benchmark manifest does not exist' >&2; exit 1; }
command -v k6 >/dev/null

export BASE_URL="${BASE_URL:-http://localhost:8080}"
export BENCHMARK_MANIFEST="$(cd "$(dirname "$BENCHMARK_MANIFEST")" && pwd)/$(basename "$BENCHMARK_MANIFEST")"
export K6_NO_USAGE_REPORT=true
rounds=${ROUNDS:-1}
[[ "$rounds" =~ ^([1-9]|10)$ ]] || { echo 'ROUNDS must be between 1 and 10' >&2; exit 1; }

# Validate inputs and scenario options without sending HTTP requests.
VARIANT=before k6 inspect --include-system-env-vars "$script_dir/recently-viewed-nplus1-performance.js" >/dev/null
umask 077
result_dir=${RESULT_DIR:-"$project_root/build/k6/recently-viewed-$(date +%Y%m%d-%H%M%S)"}
mkdir -p "$(dirname "$result_dir")"
mkdir "$result_dir" # Never overwrite an earlier comparison.
result_dir=$(cd "$result_dir" && pwd)

for ((round = 1; round <= rounds; round++)); do
  variants=(before after)
  if ((round % 2 == 0)); then variants=(after before); fi
  for variant in "${variants[@]}"; do
    printf 'Round %s/%s: %s\n' "$round" "$rounds" "$variant"
    VARIANT="$variant" K6_RESULT_PATH="$result_dir/round-$round-$variant.json" \
      k6 run --address '' "$script_dir/recently-viewed-nplus1-performance.js"
  done
done
printf 'Comparison results: %s\n' "$result_dir"
