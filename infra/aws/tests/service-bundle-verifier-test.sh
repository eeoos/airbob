#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
aws_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
verifier="$aws_dir/scripts/verify-service-bundle.sh"
fixtures_dir="$script_dir/fixtures"
valid_compose="$fixtures_dir/valid-compose.yml"
tagged_compose="$fixtures_dir/tagged-compose.yml"
image_env="$fixtures_dir/images.env"
missing_image_env=$(mktemp "${TMPDIR:-/tmp}/airbob-images.XXXXXX")
project_name="service-bundle-verifier-test-$$"

cleanup() {
  rm -f "$missing_image_env"
}
trap cleanup EXIT HUP INT TERM

sed '/^APP_IMAGE=/d' "$image_env" > "$missing_image_env"

if ! "$verifier" "$valid_compose" "$image_env"; then
  printf 'expected digest-pinned bundle to pass verification\n' >&2
  exit 1
fi

if "$verifier" "$tagged_compose" "$image_env"; then
  printf 'expected tagged image to fail verification\n' >&2
  exit 1
fi

if "$verifier" "$valid_compose" "$missing_image_env"; then
  printf 'expected missing image variable to fail verification\n' >&2
  exit 1
fi

containers=$(docker compose --project-name "$project_name" --env-file "$image_env" -f "$valid_compose" ps --all --quiet)
if [[ -n "$containers" ]]; then
  printf 'verifier created containers for project %s: %s\n' "$project_name" "$containers" >&2
  exit 1
fi

printf 'service bundle verifier tests passed\n'
