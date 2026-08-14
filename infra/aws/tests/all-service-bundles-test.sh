#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
verifier="$repo_root/infra/aws/scripts/verify-service-bundle.sh"
image_env="$repo_root/infra/aws/tests/fixtures/images.env"
manifest="$repo_root/infra/aws/bundles/manifest.json"

bundle_names=(app redis kafka debezium elasticsearch monitoring)
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

fail() {
  printf '%s\n' "$1" >&2
  return 1
}

scan_sensitive_assignments() {
  scan_file=$1
  awk '
    function trim(value) {
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+$/, "", value)
      return value
    }
    {
      candidate = trim($0)
      lower = tolower(candidate)
      sensitive = lower ~ /^(export[[:space:]]+)?["\047]?[a-z0-9_.-]*(password|secret|credential|token|api[_-]?key)[a-z0-9_.-]*["\047]?[[:space:]]*[:=]/
      if (!sensitive) {
        next
      }
      if (candidate == "\"database.password\": \"${DEBEZIUM_PASSWORD}\",") {
        next
      }
      if (candidate == "export GF_SECURITY_ADMIN_PASSWORD=\"$${GRAFANA_ADMIN_PASSWORD}\"") {
        next
      }
      exit 1
    }
  ' "$scan_file" || fail "bundle contains a literal or unapproved sensitive assignment"
}

verify_image_entries() {
  compose_file=$1
  image_count=0
  image_pattern='^\$\{[A-Z][A-Z0-9_]*_IMAGE:\?[^}]+\}$'

  while IFS= read -r line || [[ -n "$line" ]]; do
    trimmed=$(printf '%s' "$line" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
    case "$trimmed" in
      image:*)
        image_count=$((image_count + 1))
        value=${trimmed#image:}
        value=$(printf '%s' "$value" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
        [[ "$value" =~ $image_pattern ]] || fail "bundle image entry is not supplied by an image variable"
        ;;
    esac
  done < "$compose_file"

  [[ "$image_count" -gt 0 ]] || fail "bundle Compose file has no image entries"
}

verify_redis_topology() {
  redis_compose=$1
  actual=$(awk '
    /^services:[[:space:]]*$/ { in_services = 1; next }
    in_services && /^[^[:space:]]/ { exit }
    in_services && /^  [a-zA-Z0-9][a-zA-Z0-9_-]*:[[:space:]]*$/ {
      name = $0
      sub(/^  /, "", name)
      sub(/:[[:space:]]*$/, "", name)
      print name
    }
  ' "$redis_compose" | LC_ALL=C sort)
  expected=$(printf '%s\n' \
    node-exporter \
    redis \
    redis-cache \
    redis-exporter-cache \
    redis-exporter-general | LC_ALL=C sort)
  [[ "$actual" == "$expected" ]] || fail "Redis bundle topology must remain exactly two Redis services, two exporters, and node exporter"
}

validate_root() {
  [[ -f "$manifest" && -r "$manifest" && ! -L "$manifest" ]] || fail "canonical service bundle manifest is missing or unsafe"
  [[ -x "$verifier" ]] || fail "service bundle verifier is missing or not executable"
  [[ -f "$image_env" && -r "$image_env" ]] || fail "digest image fixture is missing or unreadable"

  for relative_path in "${archive_files[@]}"; do
    scan_file="$repo_root/$relative_path"
    [[ -f "$scan_file" && -r "$scan_file" && ! -L "$scan_file" ]] || fail "required bundle file is missing or unsafe"
    scan_sensitive_assignments "$scan_file"
  done

  verify_redis_topology "$repo_root/infra/aws/bundles/redis/compose.yml"

  for bundle_name in "${bundle_names[@]}"; do
    compose_file="$repo_root/infra/aws/bundles/$bundle_name/compose.yml"
    verify_image_entries "$compose_file"
    "$verifier" "$compose_file" "$image_env" >/dev/null
  done
}

validate_root

if [[ "${1:-}" == --validate-only ]]; then
  [[ "$#" -eq 1 ]] || fail "usage: all-service-bundles-test.sh [--validate-only]"
  printf 'all AWS service bundles passed aggregate validation\n'
  exit 0
fi
[[ "$#" -eq 0 ]] || fail "usage: all-service-bundles-test.sh [--validate-only]"

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-all-service-bundles.XXXXXX")

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

copy_validation_root() {
  destination=$1
  mkdir -p "$destination/infra/aws/tests" "$destination/infra/aws/scripts" "$destination/monitoring"
  cp -R "$repo_root/infra/aws/bundles" "$destination/infra/aws/"
  cp "$verifier" "$destination/infra/aws/scripts/verify-service-bundle.sh"
  cp "$script_dir/all-service-bundles-test.sh" "$destination/infra/aws/tests/all-service-bundles-test.sh"
  cp -R "$repo_root/infra/aws/tests/fixtures" "$destination/infra/aws/tests/"
  cp -R "$repo_root/monitoring/prometheus" "$destination/monitoring/"
  cp -R "$repo_root/monitoring/grafana" "$destination/monitoring/"
}

expect_mutation_failure() {
  description=$1
  mutation=$2
  mutation_root="$temp_dir/mutation-$RANDOM"
  output="$temp_dir/mutation-output-$RANDOM"
  copy_validation_root "$mutation_root"
  case "$mutation" in
    latest)
      sed 's|image: ${APP_IMAGE:?APP_IMAGE is required}|image: airbob/app:latest|' \
        "$mutation_root/infra/aws/bundles/app/compose.yml" > "$mutation_root/app.tmp"
      mv "$mutation_root/app.tmp" "$mutation_root/infra/aws/bundles/app/compose.yml"
      ;;
    tag-only)
      sed 's|image: ${REDIS_IMAGE:?REDIS_IMAGE is required}|image: redis:7.2-alpine|' \
        "$mutation_root/infra/aws/bundles/redis/compose.yml" > "$mutation_root/redis.tmp"
      mv "$mutation_root/redis.tmp" "$mutation_root/infra/aws/bundles/redis/compose.yml"
      ;;
    yaml-password)
      printf '\npassword: hunter2\n' >> "$mutation_root/infra/aws/bundles/app/compose.yml"
      ;;
    json-password)
      sed 's|"database.password": "${DEBEZIUM_PASSWORD}"|"database.password": "hunter2"|' \
        "$mutation_root/infra/aws/bundles/debezium/connector.aws.json.tmpl" > "$mutation_root/connector.tmp"
      mv "$mutation_root/connector.tmp" "$mutation_root/infra/aws/bundles/debezium/connector.aws.json.tmpl"
      ;;
    shell-token)
      printf "\nTOKEN='hunter2'\n" >> "$mutation_root/infra/aws/bundles/debezium/connect-distributed.aws.properties"
      ;;
    redis-topology)
      sed 's/^  redis-cache:/  redis-third:/' \
        "$mutation_root/infra/aws/bundles/redis/compose.yml" > "$mutation_root/redis.tmp"
      mv "$mutation_root/redis.tmp" "$mutation_root/infra/aws/bundles/redis/compose.yml"
      ;;
    *)
      fail "unknown aggregate mutation"
      ;;
  esac

  if bash "$mutation_root/infra/aws/tests/all-service-bundles-test.sh" --validate-only >"$output" 2>&1; then
    fail "aggregate validation accepted $description"
  fi
  if grep -Fq 'hunter2' "$output"; then
    fail "aggregate validation replayed a rejected secret value"
  fi
}

expect_mutation_failure 'a latest image' latest
expect_mutation_failure 'a tag-only image' tag-only
expect_mutation_failure 'password: hunter2' yaml-password
expect_mutation_failure 'a literal Debezium database password' json-password
expect_mutation_failure 'a literal shell token' shell-token
expect_mutation_failure 'a changed Redis topology' redis-topology

printf 'all AWS service bundle aggregate tests passed\n'
