#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  printf 'usage: verify-app-runtime-env.sh <runtime-env-file> <policy>\n' >&2
  exit 1
fi

runtime_env_file=$1
policy=$2
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
allowlist_file="$script_dir/../bundles/app/required-runtime-env.txt"

[[ -f "$runtime_env_file" && -r "$runtime_env_file" ]] || {
  printf 'runtime env file is not a readable regular file\n' >&2
  exit 1
}
[[ -f "$allowlist_file" && -r "$allowlist_file" ]] || {
  printf 'runtime env allowlist is not a readable regular file\n' >&2
  exit 1
}

case "$policy" in
  integrated-smoke)
    required_profile='aws,performance-lab'
    ;;
  isolated-read)
    required_profile='aws,traffic-benchmark'
    ;;
  *)
    printf 'unsupported runtime policy\n' >&2
    exit 1
    ;;
esac

expected_keys=(
  SPRING_PROFILES_ACTIVE
  TOSS_PAYMENTS_ENABLED
  GOOGLE_API_ENABLED
  AWS_S3_WRITE_ENABLED
  SLACK_NOTIFICATION_ENABLED
  SPRING_DATASOURCE_URL
  SPRING_DATASOURCE_USERNAME
  SPRING_DATASOURCE_PASSWORD
  REDIS_HOST
  REDIS_PORT
  ACCOMMODATION_DETAIL_CACHE_REDIS_HOST
  ACCOMMODATION_DETAIL_CACHE_REDIS_PORT
  KAFKA_BOOTSTRAP_SERVERS
  ELASTICSEARCH_URIS
  ELASTICSEARCH_USERNAME
  ELASTIC_PASSWORD
  AWS_S3_BUCKET_NAME
  CLOUDFRONT_DOMAIN
)
allowed_keys=()

while IFS= read -r key || [[ -n "$key" ]]; do
  [[ -n "$key" ]] || continue
  [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || {
    printf 'runtime env allowlist is malformed\n' >&2
    exit 1
  }
  allowed_keys+=("$key")
done < "$allowlist_file"

if [[ "${#allowed_keys[@]}" -ne "${#expected_keys[@]}" ]]; then
  printf 'runtime env allowlist does not contain exactly 18 keys\n' >&2
  exit 1
fi

for index in "${!expected_keys[@]}"; do
  if [[ "${allowed_keys[$index]}" != "${expected_keys[$index]}" ]]; then
    printf 'runtime env allowlist does not match the required contract\n' >&2
    exit 1
  fi
done

keys=()
values=()

contains_key() {
  wanted=$1
  shift
  for candidate in "$@"; do
    [[ "$candidate" == "$wanted" ]] && return 0
  done
  return 1
}

value_for() {
  wanted=$1
  for index in "${!keys[@]}"; do
    if [[ "${keys[$index]}" == "$wanted" ]]; then
      printf '%s' "${values[$index]}"
      return 0
    fi
  done
  return 1
}

line_number=0
while IFS= read -r line || [[ -n "$line" ]]; do
  line_number=$((line_number + 1))
  [[ "$line" != *$'\r'* ]] || {
    printf 'runtime env contains a malformed field at line %s\n' "$line_number" >&2
    exit 1
  }
  [[ "$line" =~ ^[[:space:]]*$ ]] && continue
  [[ "$line" =~ ^[[:space:]]*# ]] && continue

  case "$line" in
    *=*)
      key=${line%%=*}
      value=${line#*=}
      ;;
    *)
      printf 'runtime env contains a malformed field at line %s\n' "$line_number" >&2
      exit 1
      ;;
  esac

  [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || {
    printf 'runtime env contains an invalid key at line %s\n' "$line_number" >&2
    exit 1
  }
  contains_key "$key" "${allowed_keys[@]}" || {
    printf 'runtime env contains an unlisted key at line %s\n' "$line_number" >&2
    exit 1
  }
  if [[ "${#keys[@]}" -gt 0 ]] && contains_key "$key" "${keys[@]}"; then
    printf 'runtime env contains a duplicate key at line %s\n' "$line_number" >&2
    exit 1
  fi

  keys+=("$key")
  values+=("$value")
done < "$runtime_env_file"

for key in "${allowed_keys[@]}"; do
  contains_key "$key" "${keys[@]}" || {
    printf 'runtime env is missing a required key\n' >&2
    exit 1
  }
  value=$(value_for "$key")
  case "$key" in
    ELASTICSEARCH_USERNAME|ELASTIC_PASSWORD)
      ;;
    *)
      [[ -n "$value" ]] || {
        printf 'runtime env contains an empty required value\n' >&2
        exit 1
      }
      ;;
  esac
done

for guard in \
  TOSS_PAYMENTS_ENABLED \
  GOOGLE_API_ENABLED \
  AWS_S3_WRITE_ENABLED \
  SLACK_NOTIFICATION_ENABLED
do
  [[ "$(value_for "$guard")" == false ]] || {
    printf 'runtime env external-effect guard must be false\n' >&2
    exit 1
  }
done

[[ "$(value_for SPRING_PROFILES_ACTIVE)" == "$required_profile" ]] || {
  printf 'runtime env profile does not match the selected policy\n' >&2
  exit 1
}

general_host=$(value_for REDIS_HOST)
general_port=$(value_for REDIS_PORT)
cache_host=$(value_for ACCOMMODATION_DETAIL_CACHE_REDIS_HOST)
cache_port=$(value_for ACCOMMODATION_DETAIL_CACHE_REDIS_PORT)
normalized_general_host=$(printf '%s' "$general_host" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | tr '[:upper:]' '[:lower:]')
normalized_cache_host=$(printf '%s' "$cache_host" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | tr '[:upper:]' '[:lower:]')

if [[ "$normalized_general_host:$general_port" == "$normalized_cache_host:$cache_port" ]]; then
  printf 'runtime env Redis endpoints must be distinct\n' >&2
  exit 1
fi

[[ "$general_host" == redis-general.lab.airbob.internal && "$general_port" == 6379 ]] || {
  printf 'runtime env general Redis endpoint is invalid\n' >&2
  exit 1
}
[[ "$cache_host" == redis-cache.lab.airbob.internal && "$cache_port" == 6380 ]] || {
  printf 'runtime env cache Redis endpoint is invalid\n' >&2
  exit 1
}
[[ "$(value_for KAFKA_BOOTSTRAP_SERVERS)" == kafka.lab.airbob.internal:9092 ]] || {
  printf 'runtime env Kafka endpoint is invalid\n' >&2
  exit 1
}
[[ "$(value_for ELASTICSEARCH_URIS)" == http://elasticsearch.lab.airbob.internal:9200 ]] || {
  printf 'runtime env Elasticsearch endpoint is invalid\n' >&2
  exit 1
}

printf 'runtime env verification passed\n'
