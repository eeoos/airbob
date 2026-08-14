#!/usr/bin/env bash
set -euo pipefail

compose_file=${1:?compose file is required}
image_env_file=${2:?image env file is required}

[[ -f "$compose_file" && -r "$compose_file" ]] || {
  printf 'compose file is not readable: %s\n' "$compose_file" >&2
  exit 1
}
[[ -f "$image_env_file" && -r "$image_env_file" ]] || {
  printf 'image env file is not readable: %s\n' "$image_env_file" >&2
  exit 1
}

docker compose --env-file "$image_env_file" -f "$compose_file" config --quiet
images=$(docker compose --env-file "$image_env_file" -f "$compose_file" config --images)
[[ -n "$images" ]] || {
  printf 'bundle has no images: %s\n' "$compose_file" >&2
  exit 1
}

digest_pattern='^[a-z0-9][a-z0-9._:/-]*@sha256:[0-9a-f]{64}$'
while IFS= read -r image; do
  [[ "$image" =~ $digest_pattern ]] || {
    printf 'mutable or invalid image reference: %s\n' "$image" >&2
    exit 1
  }
done <<< "$images"
