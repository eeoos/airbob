#!/usr/bin/env bash
set -euo pipefail

printf 'cwd=%s\n' "$PWD"
for argument in "$@"; do
  printf 'arg=%s\n' "$argument"
done
