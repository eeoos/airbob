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

verify_fixed_corpus_bytes() {
  scan_file=$1
  relative_path=$2
  reject_backslash=0
  if [[ "$relative_path" == 'infra/aws/bundles/debezium/connect-distributed.aws.properties' ]]; then
    reject_backslash=1
  fi
  if ! forbidden_bytes=$(LC_ALL=C od -An -v -t x1 "$scan_file" | LC_ALL=C awk \
    -v reject_backslash="$reject_backslash" '
      {
        for (field_index = 1; field_index <= NF; field_index++) {
          byte = tolower($field_index)
          if (byte == "0d" \
            || (previous == "c2" && byte == "85") \
            || (before_previous == "e2" && previous == "80" \
              && (byte == "a8" || byte == "a9")) \
            || (reject_backslash == 1 && byte == "5c")) {
            forbidden = 1
          }
          before_previous = previous
          previous = byte
        }
      }
      END { print forbidden + 0 }
    ')
  then
    return 1
  fi
  [[ "$forbidden_bytes" == 0 ]]
}

scan_sensitive_assignments() {
  scan_file=$1
  relative_path=$2
  if ! verify_fixed_corpus_bytes "$scan_file" "$relative_path"; then
    fail "bundle contains prohibited obfuscation syntax or unapproved sensitive material"
    return 1
  fi
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
      obfuscated_continuation = candidate ~ /\\$/
      obfuscated_escape = candidate ~ /\\x[[:xdigit:]][[:xdigit:]]/ \
        || candidate ~ /\\u[[:xdigit:]][[:xdigit:]][[:xdigit:]][[:xdigit:]]/ \
        || candidate ~ /\\U[[:xdigit:]][[:xdigit:]][[:xdigit:]][[:xdigit:]][[:xdigit:]][[:xdigit:]][[:xdigit:]][[:xdigit:]]/
      if (obfuscated_continuation || obfuscated_escape) {
        rejected = 1
        exit 1
      }
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
  ' "$scan_file" || fail "bundle contains prohibited obfuscation syntax or unapproved sensitive material"
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

expected_image_variable_mappings() {
  cat <<'EOF'
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
}

read_canonical_image_env() {
  env_file=$1
  parsed_pairs=''
  image_values=''
  while IFS= read -r env_line || [[ -n "$env_line" ]]; do
    [[ "$env_line" =~ ^([A-Z][A-Z0-9_]*)=([^[:space:]]+)$ ]] || return 1
    env_key=${BASH_REMATCH[1]}
    env_value=${BASH_REMATCH[2]}
    case "$env_key" in
      APP_IMAGE|REDIS_IMAGE|REDIS_EXPORTER_IMAGE|NODE_EXPORTER_IMAGE|KAFKA_IMAGE|DEBEZIUM_IMAGE|ELASTICSEARCH_IMAGE|ELASTICSEARCH_EXPORTER_IMAGE|PROMETHEUS_IMAGE|GRAFANA_IMAGE)
        case "$env_value" in
          *@sha256:*) ;;
          *) return 1 ;;
        esac
        image_repository=${env_value%@sha256:*}
        image_digest=${env_value##*@sha256:}
        [[ -n "$image_repository" \
          && "$image_repository" != *'@sha256:'* \
          && "${#image_digest}" -eq 64 \
          && "$image_digest" =~ ^[0-9a-f]+$ ]] || return 1
        if [[ -n "$image_values" ]]; then
          image_values="$image_values
$env_value"
        else
          image_values=$env_value
        fi
        ;;
      APP_ENV_FILE|MONITORING_ENV_FILE)
        ;;
      *)
        return 1
        ;;
    esac
    if [[ -n "$parsed_pairs" ]]; then
      parsed_pairs="$parsed_pairs
$env_key=$env_value"
    else
      parsed_pairs="$env_key=$env_value"
    fi
  done < "$env_file"

  actual_env_keys=$(printf '%s\n' "$parsed_pairs" | sed 's/=.*//' | LC_ALL=C sort)
  expected_env_keys=$(printf '%s\n' \
    APP_ENV_FILE \
    APP_IMAGE \
    DEBEZIUM_IMAGE \
    ELASTICSEARCH_EXPORTER_IMAGE \
    ELASTICSEARCH_IMAGE \
    GRAFANA_IMAGE \
    KAFKA_IMAGE \
    MONITORING_ENV_FILE \
    NODE_EXPORTER_IMAGE \
    PROMETHEUS_IMAGE \
    REDIS_EXPORTER_IMAGE \
    REDIS_IMAGE | LC_ALL=C sort)
  [[ "$actual_env_keys" == "$expected_env_keys" ]] || return 1

  sorted_image_values=$(printf '%s\n' "$image_values" | LC_ALL=C sort)
  unique_image_values=$(printf '%s\n' "$image_values" | LC_ALL=C sort -u)
  [[ "$sorted_image_values" == "$unique_image_values" ]] || return 1
  printf '%s\n' "$parsed_pairs"
}

image_value_for_variable() {
  env_pairs=$1
  wanted_variable=$2
  while IFS='=' read -r current_key current_value || [[ -n "$current_key$current_value" ]]; do
    if [[ "$current_key" == "$wanted_variable" ]]; then
      printf '%s\n' "$current_value"
      return 0
    fi
  done <<< "$env_pairs"
  return 1
}

expected_resolved_image_mappings() {
  env_pairs=$1
  while IFS='=' read -r service_key image_variable || [[ -n "$service_key$image_variable" ]]; do
    if ! resolved_value=$(image_value_for_variable "$env_pairs" "$image_variable"); then
      return 1
    fi
    printf '%s=%s\n' "$service_key" "$resolved_value"
  done <<< "$(expected_image_variable_mappings)"
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

  expected_mappings=$(expected_image_variable_mappings)
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

  if ! canonical_image_env_pairs=$(read_canonical_image_env "$image_env"); then
    fail "digest image fixture does not match the strict canonical key/value contract"
    return 1
  fi
}

expected_services_for_bundle() {
  bundle_name=$1
  case "$bundle_name" in
    app)
      printf '%s\n' app node-exporter
      ;;
    redis)
      printf '%s\n' node-exporter redis redis-cache redis-exporter-cache redis-exporter-general
      ;;
    kafka)
      printf '%s\n' kafka node-exporter
      ;;
    debezium)
      printf '%s\n' debezium node-exporter
      ;;
    elasticsearch)
      printf '%s\n' elasticsearch elasticsearch-exporter node-exporter
      ;;
    monitoring)
      printf '%s\n' grafana node-exporter prometheus
      ;;
    *)
      fail "unknown bundle in resolved service contract"
      ;;
  esac
}

resolved_services_from_config() {
  LC_ALL=C awk '
    /^services:[[:space:]]*$/ { in_services = 1; next }
    in_services && /^[^[:space:]]/ { exit }
    in_services && /^  [^[:space:]].*:[[:space:]]*$/ {
      service = $0
      sub(/^  /, "", service)
      sub(/:[[:space:]]*$/, "", service)
      print service
    }
  '
}

resolved_images_from_config() {
  bundle_name=$1
  LC_ALL=C awk -v bundle_name="$bundle_name" '
    /^services:[[:space:]]*$/ { in_services = 1; next }
    in_services && /^[^[:space:]]/ { exit }
    in_services && /^  [^[:space:]].*:[[:space:]]*$/ {
      service = $0
      sub(/^  /, "", service)
      sub(/:[[:space:]]*$/, "", service)
      next
    }
    in_services && service != "" && /^    image:[[:space:]]*/ {
      image = $0
      sub(/^    image:[[:space:]]*/, "", image)
      print bundle_name "/" service "=" image
    }
  '
}

resolved_forbidden_cardinality_count() {
  LC_ALL=C awk '
    /^services:[[:space:]]*$/ { in_services = 1; next }
    in_services && /^[^[:space:]]/ { exit }
    in_services && /^  [^[:space:]].*:[[:space:]]*$/ { service_seen = 1; next }
    in_services && service_seen && /^    (scale|deploy):/ { count++ }
    END { print count + 0 }
  '
}

verify_resolved_config_view() {
  bundle_name=$1
  canonical_config=$2
  expected_services=$3
  expected_images=$4
  actual_services=$(printf '%s\n' "$canonical_config" \
    | resolved_services_from_config | LC_ALL=C sort)
  if [[ "$actual_services" != "$expected_services" ]]; then
    fail "resolved service set does not match the exact bundle contract"
    return 1
  fi
  actual_images=$(printf '%s\n' "$canonical_config" \
    | resolved_images_from_config "$bundle_name" | LC_ALL=C sort)
  if [[ "$actual_images" != "$expected_images" ]]; then
    fail "resolved service-to-image association does not match the exact bundle contract"
    return 1
  fi
  forbidden_cardinality=$(printf '%s\n' "$canonical_config" \
    | resolved_forbidden_cardinality_count)
  if [[ "$forbidden_cardinality" -ne 0 ]]; then
    fail "resolved service model declares forbidden scale or deploy cardinality"
    return 1
  fi
}

verify_resolved_service_contracts() {
  if ! all_expected_images=$(expected_resolved_image_mappings "$canonical_image_env_pairs"); then
    fail "resolved image contract could not be derived from the canonical fixture"
    return 1
  fi
  for bundle_name in "${bundle_names[@]}"; do
    compose_file="$repo_root/infra/aws/bundles/$bundle_name/compose.yml"
    expected_services=$(expected_services_for_bundle "$bundle_name" | LC_ALL=C sort)
    expected_images=$(printf '%s\n' "$all_expected_images" | LC_ALL=C awk \
      -v prefix="$bundle_name/" 'index($0, prefix) == 1 { print }' | LC_ALL=C sort)
    if ! default_config=$(COMPOSE_PROFILES= docker compose \
      --env-file "$image_env" -f "$compose_file" config 2>/dev/null)
    then
      fail "default-profile resolved service model could not be inspected"
      return 1
    fi
    if ! wildcard_config=$(COMPOSE_PROFILES= docker compose --profile '*' \
      --env-file "$image_env" -f "$compose_file" config 2>/dev/null)
    then
      fail "all-profile resolved service model could not be inspected"
      return 1
    fi
    verify_resolved_config_view \
      "$bundle_name" "$default_config" "$expected_services" "$expected_images" \
      || return 1
    verify_resolved_config_view \
      "$bundle_name" "$wildcard_config" "$expected_services" "$expected_images" \
      || return 1
  done
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
      || fail "bundle contains prohibited obfuscation syntax or unapproved sensitive material"
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
  verify_resolved_service_contracts

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
    || fail "Java Properties mutation did not reconstruct the expected key"
}

write_app_linebreak_password_mutation() {
  compose_file=$1
  linebreak_family=$2
  mutated_file="$compose_file.tmp"
  replaced=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$replaced" -eq 0 && "$line" == '      JAVA_OPTS:'* ]]; then
      java_opts=${line#'      JAVA_OPTS: '}
      printf '      - JAVA_OPTS=%s\n' "$java_opts"
      printf '      - "PASSW\\'
      case "$linebreak_family" in
        cr) printf '\r' ;;
        nel) printf '\302\205' ;;
        ls) printf '\342\200\250' ;;
        ps) printf '\342\200\251' ;;
        *) fail "unknown non-LF line-break mutation" ;;
      esac
      printf 'ORD=hunter2"\n'
      replaced=1
    else
      printf '%s\n' "$line"
    fi
  done < "$compose_file" > "$mutated_file"
  [[ "$replaced" -eq 1 ]] || fail "non-LF line-break mutation target was not found"
  mv "$mutated_file" "$compose_file"
}

assert_compose_password_key() {
  compose_file=$1
  canonical_config=$(COMPOSE_PROFILES= docker compose \
    --env-file "$(dirname -- "$compose_file")/../../tests/fixtures/images.env" \
    -f "$compose_file" config 2>/dev/null) \
    || fail "non-LF line-break mutation canonical precondition failed"
  canonical_password_count=$(printf '%s\n' "$canonical_config" | LC_ALL=C awk '
    $1 == "PASSWORD:" { count++ }
    END { print count + 0 }
  ')
  [[ "$canonical_password_count" -eq 1 ]] \
    || fail "non-LF line-break mutation did not resolve one sensitive key"
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

x-airbob-probe:
  environment:
    - PASSWORD=hunter2
EOF
      ;;
    inline-password)
      printf '\nx-airbob-probe: {environment: {PASSWORD: hunter2}}\n' \
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
    unicode-escaped-password)
      awk '
        { print }
        index($0, "\"database.password\"") && index($0, "DEBEZIUM_PASSWORD") {
          print "    \"database.pass\\u0077ord\": \"hunter2\","
        }
      ' "$mutation_root/infra/aws/bundles/debezium/connector.aws.json.tmpl" \
        > "$mutation_root/connector.tmp"
      mv "$mutation_root/connector.tmp" \
        "$mutation_root/infra/aws/bundles/debezium/connector.aws.json.tmpl"
      ;;
    yaml-hex-escaped-password)
      cat >> "$mutation_root/infra/aws/bundles/app/compose.yml" <<'EOF'

x-airbob-obfuscation-probe:
  "\x50ASSWORD": hunter2
EOF
      docker compose --env-file "$mutation_root/infra/aws/tests/fixtures/images.env" \
        -f "$mutation_root/infra/aws/bundles/app/compose.yml" config --quiet \
        >/dev/null 2>&1 || fail "YAML hex-escape mutation precondition failed"
      ;;
    yaml-long-unicode-password)
      cat >> "$mutation_root/infra/aws/bundles/app/compose.yml" <<'EOF'

x-airbob-obfuscation-probe:
  "PASSW\U0000004fRD": hunter2
EOF
      docker compose --env-file "$mutation_root/infra/aws/tests/fixtures/images.env" \
        -f "$mutation_root/infra/aws/bundles/app/compose.yml" config --quiet \
        >/dev/null 2>&1 || fail "YAML long-Unicode mutation precondition failed"
      ;;
    backslash-continued-password)
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
      ' "$mutation_root/infra/aws/bundles/app/compose.yml" > "$mutation_root/app.tmp"
      mv "$mutation_root/app.tmp" "$mutation_root/infra/aws/bundles/app/compose.yml"
      canonical_config=$(COMPOSE_PROFILES= docker compose \
        --env-file "$mutation_root/infra/aws/tests/fixtures/images.env" \
        -f "$mutation_root/infra/aws/bundles/app/compose.yml" \
        config 2>/dev/null) \
        || fail "backslash-continuation mutation canonical precondition failed"
      canonical_password_count=$(printf '%s\n' "$canonical_config" | awk '
        $1 == "PASSWORD:" { count++ }
        END { print count + 0 }
      ')
      [[ "$canonical_password_count" -eq 1 ]] \
        || fail "backslash-continuation mutation did not resolve one sensitive key"
      ;;
    properties-escaped-password)
      printf '\nPASS\\WORD=hunter2\n' \
        >> "$mutation_root/infra/aws/bundles/debezium/connect-distributed.aws.properties"
      assert_java_properties_key \
        "$mutation_root/infra/aws/bundles/debezium/connect-distributed.aws.properties" \
        PASSWORD
      ;;
    properties-escaped-access-key)
      printf '\nACCESS\\_KEY=hunter2\n' \
        >> "$mutation_root/infra/aws/bundles/debezium/connect-distributed.aws.properties"
      assert_java_properties_key \
        "$mutation_root/infra/aws/bundles/debezium/connect-distributed.aws.properties" \
        ACCESS_KEY
      ;;
    linebreak-cr-password)
      write_app_linebreak_password_mutation \
        "$mutation_root/infra/aws/bundles/app/compose.yml" cr
      assert_compose_password_key "$mutation_root/infra/aws/bundles/app/compose.yml"
      ;;
    linebreak-nel-password)
      write_app_linebreak_password_mutation \
        "$mutation_root/infra/aws/bundles/app/compose.yml" nel
      assert_compose_password_key "$mutation_root/infra/aws/bundles/app/compose.yml"
      ;;
    linebreak-ls-password)
      write_app_linebreak_password_mutation \
        "$mutation_root/infra/aws/bundles/app/compose.yml" ls
      assert_compose_password_key "$mutation_root/infra/aws/bundles/app/compose.yml"
      ;;
    linebreak-ps-password)
      write_app_linebreak_password_mutation \
        "$mutation_root/infra/aws/bundles/app/compose.yml" ps
      assert_compose_password_key "$mutation_root/infra/aws/bundles/app/compose.yml"
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
    duplicate-image-env-key)
      printf '%s\n' \
        'APP_IMAGE=registry.example.invalid/airbob/app@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
        >> "$mutation_root/infra/aws/tests/fixtures/images.env"
      ;;
    image-value-collision)
      sed \
        's|^GRAFANA_IMAGE=.*|GRAFANA_IMAGE=registry.example.invalid/airbob/prometheus@sha256:3333333333333333333333333333333333333333333333333333333333333333|' \
        "$mutation_root/infra/aws/tests/fixtures/images.env" > "$mutation_root/images.tmp"
      mv "$mutation_root/images.tmp" "$mutation_root/infra/aws/tests/fixtures/images.env"
      ;;
    resolved-image-anchor-override)
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
      ' "$mutation_root/infra/aws/bundles/redis/compose.yml" > "$mutation_root/redis.tmp"
      mv "$mutation_root/redis.tmp" "$mutation_root/infra/aws/bundles/redis/compose.yml"
      canonical_redis_config=$(COMPOSE_PROFILES= docker compose \
        --env-file "$mutation_root/infra/aws/tests/fixtures/images.env" \
        -f "$mutation_root/infra/aws/bundles/redis/compose.yml" \
        config 2>/dev/null) \
        || fail "resolved image override canonical precondition failed"
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
        || fail "resolved image override did not change the canonical service image"
      ;;
    scale-zero-exporter)
      awk '
        { print }
        /^  redis-exporter-cache:[[:space:]]*$/ { print "    scale: 0" }
      ' "$mutation_root/infra/aws/bundles/redis/compose.yml" > "$mutation_root/redis.tmp"
      mv "$mutation_root/redis.tmp" "$mutation_root/infra/aws/bundles/redis/compose.yml"
      canonical_redis_config=$(COMPOSE_PROFILES= docker compose \
        --env-file "$mutation_root/infra/aws/tests/fixtures/images.env" \
        -f "$mutation_root/infra/aws/bundles/redis/compose.yml" \
        config 2>/dev/null) \
        || fail "scale-zero mutation canonical precondition failed"
      printf '%s\n' "$canonical_redis_config" | LC_ALL=C awk '
        /^  redis-exporter-cache:[[:space:]]*$/ { in_target = 1; next }
        in_target && /^  [a-zA-Z0-9][a-zA-Z0-9_-]*:[[:space:]]*$/ { exit 1 }
        in_target && /^    scale:[[:space:]]*0[[:space:]]*$/ { found = 1 }
        END { exit(found ? 0 : 1) }
      ' || fail "scale-zero mutation did not resolve the requested cardinality"
      ;;
    scale-two-core)
      awk '
        { print }
        /^  redis:[[:space:]]*$/ { print "    scale: 2" }
      ' "$mutation_root/infra/aws/bundles/redis/compose.yml" > "$mutation_root/redis.tmp"
      mv "$mutation_root/redis.tmp" "$mutation_root/infra/aws/bundles/redis/compose.yml"
      canonical_redis_config=$(COMPOSE_PROFILES= docker compose \
        --env-file "$mutation_root/infra/aws/tests/fixtures/images.env" \
        -f "$mutation_root/infra/aws/bundles/redis/compose.yml" \
        config 2>/dev/null) \
        || fail "non-unit scale mutation canonical precondition failed"
      printf '%s\n' "$canonical_redis_config" | LC_ALL=C awk '
        /^  redis:[[:space:]]*$/ { in_target = 1; next }
        in_target && /^  [a-zA-Z0-9][a-zA-Z0-9_-]*:[[:space:]]*$/ { exit 1 }
        in_target && /^    scale:[[:space:]]*2[[:space:]]*$/ { found = 1 }
        END { exit(found ? 0 : 1) }
      ' || fail "non-unit scale mutation did not resolve the requested cardinality"
      ;;
    deploy-replicas)
      awk '
        { print }
        /^  redis-cache:[[:space:]]*$/ {
          print "    deploy:"
          print "      replicas: 2"
        }
      ' "$mutation_root/infra/aws/bundles/redis/compose.yml" > "$mutation_root/redis.tmp"
      mv "$mutation_root/redis.tmp" "$mutation_root/infra/aws/bundles/redis/compose.yml"
      canonical_redis_config=$(COMPOSE_PROFILES= docker compose \
        --env-file "$mutation_root/infra/aws/tests/fixtures/images.env" \
        -f "$mutation_root/infra/aws/bundles/redis/compose.yml" \
        config 2>/dev/null) \
        || fail "deploy replicas mutation canonical precondition failed"
      printf '%s\n' "$canonical_redis_config" | LC_ALL=C awk '
        /^  redis-cache:[[:space:]]*$/ { in_target = 1; next }
        in_target && /^  [a-zA-Z0-9][a-zA-Z0-9_-]*:[[:space:]]*$/ { exit 1 }
        in_target && /^    deploy:[[:space:]]*$/ { in_deploy = 1; next }
        in_target && in_deploy && /^      replicas:[[:space:]]*2[[:space:]]*$/ { found = 1 }
        END { exit(found ? 0 : 1) }
      ' || fail "deploy replicas mutation did not resolve the requested cardinality"
      ;;
    redis-alias-extra)
      awk '
        NR == 1 {
          print "x-airbob-extra-service: &airbob-extra-service"
          print "  image: ${REDIS_IMAGE:?REDIS_IMAGE is required}"
          print "  platform: linux/amd64"
          print ""
        }
        /^volumes:/ {
          print "  redis-third: *airbob-extra-service"
          print ""
        }
        { print }
      ' "$mutation_root/infra/aws/bundles/redis/compose.yml" > "$mutation_root/redis.tmp"
      mv "$mutation_root/redis.tmp" "$mutation_root/infra/aws/bundles/redis/compose.yml"
      resolved_services=$(COMPOSE_PROFILES= docker compose \
        --env-file "$mutation_root/infra/aws/tests/fixtures/images.env" \
        -f "$mutation_root/infra/aws/bundles/redis/compose.yml" \
        config --services 2>/dev/null) \
        || fail "Redis alias mutation canonical precondition failed"
      resolved_extra_count=$(printf '%s\n' "$resolved_services" | awk '
        $0 == "redis-third" { count++ }
        END { print count + 0 }
      ')
      [[ "$resolved_extra_count" -eq 1 ]] \
        || fail "Redis alias mutation did not resolve exactly one extra service"
      ;;
    app-profile-hidden-extra)
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
      ' "$mutation_root/infra/aws/bundles/app/compose.yml" > "$mutation_root/app.tmp"
      mv "$mutation_root/app.tmp" "$mutation_root/infra/aws/bundles/app/compose.yml"
      default_services=$(COMPOSE_PROFILES= docker compose \
        --env-file "$mutation_root/infra/aws/tests/fixtures/images.env" \
        -f "$mutation_root/infra/aws/bundles/app/compose.yml" \
        config --services 2>/dev/null) \
        || fail "profile-hidden extra mutation default-view precondition failed"
      wildcard_services=$(COMPOSE_PROFILES= docker compose --profile '*' \
        --env-file "$mutation_root/infra/aws/tests/fixtures/images.env" \
        -f "$mutation_root/infra/aws/bundles/app/compose.yml" \
        config --services 2>/dev/null) \
        || fail "profile-hidden extra mutation wildcard-view precondition failed"
      default_extra_count=$(printf '%s\n' "$default_services" | awk '
        $0 == "app-shadow" { count++ }
        END { print count + 0 }
      ')
      wildcard_extra_count=$(printf '%s\n' "$wildcard_services" | awk '
        $0 == "app-shadow" { count++ }
        END { print count + 0 }
      ')
      [[ "$default_extra_count" -eq 0 && "$wildcard_extra_count" -eq 1 ]] \
        || fail "profile-hidden extra mutation did not exercise both canonical views"
      ;;
    approved-app-profiled)
      awk '
        /^  app:[[:space:]]*$/ {
          print
          print "    profiles: [hidden]"
          next
        }
        { print }
      ' "$mutation_root/infra/aws/bundles/app/compose.yml" > "$mutation_root/app.tmp"
      mv "$mutation_root/app.tmp" "$mutation_root/infra/aws/bundles/app/compose.yml"
      default_services=$(COMPOSE_PROFILES= docker compose \
        --env-file "$mutation_root/infra/aws/tests/fixtures/images.env" \
        -f "$mutation_root/infra/aws/bundles/app/compose.yml" \
        config --services 2>/dev/null) \
        || fail "profiled approved service default-view precondition failed"
      wildcard_services=$(COMPOSE_PROFILES= docker compose --profile '*' \
        --env-file "$mutation_root/infra/aws/tests/fixtures/images.env" \
        -f "$mutation_root/infra/aws/bundles/app/compose.yml" \
        config --services 2>/dev/null) \
        || fail "profiled approved service wildcard-view precondition failed"
      default_app_count=$(printf '%s\n' "$default_services" | awk '
        $0 == "app" { count++ }
        END { print count + 0 }
      ')
      wildcard_app_count=$(printf '%s\n' "$wildcard_services" | awk '
        $0 == "app" { count++ }
        END { print count + 0 }
      ')
      [[ "$default_app_count" -eq 0 && "$wildcard_app_count" -eq 1 ]] \
        || fail "profiled approved service mutation did not exercise both canonical views"
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
expect_mutation_failure 'a Unicode-escaped duplicate password key' unicode-escaped-password
expect_mutation_failure 'a YAML hex-escaped password key' yaml-hex-escaped-password
expect_mutation_failure 'a YAML long-Unicode-escaped password key' yaml-long-unicode-password
expect_mutation_failure 'a backslash-continued password key' backslash-continued-password
expect_mutation_failure 'a Java Properties escaped password key' properties-escaped-password
expect_mutation_failure 'a Java Properties escaped access key' properties-escaped-access-key
expect_mutation_failure 'a CR-spliced password key' linebreak-cr-password
expect_mutation_failure 'a NEL-spliced password key' linebreak-nel-password
expect_mutation_failure 'an LS-spliced password key' linebreak-ls-password
expect_mutation_failure 'a PS-spliced password key' linebreak-ps-password
expect_mutation_failure 'one approved sensitive line duplicated while another is missing' approved-line-substitution
expect_mutation_failure 'an approved sensitive line copied to an unapproved path' approved-line-wrong-path
expect_mutation_failure 'an approved sensitive line with a suffix' approved-line-suffix
expect_mutation_failure 'a Redis exporter using the Redis server image variable' image-substitution
expect_mutation_failure 'a duplicated image variable that omits another required variable' image-duplication
expect_mutation_failure 'an omitted service image entry' image-omission
expect_mutation_failure 'an unapproved image variable with a valid digest' unapproved-image
expect_mutation_failure 'a duplicate canonical image env key' duplicate-image-env-key
expect_mutation_failure 'two canonical image variables sharing one digest' image-value-collision
expect_mutation_failure 'a resolved service image changed through a YAML merge anchor' resolved-image-anchor-override
expect_mutation_failure 'a required exporter scaled to zero' scale-zero-exporter
expect_mutation_failure 'a core service scaled above one' scale-two-core
expect_mutation_failure 'a service with deploy replicas declared' deploy-replicas
expect_mutation_failure 'an approved App service hidden from the default profile' approved-app-profiled
expect_mutation_failure 'a profile-hidden App service behind a YAML alias' app-profile-hidden-extra
expect_mutation_failure 'an unprofiled Redis service hidden behind a YAML alias' redis-alias-extra
expect_mutation_failure 'a changed Redis topology' redis-topology

safe_escape_root="$temp_dir/safe-nonhex-escape"
safe_escape_output="$temp_dir/safe-nonhex-escape-output"
copy_validation_root "$safe_escape_root"
cat >> "$safe_escape_root/infra/aws/bundles/app/compose.yml" <<'EOF'

x-airbob-safe-escape:
  pattern: "\\w+"
  label: "Airbob 성능"
EOF
COMPOSE_PROFILES= docker compose \
  --env-file "$safe_escape_root/infra/aws/tests/fixtures/images.env" \
  -f "$safe_escape_root/infra/aws/bundles/app/compose.yml" \
  config --quiet >/dev/null 2>&1 \
  || fail "permitted non-hexadecimal escape precondition failed"
if ! bash "$safe_escape_root/infra/aws/tests/all-service-bundles-test.sh" \
  --validate-only >"$safe_escape_output" 2>&1
then
  fail "aggregate validation rejected a permitted non-hexadecimal escape"
fi

printf 'all AWS service bundle aggregate tests passed\n'
