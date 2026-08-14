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

component_pattern='^[a-z0-9]+(([._]|__|-+)[a-z0-9]+)*$'
digest_pattern='^[0-9a-f]{64}$'

is_valid_repository() {
  repository=$1
  first_component=1

  [[ -n "$repository" && "$repository" != /* && "$repository" != */ && "$repository" != *//* ]] || return 1

  while [[ -n "$repository" ]]; do
    case "$repository" in
      */*)
        component=${repository%%/*}
        repository=${repository#*/}
        ;;
      *)
        component=$repository
        repository=
        ;;
    esac

    if [[ "$component" == *:* ]]; then
      [[ "$first_component" -eq 1 ]] || return 1
      [[ -n "$repository" ]] || return 1
      registry=${component%:*}
      port=${component##*:}
      [[ "$registry" =~ $component_pattern && "$port" =~ ^[0-9]+$ ]] || return 1
    else
      [[ "$component" =~ $component_pattern ]] || return 1
    fi

    first_component=0
  done
}

is_immutable_image_reference() {
  image=$1

  case "$image" in
    *@sha256:*)
      repository=${image%@sha256:*}
      digest=${image#*@sha256:}
      ;;
    *)
      return 1
      ;;
  esac

  [[ -n "$repository" && "$repository" != *"@"* && "$digest" != *"@"* && "$digest" =~ $digest_pattern ]] || return 1
  is_valid_repository "$repository"
}

while IFS= read -r image; do
  is_immutable_image_reference "$image" || {
    printf 'mutable or invalid image reference: %s\n' "$image" >&2
    exit 1
  }
done <<< "$images"
