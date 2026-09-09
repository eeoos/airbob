#!/usr/bin/env bash
# Runs only a disposable local Compose project. No AWS credentials or user data.
set -euo pipefail
umask 077
script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
test_root=$(mktemp -d "${TMPDIR:-/tmp}/airbob-cdc84.XXXXXX")
project="airbob-cdc84-$(openssl rand -hex 5)"
compose=(docker compose --project-name "$project" --file "$test_root/compose.json")
cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ -f "$test_root/compose.json" ]]; then
    "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  rm -rf "$test_root"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
fail() { printf '%s\n' "$1" >&2; exit 1; }
for tool in docker jq python3 curl openssl; do command -v "$tool" >/dev/null || fail "$tool is required"; done
docker info >/dev/null 2>&1 || fail 'Docker must be running'
image_id=$(docker build --quiet --file "$repo_root/docker/debezium/aws/Dockerfile" "$repo_root/docker/debezium")
[[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || fail 'CDC build did not return an immutable local image ID'

python3 - "$repo_root" "$test_root" "$image_id" <<'PY'
import json,pathlib,secrets,sys
repo,root=map(pathlib.Path,sys.argv[1:3]); image=sys.argv[3]
password=secrets.token_hex(24)
(root/'root-password').write_text(secrets.token_hex(24))
(root/'init.sql').write_text(f"""
CREATE USER 'airbob_debezium'@'%' IDENTIFIED BY '{password}';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT, LOCK TABLES ON *.* TO 'airbob_debezium'@'%';
USE airbobdb;
CREATE TABLE outbox (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, event_id VARCHAR(36) NOT NULL UNIQUE,
 destination VARCHAR(255) NOT NULL, partition_key VARCHAR(255) NOT NULL,
 aggregate_type VARCHAR(255) NOT NULL, aggregate_id VARCHAR(255) NOT NULL,
 event_type VARCHAR(255) NOT NULL, event_version VARCHAR(30) NOT NULL,
 payload TEXT NOT NULL, occurred_at DATETIME(6) NOT NULL
);
""")
config=json.loads((repo/'infra/aws/bundles/debezium/connector.aws.json.tmpl').read_text())
config['database.hostname']='mysql'
config['database.user']='airbob_debezium'
config['database.password']=password
config['database.ssl.mode']='required'
config['heartbeat.interval.ms']='1000'
(root/'connector.json').write_text(json.dumps(config))
worker=(repo/'infra/aws/bundles/debezium/connect-distributed.aws.properties').read_text()
worker=worker.replace('offset.flush.interval.ms=10000','offset.flush.interval.ms=1000')
(root/'worker.properties').write_text(worker)
spec=json.loads((repo/'infra/aws/images/release.json').read_text())
kafka=next(x for x in spec['infra'] if x['variable']=='KAFKA_IMAGE')['buildArgs']['KAFKA_BASE_IMAGE']
doc={'services':{
 'mysql':{'image':'docker.io/library/mysql@sha256:2952e3be7807f06fc18de50b3ea1a632d5c70d63482ff7d7376fe3aa8999babf',
  'mem_limit':'768m','environment':{'MYSQL_ROOT_PASSWORD_FILE':'/run/secrets/root-password','MYSQL_DATABASE':'airbobdb'},
  'command':['--server-id=1','--log-bin=mysql-bin','--binlog-format=ROW','--binlog-row-image=FULL','--innodb-buffer-pool-size=128M'],
  'volumes':[f'{root}/root-password:/run/secrets/root-password:ro',f'{root}/init.sql:/docker-entrypoint-initdb.d/init.sql:ro','mysql-data:/var/lib/mysql']},
 'kafka':{'image':kafka,'mem_limit':'768m','environment':{
  'KAFKA_HEAP_OPTS':'-Xms256m -Xmx384m','KAFKA_NODE_ID':'1','KAFKA_PROCESS_ROLES':'broker,controller',
  'KAFKA_LISTENERS':'PLAINTEXT://:9092,CONTROLLER://:9093','KAFKA_ADVERTISED_LISTENERS':'PLAINTEXT://kafka.lab.airbob.internal:9092',
  'KAFKA_CONTROLLER_LISTENER_NAMES':'CONTROLLER','KAFKA_LISTENER_SECURITY_PROTOCOL_MAP':'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
  'KAFKA_CONTROLLER_QUORUM_VOTERS':'1@kafka:9093','KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR':'1',
  'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR':'1','KAFKA_TRANSACTION_STATE_LOG_MIN_ISR':'1',
  'KAFKA_AUTO_CREATE_TOPICS_ENABLE':'false','CLUSTER_ID':'MkU3OEVBNTcwNTJENDM2Qk'},
  'volumes':[f'{repo}/docker/kafka/init-topics.sh:/tmp/init-topics.sh:ro'],
  'networks':{'default':{'aliases':['kafka.lab.airbob.internal']}}},
 'debezium':{'image':image,'mem_limit':'768m','environment':{'KAFKA_HEAP_OPTS':'-Xms256m -Xmx384m'},
  'volumes':[f'{root}/worker.properties:/opt/kafka/config/connect-distributed.properties:ro'],
  'ports':['127.0.0.1::8083'],'networks':{'default':{'aliases':['connect.lab.airbob.internal']}}}
 },'volumes':{'mysql-data':{}}}
(root/'compose.json').write_text(json.dumps(doc))
PY
"${compose[@]}" up -d mysql kafka >/dev/null
mysql_exec() {
  "${compose[@]}" exec -T mysql sh -c 'export MYSQL_PWD="$(cat /run/secrets/root-password)"; exec mysql -uroot --batch --raw --skip-column-names airbobdb'
}
ready=false
for attempt in $(seq 1 90); do
  if [[ "$(printf 'SELECT VERSION();\n' | mysql_exec 2>/dev/null)" == 8.4.8 ]] &&
    "${compose[@]}" exec -T kafka env KAFKA_HEAP_OPTS='-Xms64m -Xmx128m' /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list >/dev/null 2>&1; then
    ready=true; break
  fi
  sleep 2
done
[[ "$ready" == true ]] || fail 'local MySQL 8.4 / Kafka readiness failed'
"${compose[@]}" exec -T kafka env KAFKA_BOOTSTRAP_SERVERS=kafka:9092 KAFKA_HEAP_OPTS='-Xms64m -Xmx128m' bash /tmp/init-topics.sh >/dev/null
"${compose[@]}" up -d debezium >/dev/null
origin="http://$("${compose[@]}" port debezium 8083)"
for attempt in $(seq 1 90); do
  curl --connect-timeout 2 --max-time 5 --silent --fail "$origin/connector-plugins" > "$test_root/plugins.json" && break
  sleep 2
done
jq -e 'any(.[]; .class=="io.debezium.connector.mysql.MySqlConnector" and .version=="3.5.2.Final")' "$test_root/plugins.json" >/dev/null || fail 'expected MySQL connector version is unavailable'
curl --silent --show-error --fail --max-time 30 -X PUT -H 'Content-Type: application/json' --data-binary "@$test_root/connector.json" "$origin/connectors/airbob-outbox-connector/config" > /dev/null
await_connector() {
  local attempt
  for attempt in $(seq 1 60); do
    if curl --silent --fail --max-time 5 "$origin/connectors/airbob-outbox-connector/status" | jq -e '.connector.state=="RUNNING" and (.tasks | length==1 and all(.[]; .state=="RUNNING"))' >/dev/null; then return 0; fi
    sleep 2
  done
  return 1
}
await_connector || fail 'MySQL 8.4 connector did not start'
insert_event() {
  local label=$1 finish=$2
  printf "BEGIN; INSERT INTO outbox(event_id,destination,partition_key,aggregate_type,aggregate_id,event_type,event_version,payload,occurred_at) VALUES(UUID(),'ACCOMMODATION_CACHE.events','fixture','ACCOMMODATION','fixture','AccommodationSearchRefreshRequested','V1','{\"marker\":\"%s\"}',UTC_TIMESTAMP(6)); %s;\n" "$label" "$finish" | mysql_exec
}
consume() {
  "${compose[@]}" exec -T kafka env KAFKA_HEAP_OPTS='-Xms64m -Xmx128m' /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic ACCOMMODATION_CACHE.events --from-beginning --timeout-ms 5000 2>/dev/null || true
}
await_marker() {
  local expected=$1 attempt
  for attempt in $(seq 1 12); do
    consume > "$test_root/messages.jsonl"
    if jq -se --arg marker "$expected" 'any(.[]; .marker==$marker)' "$test_root/messages.jsonl" >/dev/null; then return 0; fi
  done
  return 1
}
insert_event committed COMMIT
insert_event rolled-back ROLLBACK
await_marker committed || fail 'committed outbox event was not delivered'
jq -se 'all(.[]; .marker!="rolled-back")' "$test_root/messages.jsonl" >/dev/null || fail 'rolled-back event was delivered'
"${compose[@]}" stop debezium >/dev/null
insert_event during-stop COMMIT
"${compose[@]}" start debezium >/dev/null
origin="http://$("${compose[@]}" port debezium 8083)"
await_connector || fail 'connector did not recover after restart'
await_marker during-stop || fail 'event written while stopped was not delivered after restart'
jq -se 'all(.[]; .marker!="rolled-back")' "$test_root/messages.jsonl" >/dev/null || fail 'rolled-back event appeared after restart'
printf '%s\n' 'MySQL 8.4 CDC passed: committed delivery, rollback exclusion, restart recovery (at-least-once).'
