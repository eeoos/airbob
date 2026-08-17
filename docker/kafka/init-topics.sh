#!/usr/bin/env bash

set -euo pipefail

KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:?KAFKA_BOOTSTRAP_SERVERS must be set}"
KAFKA_TOPICS="${KAFKA_TOPICS_COMMAND:-/opt/kafka/bin/kafka-topics.sh}"
readonly BUSINESS_TOPIC_PARTITIONS=3
REPLICATION_FACTOR="${KAFKA_REPLICATION_FACTOR:-1}"

create_topic() {
  local topic="$1"
  local partitions="$2"
  shift 2

  local args=(
    --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS"
    --create
    --if-not-exists
    --topic "$topic"
    --partitions "$partitions"
    --replication-factor "$REPLICATION_FACTOR"
  )

  local config
  for config in "$@"; do
    args+=(--config "$config")
  done

  "$KAFKA_TOPICS" "${args[@]}"
}

assert_partition_count() {
  local topic="$1"
  local expected="$2"
  local description
  local actual

  description="$("$KAFKA_TOPICS" \
    --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
    --describe \
    --topic "$topic")"
  actual="$(printf '%s\n' "$description" \
    | awk -F 'PartitionCount: ' 'NF > 1 { split($2, value, " "); print value[1]; exit }')"

  if [ "$actual" != "$expected" ]; then
    echo "Topic $topic has $actual partitions; expected $expected" >&2
    exit 1
  fi
}

# Kafka Connect owns these topics but broker-side automatic creation is disabled.
create_topic "DEBEZIUM_CONNECT_CONFIGS" 1 "cleanup.policy=compact"
assert_partition_count "DEBEZIUM_CONNECT_CONFIGS" 1
create_topic "DEBEZIUM_CONNECT_OFFSETS" 25 "cleanup.policy=compact"
assert_partition_count "DEBEZIUM_CONNECT_OFFSETS" 25
create_topic "DEBEZIUM_CONNECT_STATUSES" 5 "cleanup.policy=compact"
assert_partition_count "DEBEZIUM_CONNECT_STATUSES" 5
create_topic "DEBEZIUM_SCHEMA_HISTORY" 1 \
  "cleanup.policy=delete" \
  "retention.ms=-1" \
  "retention.bytes=-1"
assert_partition_count "DEBEZIUM_SCHEMA_HISTORY" 1
create_topic "__debezium-heartbeat.airbob_outbox" 1 "cleanup.policy=delete"
assert_partition_count "__debezium-heartbeat.airbob_outbox" 1

BUSINESS_TOPICS=(
  "PAYMENT_OPERATION.events"
  "PAYMENT_OPERATION.events.RETRY"
  "PAYMENT_OPERATION.events.DLT"
  "ACCOMMODATION_INDEX.events"
  "ACCOMMODATION_INDEX.events.RETRY"
  "ACCOMMODATION_INDEX.events.DLT"
  "OPERATOR_ALERT.events"
  "OPERATOR_ALERT.events.RETRY"
  "OPERATOR_ALERT.events.DLT"
)

for topic in "${BUSINESS_TOPICS[@]}"; do
  create_topic "$topic" "$BUSINESS_TOPIC_PARTITIONS" "cleanup.policy=delete"
  assert_partition_count "$topic" "$BUSINESS_TOPIC_PARTITIONS"
done

echo "Kafka topic bootstrap completed successfully."
