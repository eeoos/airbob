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

run_expect_signal_143() {
  description=$1
  output=$2
  pid_file=$3
  shift 3
  "$@" >"$output" 2>&1 &
  package_pid=$!
  printf '%s\n' "$package_pid" > "$pid_file"
  set +e
  wait "$package_pid"
  signal_exit=$?
  set -e
  [[ "$signal_exit" -eq 143 ]] || fail "$description exited $signal_exit instead of 143"
  if grep -Fq 'hunter2' "$output"; then
    fail "$description replayed a secret value"
  fi
}

assert_java_properties_key() {
  properties_file=$1
  expected_key=$2
  probe_source="$temp_dir/PropertiesKeyProbe.java"
  if [[ ! -f "$probe_source" ]]; then
    cat > "$probe_source" <<'EOF'
import java.io.FileInputStream;
import java.util.Properties;

class PropertiesKeyProbe {
  public static void main(String[] args) throws Exception {
    Properties properties = new Properties();
    try (FileInputStream input = new FileInputStream(args[0])) {
      properties.load(input);
    }
    if (!properties.containsKey(args[1])) {
      System.exit(1);
    }
  }
}
EOF
  fi
  java "$probe_source" "$properties_file" "$expected_key" >/dev/null 2>&1 \
    || fail "Java Properties package mutation did not reconstruct the expected key"
}

write_app_ls_password_mutation() {
  compose_file=$1
  mutated_file="$compose_file.tmp"
  replaced=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$replaced" -eq 0 && "$line" == '      JAVA_OPTS:'* ]]; then
      java_opts=${line#'      JAVA_OPTS: '}
      printf '      - JAVA_OPTS=%s\n' "$java_opts"
      printf '      - "PASSW\\\342\200\250ORD=hunter2"\n'
      replaced=1
    else
      printf '%s\n' "$line"
    fi
  done < "$compose_file" > "$mutated_file"
  [[ "$replaced" -eq 1 ]] || fail "committed LS mutation target was not found"
  mv "$mutated_file" "$compose_file"
}

assert_compose_password_key() {
  compose_file=$1
  canonical_config=$(COMPOSE_PROFILES= docker compose \
    --env-file "$(dirname -- "$compose_file")/../../tests/fixtures/images.env" \
    -f "$compose_file" config 2>/dev/null) \
    || fail "committed LS mutation canonical precondition failed"
  canonical_password_count=$(printf '%s\n' "$canonical_config" | LC_ALL=C awk '
    $1 == "PASSWORD:" { count++ }
    END { print count + 0 }
  ')
  [[ "$canonical_password_count" -eq 1 ]] \
    || fail "committed LS mutation did not resolve one sensitive key"
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

skip_worktree_repo="$temp_dir/skip-worktree-repo"
cp -R "$base_repo" "$skip_worktree_repo"
git -C "$skip_worktree_repo" update-index --skip-worktree infra/aws/bundles/kafka/jmx-exporter.yml
printf '\n# PACKAGED_SKIP_WORKTREE_DRIFT=yes\n' \
  >> "$skip_worktree_repo/infra/aws/bundles/kafka/jmx-exporter.yml"
skip_worktree_output="$temp_dir/skip-worktree-output"
mkdir "$skip_worktree_output"
run_expect_failure 'skip-worktree packaged-source bytes that differ from HEAD' "$temp_dir/skip-worktree.log" \
  "$skip_worktree_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$skip_worktree_output"
assert_no_staging "$skip_worktree_output"
assert_no_release_artifacts "$skip_worktree_output" "$valid_commit"

run_committed_secret_failure() {
  mutation=$1
  description=$2
  committed_secret_repo="$temp_dir/committed-$mutation-secret-repo"
  cp -R "$base_repo" "$committed_secret_repo"
  case "$mutation" in
    list)
      cat >> "$committed_secret_repo/infra/aws/bundles/app/compose.yml" <<'EOF'

x-airbob-probe:
  environment:
    - PASSWORD=hunter2
EOF
      ;;
    inline)
      printf '\nx-airbob-probe: {environment: {PASSWORD: hunter2}}\n' \
        >> "$committed_secret_repo/infra/aws/bundles/app/compose.yml"
      ;;
    access-key)
      printf '\nAWS_ACCESS_KEY_ID=hunter2\n' \
        >> "$committed_secret_repo/infra/aws/bundles/debezium/connect-distributed.aws.properties"
      ;;
    private-key)
      cat >> "$committed_secret_repo/infra/aws/bundles/debezium/connect-distributed.aws.properties" <<'EOF'

PRIVATE_KEY=hunter2
-----BEGIN PRIVATE KEY-----
hunter2
-----END PRIVATE KEY-----
EOF
      ;;
    unicode-key)
      awk '
        { print }
        index($0, "\"database.password\"") && index($0, "DEBEZIUM_PASSWORD") {
          print "    \"database.pass\\u0077ord\": \"hunter2\","
        }
      ' "$committed_secret_repo/infra/aws/bundles/debezium/connector.aws.json.tmpl" \
        > "$committed_secret_repo/connector.tmp"
      mv "$committed_secret_repo/connector.tmp" \
        "$committed_secret_repo/infra/aws/bundles/debezium/connector.aws.json.tmpl"
      ;;
    continued-key)
      awk '
        /^    environment:[[:space:]]*$/ {
          in_app_environment = 1
          print
          next
        }
        in_app_environment && /^      JAVA_OPTS:/ {
          value = $0
          sub(/^      JAVA_OPTS:[[:space:]]*/, "", value)
          print "      - JAVA_OPTS=" value
          print "      - \"PASSW\\"
          print "        ORD=hunter2\""
          in_app_environment = 0
          next
        }
        { print }
      ' "$committed_secret_repo/infra/aws/bundles/app/compose.yml" \
        > "$committed_secret_repo/app.tmp"
      mv "$committed_secret_repo/app.tmp" \
        "$committed_secret_repo/infra/aws/bundles/app/compose.yml"
      canonical_config=$(COMPOSE_PROFILES= docker compose \
        --env-file "$committed_secret_repo/infra/aws/tests/fixtures/images.env" \
        -f "$committed_secret_repo/infra/aws/bundles/app/compose.yml" \
        config 2>/dev/null) \
        || fail "committed backslash-continuation precondition failed"
      canonical_password_count=$(printf '%s\n' "$canonical_config" | awk '
        $1 == "PASSWORD:" { count++ }
        END { print count + 0 }
      ')
      [[ "$canonical_password_count" -eq 1 ]] \
        || fail "committed backslash continuation did not resolve one sensitive key"
      ;;
    properties-key)
      printf '\nPASS\\WORD=hunter2\n' \
        >> "$committed_secret_repo/infra/aws/bundles/debezium/connect-distributed.aws.properties"
      assert_java_properties_key \
        "$committed_secret_repo/infra/aws/bundles/debezium/connect-distributed.aws.properties" \
        PASSWORD
      ;;
    ls-key)
      write_app_ls_password_mutation \
        "$committed_secret_repo/infra/aws/bundles/app/compose.yml"
      assert_compose_password_key \
        "$committed_secret_repo/infra/aws/bundles/app/compose.yml"
      ;;
    *)
      fail "unknown committed secret mutation"
      ;;
  esac
  git -C "$committed_secret_repo" add infra/aws/bundles
  git -C "$committed_secret_repo" commit -q -m "synthetic committed $mutation secret"
  committed_secret_commit=$(git -C "$committed_secret_repo" rev-parse HEAD)
  committed_secret_output="$temp_dir/committed-$mutation-secret-output"
  mkdir "$committed_secret_output"
  run_expect_failure "$description" "$temp_dir/committed-$mutation-secret.log" \
    "$committed_secret_repo/infra/aws/scripts/package-service-bundles.sh" \
    "$committed_secret_commit" "$committed_secret_output"
  assert_no_staging "$committed_secret_output"
  assert_no_release_artifacts "$committed_secret_output" "$committed_secret_commit"
}

run_committed_secret_failure list 'a committed Compose list-form secret'
run_committed_secret_failure inline 'a committed inline YAML secret'
run_committed_secret_failure access-key 'a committed access-key assignment'
run_committed_secret_failure private-key 'committed private-key material'
run_committed_secret_failure unicode-key 'a committed Unicode-escaped duplicate password key'
run_committed_secret_failure continued-key 'a committed backslash-continued password key'
run_committed_secret_failure properties-key 'a committed Java Properties escaped password key'
run_committed_secret_failure ls-key 'a committed LS-spliced password key'

dirty_validator_repo="$temp_dir/dirty-validator-repo"
cp -R "$base_repo" "$dirty_validator_repo"
printf '\npassword: hunter2\n' \
  >> "$dirty_validator_repo/infra/aws/bundles/app/compose.yml"
git -C "$dirty_validator_repo" add infra/aws/bundles/app/compose.yml
git -C "$dirty_validator_repo" commit -q -m 'synthetic committed secret for dirty validator'
dirty_validator_commit=$(git -C "$dirty_validator_repo" rev-parse HEAD)
cat > "$dirty_validator_repo/infra/aws/tests/all-service-bundles-test.sh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod 755 "$dirty_validator_repo/infra/aws/tests/all-service-bundles-test.sh"
dirty_validator_output="$temp_dir/dirty-validator-output"
mkdir "$dirty_validator_output"
run_expect_failure 'a dirty validator blessing an invalid HEAD bundle' \
  "$temp_dir/dirty-validator.log" \
  "$dirty_validator_repo/infra/aws/scripts/package-service-bundles.sh" \
  "$dirty_validator_commit" "$dirty_validator_output"
assert_no_staging "$dirty_validator_output"
assert_no_release_artifacts "$dirty_validator_output" "$dirty_validator_commit"

committed_alias_repo="$temp_dir/committed-profile-alias-repo"
cp -R "$base_repo" "$committed_alias_repo"
awk '
  NR == 1 {
    print "x-airbob-profile-extra: &airbob-profile-extra"
    print "  image: ${APP_IMAGE:?APP_IMAGE is required}"
    print "  profiles: [hidden]"
    print ""
  }
  { print }
  END {
    print ""
    print "  app-shadow: *airbob-profile-extra"
  }
' "$committed_alias_repo/infra/aws/bundles/app/compose.yml" \
  > "$committed_alias_repo/app.tmp"
mv "$committed_alias_repo/app.tmp" \
  "$committed_alias_repo/infra/aws/bundles/app/compose.yml"
default_alias_services=$(COMPOSE_PROFILES= docker compose \
  --env-file "$committed_alias_repo/infra/aws/tests/fixtures/images.env" \
  -f "$committed_alias_repo/infra/aws/bundles/app/compose.yml" \
  config --services 2>/dev/null) \
  || fail "committed profile alias default-view precondition failed"
wildcard_alias_services=$(COMPOSE_PROFILES= docker compose --profile '*' \
  --env-file "$committed_alias_repo/infra/aws/tests/fixtures/images.env" \
  -f "$committed_alias_repo/infra/aws/bundles/app/compose.yml" \
  config --services 2>/dev/null) \
  || fail "committed profile alias wildcard-view precondition failed"
default_alias_count=$(printf '%s\n' "$default_alias_services" | awk '
  $0 == "app-shadow" { count++ }
  END { print count + 0 }
')
wildcard_alias_count=$(printf '%s\n' "$wildcard_alias_services" | awk '
  $0 == "app-shadow" { count++ }
  END { print count + 0 }
')
[[ "$default_alias_count" -eq 0 && "$wildcard_alias_count" -eq 1 ]] \
  || fail "committed profile alias did not exercise both canonical views"
git -C "$committed_alias_repo" add infra/aws/bundles/app/compose.yml
git -C "$committed_alias_repo" commit -q -m 'synthetic committed profile alias'
committed_alias_commit=$(git -C "$committed_alias_repo" rev-parse HEAD)
committed_alias_output="$temp_dir/committed-profile-alias-output"
mkdir "$committed_alias_output"
run_expect_failure 'a committed profile-hidden alias service' \
  "$temp_dir/committed-profile-alias.log" \
  "$committed_alias_repo/infra/aws/scripts/package-service-bundles.sh" \
  "$committed_alias_commit" "$committed_alias_output"
assert_no_staging "$committed_alias_output"
assert_no_release_artifacts "$committed_alias_output" "$committed_alias_commit"

run_committed_resolved_contract_failure() {
  mutation=$1
  description=$2
  committed_contract_repo="$temp_dir/committed-$mutation-contract-repo"
  cp -R "$base_repo" "$committed_contract_repo"
  redis_compose="$committed_contract_repo/infra/aws/bundles/redis/compose.yml"
  case "$mutation" in
    image-anchor)
      awk '
        NR == 1 {
          print "x-airbob-wrong-image: &airbob-wrong-image"
          print "  image: ${REDIS_EXPORTER_IMAGE:?REDIS_EXPORTER_IMAGE is required}"
          print ""
        }
        /^  redis:[[:space:]]*$/ {
          print
          print "    <<: *airbob-wrong-image"
          print "    x-airbob-declared-image:"
          in_redis = 1
          next
        }
        in_redis && /image: \$\{REDIS_IMAGE:/ {
          print "      image: ${REDIS_IMAGE:?REDIS_IMAGE is required}"
          in_redis = 0
          next
        }
        { print }
      ' "$redis_compose" > "$committed_contract_repo/redis.tmp"
      mv "$committed_contract_repo/redis.tmp" "$redis_compose"
      canonical_redis_config=$(COMPOSE_PROFILES= docker compose \
        --env-file "$committed_contract_repo/infra/aws/tests/fixtures/images.env" \
        -f "$redis_compose" config 2>/dev/null) \
        || fail "committed image override canonical precondition failed"
      canonical_redis_image=$(printf '%s\n' "$canonical_redis_config" | LC_ALL=C awk '
        /^services:[[:space:]]*$/ { in_services = 1; next }
        in_services && /^[^[:space:]]/ { exit }
        in_services && /^  redis:[[:space:]]*$/ { in_redis = 1; next }
        in_redis && /^  [a-zA-Z0-9][a-zA-Z0-9_-]*:[[:space:]]*$/ { exit }
        in_redis && /^    image:[[:space:]]*/ {
          value = $0
          sub(/^    image:[[:space:]]*/, "", value)
          print value
          exit
        }
      ')
      [[ "$canonical_redis_image" == 'registry.example.invalid/airbob/redis-exporter@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc' ]] \
        || fail "committed image override did not change the canonical service image"
      ;;
    scale-zero)
      awk '
        { print }
        /^  redis-exporter-cache:[[:space:]]*$/ { print "    scale: 0" }
      ' "$redis_compose" > "$committed_contract_repo/redis.tmp"
      mv "$committed_contract_repo/redis.tmp" "$redis_compose"
      canonical_redis_config=$(COMPOSE_PROFILES= docker compose \
        --env-file "$committed_contract_repo/infra/aws/tests/fixtures/images.env" \
        -f "$redis_compose" config 2>/dev/null) \
        || fail "committed scale-zero canonical precondition failed"
      printf '%s\n' "$canonical_redis_config" | LC_ALL=C awk '
        /^  redis-exporter-cache:[[:space:]]*$/ { in_target = 1; next }
        in_target && /^  [a-zA-Z0-9][a-zA-Z0-9_-]*:[[:space:]]*$/ { exit 1 }
        in_target && /^    scale:[[:space:]]*0[[:space:]]*$/ { found = 1 }
        END { exit(found ? 0 : 1) }
      ' || fail "committed scale-zero mutation did not resolve the requested cardinality"
      ;;
    *)
      fail "unknown committed resolved-contract mutation"
      ;;
  esac
  git -C "$committed_contract_repo" add infra/aws/bundles/redis/compose.yml
  git -C "$committed_contract_repo" commit -q -m "synthetic committed $mutation contract"
  committed_contract_commit=$(git -C "$committed_contract_repo" rev-parse HEAD)
  committed_contract_output="$temp_dir/committed-$mutation-contract-output"
  mkdir "$committed_contract_output"
  run_expect_failure "$description" "$temp_dir/committed-$mutation-contract.log" \
    "$committed_contract_repo/infra/aws/scripts/package-service-bundles.sh" \
    "$committed_contract_commit" "$committed_contract_output"
  assert_no_staging "$committed_contract_output"
  assert_no_release_artifacts "$committed_contract_output" "$committed_contract_commit"
}

run_committed_resolved_contract_failure \
  image-anchor 'a committed service image changed through a YAML merge anchor'
run_committed_resolved_contract_failure \
  scale-zero 'a committed required exporter scaled to zero'

concurrent_repo="$temp_dir/concurrent-source-repo"
cp -R "$base_repo" "$concurrent_repo"
concurrent_output="$temp_dir/concurrent-source-output"
mkdir "$concurrent_output"
fake_concurrent_bin="$temp_dir/fake-concurrent-bin"
mkdir "$fake_concurrent_bin"
cat > "$fake_concurrent_bin/tar" <<'EOF'
#!/bin/sh
case " $* " in
  *" -czf "*)
    if [ ! -e "$AIRBOB_TAR_MUTATION_MARKER" ]; then
      printf '\n# PACKAGED_TAR_TIME_DRIFT=yes\n' >> "$AIRBOB_TAR_MUTATION_SOURCE"
      : > "$AIRBOB_TAR_MUTATION_MARKER"
    fi
    ;;
esac
exec "$AIRBOB_REAL_TAR" "$@"
EOF
chmod 700 "$fake_concurrent_bin/tar"
real_tar=$(command -v tar)
run_expect_failure 'a source mutation during tar creation' "$temp_dir/concurrent-source.log" \
  env \
    PATH="$fake_concurrent_bin:$PATH" \
    AIRBOB_REAL_TAR="$real_tar" \
    AIRBOB_TAR_MUTATION_MARKER="$temp_dir/concurrent-tar-marker" \
    AIRBOB_TAR_MUTATION_SOURCE="$concurrent_repo/infra/aws/bundles/kafka/jmx-exporter.yml" \
    "$concurrent_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$concurrent_output"
[[ -f "$temp_dir/concurrent-tar-marker" ]] || fail "tar-time source mutation hook was not reached"
assert_no_staging "$concurrent_output"
assert_no_release_artifacts "$concurrent_output" "$valid_commit"

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
  fake_bin="$temp_dir/fake-bin-failure"
  mkdir "$fake_bin"
  cat > "$fake_bin/tar" <<'EOF'
#!/bin/sh
exit 73
EOF
  chmod 700 "$fake_bin/tar"

  fake_output="$temp_dir/fake-output-failure"
  mkdir "$fake_output"
  chmod 700 "$fake_output"
  printf 'preserve-me\n' > "$fake_output/sentinel"
  printf 'preserve-me\n' > "$temp_dir/fake-sentinel-failure"
  run_expect_failure 'a failing tar run' "$temp_dir/fake-failure.log" \
    env PATH="$fake_bin:$PATH" "$base_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$fake_output"
  cmp -s "$temp_dir/fake-sentinel-failure" "$fake_output/sentinel" || fail "failing tar run modified an unrelated output sentinel"
  assert_no_staging "$fake_output"
  assert_no_release_artifacts "$fake_output" "$valid_commit"
}

run_fake_tar_failure

run_signal_before_publication() {
  fake_bin="$temp_dir/fake-signal-tar-bin"
  mkdir "$fake_bin"
  cat > "$fake_bin/tar" <<'EOF'
#!/bin/sh
printf 'reached\n' > "$AIRBOB_SIGNAL_MARKER"
while [ ! -s "$AIRBOB_PACKAGE_PID_FILE" ]; do
  sleep 1
done
IFS= read -r package_pid < "$AIRBOB_PACKAGE_PID_FILE"
kill -TERM "$package_pid"
exit 143
EOF
  chmod 700 "$fake_bin/tar"

  signal_output="$temp_dir/signal-before-publication-output"
  mkdir "$signal_output"
  chmod 700 "$signal_output"
  printf 'preserve-me\n' > "$signal_output/unrelated-sentinel"
  printf 'preserve-me\n' > "$temp_dir/signal-before-publication-sentinel"
  pid_file="$temp_dir/signal-before-publication.pid"
  marker="$temp_dir/signal-before-publication.marker"
  run_expect_signal_143 'pre-publication signal run' "$temp_dir/signal-before-publication.log" "$pid_file" \
    env \
      PATH="$fake_bin:$PATH" \
      AIRBOB_PACKAGE_PID_FILE="$pid_file" \
      AIRBOB_SIGNAL_MARKER="$marker" \
      "$base_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$signal_output"
  [[ -f "$marker" ]] || fail "pre-publication signal hook was not reached"
  cmp -s "$temp_dir/signal-before-publication-sentinel" "$signal_output/unrelated-sentinel" \
    || fail "pre-publication signal run modified an unrelated sentinel"
  assert_no_staging "$signal_output"
  assert_no_release_artifacts "$signal_output" "$valid_commit"
}

run_signal_before_publication

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

run_signal_after_publication_links() {
  signal_after=$1
  fake_bin="$temp_dir/fake-signal-ln-bin-$signal_after"
  mkdir "$fake_bin"
  cat > "$fake_bin/ln" <<'EOF'
#!/bin/sh
count=0
if [ -f "$AIRBOB_LN_COUNTER" ]; then
  IFS= read -r count < "$AIRBOB_LN_COUNTER"
fi
count=$((count + 1))
printf '%s\n' "$count" > "$AIRBOB_LN_COUNTER"
"$AIRBOB_REAL_LN" "$@" || exit $?
if [ "$count" -eq "$AIRBOB_SIGNAL_AFTER_LINK" ]; then
  printf 'reached\n' > "$AIRBOB_SIGNAL_MARKER"
  while [ ! -s "$AIRBOB_PACKAGE_PID_FILE" ]; do
    sleep 1
  done
  IFS= read -r package_pid < "$AIRBOB_PACKAGE_PID_FILE"
  kill -TERM "$package_pid"
fi
exit 0
EOF
  chmod 700 "$fake_bin/ln"

  signal_output="$temp_dir/signal-after-link-output-$signal_after"
  mkdir "$signal_output"
  chmod 700 "$signal_output"
  printf 'preserve-me\n' > "$signal_output/unrelated-sentinel"
  printf 'preserve-me\n' > "$temp_dir/signal-after-link-sentinel-$signal_after"
  pid_file="$temp_dir/signal-after-link-$signal_after.pid"
  marker="$temp_dir/signal-after-link-$signal_after.marker"
  counter="$temp_dir/signal-after-link-$signal_after.counter"
  real_ln=$(command -v ln)
  run_expect_signal_143 "signal after final link $signal_after" "$temp_dir/signal-after-link-$signal_after.log" "$pid_file" \
    env \
      PATH="$fake_bin:$PATH" \
      AIRBOB_LN_COUNTER="$counter" \
      AIRBOB_PACKAGE_PID_FILE="$pid_file" \
      AIRBOB_REAL_LN="$real_ln" \
      AIRBOB_SIGNAL_AFTER_LINK="$signal_after" \
      AIRBOB_SIGNAL_MARKER="$marker" \
      "$base_repo/infra/aws/scripts/package-service-bundles.sh" "$valid_commit" "$signal_output"
  [[ -f "$marker" ]] || fail "signal-after-link $signal_after hook was not reached"
  cmp -s "$temp_dir/signal-after-link-sentinel-$signal_after" "$signal_output/unrelated-sentinel" \
    || fail "signal-after-link $signal_after modified an unrelated sentinel"
  assert_no_staging "$signal_output"
  assert_no_release_artifacts "$signal_output" "$valid_commit"
}

run_signal_after_publication_links 1
run_signal_after_publication_links 2

printf 'service bundle package tests passed\n'
