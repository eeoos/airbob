#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/../../.." && pwd -P)"

mkdir -p -- "$repo_root/build/k6/bulk-write"
cd -- "$repo_root"
exec "${K6_BIN:-k6}" run "$@" "$script_dir/wishlist-delete-comparison.js"
