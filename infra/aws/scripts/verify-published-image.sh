#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

[[ $# -eq 2 ]] || fail "usage: verify-published-image.sh IMAGE_VARIABLE IMMUTABLE_ECR_REFERENCE"
image_variable=$1
image_ref=$2

case "$image_variable" in
  REDIS_IMAGE) repository=airbob-infra/redis ;;
  REDIS_EXPORTER_IMAGE) repository=airbob-infra/redis-exporter ;;
  NODE_EXPORTER_IMAGE) repository=airbob-infra/node-exporter ;;
  KAFKA_IMAGE) repository=airbob-infra/kafka ;;
  DEBEZIUM_IMAGE) repository=airbob-infra/debezium ;;
  ELASTICSEARCH_IMAGE) repository=airbob-infra/elasticsearch ;;
  ELASTICSEARCH_EXPORTER_IMAGE) repository=airbob-infra/elasticsearch-exporter ;;
  PROMETHEUS_IMAGE) repository=airbob-infra/prometheus ;;
  GRAFANA_IMAGE) repository=airbob-infra/grafana ;;
  *) fail "runtime verification is limited to the nine approved infrastructure images" ;;
esac

[[ "$image_ref" =~ ^942632789808\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/${repository}@sha256:[0-9a-f]{64}$ ]] \
  || fail "runtime verification reference does not match its approved repository"
command -v docker >/dev/null 2>&1 || fail "Docker is required"

docker pull --platform linux/amd64 "$image_ref" >/dev/null

case "$image_variable" in
  KAFKA_IMAGE)
    docker run --rm --platform linux/amd64 --entrypoint /bin/bash "$image_ref" -ec \
      'test -s /opt/jmx/jmx_prometheus_javaagent.jar'
    ;;
  DEBEZIUM_IMAGE)
    docker run --rm --platform linux/amd64 --entrypoint /bin/bash "$image_ref" -ec \
      'test -s /opt/jmx/jmx_prometheus_javaagent.jar && find /opt/kafka/connect-plugins/debezium-mysql -type f -name "debezium-connector-mysql-*.jar" -print -quit | grep -q .'
    ;;
  ELASTICSEARCH_IMAGE)
    docker run --rm --platform linux/amd64 --entrypoint /bin/bash "$image_ref" -ec \
      'test -f /usr/share/elasticsearch/modules/repository-s3/plugin-descriptor.properties && /usr/share/elasticsearch/bin/elasticsearch-plugin list | grep -Fxq analysis-nori'
    ;;
esac

printf 'Verified published %s runtime contract.\n' "$image_variable"
