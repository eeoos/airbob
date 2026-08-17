#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
spec_file="$repo_root/infra/aws/images/release.json"

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

[[ $# -eq 3 ]] || fail "usage: publish-ecr-image.sh IMAGE_VARIABLE FULL_COMMIT_SHA OUTPUT_DIRECTORY"
image_variable=$1
commit_sha=$2
output_dir=$3

[[ "$commit_sha" =~ ^[0-9a-f]{40}$ ]] || fail "image tag must be one full lowercase Git commit SHA"
[[ -f "$spec_file" && ! -L "$spec_file" ]] || fail "image release specification is missing or unsafe"
[[ -n "${ECR_REGISTRY:-}" ]] || fail "ECR_REGISTRY is required"
[[ "$ECR_REGISTRY" =~ ^[0-9]{12}\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com$ ]] \
  || fail "ECR_REGISTRY is not the approved Seoul private registry"
command -v jq >/dev/null 2>&1 || fail "jq is required"
command -v aws >/dev/null 2>&1 || fail "AWS CLI is required"
command -v docker >/dev/null 2>&1 || fail "Docker Buildx is required"

case "$image_variable" in
  APP_IMAGE) expected_repository=airbob-repo ;;
  REDIS_IMAGE) expected_repository=airbob-infra/redis ;;
  REDIS_EXPORTER_IMAGE) expected_repository=airbob-infra/redis-exporter ;;
  NODE_EXPORTER_IMAGE) expected_repository=airbob-infra/node-exporter ;;
  KAFKA_IMAGE) expected_repository=airbob-infra/kafka ;;
  DEBEZIUM_IMAGE) expected_repository=airbob-infra/debezium ;;
  ELASTICSEARCH_IMAGE) expected_repository=airbob-infra/elasticsearch ;;
  ELASTICSEARCH_EXPORTER_IMAGE) expected_repository=airbob-infra/elasticsearch-exporter ;;
  PROMETHEUS_IMAGE) expected_repository=airbob-infra/prometheus ;;
  GRAFANA_IMAGE) expected_repository=airbob-infra/grafana ;;
  *) fail "image variable is outside the approved ten-image contract" ;;
esac

if ! entry=$(jq -ce --arg variable "$image_variable" '
  ([.app] + .infra) | map(select(.variable == $variable)) |
  if length == 1 then .[0] else error("image variable must resolve exactly once") end
' "$spec_file"); then
  fail "image variable is missing or duplicated in the release specification"
fi

repository=$(printf '%s\n' "$entry" | jq -r '.repository')
context_path=$(printf '%s\n' "$entry" | jq -r '.context')
dockerfile_path=$(printf '%s\n' "$entry" | jq -r '.dockerfile')
platforms=$(jq -r '.platforms | join(",")' "$spec_file")
[[ "$repository" == "$expected_repository" ]] || fail "image variable is mapped to the wrong ECR repository"
for relative_path in "$context_path" "$dockerfile_path"; do
  [[ "$relative_path" =~ ^[A-Za-z0-9._/-]+$ && "$relative_path" != *..* ]] \
    || fail "image build path is not canonical"
done
[[ -e "$repo_root/$context_path" && ! -L "$repo_root/$context_path" && -f "$repo_root/$dockerfile_path" && ! -L "$repo_root/$dockerfile_path" ]] \
  || fail "image build context or Dockerfile is missing or unsafe"
[[ "$platforms" == "linux/amd64,linux/arm64" ]] || fail "image platforms do not match the approved contract"

build_args=()
while IFS= read -r build_arg; do
  [[ "$build_arg" =~ ^[A-Z0-9_]+=[^[:space:]]+@sha256:[0-9a-f]{64}$ ]] \
    || fail "image build argument is not a digest-pinned reference"
  build_args+=(--build-arg "$build_arg")
done < <(printf '%s\n' "$entry" | jq -r '.buildArgs | to_entries | sort_by(.key)[] | "\(.key)=\(.value)"')
[[ ${#build_args[@]} -gt 0 ]] || fail "image build requires at least one digest-pinned base"

[[ ! -L "$output_dir" ]] || fail "image output directory must not be a symlink"
mkdir -p "$output_dir"
chmod 700 "$output_dir"

lookup_image() {
  aws ecr batch-get-image \
    --repository-name "$repository" \
    --image-ids "imageTag=$commit_sha" \
    --accepted-media-types \
      application/vnd.oci.image.index.v1+json \
      application/vnd.docker.distribution.manifest.list.v2+json \
    --output json
}

lookup_current_digest() {
  local response image_count failure_count missing_count
  if ! response=$(lookup_image); then
    fail "ECR immutable-image lookup failed"
  fi
  image_count=$(printf '%s\n' "$response" | jq -er '.images | length') \
    || fail "ECR returned an invalid immutable-image lookup response"
  failure_count=$(printf '%s\n' "$response" | jq -er '.failures | length') \
    || fail "ECR returned an invalid immutable-image lookup response"
  missing_count=$(printf '%s\n' "$response" | jq -er '[.failures[] | select(.failureCode == "ImageNotFound")] | length') \
    || fail "ECR returned an invalid immutable-image lookup response"

  if [[ "$image_count" -eq 1 && "$failure_count" -eq 0 ]]; then
    image_digest=$(printf '%s\n' "$response" | jq -er '.images[0].imageId.imageDigest') \
      || fail "ECR returned an invalid immutable-image lookup response"
    [[ "$image_digest" =~ ^sha256:[0-9a-f]{64}$ ]] \
      || fail "ECR returned a non-canonical image digest"
    return 0
  fi
  if [[ "$image_count" -eq 0 && "$failure_count" -eq 1 && "$missing_count" -eq 1 ]]; then
    image_digest=""
    return 1
  fi
  fail "ECR returned an unexpected immutable-image lookup result"
}

image_digest=""
if lookup_current_digest; then
  :
else
  docker buildx build \
    --platform "$platforms" \
    --file "$repo_root/$dockerfile_path" \
    --tag "$ECR_REGISTRY/$repository:$commit_sha" \
    --label "org.opencontainers.image.revision=$commit_sha" \
    --push \
    "${build_args[@]}" \
    "$repo_root/$context_path"

  image_digest=""
  for _attempt in 1 2 3 4 5; do
    if lookup_current_digest; then
      break
    fi
    sleep 2
  done
  [[ "$image_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "published ECR image digest did not become readable"
fi

image_ref="$ECR_REGISTRY/$repository@$image_digest"
manifest=$(docker buildx imagetools inspect --raw "$image_ref")
architectures=$(printf '%s\n' "$manifest" | jq -c '
  [.manifests[]? | select(.platform.os == "linux") | .platform.architecture] |
  unique | sort
')
[[ "$architectures" == '["amd64","arm64"]' ]] \
  || fail "published ECR image must contain exactly linux/amd64 and linux/arm64"

output_file="$output_dir/$image_variable.ref"
[[ ! -L "$output_file" ]] || fail "image reference output must not be a symlink"
printf '%s\n' "$image_ref" > "$output_file"
chmod 600 "$output_file"
printf 'Published immutable %s reference.\n' "$image_variable"
