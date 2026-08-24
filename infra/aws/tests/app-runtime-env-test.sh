#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
aws_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
verifier="$aws_dir/scripts/verify-app-runtime-env.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-app-runtime-env.XXXXXX")
valid_env="$temp_dir/valid.env"
secret='task-3-password-must-not-be-printed'
synthetic_secret='  ${TASK3_AMBIENT_SECRET} "double quoted" '\''single quoted'\'' # literal suffix  '

cleanup() {
  status=$?
  trap - EXIT
  rm -rf "$temp_dir"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

write_valid_env() {
  profile=$1
  output=$2
  cat > "$output" <<EOF
# Blank lines and full-line comments are not keys.

SPRING_PROFILES_ACTIVE=$profile
TOSS_PAYMENTS_ENABLED=false
GOOGLE_API_ENABLED=false
AWS_S3_WRITE_ENABLED=false
OPERATOR_ALERT_SLACK_ENABLED=false
SPRING_DATASOURCE_URL=jdbc:mysql://mysql.lab.airbob.internal:3306/airbob
SPRING_DATASOURCE_USERNAME=airbob
SPRING_DATASOURCE_PASSWORD=$secret
REDIS_HOST=redis-general.lab.airbob.internal
REDIS_PORT=6379
ACCOMMODATION_DETAIL_CACHE_ENABLED=false
ACCOMMODATION_DETAIL_CACHE_REDIS_HOST=redis-cache.lab.airbob.internal
ACCOMMODATION_DETAIL_CACHE_REDIS_PORT=6380
KAFKA_BOOTSTRAP_SERVERS=kafka.lab.airbob.internal:9092
ELASTICSEARCH_URIS=http://elasticsearch.lab.airbob.internal:9200
ELASTICSEARCH_USERNAME=
ELASTIC_PASSWORD=
AWS_S3_BUCKET_NAME=airbob-performance-lab
CLOUDFRONT_DOMAIN=assets.lab.airbob.internal
EOF
}

replace_key() {
  key=$1
  value=$2
  input=$3
  output=$4
  awk -v key="$key" -v value="$value" '
    index($0, key "=") == 1 { print key "=" value; next }
    { print }
  ' "$input" > "$output"
}

remove_key() {
  key=$1
  input=$2
  output=$3
  awk -v key="$key" 'index($0, key "=") != 1 { print }' "$input" > "$output"
}

run_verifier() {
  "$verifier" "$@"
}

expect_success() {
  description=$1
  shift
  output="$temp_dir/success-output"
  if ! run_verifier "$@" >"$output" 2>&1; then
    printf 'expected %s to pass verification\n' "$description" >&2
    if grep -F "$secret" "$output" >/dev/null \
      || grep -F "$synthetic_secret" "$output" >/dev/null; then
      printf '[verifier output contained the datasource password and was redacted]\n' >&2
    else
      sed 's/^/[verifier] /' "$output" >&2
    fi
    exit 1
  fi
  if grep -F "$secret" "$output" >/dev/null \
    || grep -F "$synthetic_secret" "$output" >/dev/null; then
    printf 'verifier disclosed the datasource password while accepting %s\n' "$description" >&2
    exit 1
  fi
}

expect_failure() {
  description=$1
  shift
  output="$temp_dir/failure-output"
  if run_verifier "$@" >"$output" 2>&1; then
    printf 'expected %s to fail verification\n' "$description" >&2
    exit 1
  fi
  if grep -F "$secret" "$output" >/dev/null \
    || grep -F "$synthetic_secret" "$output" >/dev/null; then
    printf 'verifier disclosed the datasource password while rejecting %s\n' "$description" >&2
    exit 1
  fi
}

write_valid_env 'aws,performance-lab' "$valid_env"
expect_success 'integrated-smoke runtime env with cache disabled' "$valid_env" integrated-smoke

integrated_cache_enabled_env="$temp_dir/integrated-cache-enabled.env"
replace_key ACCOMMODATION_DETAIL_CACHE_ENABLED true "$valid_env" "$integrated_cache_enabled_env"
expect_success 'integrated-smoke runtime env with cache enabled' "$integrated_cache_enabled_env" integrated-smoke

isolated_env="$temp_dir/isolated.env"
write_valid_env 'aws,traffic-benchmark' "$isolated_env"
expect_success 'isolated-read runtime env with cache disabled' "$isolated_env" isolated-read

isolated_cache_enabled_env="$temp_dir/isolated-cache-enabled.env"
replace_key ACCOMMODATION_DETAIL_CACHE_ENABLED true "$isolated_env" "$isolated_cache_enabled_env"
expect_success 'isolated-read runtime env with cache enabled' "$isolated_cache_enabled_env" isolated-read
expect_failure 'integrated profile under isolated-read policy' "$valid_env" isolated-read
expect_failure 'isolated profile under integrated-smoke policy' "$isolated_env" integrated-smoke
expect_failure 'unknown policy' "$valid_env" unknown-policy
expect_failure 'missing policy argument' "$valid_env"
expect_failure 'missing runtime env file' "$temp_dir/absent.env" integrated-smoke
expect_failure 'non-regular runtime env file' /dev/null integrated-smoke

for guard in \
  TOSS_PAYMENTS_ENABLED \
  GOOGLE_API_ENABLED \
  AWS_S3_WRITE_ENABLED \
  OPERATOR_ALERT_SLACK_ENABLED
do
  fixture="$temp_dir/non-false-$guard.env"
  replace_key "$guard" true "$valid_env" "$fixture"
  expect_failure "$guard not set to false" "$fixture" integrated-smoke
done

for mutation in \
  'REDIS_HOST=wrong.lab.airbob.internal' \
  'REDIS_PORT=6380' \
  'ACCOMMODATION_DETAIL_CACHE_REDIS_HOST=wrong.lab.airbob.internal' \
  'ACCOMMODATION_DETAIL_CACHE_REDIS_PORT=6379' \
  'KAFKA_BOOTSTRAP_SERVERS=kafka.lab.airbob.internal:19092' \
  'ELASTICSEARCH_URIS=https://elasticsearch.lab.airbob.internal:9200'
do
  key=${mutation%%=*}
  value=${mutation#*=}
  fixture="$temp_dir/wrong-$key.env"
  replace_key "$key" "$value" "$valid_env" "$fixture"
  expect_failure "wrong $key endpoint" "$fixture" integrated-smoke
done

same_redis="$temp_dir/same-redis.env"
replace_key ACCOMMODATION_DETAIL_CACHE_REDIS_HOST '  REDIS-GENERAL.LAB.AIRBOB.INTERNAL  ' "$valid_env" "$same_redis"
replace_key ACCOMMODATION_DETAIL_CACHE_REDIS_PORT 6379 "$same_redis" "$same_redis.tmp"
mv "$same_redis.tmp" "$same_redis"
expect_failure 'identical normalized Redis endpoint tuples' "$same_redis" integrated-smoke

duplicate="$temp_dir/duplicate.env"
cp "$valid_env" "$duplicate"
printf 'REDIS_PORT=6379\n' >> "$duplicate"
expect_failure 'duplicate key' "$duplicate" integrated-smoke

duplicate_cache_toggle="$temp_dir/duplicate-cache-toggle.env"
cp "$valid_env" "$duplicate_cache_toggle"
printf 'ACCOMMODATION_DETAIL_CACHE_ENABLED=true\n' >> "$duplicate_cache_toggle"
expect_failure 'duplicate cache toggle' "$duplicate_cache_toggle" integrated-smoke

missing_cache_toggle="$temp_dir/missing-cache-toggle.env"
remove_key ACCOMMODATION_DETAIL_CACHE_ENABLED "$valid_env" "$missing_cache_toggle"
expect_failure 'missing cache toggle' "$missing_cache_toggle" integrated-smoke

for invalid_cache_toggle in \
  '' \
  TRUE \
  False \
  1 \
  yes \
  ' true' \
  'false '
do
  fixture="$temp_dir/invalid-cache-toggle-$RANDOM.env"
  replace_key ACCOMMODATION_DETAIL_CACHE_ENABLED "$invalid_cache_toggle" "$valid_env" "$fixture"
  expect_failure 'invalid cache toggle' "$fixture" integrated-smoke
done

missing="$temp_dir/missing.env"
remove_key CLOUDFRONT_DOMAIN "$valid_env" "$missing"
expect_failure 'missing required key' "$missing" integrated-smoke

for malformed in \
  'REDIS_HOST' \
  '=value' \
  'REDIS HOST=value' \
  ' REDIS_HOST=value' \
  'REDIS_HOST =value'
do
  fixture="$temp_dir/malformed-$RANDOM.env"
  cp "$valid_env" "$fixture"
  printf '%s\n' "$malformed" >> "$fixture"
  expect_failure 'malformed field or whitespace around name' "$fixture" integrated-smoke
done

for key in \
  PAYMENT_TOSS_ENABLED \
  CLOUD_AWS_S3_WRITEENABLED \
  SPRING_APPLICATION_JSON \
  JAVA_TOOL_OPTIONS \
  JDK_JAVA_OPTIONS \
  SPRING_CONFIG_ADDITIONAL_LOCATION \
  SPRING_CONFIG_LOCATION \
  SPRING_CONFIG_IMPORT \
  TOSS_SECRET_KEY \
  GOOGLE_API_KEY \
  SLACK_NOTIFICATION_ENABLED \
  SLACK_WEBHOOK_URL \
  AWS_ACCESS_KEY_ID \
  AWS_SECRET_ACCESS_KEY \
  ARBITRARY_METADATA
do
  fixture="$temp_dir/extra-$key.env"
  cp "$valid_env" "$fixture"
  printf '%s=blocked\n' "$key" >> "$fixture"
  expect_failure "unlisted key $key" "$fixture" integrated-smoke
done

for key in \
  SPRING_PROFILES_ACTIVE \
  TOSS_PAYMENTS_ENABLED \
  GOOGLE_API_ENABLED \
  AWS_S3_WRITE_ENABLED \
  OPERATOR_ALERT_SLACK_ENABLED \
  SPRING_DATASOURCE_URL \
  SPRING_DATASOURCE_USERNAME \
  SPRING_DATASOURCE_PASSWORD \
  REDIS_HOST \
  REDIS_PORT \
  ACCOMMODATION_DETAIL_CACHE_REDIS_HOST \
  ACCOMMODATION_DETAIL_CACHE_REDIS_PORT \
  KAFKA_BOOTSTRAP_SERVERS \
  ELASTICSEARCH_URIS \
  AWS_S3_BUCKET_NAME \
  CLOUDFRONT_DOMAIN
do
  fixture="$temp_dir/empty-$key.env"
  replace_key "$key" '' "$valid_env" "$fixture"
  expect_failure "empty value for $key" "$fixture" integrated-smoke
done

not_sourced_marker="$temp_dir/env-was-sourced"
not_sourced="$temp_dir/not-sourced.env"
replace_key CLOUDFRONT_DOMAIN "\$(touch $not_sourced_marker)" "$valid_env" "$not_sourced"
expect_success 'literal shell syntax in an allowed value' "$not_sourced" integrated-smoke
if [[ -e "$not_sourced_marker" ]]; then
  printf 'verifier sourced or evaluated the runtime env file\n' >&2
  exit 1
fi

raw_secret_env="$temp_dir/raw-secret.env"
replace_key SPRING_DATASOURCE_PASSWORD "$synthetic_secret" "$valid_env" "$raw_secret_env"
expect_success 'raw synthetic datasource secret' "$raw_secret_env" integrated-smoke

compose_env="$temp_dir/compose.env"
compose_config="$temp_dir/compose-config.json"
compose_stderr="$temp_dir/compose-stderr"
printf '%s\n' \
  'APP_IMAGE=registry.example.invalid/airbob/app@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
  'NODE_EXPORTER_IMAGE=registry.example.invalid/airbob/node-exporter@sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd' \
  "APP_ENV_FILE=$raw_secret_env" > "$compose_env"

if ! TASK3_AMBIENT_SECRET='ambient-value-must-not-replace-file-bytes' \
  docker compose \
    --env-file "$compose_env" \
    -f "$aws_dir/bundles/app/compose.yml" \
    config --format json >"$compose_config" 2>"$compose_stderr"
then
  printf 'expected app bundle to render the raw runtime env file\n' >&2
  exit 1
fi
if grep -F "$synthetic_secret" "$compose_stderr" >/dev/null; then
  printf 'docker compose disclosed the synthetic datasource secret on stderr\n' >&2
  exit 1
fi
expected_config_field='"SPRING_DATASOURCE_PASSWORD": "  $${TASK3_AMBIENT_SECRET} \"double quoted\" '\''single quoted'\'' # literal suffix  "'
if ! grep -Fq "$expected_config_field" "$compose_config"; then
  printf 'effective app datasource password did not preserve the verified bytes\n' >&2
  exit 1
fi

printf 'app runtime env tests passed\n'
