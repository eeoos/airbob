#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
packager="$repo_root/infra/aws/scripts/package-service-bundles.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-package-service-bundles.XXXXXX")

cleanup() {
  status=$?
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

[[ -x "$packager" ]] || fail "service bundle package script is missing or not executable"

expected_files="$temp_dir/expected-files.txt"
cat > "$expected_files" <<'EOF'
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
EOF

sha256_file() {
  target=$1
  if command -v sha256sum >/dev/null 2>&1; then
    checksum_output=$(sha256sum "$target")
  elif command -v shasum >/dev/null 2>&1; then
    checksum_output=$(shasum -a 256 "$target")
  else
    fail "no SHA-256 tool is available to the package test"
  fi
  printf '%s' "${checksum_output%% *}"
}

make_package_repo() {
  destination=$1
  mkdir -p "$destination/infra/aws/tests" "$destination/infra/aws/scripts" "$destination/monitoring"
  cp -R "$repo_root/infra/aws/bundles" "$destination/infra/aws/"
  cp "$repo_root/infra/aws/scripts/verify-service-bundle.sh" "$destination/infra/aws/scripts/verify-service-bundle.sh"
  cp "$packager" "$destination/infra/aws/scripts/package-service-bundles.sh"
  cp "$repo_root/infra/aws/tests/all-service-bundles-test.sh" "$destination/infra/aws/tests/all-service-bundles-test.sh"
  cp -R "$repo_root/infra/aws/tests/fixtures" "$destination/infra/aws/tests/"
  cp -R "$repo_root/monitoring/prometheus" "$destination/monitoring/"
  cp -R "$repo_root/monitoring/grafana" "$destination/monitoring/"
  git -C "$destination" init -q
  git -C "$destination" config user.name 'Airbob package test'
  git -C "$destination" config user.email 'package-test@example.invalid'
  git -C "$destination" add infra monitoring
  git -C "$destination" commit -q -m 'package fixture'
}

run_expect_failure() {
  description=$1
  output=$2
  shift 2
  if "$@" >"$output" 2>&1; then
    fail "expected $description to fail"
  fi
  if grep -Fq 'hunter2' "$output"; then
    fail "package failure output replayed a secret value"
  fi
}

assert_no_staging() {
  output_dir=$1
  for candidate in "$output_dir"/.airbob-service-bundles.*; do
    if [[ -e "$candidate" || -L "$candidate" ]]; then
      fail "package run left a private staging path behind"
    fi
  done
}

assert_no_release_artifacts() {
  output_dir=$1
  commit=$2
  for suffix in .tar.gz .tar.gz.sha256 .manifest.json; do
    candidate="$output_dir/airbob-service-bundles-$commit$suffix"
    [[ ! -e "$candidate" && ! -L "$candidate" ]] || fail "failed package run published a release artifact"
  done
}

base_repo="$temp_dir/repo"
make_package_repo "$base_repo"
existing_non_head_commit=$(git -C "$base_repo" rev-parse HEAD)
git -C "$base_repo" commit -q --allow-empty -m 'current package fixture head'
valid_commit=$(git -C "$base_repo" rev-parse HEAD)
[[ "$valid_commit" =~ ^[0-9a-f]{40}$ ]] || fail "fixture repository did not produce a full lower-case commit"

invalid_output="$temp_dir/invalid-output"
mkdir "$invalid_output"
chmod 700 "$invalid_output"
run_expect_failure 'an empty commit' "$temp_dir/empty-commit.log" "$base_repo/infra/aws/scripts/package-service-bundles.sh" '' "$invalid_output"
run_expect_failure 'a short commit' "$temp_dir/short-commit.log" "$base_repo/infra/aws/scripts/package-service-bundles.sh" deadbeef "$invalid_output"
run_expect_failure 'an upper-case commit' "$temp_dir/uppercase-commit.log" "$base_repo/infra/aws/scripts/package-service-bundles.sh" 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' "$invalid_output"
run_expect_failure 'a nonexistent commit' "$temp_dir/nonexistent-commit.log" "$base_repo/infra/aws/scripts/package-service-bundles.sh" 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$invalid_output"
run_expect_failure 'an existing commit that is not current HEAD' "$temp_dir/non-head-commit.log" \
  "$base_repo/infra/aws/scripts/package-service-bundles.sh" "$existing_non_head_commit" "$invalid_output"

unstaged_repo="$temp_dir/unstaged-source-repo"
cp -R "$base_repo" "$unstaged_repo"
printf '\n' >> "$unstaged_repo/infra/aws/bundles/kafka/jmx-exporter.yml"
unstaged_output="$temp_dir/unstaged-source-output"
mkdir "$unstaged_output"
run_expect_failure 'an unstaged packaged-source mismatch from HEAD' "$temp_dir/unstaged-source.log" \
  "$unstaged_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$unstaged_output"
assert_no_staging "$unstaged_output"

staged_repo="$temp_dir/staged-source-repo"
cp -R "$base_repo" "$staged_repo"
printf '\n' >> "$staged_repo/infra/aws/bundles/debezium/jmx-exporter.yml"
git -C "$staged_repo" add infra/aws/bundles/debezium/jmx-exporter.yml
staged_output="$temp_dir/staged-source-output"
mkdir "$staged_output"
run_expect_failure 'a staged packaged-source mismatch from HEAD' "$temp_dir/staged-source.log" \
  "$staged_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$staged_output"
assert_no_staging "$staged_output"

valid_output="$temp_dir/valid-output"
mkdir "$valid_output"
chmod 700 "$valid_output"
permissions=$(LC_ALL=C ls -ld "$valid_output" | cut -c1-10)
[[ "$permissions" == drwx------ ]] || fail "valid package output directory is not mode 0700"
unrelated_cwd="$temp_dir/unrelated-caller-directory"
mkdir "$unrelated_cwd"
(
  cd "$unrelated_cwd"
  "$base_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$valid_output"
) >"$temp_dir/valid-package.log" 2>&1 || fail "valid full commit did not package successfully from an unrelated caller directory"

archive_name="airbob-service-bundles-$valid_commit.tar.gz"
archive="$valid_output/$archive_name"
checksum="$archive.sha256"
release_manifest="$valid_output/airbob-service-bundles-$valid_commit.manifest.json"
[[ -f "$archive" && ! -L "$archive" ]] || fail "package archive was not published as a regular file"
[[ -f "$checksum" && ! -L "$checksum" ]] || fail "package checksum was not published as a regular file"
[[ -f "$release_manifest" && ! -L "$release_manifest" ]] || fail "package release manifest was not published as a regular file"

actual_digest=$(sha256_file "$archive")
IFS=' ' read -r checksum_digest checksum_archive < "$checksum"
[[ "$checksum_digest" == "$actual_digest" && "$checksum_archive" == "$archive_name" ]] || fail "published checksum does not attest the archive"

archive_listing="$temp_dir/archive-listing.txt"
tar -tzf "$archive" > "$archive_listing"
cmp -s "$expected_files" "$archive_listing" || fail "archive path entries do not equal the fixed nineteen-file allowlist"
line_count=$(wc -l < "$archive_listing" | tr -d '[:space:]')
[[ "$line_count" == 19 ]] || fail "archive does not contain exactly nineteen path entries"
while IFS= read -r archived_path || [[ -n "$archived_path" ]]; do
  case "/$archived_path/" in
    */runtime.env/*|*/.env/*|*.key/*|*.pem/*|*private*key*|*service-account*|*service_account*|*credential*.json/*|*/fixtures/*.env/*)
      fail "archive contains a forbidden secret-bearing path"
      ;;
  esac
done < "$archive_listing"

expected_release_manifest="$temp_dir/expected-release-manifest.json"
{
  printf '{\n'
  printf '  "schemaVersion": 1,\n'
  printf '  "commit": "%s",\n' "$valid_commit"
  printf '  "archive": "%s",\n' "$archive_name"
  printf '  "sha256": "%s",\n' "$actual_digest"
  printf '  "files": [\n'
  index=0
  while IFS= read -r expected_path || [[ -n "$expected_path" ]]; do
    index=$((index + 1))
    if [[ "$index" -lt 19 ]]; then
      printf '    "%s",\n' "$expected_path"
    else
      printf '    "%s"\n' "$expected_path"
    fi
  done < "$expected_files"
  printf '  ]\n'
  printf '}\n'
} > "$expected_release_manifest"
cmp -s "$expected_release_manifest" "$release_manifest" || fail "release manifest does not exactly bind commit, archive, digest, schema, and file list"
assert_no_staging "$valid_output"

for artifact_suffix in .tar.gz .tar.gz.sha256 .manifest.json; do
  overwrite_output="$temp_dir/preexisting-${artifact_suffix//[^a-zA-Z0-9]/_}"
  mkdir "$overwrite_output"
  chmod 700 "$overwrite_output"
  protected="$overwrite_output/airbob-service-bundles-$valid_commit$artifact_suffix"
  sentinel="$temp_dir/sentinel-${artifact_suffix//[^a-zA-Z0-9]/_}"
  printf 'must-not-be-overwritten\n' > "$protected"
  printf 'must-not-be-overwritten\n' > "$sentinel"
  run_expect_failure "pre-existing $artifact_suffix artifact" "$temp_dir/preexisting-${artifact_suffix//[^a-zA-Z0-9]/_}.log" \
    "$base_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$overwrite_output"
  cmp -s "$sentinel" "$protected" || fail "package run overwrote a pre-existing artifact"
  assert_no_staging "$overwrite_output"
done

symlink_output_target="$temp_dir/symlink-output-target"
mkdir "$symlink_output_target"
symlink_output="$temp_dir/symlink-output"
ln -s "$symlink_output_target" "$symlink_output"
run_expect_failure 'a symlink output directory' "$temp_dir/symlink-output.log" \
  "$base_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$symlink_output"

run_manifest_mutation() {
  mutation=$1
  mutation_repo="$temp_dir/manifest-$mutation"
  cp -R "$base_repo" "$mutation_repo"
  mutation_manifest="$mutation_repo/infra/aws/bundles/manifest.json"
  case "$mutation" in
    reordered-paths)
      sed \
        -e 's|infra/aws/bundles/app/compose.yml|AIRBOB_PATH_SWAP_MARKER|' \
        -e 's|infra/aws/bundles/app/required-runtime-env.txt|infra/aws/bundles/app/compose.yml|' \
        -e 's|AIRBOB_PATH_SWAP_MARKER|infra/aws/bundles/app/required-runtime-env.txt|' \
        "$mutation_manifest" > "$mutation_repo/manifest.tmp"
      ;;
    duplicated-path)
      sed 's|infra/aws/bundles/app/required-runtime-env.txt|infra/aws/bundles/app/compose.yml|' "$mutation_manifest" > "$mutation_repo/manifest.tmp"
      ;;
    removed-path)
      sed '/infra\/aws\/bundles\/app\/compose.yml/d' "$mutation_manifest" > "$mutation_repo/manifest.tmp"
      ;;
    extra-path)
      awk '/"infra\/aws\/bundles\/app\/compose.yml",/ { print; print "    \"infra/aws/bundles/app/extra.yml\","; next } { print }' \
        "$mutation_manifest" > "$mutation_repo/manifest.tmp"
      ;;
    absolute-path)
      sed 's|infra/aws/bundles/app/compose.yml|/etc/passwd|' "$mutation_manifest" > "$mutation_repo/manifest.tmp"
      ;;
    parent-path)
      sed 's|infra/aws/bundles/app/compose.yml|infra/aws/bundles/../secret|' "$mutation_manifest" > "$mutation_repo/manifest.tmp"
      ;;
    newline-path)
      sed 's|infra/aws/bundles/app/compose.yml|infra/aws/bundles/app/compose.yml\\nsecret|' "$mutation_manifest" > "$mutation_repo/manifest.tmp"
      ;;
    option-path)
      sed 's|infra/aws/bundles/app/compose.yml|--checkpoint-action=exec=sh|' "$mutation_manifest" > "$mutation_repo/manifest.tmp"
      ;;
    bundle-order)
      sed \
        -e 's|"app"|"AIRBOB_BUNDLE_SWAP_MARKER"|' \
        -e 's|"redis"|"app"|' \
        -e 's|"AIRBOB_BUNDLE_SWAP_MARKER"|"redis"|' \
        "$mutation_manifest" > "$mutation_repo/manifest.tmp"
      ;;
    image-order)
      sed \
        -e 's|"APP_IMAGE"|"AIRBOB_IMAGE_SWAP_MARKER"|' \
        -e 's|"REDIS_IMAGE"|"APP_IMAGE"|' \
        -e 's|"AIRBOB_IMAGE_SWAP_MARKER"|"REDIS_IMAGE"|' \
        "$mutation_manifest" > "$mutation_repo/manifest.tmp"
      ;;
    *)
      fail "unknown manifest mutation"
      ;;
  esac
  mv "$mutation_repo/manifest.tmp" "$mutation_manifest"
  mutation_output="$temp_dir/manifest-output-$mutation"
  mkdir "$mutation_output"
  chmod 700 "$mutation_output"
  run_expect_failure "the $mutation manifest mutation" "$temp_dir/manifest-$mutation.log" \
    "$mutation_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$mutation_output"
  assert_no_release_artifacts "$mutation_output" "$valid_commit"
  assert_no_staging "$mutation_output"
}

for mutation in \
  reordered-paths duplicated-path removed-path extra-path absolute-path \
  parent-path newline-path option-path bundle-order image-order
do
  run_manifest_mutation "$mutation"
done

leaf_symlink_repo="$temp_dir/leaf-symlink-repo"
cp -R "$base_repo" "$leaf_symlink_repo"
mv "$leaf_symlink_repo/infra/aws/bundles/app/compose.yml" "$leaf_symlink_repo/infra/aws/bundles/app/compose.real.yml"
ln -s compose.real.yml "$leaf_symlink_repo/infra/aws/bundles/app/compose.yml"
leaf_symlink_output="$temp_dir/leaf-symlink-output"
mkdir "$leaf_symlink_output"
run_expect_failure 'an allowlisted leaf symlink' "$temp_dir/leaf-symlink.log" \
  "$leaf_symlink_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$leaf_symlink_output"
assert_no_staging "$leaf_symlink_output"

parent_symlink_repo="$temp_dir/parent-symlink-repo"
cp -R "$base_repo" "$parent_symlink_repo"
mv "$parent_symlink_repo/monitoring/grafana/provisioning/datasources" "$parent_symlink_repo/monitoring/grafana/provisioning/datasources.real"
ln -s datasources.real "$parent_symlink_repo/monitoring/grafana/provisioning/datasources"
parent_symlink_output="$temp_dir/parent-symlink-output"
mkdir "$parent_symlink_output"
run_expect_failure 'an allowlisted symlinked parent' "$temp_dir/parent-symlink.log" \
  "$parent_symlink_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$parent_symlink_output"
assert_no_staging "$parent_symlink_output"

nonregular_repo="$temp_dir/nonregular-repo"
cp -R "$base_repo" "$nonregular_repo"
mv "$nonregular_repo/infra/aws/bundles/kafka/jmx-exporter.yml" "$nonregular_repo/infra/aws/bundles/kafka/jmx-exporter.real.yml"
mkdir "$nonregular_repo/infra/aws/bundles/kafka/jmx-exporter.yml"
nonregular_output="$temp_dir/nonregular-output"
mkdir "$nonregular_output"
run_expect_failure 'a non-regular allowlisted path' "$temp_dir/nonregular.log" \
  "$nonregular_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$nonregular_output"
assert_no_staging "$nonregular_output"

run_fake_tar_failure() {
  mode=$1
  fake_bin="$temp_dir/fake-bin-$mode"
  mkdir "$fake_bin"
  if [[ "$mode" == failure ]]; then
    cat > "$fake_bin/tar" <<'EOF'
#!/bin/sh
exit 73
EOF
  else
    cat > "$fake_bin/tar" <<'EOF'
#!/bin/sh
kill -TERM "$PPID"
sleep 1
exit 143
EOF
  fi
  chmod 700 "$fake_bin/tar"

  fake_output="$temp_dir/fake-output-$mode"
  mkdir "$fake_output"
  chmod 700 "$fake_output"
  printf 'preserve-me\n' > "$fake_output/sentinel"
  printf 'preserve-me\n' > "$temp_dir/fake-sentinel-$mode"
  run_expect_failure "a $mode tar run" "$temp_dir/fake-$mode.log" \
    env PATH="$fake_bin:$PATH" "$base_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$fake_output"
  cmp -s "$temp_dir/fake-sentinel-$mode" "$fake_output/sentinel" || fail "$mode run modified an unrelated output sentinel"
  assert_no_staging "$fake_output"
  assert_no_release_artifacts "$fake_output" "$valid_commit"
}

run_fake_tar_failure failure
run_fake_tar_failure interrupted

run_intermediate_publish_failure() {
  fail_on=$1
  fake_bin="$temp_dir/fake-ln-bin-$fail_on"
  mkdir "$fake_bin"
  cat > "$fake_bin/ln" <<'EOF'
#!/bin/sh
count=0
if [ -f "$AIRBOB_LN_COUNTER" ]; then
  IFS= read -r count < "$AIRBOB_LN_COUNTER"
fi
count=$((count + 1))
printf '%s\n' "$count" > "$AIRBOB_LN_COUNTER"
if [ "$count" -eq "$AIRBOB_LN_FAIL_ON" ]; then
  exit 75
fi
exec "$AIRBOB_REAL_LN" "$@"
EOF
  chmod 700 "$fake_bin/ln"

  publish_output="$temp_dir/intermediate-publish-output-$fail_on"
  mkdir "$publish_output"
  chmod 700 "$publish_output"
  printf 'preserve-me\n' > "$publish_output/unrelated-sentinel"
  printf 'preserve-me\n' > "$temp_dir/intermediate-sentinel-$fail_on"
  counter="$temp_dir/ln-counter-$fail_on"
  real_ln=$(command -v ln)
  run_expect_failure "link $fail_on publication failure" "$temp_dir/intermediate-publish-$fail_on.log" \
    env \
      PATH="$fake_bin:$PATH" \
      AIRBOB_LN_COUNTER="$counter" \
      AIRBOB_LN_FAIL_ON="$fail_on" \
      AIRBOB_REAL_LN="$real_ln" \
      "$base_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$publish_output"
  cmp -s "$temp_dir/intermediate-sentinel-$fail_on" "$publish_output/unrelated-sentinel" \
    || fail "intermediate publication rollback modified an unrelated sentinel"
  assert_no_staging "$publish_output"
  assert_no_release_artifacts "$publish_output" "$valid_commit"
}

run_intermediate_publish_failure 2
run_intermediate_publish_failure 3

printf 'service bundle package tests passed\n'
