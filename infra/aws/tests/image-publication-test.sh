#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
spec_file="$repo_root/infra/aws/images/release.json"
publisher="$repo_root/infra/aws/scripts/publish-ecr-image.sh"
assembler="$repo_root/infra/aws/scripts/assemble-image-release.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-image-publication-test.XXXXXX")

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  rm -rf "$temp_dir"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

assert_contains() {
  local file=$1
  local expected=$2
  grep -Fq -- "$expected" "$file" || fail "$file does not contain: $expected"
}

assert_not_contains() {
  local file=$1
  local rejected=$2
  if grep -Eq -- "$rejected" "$file"; then
    fail "$file contains forbidden pattern: $rejected"
  fi
}

for required_file in \
  "$spec_file" \
  "$publisher" \
  "$assembler" \
  "$repo_root/infra/aws/scripts/verify-published-image.sh" \
  "$repo_root/docker/aws-mirror/Dockerfile" \
  "$repo_root/docker/kafka/Dockerfile" \
  "$repo_root/docker/debezium/Dockerfile" \
  "$repo_root/docker/debezium/Dockerfile.dockerignore" \
  "$repo_root/docker/elasticsearch/Dockerfile" \
  "$repo_root/docker/app/Dockerfile" \
  "$repo_root/docker/app/Dockerfile.dockerignore" \
  "$repo_root/.github/workflows/cd.yml" \
  "$repo_root/.github/workflows/infra-images.yml"
do
  [[ -f "$required_file" && ! -L "$required_file" ]] \
    || fail "required image-publication file is missing or unsafe: $required_file"
done
[[ -x "$publisher" && -x "$assembler" && -x "$repo_root/infra/aws/scripts/verify-published-image.sh" ]] \
  || fail "image publication scripts must be executable"

command -v jq >/dev/null 2>&1 || fail "jq is required"

jq -e '
  .schemaVersion == 1 and
  .platforms == ["linux/amd64", "linux/arm64"] and
  .app.variable == "APP_IMAGE" and
  .app.repository == "airbob-repo" and
  ([.infra[].variable] | sort) == ([
    "DEBEZIUM_IMAGE",
    "ELASTICSEARCH_EXPORTER_IMAGE",
    "ELASTICSEARCH_IMAGE",
    "GRAFANA_IMAGE",
    "KAFKA_IMAGE",
    "NODE_EXPORTER_IMAGE",
    "PROMETHEUS_IMAGE",
    "REDIS_EXPORTER_IMAGE",
    "REDIS_IMAGE"
  ] | sort) and
  ([.infra[].repository] | sort) == ([
    "airbob-infra/debezium",
    "airbob-infra/elasticsearch",
    "airbob-infra/elasticsearch-exporter",
    "airbob-infra/grafana",
    "airbob-infra/kafka",
    "airbob-infra/node-exporter",
    "airbob-infra/prometheus",
    "airbob-infra/redis",
    "airbob-infra/redis-exporter"
  ] | sort) and
  ([.app] + .infra | length) == 10 and
  ([.app] + .infra | all(
    .context | test("^[A-Za-z0-9._/-]+$") and
    (contains("..") | not)
  )) and
  ([.app] + .infra | all(
    .dockerfile | test("^[A-Za-z0-9._/-]+/Dockerfile$") and
    (contains("..") | not)
  )) and
  ([.app] + .infra | map(.buildArgs | to_entries[]) | all(
    .value | test("^[^[:space:]]+@sha256:[0-9a-f]{64}$")
  )) and
  .artifacts.jmxExporter.version == "1.6.0" and
  (.artifacts.jmxExporter.sha256 | test("^[0-9a-f]{64}$")) and
  .artifacts.debezium.version == "2.6.1.Final" and
  (.artifacts.debezium.sha256 | test("^[0-9a-f]{64}$"))
' "$spec_file" >/dev/null || fail "image release specification is incomplete or unsafe"

jq -e '
  def digest_from($repository):
    startswith($repository + "@sha256:") and test("@sha256:[0-9a-f]{64}$");
  ([.infra[] | {key: .variable, value: .buildArgs}] | from_entries) as $args |
  (.app.buildArgs | keys) == ["APP_BASE_IMAGE"] and
  (.app.buildArgs.APP_BASE_IMAGE | digest_from("docker.io/library/eclipse-temurin")) and
  ($args.REDIS_IMAGE | keys) == ["UPSTREAM_IMAGE"] and
  ($args.REDIS_IMAGE.UPSTREAM_IMAGE | digest_from("docker.io/library/redis")) and
  ($args.REDIS_EXPORTER_IMAGE | keys) == ["UPSTREAM_IMAGE"] and
  ($args.REDIS_EXPORTER_IMAGE.UPSTREAM_IMAGE | digest_from("docker.io/oliver006/redis_exporter")) and
  ($args.NODE_EXPORTER_IMAGE | keys) == ["UPSTREAM_IMAGE"] and
  ($args.NODE_EXPORTER_IMAGE.UPSTREAM_IMAGE | digest_from("docker.io/prom/node-exporter")) and
  ($args.KAFKA_IMAGE | keys | sort) == ["ALPINE_IMAGE", "KAFKA_BASE_IMAGE"] and
  ($args.KAFKA_IMAGE.ALPINE_IMAGE | digest_from("docker.io/library/alpine")) and
  ($args.KAFKA_IMAGE.KAFKA_BASE_IMAGE | digest_from("docker.io/apache/kafka")) and
  ($args.DEBEZIUM_IMAGE | keys | sort) == ["ALPINE_IMAGE", "KAFKA_BASE_IMAGE"] and
  ($args.DEBEZIUM_IMAGE.ALPINE_IMAGE | digest_from("docker.io/library/alpine")) and
  ($args.DEBEZIUM_IMAGE.KAFKA_BASE_IMAGE | digest_from("docker.io/apache/kafka")) and
  ($args.ELASTICSEARCH_IMAGE | keys) == ["ELASTICSEARCH_BASE_IMAGE"] and
  ($args.ELASTICSEARCH_IMAGE.ELASTICSEARCH_BASE_IMAGE | digest_from("docker.io/library/elasticsearch")) and
  ($args.ELASTICSEARCH_EXPORTER_IMAGE | keys) == ["UPSTREAM_IMAGE"] and
  ($args.ELASTICSEARCH_EXPORTER_IMAGE.UPSTREAM_IMAGE | digest_from("quay.io/prometheuscommunity/elasticsearch-exporter")) and
  ($args.PROMETHEUS_IMAGE | keys) == ["UPSTREAM_IMAGE"] and
  ($args.PROMETHEUS_IMAGE.UPSTREAM_IMAGE | digest_from("docker.io/prom/prometheus")) and
  ($args.GRAFANA_IMAGE | keys) == ["UPSTREAM_IMAGE"] and
  ($args.GRAFANA_IMAGE.UPSTREAM_IMAGE | digest_from("docker.io/grafana/grafana"))
' "$spec_file" >/dev/null || fail "image release specification maps an image to an unapproved upstream"

while IFS= read -r relative_path; do
  [[ -e "$repo_root/$relative_path" && ! -L "$repo_root/$relative_path" ]] \
    || fail "release specification references a missing or unsafe context: $relative_path"
done < <(jq -r '([.app] + .infra)[].context' "$spec_file" | sort -u)

while IFS= read -r relative_path; do
  [[ -f "$repo_root/$relative_path" && ! -L "$repo_root/$relative_path" ]] \
    || fail "release specification references a missing or unsafe file: $relative_path"
done < <(jq -r '([.app] + .infra)[].dockerfile' "$spec_file" | sort -u)

for dockerfile in \
  "$repo_root/docker/aws-mirror/Dockerfile" \
  "$repo_root/docker/kafka/Dockerfile" \
  "$repo_root/docker/debezium/Dockerfile" \
  "$repo_root/docker/elasticsearch/Dockerfile" \
  "$repo_root/docker/app/Dockerfile"
do
  assert_not_contains "$dockerfile" '^FROM[[:space:]]+[^$[:space:]]+:[^@[:space:]]+'
done
while IFS=$'\t' read -r dockerfile build_arg base_image; do
  if [[ "$dockerfile" == docker/aws-mirror/Dockerfile ]]; then
    continue
  fi
  assert_contains "$repo_root/$dockerfile" "ARG $build_arg=$base_image"
done < <(jq -r '([.app] + .infra)[] | .dockerfile as $dockerfile | .buildArgs | to_entries[] | [$dockerfile, .key, .value] | @tsv' "$spec_file")
assert_contains "$repo_root/docker/aws-mirror/Dockerfile" 'ARG UPSTREAM_IMAGE'
assert_not_contains "$repo_root/docker/aws-mirror/Dockerfile" '^ARG[[:space:]]+UPSTREAM_IMAGE='
assert_contains "$repo_root/docker/kafka/Dockerfile" 'jmx_prometheus_javaagent.jar'
assert_contains "$repo_root/docker/kafka/Dockerfile" "$(jq -r '.artifacts.jmxExporter.version' "$spec_file")"
assert_contains "$repo_root/docker/kafka/Dockerfile" "$(jq -r '.artifacts.jmxExporter.sha256' "$spec_file")"
assert_contains "$repo_root/docker/debezium/Dockerfile" 'jmx_prometheus_javaagent.jar'
assert_contains "$repo_root/docker/debezium/Dockerfile" "$(jq -r '.artifacts.jmxExporter.sha256' "$spec_file")"
assert_contains "$repo_root/docker/debezium/Dockerfile" "$(jq -r '.artifacts.debezium.version' "$spec_file")"
assert_contains "$repo_root/docker/debezium/Dockerfile" "$(jq -r '.artifacts.debezium.sha256' "$spec_file")"
assert_contains "$repo_root/docker/debezium/Dockerfile" 'sha256sum -c -'
assert_contains "$repo_root/docker/elasticsearch/Dockerfile" 'modules/repository-s3/plugin-descriptor.properties'
assert_contains "$repo_root/docker/elasticsearch/Dockerfile" 'analysis-nori'
assert_contains "$repo_root/docker/app/Dockerfile" 'COPY build/libs/airbob.jar app.jar'
assert_not_contains "$repo_root/docker/app/Dockerfile" 'COPY[[:space:]]+build/libs/\*\.jar'
assert_contains "$repo_root/docker/app/Dockerfile.dockerignore" '!build/libs/airbob.jar'
assert_not_contains "$repo_root/docker/app/Dockerfile.dockerignore" 'connector|\.env|\.git'
assert_contains "$repo_root/docker/debezium/Dockerfile.dockerignore" '!connect-distributed.properties'
assert_not_contains "$repo_root/docker/debezium/Dockerfile.dockerignore" 'connector-(local|oci)\.json'

for workflow in "$repo_root/.github/workflows/cd.yml" "$repo_root/.github/workflows/infra-images.yml"; do
  assert_contains "$workflow" 'id-token: write'
  assert_contains "$workflow" 'environment: aws-image-publisher'
  assert_contains "$workflow" 'role-to-assume: ${{ vars.AWS_IMAGE_PUBLISHER_ROLE_ARN }}'
  assert_contains "$workflow" 'infra/aws/scripts/publish-ecr-image.sh'
  assert_not_contains "$workflow" 'AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY|aws-access-key-id|aws-secret-access-key'
done
for workflow in "$repo_root/.github/workflows/"*.yml; do
  if grep -E '^[[:space:]]*uses:[[:space:]]*[^#[:space:]]+@' "$workflow" \
    | grep -Ev '@[0-9a-f]{40}([[:space:]]*#.*)?$' >/dev/null
  then
    fail "$workflow contains a GitHub Action that is not pinned by a full commit SHA"
  fi
done
assert_contains "$repo_root/.github/workflows/cd.yml" 'APP_IMAGE'
assert_contains "$repo_root/.github/workflows/infra-images.yml" 'cancel-in-progress: false'
assert_not_contains "$repo_root/.github/workflows/infra-images.yml" 'airbob-infra/[^[:space:]]*:latest'
assert_contains "$repo_root/.github/workflows/infra-images.yml" 'infra/aws/scripts/verify-published-image.sh'
assert_contains "$repo_root/.github/workflows/infra-images.yml" 'ghcr.io/${{ github.repository_owner }}/${{ matrix.image }}:latest'

sed -n '/^  deploy-oci:/,$p' "$repo_root/.github/workflows/cd.yml" > "$temp_dir/deploy-oci.yml"
assert_contains "$temp_dir/deploy-oci.yml" 'needs: publish-oci-image'
assert_not_contains "$temp_dir/deploy-oci.yml" 'publish-ecr-image|AWS_IMAGE_PUBLISHER_ROLE_ARN'
sed -n '/^  publish-oci-image:/,/^  publish-ecr-image:/p' "$repo_root/.github/workflows/cd.yml" > "$temp_dir/publish-oci.yml"
assert_contains "$temp_dir/publish-oci.yml" 'needs: build-app'
assert_not_contains "$temp_dir/publish-oci.yml" 'id-token: write|AWS_IMAGE_PUBLISHER_ROLE_ARN'
sed -n '/^  publish-ecr-images:/,/^  assemble-infra-release:/p' "$repo_root/.github/workflows/infra-images.yml" > "$temp_dir/publish-ecr.yml"
assert_contains "$temp_dir/publish-ecr.yml" "if: github.ref == 'refs/heads/main'"
sed -n '/^  publish-oci-compat-images:/,$p' "$repo_root/.github/workflows/infra-images.yml" > "$temp_dir/infra-oci.yml"
assert_contains "$temp_dir/infra-oci.yml" 'needs: prepare'
assert_contains "$temp_dir/infra-oci.yml" "if: github.ref == 'refs/heads/main'"
assert_not_contains "$temp_dir/infra-oci.yml" 'needs:.*publish-ecr-images'

fake_bin="$temp_dir/bin"
mkdir -p "$fake_bin" "$temp_dir/out"

cat > "$fake_bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >> "${FAKE_CALL_LOG:?}"
if [[ " $* " == *' ecr batch-get-image '* ]]; then
  if [[ "${FAKE_LOOKUP_MODE:-}" == error ]]; then
    exit 70
  fi
  if [[ "${FAKE_LOOKUP_MODE:-}" == empty ]]; then
    printf '{"images":[],"failures":[]}\n'
    exit 0
  fi
  if [[ "${FAKE_IMAGE_EXISTS:-false}" == true ]] \
    || grep -Fq 'docker buildx build' "${FAKE_CALL_LOG:?}"; then
    printf '{"images":[{"imageId":{"imageDigest":"sha256:%s"}}],"failures":[]}\n' "${FAKE_DIGEST_HEX:?}"
  else
    printf '{"images":[],"failures":[{"failureCode":"ImageNotFound"}]}\n'
  fi
  exit 0
fi
exit 64
EOF
chmod 700 "$fake_bin/aws"

cat > "$fake_bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker %s\n' "$*" >> "${FAKE_CALL_LOG:?}"
if [[ "${1:-}" == buildx && "${2:-}" == build ]]; then
  exit 0
fi
if [[ "${1:-}" == buildx && "${2:-}" == imagetools && "${3:-}" == inspect && "${4:-}" == --raw ]]; then
  if [[ "${FAKE_MISSING_ARM64:-false}" == true ]]; then
    printf '%s\n' '{"manifests":[{"platform":{"os":"linux","architecture":"amd64"}}]}'
  else
    printf '%s\n' '{"manifests":[{"platform":{"os":"linux","architecture":"amd64"}},{"platform":{"os":"linux","architecture":"arm64"}}]}'
  fi
  exit 0
fi
if [[ "${1:-}" == pull || "${1:-}" == run ]]; then
  exit 0
fi
exit 65
EOF
chmod 700 "$fake_bin/docker"

commit_sha=0123456789abcdef0123456789abcdef01234567
digest_hex=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
export FAKE_CALL_LOG="$temp_dir/calls.log"
export FAKE_DIGEST_HEX="$digest_hex"
export ECR_REGISTRY=942632789808.dkr.ecr.ap-northeast-2.amazonaws.com

: > "$FAKE_CALL_LOG"
PATH="$fake_bin:/opt/homebrew/bin:/usr/bin:/bin" \
  FAKE_IMAGE_EXISTS=true \
  "$publisher" REDIS_IMAGE "$commit_sha" "$temp_dir/out"
assert_contains "$temp_dir/out/REDIS_IMAGE.ref" "$ECR_REGISTRY/airbob-infra/redis@sha256:$digest_hex"
if grep -Fq 'docker buildx build' "$FAKE_CALL_LOG"; then
  fail "an existing immutable ECR tag must not be rebuilt or overwritten"
fi
assert_contains "$FAKE_CALL_LOG" 'docker buildx imagetools inspect --raw'

for lookup_mode in error empty; do
  : > "$FAKE_CALL_LOG"
  if PATH="$fake_bin:/opt/homebrew/bin:/usr/bin:/bin" \
    FAKE_IMAGE_EXISTS=false \
    FAKE_LOOKUP_MODE="$lookup_mode" \
    "$publisher" REDIS_IMAGE "$commit_sha" "$temp_dir/out" >/dev/null 2>&1
  then
    fail "publisher accepted an unsafe ECR lookup result: $lookup_mode"
  fi
  if grep -Fq 'docker buildx build' "$FAKE_CALL_LOG"; then
    fail "publisher rebuilt an immutable tag after an unsafe ECR lookup: $lookup_mode"
  fi
done

: > "$FAKE_CALL_LOG"
rm -f "$temp_dir/out/REDIS_IMAGE.ref"
PATH="$fake_bin:/opt/homebrew/bin:/usr/bin:/bin" \
  FAKE_IMAGE_EXISTS=false \
  "$publisher" REDIS_IMAGE "$commit_sha" "$temp_dir/out"
assert_contains "$FAKE_CALL_LOG" 'docker buildx build'
assert_contains "$temp_dir/out/REDIS_IMAGE.ref" "$ECR_REGISTRY/airbob-infra/redis@sha256:$digest_hex"

if PATH="$fake_bin:/opt/homebrew/bin:/usr/bin:/bin" \
  FAKE_IMAGE_EXISTS=true \
  "$publisher" REDIS_IMAGE short-sha "$temp_dir/out" >/dev/null 2>&1
then
  fail "publisher accepted a non-canonical commit tag"
fi
if PATH="$fake_bin:/opt/homebrew/bin:/usr/bin:/bin" \
  FAKE_IMAGE_EXISTS=true \
  "$publisher" UNKNOWN_IMAGE "$commit_sha" "$temp_dir/out" >/dev/null 2>&1
then
  fail "publisher accepted an image outside the ten-variable allowlist"
fi
if PATH="$fake_bin:/opt/homebrew/bin:/usr/bin:/bin" \
  FAKE_IMAGE_EXISTS=true \
  FAKE_MISSING_ARM64=true \
  "$publisher" REDIS_IMAGE "$commit_sha" "$temp_dir/out" >/dev/null 2>&1
then
  fail "publisher accepted an ECR manifest without both required platforms"
fi

for runtime_contract in \
  'KAFKA_IMAGE airbob-infra/kafka jmx_prometheus_javaagent.jar' \
  'DEBEZIUM_IMAGE airbob-infra/debezium debezium-connector-mysql' \
  'ELASTICSEARCH_IMAGE airbob-infra/elasticsearch repository-s3'
do
  read -r variable repository proof <<< "$runtime_contract"
  : > "$FAKE_CALL_LOG"
  PATH="$fake_bin:/opt/homebrew/bin:/usr/bin:/bin" \
    "$repo_root/infra/aws/scripts/verify-published-image.sh" \
      "$variable" "$ECR_REGISTRY/$repository@sha256:$digest_hex" >/dev/null
  assert_contains "$FAKE_CALL_LOG" "docker pull --platform linux/amd64 $ECR_REGISTRY/$repository@sha256:$digest_hex"
  assert_contains "$FAKE_CALL_LOG" 'docker run --rm --platform linux/amd64'
  assert_contains "$FAKE_CALL_LOG" "$proof"
done
: > "$FAKE_CALL_LOG"
PATH="$fake_bin:/opt/homebrew/bin:/usr/bin:/bin" \
  "$repo_root/infra/aws/scripts/verify-published-image.sh" \
    REDIS_IMAGE "$ECR_REGISTRY/airbob-infra/redis@sha256:$digest_hex" >/dev/null
assert_contains "$FAKE_CALL_LOG" 'docker pull --platform linux/amd64'
if grep -Fq 'docker run' "$FAKE_CALL_LOG"; then
  fail "a mirrored upstream image unexpectedly executed a custom runtime probe"
fi
if PATH="$fake_bin:/opt/homebrew/bin:/usr/bin:/bin" \
  "$repo_root/infra/aws/scripts/verify-published-image.sh" \
    KAFKA_IMAGE "$ECR_REGISTRY/airbob-infra/redis@sha256:$digest_hex" >/dev/null 2>&1
then
  fail "runtime verifier accepted an image from the wrong repository"
fi

app_dir="$temp_dir/app-release"
mkdir -p "$app_dir"
printf '%s\n' "$ECR_REGISTRY/airbob-repo@sha256:$digest_hex" > "$app_dir/APP_IMAGE.ref"
"$assembler" app "$commit_sha" "$app_dir" "$temp_dir/app-release.json"
jq -e --arg commit "$commit_sha" --arg ref "$ECR_REGISTRY/airbob-repo@sha256:$digest_hex" '
  .schemaVersion == 1 and .kind == "app" and .gitCommit == $commit and
  .images == {APP_IMAGE: $ref}
' "$temp_dir/app-release.json" >/dev/null || fail "app release manifest is invalid"

infra_dir="$temp_dir/infra-release"
mkdir -p "$infra_dir"
for variable in $(jq -r '.infra[].variable' "$spec_file"); do
  repository=$(jq -r --arg variable "$variable" '.infra[] | select(.variable == $variable) | .repository' "$spec_file")
  printf '%s\n' "$ECR_REGISTRY/$repository@sha256:$digest_hex" > "$infra_dir/$variable.ref"
done
"$assembler" infra "$commit_sha" "$infra_dir" "$temp_dir/infra-release.json"
jq -e --arg commit "$commit_sha" '
  .schemaVersion == 1 and .kind == "infra" and .gitCommit == $commit and
  (.images | keys | length) == 9
' "$temp_dir/infra-release.json" >/dev/null || fail "infra release manifest is invalid"

rm -f "$infra_dir/REDIS_IMAGE.ref"
if "$assembler" infra "$commit_sha" "$infra_dir" "$temp_dir/invalid.json" >/dev/null 2>&1; then
  fail "assembler accepted an incomplete infra release"
fi

printf '%s\n' 'immutable image publication tests passed'
