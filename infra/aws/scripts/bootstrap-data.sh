#!/usr/bin/env bash
set -euo pipefail
umask 077

required_environment=(
  AIRBOB_REGION AIRBOB_RUN_ID AIRBOB_DATASET_BUCKET AIRBOB_EVIDENCE_BUCKET
  AIRBOB_DATASET_RELEASE AIRBOB_DATASET_MANIFEST_SHA256 AIRBOB_DATABASE_BOOTSTRAP
  AIRBOB_RDS_ENDPOINT AIRBOB_RDS_RESOURCE_ID AIRBOB_RDS_ENGINE_VERSION
  AIRBOB_RDS_MASTER_SECRET_ARN AIRBOB_DEBEZIUM_SECRET_ARN
  AIRBOB_ELASTICSEARCH_IMAGE_DIGEST AIRBOB_DATASET_VALIDATOR AIRBOB_COUPON_LUA_FILE
)
for environment_name in "${required_environment[@]}"; do
  [[ -n "${!environment_name:-}" ]] || { printf 'missing bootstrap environment: %s\n' "$environment_name" >&2; exit 1; }
done
case "$AIRBOB_DATABASE_BOOTSTRAP" in dump|snapshot) ;; *) printf '%s\n' 'unsupported database bootstrap mode' >&2; exit 1 ;; esac
[[ "$AIRBOB_DATASET_RELEASE" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ ]] || { printf '%s\n' 'unsafe dataset release' >&2; exit 1; }
[[ "$AIRBOB_DATASET_MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]] || { printf '%s\n' 'unsafe dataset manifest digest' >&2; exit 1; }
[[ "$AIRBOB_ELASTICSEARCH_IMAGE_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || { printf '%s\n' 'unsafe Elasticsearch image digest' >&2; exit 1; }
[[ -x "$AIRBOB_DATASET_VALIDATOR" && -f "$AIRBOB_COUPON_LUA_FILE" ]] || { printf '%s\n' 'trusted bootstrap helpers are missing' >&2; exit 1; }

command -v aws >/dev/null 2>&1 || { printf '%s\n' 'AWS CLI is required' >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { printf '%s\n' 'jq is required' >&2; exit 1; }
if ! command -v mysql >/dev/null 2>&1 || ! command -v zstd >/dev/null 2>&1; then
  dnf install -y mariadb105 zstd || dnf install -y mariadb zstd
fi
command -v mysql >/dev/null 2>&1 && command -v zstd >/dev/null 2>&1 \
  || { printf '%s\n' 'MySQL client and zstd are required' >&2; exit 1; }
for runtime_command in curl docker openssl sha256sum; do
  command -v "$runtime_command" >/dev/null 2>&1 \
    || { printf 'required bootstrap command is unavailable: %s\n' "$runtime_command" >&2; exit 1; }
done

work_root=/var/lib/airbob/data-bootstrap
release_root="$work_root/release"
secret_root="$work_root/secrets"
install -d -m 700 "$release_root/mysql" "$secret_root"
manifest="$release_root/manifest.json"
dump="$release_root/mysql/airbob.sql.zst"
checksum="$release_root/mysql/sha256.txt"
dataset_uri="s3://$AIRBOB_DATASET_BUCKET/datasets/$AIRBOB_DATASET_RELEASE"
master_secret_file="$secret_root/rds-master.json"
debezium_secret_file="$secret_root/debezium.json"
connector_payload="$secret_root/connector.json"
cleanup() {
  unset MYSQL_PWD master_password debezium_password
  rm -f "$master_secret_file" "$debezium_secret_file" "$connector_payload"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
cleanup

aws --region "$AIRBOB_REGION" s3 cp "$dataset_uri/manifest.json" "$manifest" --only-show-errors
actual_manifest_sha=$(sha256sum "$manifest" | awk '{print $1}')
[[ "$actual_manifest_sha" == "$AIRBOB_DATASET_MANIFEST_SHA256" ]] \
  || { printf '%s\n' 'dataset manifest digest mismatch' >&2; exit 1; }
release_kind=$(jq -r '.releaseKind' "$manifest")
search_enabled=$(jq -r '.search.enabled' "$manifest")
aws --region "$AIRBOB_REGION" s3 cp "$dataset_uri/mysql/sha256.txt" "$checksum" --only-show-errors
if [[ "$AIRBOB_DATABASE_BOOTSTRAP" == dump ]]; then
  aws --region "$AIRBOB_REGION" s3 cp "$dataset_uri/mysql/airbob.sql.zst" "$dump" --only-show-errors
fi
if [[ "$search_enabled" == true ]]; then
  install -d -m 700 "$release_root/elasticsearch"
  aws --region "$AIRBOB_REGION" s3 cp \
    "$dataset_uri/elasticsearch/snapshot-reference.json" \
    "$release_root/elasticsearch/snapshot-reference.json" --only-show-errors
fi
if [[ "$AIRBOB_DATABASE_BOOTSTRAP" == dump ]]; then
  "$AIRBOB_DATASET_VALIDATOR" "$release_root" "$AIRBOB_DATASET_RELEASE" "$release_kind" >/dev/null
else
  "$AIRBOB_DATASET_VALIDATOR" "$release_root" "$AIRBOB_DATASET_RELEASE" "$release_kind" --metadata-only >/dev/null
fi
[[ "$(jq -r '.search.imageDigest // empty' "$manifest")" == "$AIRBOB_ELASTICSEARCH_IMAGE_DIGEST" || "$search_enabled" == false ]] \
  || { printf '%s\n' 'dataset Elasticsearch image digest mismatch' >&2; exit 1; }

aws --region "$AIRBOB_REGION" secretsmanager get-secret-value \
  --secret-id "$AIRBOB_RDS_MASTER_SECRET_ARN" --query SecretString --output text > "$master_secret_file"
chmod 600 "$master_secret_file"
master_username=$(jq -r '.username' "$master_secret_file")
master_password=$(jq -r '.password' "$master_secret_file")
[[ "$master_username" =~ ^[a-zA-Z][a-zA-Z0-9_]{0,31}$ && -n "$master_password" ]] \
  || { printf '%s\n' 'RDS managed secret has an invalid contract' >&2; exit 1; }

mysql_exec() {
  MYSQL_PWD="$master_password" mysql \
    --protocol=TCP --host="$AIRBOB_RDS_ENDPOINT" --port=3306 --user="$master_username" \
    --ssl --batch --raw --skip-column-names "$@"
}
for attempt in $(seq 1 120); do
  if mysql_exec --execute='SELECT 1' >/dev/null 2>&1; then
    break
  fi
  [[ "$attempt" -lt 120 ]] || { printf '%s\n' 'RDS did not become ready' >&2; exit 1; }
  sleep 10
done

if [[ "$AIRBOB_DATABASE_BOOTSTRAP" == dump ]]; then
  zstd --decompress --stdout "$dump" | mysql_exec >/dev/null
fi

mysql_exec --execute="CALL mysql.rds_set_configuration('binlog retention hours', 24);" >/dev/null
mysql_exec --execute="
  UPDATE performance_schema.setup_consumers
  SET ENABLED = 'YES'
  WHERE NAME IN ('events_statements_current', 'events_statements_history', 'events_statements_history_long');
  UPDATE performance_schema.setup_instruments
  SET ENABLED = 'YES', TIMED = 'YES'
  WHERE NAME LIKE 'statement/%';
" >/dev/null

for variable_contract in 'binlog_format:ROW' 'binlog_row_image:FULL' 'performance_schema:ON'; do
  variable_name=${variable_contract%%:*}
  expected_value=${variable_contract#*:}
  actual_value=$(mysql_exec --execute="SHOW GLOBAL VARIABLES LIKE '$variable_name'" | awk '{print $2}')
  [[ "$actual_value" =~ ^($expected_value)$ ]] || { printf 'RDS variable contract failed: %s\n' "$variable_name" >&2; exit 1; }
done
time_zone=$(mysql_exec --execute="SHOW GLOBAL VARIABLES LIKE 'time_zone'" | awk '{print $2}')
[[ "$time_zone" == UTC || "$time_zone" == +00:00 ]] \
  || { printf '%s\n' 'RDS timezone contract failed' >&2; exit 1; }

outbox_policy=$(jq -r '.mysql.outboxPolicy' "$manifest")
if [[ "$outbox_policy" == truncate-after-import ]]; then
  mysql_exec airbobdb --execute='TRUNCATE TABLE outbox;' >/dev/null
fi
outbox_count=$(mysql_exec airbobdb --execute='SELECT COUNT(*) FROM outbox')
[[ "$outbox_count" == 0 ]] || { printf '%s\n' 'dataset outbox is not empty' >&2; exit 1; }

flyway_version=$(mysql_exec airbobdb --execute='SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1')
[[ "$flyway_version" == "$(jq -r '.mysql.flywayVersion' "$manifest")" ]] \
  || { printf '%s\n' 'restored Flyway lineage does not match the dataset release' >&2; exit 1; }
migration_file="$work_root/flyway-migrations.tsv"
mysql_exec airbobdb --execute="
  SELECT installed_rank, COALESCE(version, '<NULL>'), description, type, script,
         COALESCE(checksum, '<NULL>'), success
  FROM flyway_schema_history
  ORDER BY installed_rank;
" > "$migration_file"
migration_checksum=$(sha256sum "$migration_file" | awk '{print $1}')
[[ "$migration_checksum" == "$(jq -r '.mysql.migrationChecksumSha256' "$manifest")" ]] \
  || { printf '%s\n' 'Flyway migration checksum does not match the dataset release' >&2; exit 1; }

while IFS=$'\t' read -r table_name expected_rows; do
  [[ "$table_name" =~ ^[a-z][a-z0-9_]{0,63}$ && "$expected_rows" =~ ^[0-9]+$ ]] \
    || { printf '%s\n' 'unsafe expected table-row contract' >&2; exit 1; }
  actual_rows=$(mysql_exec airbobdb --execute="SELECT COUNT(*) FROM \`$table_name\`")
  [[ "$actual_rows" == "$expected_rows" ]] || { printf 'row-count contract failed: %s\n' "$table_name" >&2; exit 1; }
done < <(jq -r '.mysql.expectedTableRows | to_entries | sort_by(.key)[] | [.key, (.value | tostring)] | @tsv' "$manifest")

schema_file="$work_root/schema-fingerprint.tsv"
mysql_exec --execute="
  SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE,
         COALESCE(COLUMN_DEFAULT, '<NULL>'), EXTRA, COLLATION_NAME
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'airbobdb'
  ORDER BY TABLE_NAME, ORDINAL_POSITION;
" > "$schema_file"
schema_fingerprint=$(sha256sum "$schema_file" | awk '{print $1}')
[[ "$schema_fingerprint" == "$(jq -r '.mysql.schemaFingerprintSha256' "$manifest")" ]] \
  || { printf '%s\n' 'schema fingerprint does not match the dataset release' >&2; exit 1; }

debezium_username=airbob_debezium
if aws --region "$AIRBOB_REGION" secretsmanager get-secret-value \
  --secret-id "$AIRBOB_DEBEZIUM_SECRET_ARN" --query SecretString --output text > "$debezium_secret_file" 2>/dev/null; then
  debezium_password=$(jq -r '.password' "$debezium_secret_file")
else
  debezium_password=$(openssl rand -hex 32)
  jq -n --arg username "$debezium_username" --arg password "$debezium_password" \
    '{username: $username, password: $password}' > "$debezium_secret_file"
  aws --region "$AIRBOB_REGION" secretsmanager put-secret-value \
    --secret-id "$AIRBOB_DEBEZIUM_SECRET_ARN" --secret-string "file://$debezium_secret_file" >/dev/null
fi
chmod 600 "$debezium_secret_file"
[[ "$debezium_password" =~ ^[0-9a-f]{64}$ ]] || { printf '%s\n' 'Debezium secret has an invalid contract' >&2; exit 1; }
mysql_exec >/dev/null <<AIRBOB_DEBEZIUM_SQL
CREATE USER IF NOT EXISTS '$debezium_username'@'%' IDENTIFIED BY '$debezium_password';
ALTER USER '$debezium_username'@'%' IDENTIFIED BY '$debezium_password';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT, LOCK TABLES ON *.* TO '$debezium_username'@'%';
AIRBOB_DEBEZIUM_SQL

if [[ "$search_enabled" == true ]]; then
  snapshot_reference="$release_root/elasticsearch/snapshot-reference.json"
  repository=$(jq -r '.repository' "$snapshot_reference")
  snapshot_bucket=$(jq -r '.bucket' "$snapshot_reference")
  snapshot_base_path=$(jq -r '.basePath' "$snapshot_reference")
  snapshot_name=$(jq -r '.snapshot' "$snapshot_reference")
  expected_documents=$(jq -r '.documentCount' "$snapshot_reference")
  expected_mapping_sha=$(jq -r '.mappingSha256' "$snapshot_reference")

  elasticsearch_info=$(curl --fail --silent --show-error http://elasticsearch.lab.airbob.internal:9200/)
  [[ "$(jq -r '.version.number' <<<"$elasticsearch_info")" == 8.18.8 ]] \
    || { printf '%s\n' 'Elasticsearch version does not match the dataset release' >&2; exit 1; }
  plugin_info=$(curl --fail --silent --show-error 'http://elasticsearch.lab.airbob.internal:9200/_nodes/plugins?filter_path=nodes.*.modules.name,nodes.*.plugins.name')
  for plugin in analysis-nori repository-s3; do
    jq -e --arg plugin "$plugin" '[.. | objects | .name? // empty] | index($plugin) != null' <<<"$plugin_info" >/dev/null \
      || { printf 'required Elasticsearch plugin is missing: %s\n' "$plugin" >&2; exit 1; }
  done
  repository_body=$(jq -n --arg bucket "$snapshot_bucket" --arg basePath "$snapshot_base_path" \
    '{type: "s3", settings: {bucket: $bucket, base_path: $basePath, readonly: true}}')
  curl --fail --silent --show-error --request PUT \
    --header 'Content-Type: application/json' --data-binary "$repository_body" \
    "http://elasticsearch.lab.airbob.internal:9200/_snapshot/$repository" >/dev/null
  curl --silent --show-error --request DELETE \
    'http://elasticsearch.lab.airbob.internal:9200/accommodations' >/dev/null || true
  restore_body='{"indices":"accommodations","include_global_state":false,"index_settings":{"index.number_of_replicas":0}}'
  curl --fail --silent --show-error --request POST --header 'Content-Type: application/json' \
    --data-binary "$restore_body" \
    "http://elasticsearch.lab.airbob.internal:9200/_snapshot/$repository/$snapshot_name/_restore?wait_for_completion=true" >/dev/null
  restored_documents=$(curl --fail --silent --show-error \
    'http://elasticsearch.lab.airbob.internal:9200/accommodations/_count' | jq -r '.count')
  [[ "$restored_documents" == "$expected_documents" ]] \
    || { printf '%s\n' 'Elasticsearch document count does not match the snapshot reference' >&2; exit 1; }
  mapping_file="$work_root/elasticsearch-mapping.json"
  curl --fail --silent --show-error \
    'http://elasticsearch.lab.airbob.internal:9200/accommodations/_mapping' \
    | jq -S '.accommodations.mappings' > "$mapping_file"
  [[ "$(sha256sum "$mapping_file" | awk '{print $1}')" == "$expected_mapping_sha" ]] \
    || { printf '%s\n' 'Elasticsearch mapping fingerprint does not match the snapshot reference' >&2; exit 1; }

  database_ids="$work_root/database-accommodation-ids.txt"
  elasticsearch_ids="$work_root/elasticsearch-accommodation-ids.txt"
  elasticsearch_content="$work_root/elasticsearch-content.jsonl"
  elasticsearch_page="$work_root/elasticsearch-page.json"
  mysql_exec airbobdb --execute="SELECT id FROM accommodation WHERE status = 'PUBLISHED' ORDER BY id" > "$database_ids"
  : > "$elasticsearch_ids"
  : > "$elasticsearch_content"
  curl --fail --silent --show-error --request POST --header 'Content-Type: application/json' \
    --data-binary '{"size":1000,"sort":["_doc"],"_source":true}' \
    'http://elasticsearch.lab.airbob.internal:9200/accommodations/_search?scroll=2m' > "$elasticsearch_page"
  while :; do
    jq -e '.timed_out == false and ._shards.failed == 0' "$elasticsearch_page" >/dev/null \
      || { printf '%s\n' 'Elasticsearch scroll did not complete cleanly' >&2; exit 1; }
    page_hits=$(jq '.hits.hits | length' "$elasticsearch_page")
    scroll_id=$(jq -r '._scroll_id' "$elasticsearch_page")
    [[ "$page_hits" =~ ^[0-9]+$ && "$scroll_id" != null && -n "$scroll_id" ]] \
      || { printf '%s\n' 'Elasticsearch scroll response is invalid' >&2; exit 1; }
    [[ "$page_hits" -gt 0 ]] || break
    jq -r '.hits.hits[]._source.accommodationId' "$elasticsearch_page" >> "$elasticsearch_ids"
    jq -S -c '.hits.hits[]._source' "$elasticsearch_page" >> "$elasticsearch_content"
    scroll_body=$(jq -n --arg scrollId "$scroll_id" '{scroll: "2m", scroll_id: $scrollId}')
    curl --fail --silent --show-error --request POST --header 'Content-Type: application/json' \
      --data-binary "$scroll_body" 'http://elasticsearch.lab.airbob.internal:9200/_search/scroll' \
      > "$elasticsearch_page.next"
    mv "$elasticsearch_page.next" "$elasticsearch_page"
  done
  scroll_delete_body=$(jq -n --arg scrollId "$scroll_id" '{scroll_id: [$scrollId]}')
  curl --fail --silent --show-error --request DELETE --header 'Content-Type: application/json' \
    --data-binary "$scroll_delete_body" 'http://elasticsearch.lab.airbob.internal:9200/_search/scroll' >/dev/null

  awk 'NF != 1 || $1 !~ /^[1-9][0-9]*$/ { exit 1 }' "$database_ids" \
    || { printf '%s\n' 'database accommodation id stream is invalid' >&2; exit 1; }
  awk 'NF != 1 || $1 !~ /^[1-9][0-9]*$/ { exit 1 }' "$elasticsearch_ids" \
    || { printf '%s\n' 'Elasticsearch accommodation id stream is invalid' >&2; exit 1; }
  LC_ALL=C sort -n "$database_ids" -o "$database_ids"
  LC_ALL=C sort -n "$elasticsearch_ids" -o "$elasticsearch_ids"
  LC_ALL=C sort "$elasticsearch_content" -o "$elasticsearch_content"
  [[ "$(wc -l < "$database_ids" | tr -d ' ')" == "$expected_documents" && \
      "$(wc -l < "$elasticsearch_ids" | tr -d ' ')" == "$expected_documents" ]] \
    || { printf '%s\n' 'cross-store accommodation id counts do not match the release' >&2; exit 1; }
  [[ "$(sha256sum "$database_ids" | awk '{print $1}')" == "$(jq -r '.dbIdsSha256' "$snapshot_reference")" ]] \
    || { printf '%s\n' 'database accommodation id fingerprint does not match the release' >&2; exit 1; }
  [[ "$(sha256sum "$elasticsearch_ids" | awk '{print $1}')" == "$(jq -r '.esIdsSha256' "$snapshot_reference")" ]] \
    || { printf '%s\n' 'Elasticsearch accommodation id fingerprint does not match the release' >&2; exit 1; }
  [[ "$(sha256sum "$elasticsearch_content" | awk '{print $1}')" == "$(jq -r '.contentFingerprintSha256' "$snapshot_reference")" ]] \
    || { printf '%s\n' 'Elasticsearch content fingerprint does not match the release' >&2; exit 1; }
  search_state=restored
else
  search_state=skipped
fi

redis_image=$(awk -F= '$1 == "REDIS_IMAGE" {print substr($0, index($0, "=") + 1)}' /etc/airbob/images.env)
[[ "$redis_image" =~ @sha256:[0-9a-f]{64}$ ]] || { printf '%s\n' 'immutable Redis image is unavailable' >&2; exit 1; }
redis_cli() {
  local port=$1
  shift
  docker run --rm --network host "$redis_image" redis-cli --host redis-general.lab.airbob.internal --port "$port" "$@"
}
redis_cli 6379 FLUSHDB >/dev/null
redis_cli 6380 FLUSHDB >/dev/null
coupon_count=$(jq '.couponPreparation | length' "$manifest")
while IFS=$'\t' read -r coupon_id expected_quantity; do
  [[ "$coupon_id" =~ ^[1-9][0-9]*$ && "$expected_quantity" =~ ^[0-9]+$ ]] \
    || { printf '%s\n' 'unsafe coupon preparation contract' >&2; exit 1; }
  coupon_row=$(mysql_exec airbobdb --execute="
    SELECT id, total_quantity,
           UNIX_TIMESTAMP(issue_start_at) * 1000,
           UNIX_TIMESTAMP(issue_end_at) * 1000,
           IF(is_active, 1, 0),
           (UNIX_TIMESTAMP(issue_end_at) + 604800) * 1000,
           issued_quantity,
           IF(redis_stock_prepared_at IS NULL, 0, 1)
    FROM coupon WHERE id = $coupon_id;
  ")
  IFS=$'\t' read -r actual_id total_quantity issue_start issue_end active expires_at issued_quantity already_prepared <<<"$coupon_row"
  [[ "$actual_id" == "$coupon_id" && "$total_quantity" == "$expected_quantity" && "$active" == 1 && "$issued_quantity" == 0 && "$already_prepared" == 0 ]] \
    || { printf 'coupon preparation invariant failed: %s\n' "$coupon_id" >&2; exit 1; }
  prepare_result=$(docker run --rm --network host \
    --volume "$AIRBOB_COUPON_LUA_FILE:/tmp/coupon_prepare.lua:ro" "$redis_image" \
    redis-cli --host redis-general.lab.airbob.internal --port 6379 --raw \
    --eval /tmp/coupon_prepare.lua "coupon:{$coupon_id}:meta" "coupon:{$coupon_id}:issued" , \
    "$total_quantity" "$issue_start" "$issue_end" 1 "$expires_at" 0)
  [[ "$prepare_result" == 1 ]] || { printf 'coupon preparation failed: %s\n' "$coupon_id" >&2; exit 1; }
  mysql_exec airbobdb --execute="UPDATE coupon SET redis_stock_prepared_at = UTC_TIMESTAMP(6) WHERE id = $coupon_id" >/dev/null
done < <(jq -r '.couponPreparation[] | [.couponId, .quantity] | @tsv' "$manifest")
[[ "$(redis_cli 6379 DBSIZE | tr -d '\r')" == "$coupon_count" ]] \
  || { printf '%s\n' 'general Redis contains undeclared bootstrap keys' >&2; exit 1; }
[[ "$(redis_cli 6380 DBSIZE | tr -d '\r')" == 0 ]] \
  || { printf '%s\n' 'detail-cache Redis must start empty' >&2; exit 1; }
redis_state=$([[ "$coupon_count" -eq 0 ]] && printf empty || printf coupon-prepared)

debezium_compose=/opt/airbob/release/infra/aws/bundles/debezium/compose.yml
compose=(docker compose --env-file /etc/airbob/images.env -f "$debezium_compose")
kafka_exec=("${compose[@]}" exec --no-TTY debezium env KAFKA_OPTS= KAFKA_HEAP_OPTS=-Xms64m\ -Xmx64m)
while IFS=$'\t' read -r topic partitions retention_ms; do
  [[ "$topic" =~ ^[A-Z]+\.events$ && "$partitions" =~ ^[1-9][0-9]*$ && "$retention_ms" =~ ^[1-9][0-9]*$ ]] \
    || { printf '%s\n' 'unsafe Kafka topic contract' >&2; exit 1; }
  "${kafka_exec[@]}" /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka.lab.airbob.internal:9092 --create --if-not-exists \
    --topic "$topic" --partitions "$partitions" --replication-factor 1 \
    --config "retention.ms=$retention_ms" >/dev/null
  topic_description=$("${kafka_exec[@]}" /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka.lab.airbob.internal:9092 --describe --topic "$topic")
  actual_partitions=$(awk '{ for (i = 1; i <= NF; i++) if ($i == "PartitionCount:") { print $(i + 1); exit } }' <<<"$topic_description")
  [[ "$actual_partitions" == "$partitions" ]] \
    || { printf 'Kafka partition contract failed: %s\n' "$topic" >&2; exit 1; }
  topic_config=$("${kafka_exec[@]}" /opt/kafka/bin/kafka-configs.sh \
    --bootstrap-server kafka.lab.airbob.internal:9092 --describe \
    --entity-type topics --entity-name "$topic")
  [[ "$topic_config" =~ retention\.ms=([0-9]+) && "${BASH_REMATCH[1]}" == "$retention_ms" ]] \
    || { printf 'Kafka retention contract failed: %s\n' "$topic" >&2; exit 1; }
  topic_offsets=$("${kafka_exec[@]}" /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server kafka.lab.airbob.internal:9092 --topic "$topic" --time -1)
  if [[ -z "$topic_offsets" ]] || ! awk -F: -v expected="$partitions" \
    'NF != 3 || $3 != 0 { exit 1 } END { if (NR != expected) exit 1 }' <<<"$topic_offsets"; then
    printf 'Kafka topic is not empty or complete: %s\n' "$topic" >&2
    exit 1
  fi
done < <(jq -r '.kafka.topics[] | [.name, .partitions, .retentionMs] | @tsv' "$manifest")

connector_template=/opt/airbob/release/infra/aws/bundles/debezium/connector.aws.json.tmpl
jq --arg endpoint "$AIRBOB_RDS_ENDPOINT" --arg username "$debezium_username" --rawfile password "$debezium_secret_file" '
  ($password | fromjson | .password) as $secret |
  walk(if type == "string" then
    gsub("\\$\\{RDS_ENDPOINT\\}"; $endpoint) |
    gsub("\\$\\{DEBEZIUM_USERNAME\\}"; $username) |
    gsub("\\$\\{DEBEZIUM_PASSWORD\\}"; $secret)
  else . end)
' "$connector_template" > "$connector_payload"
chmod 600 "$connector_payload"
curl --fail --silent --show-error --request PUT --header 'Content-Type: application/json' \
  --data-binary "@$connector_payload" \
  'http://127.0.0.1:8083/connectors/airbob-outbox-connector/config' >/dev/null
for attempt in $(seq 1 60); do
  connector_status=$(curl --fail --silent --show-error \
    'http://127.0.0.1:8083/connectors/airbob-outbox-connector/status')
  connector_state=$(jq -r '.connector.state' <<<"$connector_status")
  if [[ "$connector_state" == RUNNING ]] && jq -e \
    '.tasks | length == 1 and all(.[]; .state == "RUNNING")' <<<"$connector_status" >/dev/null; then
    break
  fi
  [[ "$attempt" -lt 60 ]] || { printf '%s\n' 'Debezium connector did not become RUNNING' >&2; exit 1; }
  sleep 5
done

cleanup

verified_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
receipt="$work_root/data-bootstrap-receipt.json"
jq -n \
  --arg runId "$AIRBOB_RUN_ID" \
  --arg datasetRelease "$AIRBOB_DATASET_RELEASE" \
  --arg datasetRunId "$(jq -r '.datasetRunId' "$manifest")" \
  --arg releaseKind "$release_kind" \
  --arg databaseBootstrap "$AIRBOB_DATABASE_BOOTSTRAP" \
  --arg dumpSha256 "$(jq -r '.mysql.dumpSha256' "$manifest")" \
  --arg flywayVersion "$flyway_version" \
  --arg migrationChecksumSha256 "$migration_checksum" \
  --arg schemaFingerprintSha256 "$schema_fingerprint" \
  --arg datasetManifestSha256 "$actual_manifest_sha" \
  --arg rdsResourceId "$AIRBOB_RDS_RESOURCE_ID" \
  --arg rdsEngineVersion "$AIRBOB_RDS_ENGINE_VERSION" \
  --arg redisState "$redis_state" \
  --arg connectorState "$connector_state" \
  --arg searchState "$search_state" \
  --arg verifiedAt "$verified_at" \
  --argjson kafkaTopics "$(jq '.kafka.topics' "$manifest")" \
  '{
    schemaVersion: 1,
    runId: $runId,
    datasetRelease: $datasetRelease,
    datasetRunId: $datasetRunId,
    releaseKind: $releaseKind,
    databaseBootstrap: $databaseBootstrap,
    dumpSha256: $dumpSha256,
    flywayVersion: $flywayVersion,
    migrationChecksumSha256: $migrationChecksumSha256,
    schemaFingerprintSha256: $schemaFingerprintSha256,
    datasetManifestSha256: $datasetManifestSha256,
    rdsResourceId: $rdsResourceId,
    rdsEngineVersion: $rdsEngineVersion,
    outboxState: "empty",
    redisState: $redisState,
    kafkaTopics: $kafkaTopics,
    connectorState: $connectorState,
    searchState: $searchState,
    verifiedAt: $verifiedAt
  }' > "$receipt"
aws --region "$AIRBOB_REGION" s3api put-object \
  --bucket "$AIRBOB_EVIDENCE_BUCKET" \
  --key "data-bootstrap/$AIRBOB_RUN_ID/$AIRBOB_DATASET_RELEASE.json" \
  --body "$receipt" --tagging Retention=summary >/dev/null

printf '%s\n' 'data bootstrap verified'
