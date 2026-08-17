#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
aws_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
verifier="$aws_dir/scripts/verify-service-bundle.sh"
fixtures_dir="$script_dir/fixtures"
valid_compose="$fixtures_dir/valid-compose.yml"
tagged_compose="$fixtures_dir/tagged-compose.yml"
zero_images_compose="$fixtures_dir/zero-images-compose.yml"
image_env="$fixtures_dir/images.env"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-service-bundle.XXXXXX")
missing_image_env="$temp_dir/missing-image.env"
registry_port_image_env="$temp_dir/registry-port-image.env"
double_underscore_image_env="$temp_dir/double-underscore-image.env"
repeated_hyphen_image_env="$temp_dir/repeated-hyphen-image.env"
registry_port_without_path_image_env="$temp_dir/registry-port-without-path-image.env"
tagged_digest_image_env="$temp_dir/tagged-digest-image.env"
doubled_component_image_env="$temp_dir/doubled-component-image.env"
separator_leading_image_env="$temp_dir/separator-leading-image.env"
separator_trailing_image_env="$temp_dir/separator-trailing-image.env"
triple_underscore_image_env="$temp_dir/triple-underscore-image.env"
whitespace_image_env="$temp_dir/whitespace-image.env"
uppercase_image_env="$temp_dir/uppercase-image.env"
multiple_at_image_env="$temp_dir/multiple-at-image.env"
unreadable_file="$temp_dir/unreadable"
project_name="service-bundle-verifier-test-$$"

cleanup() {
  status=$?
  trap - EXIT
  set +e
  docker compose --project-name "$project_name" --env-file "$image_env" -f "$valid_compose" down --remove-orphans >/dev/null 2>&1
  cleanup_status=$?
  rm -rf "$temp_dir"
  if [[ "$status" -ne 0 ]]; then
    exit "$status"
  fi
  exit "$cleanup_status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

make_image_env() {
  image=$1
  output=$2
  sed "s|^APP_IMAGE=.*$|APP_IMAGE=$image|" "$image_env" > "$output"
}

run_verifier() {
  COMPOSE_PROJECT_NAME="$project_name" "$verifier" "$@"
}

expect_failure() {
  description=$1
  shift
  if run_verifier "$@"; then
    printf 'expected %s to fail verification\n' "$description" >&2
    exit 1
  fi
}

sed '/^APP_IMAGE=/d' "$image_env" > "$missing_image_env"
make_image_env 'registry.example.invalid:5000/airbob/app@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$registry_port_image_env"
make_image_env 'registry.example.invalid/airbob/app__worker@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$double_underscore_image_env"
make_image_env 'registry---mirror.example.invalid:5000/airbob/app---worker@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$repeated_hyphen_image_env"
make_image_env 'registry.example.invalid:5000@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$registry_port_without_path_image_env"
make_image_env 'registry.example.invalid/airbob/app:latest@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$tagged_digest_image_env"
make_image_env 'registry.example.invalid//airbob/app@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$doubled_component_image_env"
make_image_env 'registry.example.invalid/airbob/-app@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$separator_leading_image_env"
make_image_env 'registry.example.invalid/airbob/app-@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$separator_trailing_image_env"
make_image_env 'registry.example.invalid/airbob/app___worker@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$triple_underscore_image_env"
make_image_env 'registry.example.invalid/airbob/app name@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$whitespace_image_env"
make_image_env 'registry.example.invalid/airbob/App@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$uppercase_image_env"
make_image_env 'registry.example.invalid/airbob/app@@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$multiple_at_image_env"
touch "$unreadable_file"
chmod 000 "$unreadable_file"

if ! run_verifier "$valid_compose" "$image_env"; then
  printf 'expected digest-pinned bundle to pass verification\n' >&2
  exit 1
fi

if ! run_verifier "$valid_compose" "$registry_port_image_env"; then
  printf 'expected registry-port digest image to pass verification\n' >&2
  exit 1
fi
if ! run_verifier "$valid_compose" "$double_underscore_image_env"; then
  printf 'expected double-underscore repository component to pass verification\n' >&2
  exit 1
fi
if ! run_verifier "$valid_compose" "$repeated_hyphen_image_env"; then
  printf 'expected repeated-hyphen repository component to pass verification\n' >&2
  exit 1
fi

expect_failure 'registry port without repository path' "$valid_compose" "$registry_port_without_path_image_env"
expect_failure 'tagged image' "$tagged_compose" "$image_env"
expect_failure 'missing image variable' "$valid_compose" "$missing_image_env"
expect_failure 'tag plus digest image' "$valid_compose" "$tagged_digest_image_env"
expect_failure 'doubled repository component' "$valid_compose" "$doubled_component_image_env"
expect_failure 'separator-leading repository component' "$valid_compose" "$separator_leading_image_env"
expect_failure 'separator-trailing repository component' "$valid_compose" "$separator_trailing_image_env"
expect_failure 'triple-underscore repository component' "$valid_compose" "$triple_underscore_image_env"
expect_failure 'whitespace repository reference' "$valid_compose" "$whitespace_image_env"
expect_failure 'uppercase repository reference' "$valid_compose" "$uppercase_image_env"
expect_failure 'multiple at-sign repository reference' "$valid_compose" "$multiple_at_image_env"
expect_failure 'non-regular compose file' /dev/null "$image_env"
expect_failure 'non-regular image env file' "$valid_compose" /dev/null
if [[ ! -r "$unreadable_file" ]]; then
  expect_failure 'unreadable compose file' "$unreadable_file" "$image_env"
  expect_failure 'unreadable image env file' "$valid_compose" "$unreadable_file"
fi
expect_failure 'bundle with no images' "$zero_images_compose" "$image_env"

containers=$(docker compose --project-name "$project_name" --env-file "$image_env" -f "$valid_compose" ps --all --quiet)
if [[ -n "$containers" ]]; then
  printf 'verifier created containers for project %s: %s\n' "$project_name" "$containers" >&2
  exit 1
fi

printf 'service bundle verifier tests passed\n'
