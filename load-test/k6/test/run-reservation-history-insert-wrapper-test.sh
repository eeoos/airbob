#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$test_dir/../../.." && pwd -P)"
wrapper="$repo_root/load-test/k6/bulk-write/run-reservation-history-insert.sh"
capture="$test_dir/capture-k6-invocation.sh"
benchmark_script="$repo_root/load-test/k6/bulk-write/reservation-history-insert-comparison.js"

output="$({
  cd -- "${TMPDIR:-/tmp}"
  K6_BIN="$capture" "$wrapper" --quiet --tag 'run label=with spaces'
})"
expected="$(printf '%s\n' \
  "cwd=$repo_root" \
  'arg=run' \
  'arg=--quiet' \
  'arg=--tag' \
  'arg=run label=with spaces' \
  "arg=$benchmark_script")"

if [[ "$output" != "$expected" ]]; then
  printf 'unexpected wrapper invocation\nexpected:\n%s\nactual:\n%s\n' "$expected" "$output" >&2
  exit 1
fi

if [[ ! -d "$repo_root/build/k6/bulk-write" ]]; then
  printf 'wrapper did not create build/k6/bulk-write under the repository root\n' >&2
  exit 1
fi
