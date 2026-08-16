#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

[[ $# -eq 4 ]] || fail "usage: assemble-image-release.sh app|infra FULL_COMMIT_SHA INPUT_DIRECTORY OUTPUT_JSON"
release_kind=$1
commit_sha=$2
input_dir=$3
output_file=$4

[[ "$release_kind" == app || "$release_kind" == infra ]] || fail "release kind must be app or infra"
[[ "$commit_sha" =~ ^[0-9a-f]{40}$ ]] || fail "release commit must be one full lowercase Git SHA"
[[ -d "$input_dir" && ! -L "$input_dir" ]] || fail "release input directory is missing or unsafe"
[[ ! -L "$output_file" ]] || fail "release output must not be a symlink"
command -v jq >/dev/null 2>&1 || fail "jq is required"

if [[ "$release_kind" == app ]]; then
  expected_variables=(APP_IMAGE)
else
  expected_variables=(
    DEBEZIUM_IMAGE
    ELASTICSEARCH_EXPORTER_IMAGE
    ELASTICSEARCH_IMAGE
    GRAFANA_IMAGE
    KAFKA_IMAGE
    NODE_EXPORTER_IMAGE
    PROMETHEUS_IMAGE
    REDIS_EXPORTER_IMAGE
    REDIS_IMAGE
  )
fi

expected_repository() {
  case "$1" in
    APP_IMAGE) printf '%s\n' airbob-repo ;;
    REDIS_IMAGE) printf '%s\n' airbob-infra/redis ;;
    REDIS_EXPORTER_IMAGE) printf '%s\n' airbob-infra/redis-exporter ;;
    NODE_EXPORTER_IMAGE) printf '%s\n' airbob-infra/node-exporter ;;
    KAFKA_IMAGE) printf '%s\n' airbob-infra/kafka ;;
    DEBEZIUM_IMAGE) printf '%s\n' airbob-infra/debezium ;;
    ELASTICSEARCH_IMAGE) printf '%s\n' airbob-infra/elasticsearch ;;
    ELASTICSEARCH_EXPORTER_IMAGE) printf '%s\n' airbob-infra/elasticsearch-exporter ;;
    PROMETHEUS_IMAGE) printf '%s\n' airbob-infra/prometheus ;;
    GRAFANA_IMAGE) printf '%s\n' airbob-infra/grafana ;;
    *) return 1 ;;
  esac
}

shopt -s nullglob dotglob
entries=("$input_dir"/*)
[[ ${#entries[@]} -eq ${#expected_variables[@]} ]] || fail "release input must contain exactly the expected reference files"

images='{}'
for variable in "${expected_variables[@]}"; do
  reference_file="$input_dir/$variable.ref"
  [[ -f "$reference_file" && ! -L "$reference_file" ]] || fail "release reference is missing or unsafe"
  IFS= read -r image_ref < "$reference_file" || fail "release reference is empty"
  [[ "$(wc -l < "$reference_file" | tr -d ' ')" -eq 1 ]] || fail "release reference must contain exactly one line"
  repository=$(expected_repository "$variable")
  [[ "$image_ref" =~ ^942632789808\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/${repository}@sha256:[0-9a-f]{64}$ ]] \
    || fail "release reference does not match its approved repository"
  images=$(printf '%s\n' "$images" | jq -c --arg variable "$variable" --arg ref "$image_ref" '. + {($variable): $ref}')
done

output_parent=$(dirname -- "$output_file")
mkdir -p "$output_parent"
temp_output="$output_file.tmp.$$"
cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  rm -f "$temp_output"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

jq -nS \
  --arg kind "$release_kind" \
  --arg commit "$commit_sha" \
  --argjson images "$images" \
  '{schemaVersion: 1, kind: $kind, gitCommit: $commit, images: $images}' \
  > "$temp_output"
chmod 600 "$temp_output"
mv "$temp_output" "$output_file"
trap - EXIT HUP INT TERM
