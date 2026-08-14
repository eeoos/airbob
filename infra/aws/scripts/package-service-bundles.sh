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

archive_name="airbob-service-bundles-$commit.tar.gz"
checksum_name="$archive_name.sha256"
release_manifest_name="airbob-service-bundles-$commit.manifest.json"
git -C "$repo_root" diff --quiet "$commit" -- "${archive_files[@]}" \
  || fail "packaged source files do not match the requested HEAD commit"
for final_name in "$archive_name" "$checksum_name" "$release_manifest_name"; do
  final_path="$output_physical/$final_name"
  [[ ! -e "$final_path" && ! -L "$final_path" ]] || fail "refusing to overwrite a pre-existing package artifact"
done

bash "$repo_root/infra/aws/tests/all-service-bundles-test.sh" --validate-only >/dev/null

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
done

printf '%s\n' "${archive_files[@]}" > "$expected_listing"

COPYFILE_DISABLE=1 tar -C "$repo_root" -czf "$archive_path" "${archive_files[@]}"
COPYFILE_DISABLE=1 tar -tzf "$archive_path" > "$actual_listing"
cmp -s "$expected_listing" "$actual_listing" || fail "created archive does not contain exactly the fixed nineteen-file allowlist"

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

archive_digest=$(sha256_file "$archive_path")
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

published_archive=1
ln "$archive_path" "$archive_final" || fail "failed to publish archive without overwriting"
published_checksum=1
ln "$checksum_path" "$checksum_final" || fail "failed to publish checksum without overwriting"
published_release_manifest=1
ln "$release_manifest_path" "$release_manifest_final" || fail "failed to publish release manifest without overwriting"
publication_complete=1

printf 'packaged %s\n' "$archive_name"
