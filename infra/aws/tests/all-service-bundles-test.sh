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
  relative_path=$2
  LC_ALL=C awk -v relative_path="$relative_path" '
    function trim(value) {
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+$/, "", value)
      return value
    }
    function approved_line_id(candidate) {
      if (relative_path == "infra/aws/bundles/app/required-runtime-env.txt") {
        if (candidate == "SPRING_DATASOURCE_PASSWORD") return 1
        if (candidate == "ELASTIC_PASSWORD") return 2
      }
      if (relative_path == "infra/aws/bundles/debezium/connector.aws.json.tmpl") {
        if (candidate == "\"database.password\": \"${DEBEZIUM_PASSWORD}\",") return 3
      }
      if (relative_path == "infra/aws/bundles/monitoring/compose.yml") {
        if (candidate == "if [ -z \"$${GRAFANA_ADMIN_PASSWORD:-}\" ]; then") return 4
        if (candidate == "echo \"GRAFANA_ADMIN_PASSWORD must be set for AWS Grafana\" >&2") return 5
        if (candidate == "export GF_SECURITY_ADMIN_PASSWORD=\"$${GRAFANA_ADMIN_PASSWORD}\"") return 6
      }
      return 0
    }
    {
      candidate = trim($0)
      lower = tolower(candidate)
      sensitive_material = lower ~ /(password|passwd|secret|credential|token|api[ _.-]*key|access[ _.-]*key|private[ _.-]*key|service[ _.-]*account)/ \
        || lower ~ /-----begin[^-]*key-----/
      if (!sensitive_material) {
        next
      }
      approved_id = approved_line_id(candidate)
      if (approved_id != 0) {
        approved_counts[approved_id]++
        next
      }
      rejected = 1
      exit 1
    }
    END {
      if (!rejected) {
        printf "%d %d %d %d %d %d\n", \
          approved_counts[1] + 0, approved_counts[2] + 0, \
          approved_counts[3] + 0, approved_counts[4] + 0, \
          approved_counts[5] + 0, approved_counts[6] + 0
      }
    }
  ' "$scan_file" || fail "bundle contains literal or unapproved sensitive material"
}

image_mappings_for_bundle() {
  bundle_name=$1
  compose_file=$2
  in_services=0
  current_service=''
  image_pattern='^\$\{[A-Z][A-Z0-9_]*_IMAGE:\?[^}]+\}$'
  service_pattern='^  ([a-zA-Z0-9][a-zA-Z0-9_-]*):[[:space:]]*$'

  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" == services: ]]; then
      in_services=1
      continue
    fi
    [[ "$in_services" -eq 1 ]] || continue
    if [[ "$line" =~ ^[^[:space:]] ]]; then
      break
    fi
    if [[ "$line" =~ $service_pattern ]]; then
      current_service=${BASH_REMATCH[1]}
      continue
    fi
    trimmed=$(printf '%s' "$line" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
    case "$trimmed" in
      image:*)
        [[ -n "$current_service" ]] || fail "bundle image entry is outside a service"
        value=${trimmed#image:}
        value=$(printf '%s' "$value" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
        [[ "$value" =~ $image_pattern ]] || fail "bundle image entry is not supplied by an image variable"
        variable=${value#\$\{}
        variable=${variable%%:*}
        printf '%s/%s=%s\n' "$bundle_name" "$current_service" "$variable"
        ;;
    esac
  done < "$compose_file"
}

verify_image_contracts() {
  actual_mappings=''
  for bundle_name in "${bundle_names[@]}"; do
    compose_file="$repo_root/infra/aws/bundles/$bundle_name/compose.yml"
    mappings=$(image_mappings_for_bundle "$bundle_name" "$compose_file")
    if [[ -n "$actual_mappings" ]]; then
      actual_mappings="$actual_mappings
$mappings"
    else
      actual_mappings=$mappings
    fi
  done

  expected_mappings=$(cat <<'EOF'
app/app=APP_IMAGE
app/node-exporter=NODE_EXPORTER_IMAGE
redis/redis=REDIS_IMAGE
redis/redis-cache=REDIS_IMAGE
redis/redis-exporter-general=REDIS_EXPORTER_IMAGE
redis/redis-exporter-cache=REDIS_EXPORTER_IMAGE
redis/node-exporter=NODE_EXPORTER_IMAGE
kafka/kafka=KAFKA_IMAGE
kafka/node-exporter=NODE_EXPORTER_IMAGE
debezium/debezium=DEBEZIUM_IMAGE
debezium/node-exporter=NODE_EXPORTER_IMAGE
elasticsearch/elasticsearch=ELASTICSEARCH_IMAGE
elasticsearch/elasticsearch-exporter=ELASTICSEARCH_EXPORTER_IMAGE
elasticsearch/node-exporter=NODE_EXPORTER_IMAGE
monitoring/prometheus=PROMETHEUS_IMAGE
monitoring/grafana=GRAFANA_IMAGE
monitoring/node-exporter=NODE_EXPORTER_IMAGE
EOF
)
  actual_sorted=$(printf '%s\n' "$actual_mappings" | LC_ALL=C sort)
  expected_sorted=$(printf '%s\n' "$expected_mappings" | LC_ALL=C sort)
  [[ "$actual_sorted" == "$expected_sorted" ]] || fail "bundle service-to-image-variable mapping does not match the approved contract"

  actual_variables=$(printf '%s\n' "$actual_mappings" | sed 's/.*=//' | LC_ALL=C sort -u)
  expected_variables=$(printf '%s\n' \
    APP_IMAGE \
    DEBEZIUM_IMAGE \
    ELASTICSEARCH_EXPORTER_IMAGE \
    ELASTICSEARCH_IMAGE \
    GRAFANA_IMAGE \
    KAFKA_IMAGE \
    NODE_EXPORTER_IMAGE \
    PROMETHEUS_IMAGE \
    REDIS_EXPORTER_IMAGE \
    REDIS_IMAGE | LC_ALL=C sort)
  [[ "$actual_variables" == "$expected_variables" ]] || fail "bundle image-variable set does not match the canonical ten-variable contract"
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

  approved_spring_password=0
  approved_elastic_password=0
  approved_debezium_password=0
  approved_grafana_guard=0
  approved_grafana_error=0
  approved_grafana_export=0
  for relative_path in "${archive_files[@]}"; do
    scan_file="$repo_root/$relative_path"
    [[ -f "$scan_file" && -r "$scan_file" && ! -L "$scan_file" ]] || fail "required bundle file is missing or unsafe"
    approved_counts=$(scan_sensitive_assignments "$scan_file" "$relative_path") \
      || fail "bundle contains literal or unapproved sensitive material"
    IFS=' ' read -r current_spring current_elastic current_debezium \
      current_guard current_error current_export <<< "$approved_counts"
    approved_spring_password=$((approved_spring_password + current_spring))
    approved_elastic_password=$((approved_elastic_password + current_elastic))
    approved_debezium_password=$((approved_debezium_password + current_debezium))
    approved_grafana_guard=$((approved_grafana_guard + current_guard))
    approved_grafana_error=$((approved_grafana_error + current_error))
    approved_grafana_export=$((approved_grafana_export + current_export))
  done
  [[ "$approved_spring_password" -eq 1 \
    && "$approved_elastic_password" -eq 1 \
    && "$approved_debezium_password" -eq 1 \
    && "$approved_grafana_guard" -eq 1 \
    && "$approved_grafana_error" -eq 1 \
    && "$approved_grafana_export" -eq 1 ]] \
    || fail "each approved sensitive placeholder and guard line must occur exactly once at its approved path"

  verify_redis_topology "$repo_root/infra/aws/bundles/redis/compose.yml"
  verify_image_contracts

  for bundle_name in "${bundle_names[@]}"; do
    compose_file="$repo_root/infra/aws/bundles/$bundle_name/compose.yml"
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
    list-password)
      cat >> "$mutation_root/infra/aws/bundles/app/compose.yml" <<'EOF'

x-airbob-secret-probe:
  environment:
    - PASSWORD=hunter2
EOF
      ;;
    inline-password)
      printf '\nx-airbob-secret-probe: {environment: {PASSWORD: hunter2}}\n' \
        >> "$mutation_root/infra/aws/bundles/app/compose.yml"
      ;;
    access-key)
      printf '\nAWS_ACCESS_KEY_ID=hunter2\n' \
        >> "$mutation_root/infra/aws/bundles/debezium/connect-distributed.aws.properties"
      ;;
    private-key)
      cat >> "$mutation_root/infra/aws/bundles/debezium/connect-distributed.aws.properties" <<'EOF'

PRIVATE_KEY=hunter2
-----BEGIN PRIVATE KEY-----
hunter2
-----END PRIVATE KEY-----
EOF
      ;;
    approved-line-substitution)
      sed 's/^ELASTIC_PASSWORD$/SPRING_DATASOURCE_PASSWORD/' \
        "$mutation_root/infra/aws/bundles/app/required-runtime-env.txt" > "$mutation_root/required-runtime-env.tmp"
      mv "$mutation_root/required-runtime-env.tmp" \
        "$mutation_root/infra/aws/bundles/app/required-runtime-env.txt"
      ;;
    approved-line-wrong-path)
      printf '\nSPRING_DATASOURCE_PASSWORD\n' \
        >> "$mutation_root/infra/aws/bundles/kafka/jmx-exporter.yml"
      ;;
    approved-line-suffix)
      sed 's/^ELASTIC_PASSWORD$/ELASTIC_PASSWORD # suffix/' \
        "$mutation_root/infra/aws/bundles/app/required-runtime-env.txt" > "$mutation_root/required-runtime-env.tmp"
      mv "$mutation_root/required-runtime-env.tmp" \
        "$mutation_root/infra/aws/bundles/app/required-runtime-env.txt"
      ;;
    image-substitution)
      awk '
        !changed && /image: \$\{REDIS_EXPORTER_IMAGE:/ {
          sub(/REDIS_EXPORTER_IMAGE/, "REDIS_IMAGE")
          changed = 1
        }
        { print }
      ' "$mutation_root/infra/aws/bundles/redis/compose.yml" > "$mutation_root/redis.tmp"
      mv "$mutation_root/redis.tmp" "$mutation_root/infra/aws/bundles/redis/compose.yml"
      ;;
    image-duplication)
      sed 's/GRAFANA_IMAGE/PROMETHEUS_IMAGE/' \
        "$mutation_root/infra/aws/bundles/monitoring/compose.yml" > "$mutation_root/monitoring.tmp"
      mv "$mutation_root/monitoring.tmp" "$mutation_root/infra/aws/bundles/monitoring/compose.yml"
      ;;
    image-omission)
      sed '/image: ${KAFKA_IMAGE:?KAFKA_IMAGE is required}/d' \
        "$mutation_root/infra/aws/bundles/kafka/compose.yml" > "$mutation_root/kafka.tmp"
      mv "$mutation_root/kafka.tmp" "$mutation_root/infra/aws/bundles/kafka/compose.yml"
      ;;
    unapproved-image)
      sed 's/APP_IMAGE/UNAPPROVED_IMAGE/' \
        "$mutation_root/infra/aws/bundles/app/compose.yml" > "$mutation_root/app.tmp"
      mv "$mutation_root/app.tmp" "$mutation_root/infra/aws/bundles/app/compose.yml"
      printf '%s\n' \
        'UNAPPROVED_IMAGE=registry.example.invalid/airbob/unapproved@sha256:5555555555555555555555555555555555555555555555555555555555555555' \
        >> "$mutation_root/infra/aws/tests/fixtures/images.env"
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
expect_mutation_failure 'a Compose list-form password' list-password
expect_mutation_failure 'an inline YAML password' inline-password
expect_mutation_failure 'an access-key assignment' access-key
expect_mutation_failure 'private-key material' private-key
expect_mutation_failure 'one approved sensitive line duplicated while another is missing' approved-line-substitution
expect_mutation_failure 'an approved sensitive line copied to an unapproved path' approved-line-wrong-path
expect_mutation_failure 'an approved sensitive line with a suffix' approved-line-suffix
expect_mutation_failure 'a Redis exporter using the Redis server image variable' image-substitution
expect_mutation_failure 'a duplicated image variable that omits another required variable' image-duplication
expect_mutation_failure 'an omitted service image entry' image-omission
expect_mutation_failure 'an unapproved image variable with a valid digest' unapproved-image
expect_mutation_failure 'a changed Redis topology' redis-topology

printf 'all AWS service bundle aggregate tests passed\n'
