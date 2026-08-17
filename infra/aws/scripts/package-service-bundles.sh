#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

[[ "$#" -eq 2 ]] || fail "usage: package-service-bundles.sh <40-char-commit> <output-directory>"

commit=$1
output_dir=$2
commit_pattern='^[0-9a-f]{40}$'
[[ "$commit" =~ $commit_pattern ]] || fail "commit must be exactly 40 lower-case hexadecimal characters"

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
[[ -d "$repo_root/.git" || -f "$repo_root/.git" ]] || fail "repository root is not a Git worktree"
git -C "$repo_root" cat-file -e "${commit}^{commit}" 2>/dev/null || fail "commit does not exist in this repository"
head_commit=$(git -C "$repo_root" rev-parse --verify HEAD 2>/dev/null) || fail "current repository HEAD cannot be resolved"
[[ "$commit" == "$head_commit" ]] || fail "commit must equal the current repository HEAD"

[[ -d "$output_dir" && -w "$output_dir" && ! -L "$output_dir" ]] || fail "output directory must be a pre-existing writable non-symlink directory"
output_physical=$(CDPATH= cd -P -- "$output_dir" && pwd -P) || fail "output directory cannot be resolved physically"
[[ -d "$output_physical" && -w "$output_physical" && ! -L "$output_physical" ]] || fail "physical output directory is not writable or safe"

bundle_names=(app redis kafka debezium elasticsearch monitoring)
image_variables=(
  APP_IMAGE
  REDIS_IMAGE
  REDIS_EXPORTER_IMAGE
  NODE_EXPORTER_IMAGE
  KAFKA_IMAGE
  DEBEZIUM_IMAGE
  ELASTICSEARCH_IMAGE
  ELASTICSEARCH_EXPORTER_IMAGE
  PROMETHEUS_IMAGE
  GRAFANA_IMAGE
)
archive_files=(
  infra/aws/bundles/app/compose.yml
  infra/aws/bundles/app/required-runtime-env.txt
  infra/aws/bundles/redis/compose.yml
  infra/aws/bundles/kafka/compose.yml
  infra/aws/bundles/kafka/jmx-exporter.yml
  infra/aws/bundles/debezium/compose.yml
  infra/aws/bundles/debezium/connect-distributed.aws.properties
  infra/aws/bundles/debezium/connector.aws.json.tmpl
  infra/aws/bundles/debezium/jmx-exporter.yml
  infra/aws/bundles/elasticsearch/compose.yml
  infra/aws/bundles/monitoring/compose.yml
  monitoring/prometheus/prometheus.aws.yml
  monitoring/grafana/provisioning/datasources/prometheus.yml
  monitoring/grafana/provisioning/datasources/cloudwatch.aws.yml
  monitoring/grafana/provisioning/dashboards/airbob.yml
  monitoring/grafana/dashboards/airbob-coupon-issuance.json
  monitoring/grafana/dashboards/airbob-query-count.json
  monitoring/grafana/dashboards/airbob-redis.json
  monitoring/grafana/dashboards/airbob-spring-boot-statistics.json
)
validation_files=(
  infra/aws/tests/all-service-bundles-test.sh
  infra/aws/scripts/verify-service-bundle.sh
  infra/aws/tests/fixtures/images.env
  infra/aws/tests/fixtures/runtime.env
  infra/aws/bundles/manifest.json
)

archive_name="airbob-service-bundles-$commit.tar.gz"
checksum_name="$archive_name.sha256"
release_manifest_name="airbob-service-bundles-$commit.manifest.json"
for final_name in "$archive_name" "$checksum_name" "$release_manifest_name"; do
  final_path="$output_physical/$final_name"
  [[ ! -e "$final_path" && ! -L "$final_path" ]] || fail "refusing to overwrite a pre-existing package artifact"
done

staging_dir=''
staging_valid=0
cleanup_done=0
publication_complete=0
published_archive=0
published_checksum=0
published_release_manifest=0
archive_path=''
checksum_path=''
release_manifest_path=''
archive_final="$output_physical/$archive_name"
checksum_final="$output_physical/$checksum_name"
release_manifest_final="$output_physical/$release_manifest_name"

rollback_published_file() {
  published=$1
  final_path=$2
  staged_path=$3
  if [[ "$published" -eq 1 \
    && -n "$final_path" \
    && -n "$staged_path" \
    && -f "$final_path" \
    && ! -L "$final_path" \
    && -f "$staged_path" \
    && "$final_path" -ef "$staged_path" ]]
  then
    rm -f -- "$final_path" || true
  fi
}

cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  if [[ "$cleanup_done" -eq 0 ]]; then
    cleanup_done=1
    if [[ "$publication_complete" -eq 0 ]]; then
      rollback_published_file "$published_release_manifest" "$release_manifest_final" "$release_manifest_path"
      rollback_published_file "$published_checksum" "$checksum_final" "$checksum_path"
      rollback_published_file "$published_archive" "$archive_final" "$archive_path"
    fi
    if [[ "$staging_valid" -eq 1 && -n "$staging_dir" && -d "$staging_dir" && ! -L "$staging_dir" ]]; then
      case "$staging_dir" in
        "$output_physical"/.airbob-service-bundles.*)
          rm -rf -- "$staging_dir"
          ;;
      esac
    fi
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

staging_candidate=$(mktemp -d "$output_physical/.airbob-service-bundles.XXXXXX") || fail "failed to create private package staging directory"
chmod 700 "$staging_candidate"
staging_dir=$(CDPATH= cd -P -- "$staging_candidate" && pwd -P) || fail "failed to resolve package staging directory"
case "$staging_dir" in
  "$output_physical"/.airbob-service-bundles.*)
    [[ -d "$staging_dir" && ! -L "$staging_dir" ]] || fail "package staging directory is unsafe"
    staging_valid=1
    ;;
  *)
    fail "package staging directory escaped the output directory"
    ;;
esac

expected_manifest="$staging_dir/expected-manifest.json"
expected_listing="$staging_dir/expected-files.txt"
archive_path="$staging_dir/$archive_name"
checksum_path="$staging_dir/$checksum_name"
release_manifest_path="$staging_dir/$release_manifest_name"
actual_listing="$staging_dir/archive-files.txt"
actual_types="$staging_dir/archive-types.txt"
expected_types="$staging_dir/expected-types.txt"
commit_blob_root="$staging_dir/commit-blobs"
archive_extract_root="$staging_dir/archive-extract"
mkdir -p "$commit_blob_root" "$archive_extract_root"
chmod 700 "$commit_blob_root" "$archive_extract_root"

materialize_commit_file() {
  local relative_path=$1
  local target_mode=$2
  local tree_entry
  local commit_blob

  tree_entry=$(git -C "$repo_root" ls-tree "$commit" -- "$relative_path")
  case "$tree_entry" in
    "100644 blob "*$'\t'"$relative_path"|"100755 blob "*$'\t'"$relative_path")
      ;;
    *)
      fail "required path is not a regular file in the requested commit"
      ;;
  esac
  commit_blob="$commit_blob_root/$relative_path"
  mkdir -p "$commit_blob_root/${relative_path%/*}"
  git -C "$repo_root" cat-file blob "$commit:$relative_path" > "$commit_blob" \
    || fail "required blob cannot be read from the requested commit"
  chmod "$target_mode" "$commit_blob"
}

write_json_array() {
  array_name=$1
  shift
  values=("$@")
  printf '  "%s": [\n' "$array_name"
  last_index=$((${#values[@]} - 1))
  for index in "${!values[@]}"; do
    if [[ "$index" -lt "$last_index" ]]; then
      printf '    "%s",\n' "${values[$index]}"
    else
      printf '    "%s"\n' "${values[$index]}"
    fi
  done
  printf '  ]'
}

{
  printf '{\n'
  printf '  "schemaVersion": 1,\n'
  write_json_array bundles "${bundle_names[@]}"
  printf ',\n'
  write_json_array imageVariables "${image_variables[@]}"
  printf ',\n'
  write_json_array files "${archive_files[@]}"
  printf '\n}\n'
} > "$expected_manifest"

manifest_relative=infra/aws/bundles/manifest.json
manifest_path="$repo_root/$manifest_relative"
[[ -f "$manifest_path" && -r "$manifest_path" && ! -L "$manifest_path" ]] || fail "repository bundle manifest is missing or unsafe"
manifest_parent_expected="$repo_root/${manifest_relative%/*}"
manifest_parent_physical=$(CDPATH= cd -P -- "$manifest_parent_expected" && pwd -P) || fail "repository manifest parent cannot be resolved physically"
[[ "$manifest_parent_physical" == "$manifest_parent_expected" ]] || fail "repository manifest has a symlinked parent"
cmp -s "$expected_manifest" "$manifest_path" || fail "repository bundle manifest does not match the canonical fixed arrays"

for relative_path in "${archive_files[@]}"; do
  [[ -n "$relative_path" ]] || fail "archive allowlist contains an empty path"
  [[ "$relative_path" != *$'\n'* && "$relative_path" != *$'\r'* ]] || fail "archive allowlist contains a newline-bearing path"
  case "$relative_path" in
    /*|-*)
      fail "archive allowlist contains an absolute or option-like path"
      ;;
  esac
  case "/$relative_path/" in
    */../*)
      fail "archive allowlist contains a parent-directory component"
      ;;
  esac

  source_path="$repo_root/$relative_path"
  [[ -f "$source_path" && -r "$source_path" && ! -L "$source_path" ]] || fail "archive allowlist path is not a readable non-symlink regular file"
  parent_relative=${relative_path%/*}
  parent_expected="$repo_root/$parent_relative"
  parent_physical=$(CDPATH= cd -P -- "$parent_expected" && pwd -P) || fail "archive allowlist parent cannot be resolved physically"
  [[ "$parent_physical" == "$parent_expected" ]] || fail "archive allowlist path has a symlinked parent"

  materialize_commit_file "$relative_path" 600
  commit_blob="$commit_blob_root/$relative_path"
  cmp -s "$source_path" "$commit_blob" \
    || fail "archive allowlist source bytes do not match the requested commit"
done

for relative_path in "${validation_files[@]}"; do
  case "$relative_path" in
    *.sh) validation_mode=700 ;;
    *) validation_mode=600 ;;
  esac
  materialize_commit_file "$relative_path" "$validation_mode"
done

bash "$commit_blob_root/infra/aws/tests/all-service-bundles-test.sh" \
  --validate-only >/dev/null

printf '%s\n' "${archive_files[@]}" > "$expected_listing"
for relative_path in "${archive_files[@]}"; do
  printf '%s\n' '-'
done > "$expected_types"

sha256_file() {
  checksum_target=$1
  if command -v sha256sum >/dev/null 2>&1; then
    checksum_output=$(sha256sum "$checksum_target")
  elif command -v shasum >/dev/null 2>&1; then
    checksum_output=$(shasum -a 256 "$checksum_target")
  else
    fail "no supported SHA-256 command is available"
  fi
  checksum_digest=${checksum_output%% *}
  [[ "$checksum_digest" =~ ^[0-9a-f]{64}$ ]] || fail "SHA-256 command returned an invalid digest"
  printf '%s' "$checksum_digest"
}

COPYFILE_DISABLE=1 tar -C "$repo_root" -czf "$archive_path" "${archive_files[@]}"
COPYFILE_DISABLE=1 tar -tzf "$archive_path" > "$actual_listing"
cmp -s "$expected_listing" "$actual_listing" || fail "created archive does not contain exactly the fixed nineteen-file allowlist"
archive_digest=$(sha256_file "$archive_path")

COPYFILE_DISABLE=1 tar -tvzf "$archive_path" | awk '{ print substr($1, 1, 1) }' > "$actual_types"
cmp -s "$expected_types" "$actual_types" || fail "created archive contains a non-regular member type"
COPYFILE_DISABLE=1 tar -xzf "$archive_path" -C "$archive_extract_root"
for relative_path in "${archive_files[@]}"; do
  extracted_path="$archive_extract_root/$relative_path"
  [[ -f "$extracted_path" && -r "$extracted_path" && ! -L "$extracted_path" ]] \
    || fail "created archive did not extract an expected regular file"
  extracted_parent_expected="$archive_extract_root/${relative_path%/*}"
  extracted_parent_physical=$(CDPATH= cd -P -- "$extracted_parent_expected" && pwd -P) \
    || fail "created archive member parent cannot be resolved physically"
  [[ "$extracted_parent_physical" == "$extracted_parent_expected" ]] \
    || fail "created archive member has a symlinked parent"
  cmp -s "$extracted_path" "$commit_blob_root/$relative_path" \
    || fail "created archive member bytes do not match the requested commit"
done

post_extract_digest=$(sha256_file "$archive_path")
[[ "$post_extract_digest" == "$archive_digest" ]] || fail "archive changed during commit-byte verification"
archive_digest=$post_extract_digest
printf '%s  %s\n' "$archive_digest" "$archive_name" > "$checksum_path"

{
  printf '{\n'
  printf '  "schemaVersion": 1,\n'
  printf '  "commit": "%s",\n' "$commit"
  printf '  "archive": "%s",\n' "$archive_name"
  printf '  "sha256": "%s",\n' "$archive_digest"
  write_json_array files "${archive_files[@]}"
  printf '\n}\n'
} > "$release_manifest_path"

recalculated_digest=$(sha256_file "$archive_path")
IFS=' ' read -r checksum_digest checksum_archive < "$checksum_path"
release_digest=$(sed -n 's/^  "sha256": "\([0-9a-f][0-9a-f]*\)",$/\1/p' "$release_manifest_path")
[[ "$release_digest" =~ ^[0-9a-f]{64}$ ]] || fail "release manifest SHA-256 is missing or invalid"
[[ "$recalculated_digest" == "$archive_digest" ]] || fail "archive changed after checksum creation"
[[ "$checksum_digest" == "$recalculated_digest" && "$checksum_archive" == "$archive_name" ]] || fail "checksum file does not attest the staged archive"
[[ "$release_digest" == "$recalculated_digest" ]] || fail "release manifest does not attest the staged archive"
COPYFILE_DISABLE=1 tar -tzf "$archive_path" > "$actual_listing"
cmp -s "$expected_listing" "$actual_listing" || fail "archive listing changed before publication"

head_commit_before_publish=$(git -C "$repo_root" rev-parse --verify HEAD 2>/dev/null) \
  || fail "current repository HEAD cannot be resolved before publication"
[[ "$head_commit_before_publish" == "$commit" ]] \
  || fail "repository HEAD changed before package publication"
for relative_path in "${archive_files[@]}"; do
  source_path="$repo_root/$relative_path"
  [[ -f "$source_path" && -r "$source_path" && ! -L "$source_path" ]] \
    || fail "archive allowlist source became unsafe before publication"
  source_parent_expected="$repo_root/${relative_path%/*}"
  source_parent_physical=$(CDPATH= cd -P -- "$source_parent_expected" && pwd -P) \
    || fail "archive allowlist parent cannot be resolved before publication"
  [[ "$source_parent_physical" == "$source_parent_expected" ]] \
    || fail "archive allowlist source gained a symlinked parent before publication"
  cmp -s "$source_path" "$commit_blob_root/$relative_path" \
    || fail "archive allowlist source bytes changed before publication"
done

published_archive=1
ln "$archive_path" "$archive_final" || fail "failed to publish archive without overwriting"
published_checksum=1
ln "$checksum_path" "$checksum_final" || fail "failed to publish checksum without overwriting"
published_release_manifest=1
ln "$release_manifest_path" "$release_manifest_final" || fail "failed to publish release manifest without overwriting"
publication_complete=1

printf 'packaged %s\n' "$archive_name"
