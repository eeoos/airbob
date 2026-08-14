# AWS Performance Lab Service Bundles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define locally verifiable Docker Compose/config bundles for every AWS EC2 service host without creating AWS resources or publishing mutable images.

**Architecture:** Each EC2 role owns one focused bundle: app, Redis, Kafka, Debezium, Elasticsearch, or monitoring. Compose files consume `repository@sha256:<64-hex>` image inputs, expose only the host ports required by the approved security-group contract, and use enforceable standalone-Compose resource limits. A shared verifier resolves every bundle with a synthetic digest fixture, rejects tags such as `latest`, and packages the reviewed files into a checksum-addressed archive for future S3/SSM bootstrap.

**Tech Stack:** Docker Compose v2, Bash, Spring Boot test infrastructure with SnakeYAML/Jackson, Prometheus 3.x configuration, Grafana provisioning, Kafka 3.7 KRaft, Debezium 2.6 distributed worker, Elasticsearch 8.18.8, Prometheus JMX Exporter 1.6.0.

## Global Constraints

- Work only in the `infra/aws-performance-lab` worktree; do not change any other branch or worktree.
- This plan creates no Terraform resources, AWS accounts/resources, ECR pushes, S3 uploads, GitHub OIDC roles, Route 53 records, or DNS changes.
- AWS keeps six service-host boundaries: app, Redis, Kafka, Debezium, Elasticsearch, and monitoring.
- Redis is one EC2 host with exactly two Redis containers (`redis`, `redis-cache`) and exactly two Redis exporters; do not add a third Redis endpoint.
- General Redis uses host port `6379`, AOF with `appendfsync everysec`, `512mb`, `noeviction`, a persistent volume, and a `640M` container limit.
- Accommodation-detail cache Redis uses host port `6380`, no RDB/AOF persistence, `256mb`, `allkeys-lru`, no data volume, and a `320M` container limit.
- Redis exporters use host ports `9121` and `9122`, preserve `general|cache` labels, and each has a `64M` limit.
- App uses `2.0` CPUs, `3G` memory, and `-Xms1536m -Xmx1536m -XX:+UseG1GC`; it receives both Redis endpoints explicitly.
- Kafka uses VPC listener `kafka.lab.airbob.internal:9092`, container listener `kafka:19092`, heap `1g`, and memory limit `1536M`.
- Debezium uses `kafka.lab.airbob.internal:9092` for worker, producer, consumer, and schema history; REST is bound to host loopback `127.0.0.1:8083`; heap is `512m` with a `768M` limit.
- Elasticsearch is single-node `8.18.8` with Nori, heap `1g`, a `2G` limit, host port `9200`, and exporter port `9114`.
- Prometheus/Grafana bind only to host loopback on `9090`/`3000`; dependency exporters and node exporters remain VPC-only through security groups.
- Every EC2 service host runs exactly one node exporter on host port `9100`.
- Kafka and Debezium images expose Prometheus JMX Exporter `1.6.0` at `/opt/jmx/jmx_prometheus_javaagent.jar`; Kafka uses port `7071`, Debezium uses `9404`.
- Every Compose `image` value is supplied as `repository@sha256:<64 lowercase hex>`; tags and `latest` are rejected.
- Runtime secrets are never committed, interpolated into Terraform state, included in bundle archives, or printed by verification scripts.
- `docker compose config` validation must not start containers or pull images.
- A topology, memory, port, persistence, image, or data-bootstrap contract that cannot be met is a blocker; preserve evidence and ask the user instead of resizing, merging services, adding fallbacks, or substituting components.

---

### Task 1: Shared Immutable-Image Bundle Verifier

**Files:**
- Create: `infra/aws/bundles/README.md`
- Create: `infra/aws/scripts/verify-service-bundle.sh`
- Create: `infra/aws/tests/fixtures/images.env`
- Create: `infra/aws/tests/fixtures/runtime.env`
- Create: `infra/aws/tests/fixtures/valid-compose.yml`
- Create: `infra/aws/tests/fixtures/tagged-compose.yml`
- Create: `infra/aws/tests/service-bundle-verifier-test.sh`

**Interfaces:**
- Consumes: a Compose file and an image-variable env file.
- Produces: `verify-service-bundle.sh <compose-file> <image-env-file>`, which returns zero only when Compose resolves and every image is a digest reference.

- [ ] **Step 1: Write the failing shell contract test**

Create a fixture with `image: ${APP_IMAGE:?APP_IMAGE is required}` and a fixture env containing:

```dotenv
APP_IMAGE=registry.example.invalid/airbob/app@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
REDIS_IMAGE=registry.example.invalid/airbob/redis@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
REDIS_EXPORTER_IMAGE=registry.example.invalid/airbob/redis-exporter@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
NODE_EXPORTER_IMAGE=registry.example.invalid/airbob/node-exporter@sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
KAFKA_IMAGE=registry.example.invalid/airbob/kafka@sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
DEBEZIUM_IMAGE=registry.example.invalid/airbob/debezium@sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
ELASTICSEARCH_IMAGE=registry.example.invalid/airbob/elasticsearch@sha256:1111111111111111111111111111111111111111111111111111111111111111
ELASTICSEARCH_EXPORTER_IMAGE=registry.example.invalid/airbob/elasticsearch-exporter@sha256:2222222222222222222222222222222222222222222222222222222222222222
PROMETHEUS_IMAGE=registry.example.invalid/airbob/prometheus@sha256:3333333333333333333333333333333333333333333333333333333333333333
GRAFANA_IMAGE=registry.example.invalid/airbob/grafana@sha256:4444444444444444444444444444444444444444444444444444444444444444
APP_ENV_FILE=../../tests/fixtures/runtime.env
MONITORING_ENV_FILE=../../tests/fixtures/runtime.env
```

The test must prove: valid digest succeeds, `image: redis:7.2-alpine` fails, a missing env variable fails, and invoking the verifier does not create a container.

- [ ] **Step 2: Run the shell test and confirm RED**

Run:

```bash
bash infra/aws/tests/service-bundle-verifier-test.sh
```

Expected: failure because `verify-service-bundle.sh` does not exist.

- [ ] **Step 3: Implement the verifier**

Use this public contract:

```bash
#!/usr/bin/env bash
set -euo pipefail

compose_file=${1:?compose file is required}
image_env_file=${2:?image env file is required}

docker compose --env-file "$image_env_file" -f "$compose_file" config --quiet
images=$(docker compose --env-file "$image_env_file" -f "$compose_file" config --images)
[[ -n "$images" ]] || {
  printf 'bundle has no images: %s\n' "$compose_file" >&2
  exit 1
}

digest_pattern='^[a-z0-9][a-z0-9._:/-]*@sha256:[0-9a-f]{64}$'
while IFS= read -r image; do
  [[ "$image" =~ $digest_pattern ]] || {
    printf 'mutable or invalid image reference: %s\n' "$image" >&2
    exit 1
  }
done <<< "$images"
```

Add explicit readable-file checks and require at least one resolved image. Do not print env-file contents.

- [ ] **Step 4: Document the bundle boundary**

`infra/aws/bundles/README.md` must state that bundles are configuration artifacts only, images are supplied by digest, secrets live in root-owned runtime env files outside the archive, and AWS provisioning/publication are separate plans.

- [ ] **Step 5: Verify and commit**

Run:

```bash
bash -n infra/aws/scripts/verify-service-bundle.sh
bash -n infra/aws/tests/service-bundle-verifier-test.sh
bash infra/aws/tests/service-bundle-verifier-test.sh
git diff --check
```

Commit:

```bash
git add infra/aws/bundles/README.md infra/aws/scripts/verify-service-bundle.sh infra/aws/tests
git commit -m "feat: verify immutable AWS service bundles"
```

---

### Task 2: Redis Two-Container Host Bundle

**Files:**
- Create: `infra/aws/bundles/redis/compose.yml`
- Create: `src/test/java/kr/kro/airbob/common/monitoring/AwsRedisBundleConfigurationTest.java`

**Interfaces:**
- Consumes: `REDIS_IMAGE`, `REDIS_EXPORTER_IMAGE`, and `NODE_EXPORTER_IMAGE` digest variables.
- Produces: one Redis-host bundle exposing `6379`, `6380`, `9121`, `9122`, and `9100`.

- [ ] **Step 1: Add failing topology and policy tests**

Parse `infra/aws/bundles/redis/compose.yml` with SnakeYAML and require the service names to be exactly:

```java
assertThat(services.keySet()).containsExactlyInAnyOrder(
    "redis", "redis-cache", "redis-exporter-general",
    "redis-exporter-cache", "node-exporter");
```

Assert the two Redis commands, ports, volumes, `mem_limit`, `memswap_limit`, exporter addresses/limits, restart policy, `platform=linux/amd64`, and node exporter host mounts. Keep `RedisMonitoringConfigurationTest` in the focused regression command for the existing local/OCI contracts, but do not create or assert `prometheus.aws.yml` in this task. AWS Redis scrape labels belong to Task 6, which creates that file.

- [ ] **Step 2: Confirm RED**

Run:

```bash
./gradlew test --tests "kr.kro.airbob.common.monitoring.AwsRedisBundleConfigurationTest" --tests "kr.kro.airbob.common.monitoring.RedisMonitoringConfigurationTest"
```

Expected: failure because the AWS Redis bundle is absent.

- [ ] **Step 3: Create the exact Redis services**

Use these resource/persistence contracts:

```yaml
services:
  redis:
    image: ${REDIS_IMAGE:?REDIS_IMAGE is required}
    platform: linux/amd64
    command: >-
      redis-server --save "" --appendonly yes --appendfsync everysec
      --maxmemory 512mb --maxmemory-policy noeviction
    ports: ["6379:6379"]
    volumes: ["redis-general-data:/data"]
    mem_limit: 640M
    memswap_limit: 640M
    restart: unless-stopped
  redis-cache:
    image: ${REDIS_IMAGE:?REDIS_IMAGE is required}
    platform: linux/amd64
    command: >-
      redis-server --save "" --appendonly no
      --maxmemory 256mb --maxmemory-policy allkeys-lru
    ports: ["6380:6379"]
    mem_limit: 320M
    memswap_limit: 320M
    restart: unless-stopped
```

The two exporters point to `redis://redis:6379` and `redis://redis-cache:6379`, publish `9121:9121` and `9122:9121`, and each uses `64M` memory/swap limits. `node-exporter` publishes `9100:9100`, uses `pid: host`, mounts `/` read-only at `/host`, sets `--path.rootfs=/host`, and has a `128M` memory/swap limit. Add `redis-cli ping` health checks for both Redis services; exporter readiness is verified from Prometheus in Task 6 because the exporter image does not provide a separate HTTP client.

- [ ] **Step 4: Verify Compose and tests**

Run:

```bash
bash infra/aws/scripts/verify-service-bundle.sh infra/aws/bundles/redis/compose.yml infra/aws/tests/fixtures/images.env
./gradlew test --tests "kr.kro.airbob.common.monitoring.AwsRedisBundleConfigurationTest" --tests "kr.kro.airbob.common.monitoring.RedisMonitoringConfigurationTest"
```

Expected: PASS with exactly two Redis containers and two Redis exporters.

- [ ] **Step 5: Commit**

```bash
git add infra/aws/bundles/redis/compose.yml src/test/java/kr/kro/airbob/common/monitoring/AwsRedisBundleConfigurationTest.java
git commit -m "feat: add AWS two-Redis host bundle"
```

---

### Task 3: App ASG Node Bundle and Runtime Guard Contract

**Files:**
- Create: `infra/aws/bundles/app/compose.yml`
- Create: `infra/aws/bundles/app/required-runtime-env.txt`
- Create: `infra/aws/scripts/verify-app-runtime-env.sh`
- Create: `infra/aws/tests/app-runtime-env-test.sh`
- Create: `src/test/java/kr/kro/airbob/common/monitoring/AwsAppBundleConfigurationTest.java`

**Interfaces:**
- Consumes: `APP_IMAGE`, `NODE_EXPORTER_IMAGE`, `APP_ENV_FILE`, and policy `integrated-smoke|isolated-read`.
- Produces: `verify-app-runtime-env.sh <runtime-env-file> <policy>` plus an app/node-exporter Compose bundle.

- [ ] **Step 1: Add failing runtime and Compose tests**

The shell test must prove that integrated smoke accepts only `SPRING_PROFILES_ACTIVE=aws,performance-lab`, isolated read accepts only `SPRING_PROFILES_ACTIVE=aws,traffic-benchmark`, and both require these exact guard values:

```dotenv
TOSS_PAYMENTS_ENABLED=false
GOOGLE_API_ENABLED=false
AWS_S3_WRITE_ENABLED=false
SLACK_NOTIFICATION_ENABLED=false
REDIS_HOST=redis-general.lab.airbob.internal
REDIS_PORT=6379
ACCOMMODATION_DETAIL_CACHE_REDIS_HOST=redis-cache.lab.airbob.internal
ACCOMMODATION_DETAIL_CACHE_REDIS_PORT=6380
```

The verifier must reject identical general/cache endpoint tuples and must never print datasource passwords. The Java test must require app `8080`, node exporter `9100`, `cpus: 2.0`, `mem_limit: 3G`, `memswap_limit: 3G`, fixed JVM options, health check, and digest-only image variables.

- [ ] **Step 2: Confirm RED**

Run:

```bash
bash infra/aws/tests/app-runtime-env-test.sh
./gradlew test --tests "kr.kro.airbob.common.monitoring.AwsAppBundleConfigurationTest"
```

Expected: failure because the verifier and app bundle are absent.

- [ ] **Step 3: Implement the app bundle**

Use this service contract:

```yaml
services:
  app:
    image: ${APP_IMAGE:?APP_IMAGE is required}
    platform: linux/amd64
    env_file:
      - ${APP_ENV_FILE:?APP_ENV_FILE is required}
    environment:
      JAVA_OPTS: -Xms1536m -Xmx1536m -XX:+UseG1GC
    ports: ["8080:8080"]
    cpus: 2.0
    mem_limit: 3G
    memswap_limit: 3G
    restart: unless-stopped
```

Add the existing actuator health check with a 90-second start period. Add one node exporter with the same host-mode contract as Task 2.

- [ ] **Step 4: Implement fail-closed env verification**

`required-runtime-env.txt` lists `SPRING_PROFILES_ACTIVE`, the four external guards, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, both Redis host/ports, `KAFKA_BOOTSTRAP_SERVERS`, `ELASTICSEARCH_URIS`, `ELASTICSEARCH_USERNAME`, `ELASTIC_PASSWORD`, `AWS_S3_BUCKET_NAME`, and `CLOUDFRONT_DOMAIN`. Parse the env file without sourcing it. Reject duplicate keys, missing keys, whitespace around names, non-false guard values, a profile/policy mismatch, Redis endpoint equality after lowercasing/trimming hosts, Kafka values other than `kafka.lab.airbob.internal:9092`, and Elasticsearch values other than `http://elasticsearch.lab.airbob.internal:9200`. Permit empty values only for `ELASTICSEARCH_USERNAME` and `ELASTIC_PASSWORD` because the private lab ES node has security disabled. Reject `TOSS_SECRET_KEY`, `GOOGLE_API_KEY`, `SLACK_WEBHOOK_URL`, `AWS_ACCESS_KEY_ID`, and `AWS_SECRET_ACCESS_KEY` if they appear in the lab runtime env file.

- [ ] **Step 5: Verify and commit**

Run:

```bash
bash infra/aws/tests/app-runtime-env-test.sh
bash infra/aws/scripts/verify-service-bundle.sh infra/aws/bundles/app/compose.yml infra/aws/tests/fixtures/images.env
./gradlew test --tests "kr.kro.airbob.common.monitoring.AwsAppBundleConfigurationTest"
git diff --check
```

Commit:

```bash
git add infra/aws/bundles/app infra/aws/scripts/verify-app-runtime-env.sh infra/aws/tests/app-runtime-env-test.sh src/test/java/kr/kro/airbob/common/monitoring/AwsAppBundleConfigurationTest.java
git commit -m "feat: define AWS app node bundle contract"
```

---

### Task 4: Kafka and Debezium Cross-Host Bundles

**Files:**
- Create: `infra/aws/bundles/kafka/compose.yml`
- Create: `infra/aws/bundles/kafka/jmx-exporter.yml`
- Create: `infra/aws/bundles/debezium/compose.yml`
- Create: `infra/aws/bundles/debezium/connect-distributed.aws.properties`
- Create: `infra/aws/bundles/debezium/connector.aws.json.tmpl`
- Create: `infra/aws/bundles/debezium/jmx-exporter.yml`
- Create: `src/test/java/kr/kro/airbob/common/monitoring/AwsKafkaDebeziumBundleConfigurationTest.java`

**Interfaces:**
- Consumes: custom `KAFKA_IMAGE` and `DEBEZIUM_IMAGE` digests that contain JMX Exporter 1.6.0 at the fixed path.
- Produces: Kafka ports `9092/7071/9100`, Debezium ports `127.0.0.1:8083/9404/9100`, a distributed-worker config, and a secret-free connector template.

- [ ] **Step 1: Write failing cross-host tests**

Require Kafka to have `INTERNAL://:19092`, `VPC://:9092`, `CONTROLLER://:9093` and advertised listeners `INTERNAL://kafka:19092,VPC://kafka.lab.airbob.internal:9092`. Require every Debezium worker/producer/consumer/schema-history Kafka value to use `kafka.lab.airbob.internal:9092`. Reject `kafka:9092`, `mysql`, plaintext credentials, and `snapshot.mode=initial` in AWS files.

- [ ] **Step 2: Confirm RED**

```bash
./gradlew test --tests "kr.kro.airbob.common.monitoring.AwsKafkaDebeziumBundleConfigurationTest"
```

Expected: failure because the bundle/config files are absent.

- [ ] **Step 3: Add the Kafka bundle**

Use KRaft node `1`, cluster ID `airbob-performance-lab`, single-node replication factors, `/var/lib/kafka/data`, heap `-Xms1g -Xmx1g`, `1536M` memory/swap limits, and:

```yaml
environment:
  KAFKA_OPTS: >-
    -javaagent:/opt/jmx/jmx_prometheus_javaagent.jar=7071:/opt/jmx/kafka.yml
ports:
  - "9092:9092"
  - "7071:7071"
```

Mount `jmx-exporter.yml` read-only at `/opt/jmx/kafka.yml`. Add broker health and node exporter.

- [ ] **Step 4: Add the Debezium bundle and templates**

The worker file uses `group.id=airbob-debezium-connect` and topics `airbob_debezium_configs`, `airbob_debezium_offsets`, `airbob_debezium_statuses`, each with replication factor `1`. Set `rest.advertised.host.name=connect.lab.airbob.internal`, plugin path `/opt/kafka/connect-plugins`, JSON key converter, String value converter, and `offset.flush.interval.ms=10000`.

The connector template retains the current `airbobdb.outbox` EventRouter mapping and uses:

```json
{
  "database.hostname": "${RDS_ENDPOINT}",
  "database.user": "${DEBEZIUM_USERNAME}",
  "database.password": "${DEBEZIUM_PASSWORD}",
  "snapshot.mode": "no_data",
  "schema.history.internal.kafka.bootstrap.servers": "kafka.lab.airbob.internal:9092"
}
```

Bind REST to `127.0.0.1:8083`, publish JMX `9404`, use heap `-Xms512m -Xmx512m`, `768M` memory/swap limits, and add node exporter. Do not place credential values in Compose or the template.

- [ ] **Step 5: Verify and commit**

```bash
bash infra/aws/scripts/verify-service-bundle.sh infra/aws/bundles/kafka/compose.yml infra/aws/tests/fixtures/images.env
bash infra/aws/scripts/verify-service-bundle.sh infra/aws/bundles/debezium/compose.yml infra/aws/tests/fixtures/images.env
./gradlew test --tests "kr.kro.airbob.common.monitoring.AwsKafkaDebeziumBundleConfigurationTest"
git diff --check
```

Commit:

```bash
git add infra/aws/bundles/kafka infra/aws/bundles/debezium src/test/java/kr/kro/airbob/common/monitoring/AwsKafkaDebeziumBundleConfigurationTest.java
git commit -m "feat: add AWS Kafka and Debezium bundles"
```

---

### Task 5: Elasticsearch Host Bundle

**Files:**
- Create: `infra/aws/bundles/elasticsearch/compose.yml`
- Create: `src/test/java/kr/kro/airbob/common/monitoring/AwsElasticsearchBundleConfigurationTest.java`

**Interfaces:**
- Consumes: `ELASTICSEARCH_IMAGE`, `ELASTICSEARCH_EXPORTER_IMAGE`, and `NODE_EXPORTER_IMAGE` digests.
- Produces: ES `9200`, exporter `9114`, node exporter `9100`, and persistent ES data.

- [ ] **Step 1: Add the failing contract test**

Require exactly `elasticsearch`, `elasticsearch-exporter`, and `node-exporter`; single-node mode; security disabled only on the private VPC boundary; heap `-Xms1g -Xmx1g`; `2G` memory/swap limit; data volume; `nofile=65535`; exporter URI `http://elasticsearch:9200`; and the exact host ports.

- [ ] **Step 2: Confirm RED**

```bash
./gradlew test --tests "kr.kro.airbob.common.monitoring.AwsElasticsearchBundleConfigurationTest"
```

Expected: failure because the bundle is absent.

- [ ] **Step 3: Implement the three-service bundle**

Use:

```yaml
environment:
  discovery.type: single-node
  xpack.security.enabled: "false"
  ES_JAVA_OPTS: -Xms1g -Xmx1g
ports: ["9200:9200"]
mem_limit: 2G
memswap_limit: 2G
ulimits:
  nofile:
    soft: 65535
    hard: 65535
```

The custom ES image contract requires version `8.18.8`, Nori, and repository-s3 capability; image construction/publication is outside this plan. Configure exporter `--es.uri=http://elasticsearch:9200`, publish `9114:9114`, limit it to `128M`, and add node exporter.

- [ ] **Step 4: Verify and commit**

```bash
bash infra/aws/scripts/verify-service-bundle.sh infra/aws/bundles/elasticsearch/compose.yml infra/aws/tests/fixtures/images.env
./gradlew test --tests "kr.kro.airbob.common.monitoring.AwsElasticsearchBundleConfigurationTest"
git diff --check
```

Commit:

```bash
git add infra/aws/bundles/elasticsearch/compose.yml src/test/java/kr/kro/airbob/common/monitoring/AwsElasticsearchBundleConfigurationTest.java
git commit -m "feat: define AWS Elasticsearch host bundle"
```

---

### Task 6: Monitoring Bundle and AWS Target Discovery

**Files:**
- Create: `infra/aws/bundles/monitoring/compose.yml`
- Create: `monitoring/prometheus/prometheus.aws.yml`
- Create: `monitoring/grafana/provisioning/datasources/cloudwatch.aws.yml`
- Create: `src/test/java/kr/kro/airbob/common/monitoring/AwsMonitoringBundleConfigurationTest.java`
- Modify: `src/test/java/kr/kro/airbob/common/monitoring/RedisMonitoringConfigurationTest.java`

**Interfaces:**
- Consumes: `PROMETHEUS_IMAGE`, `GRAFANA_IMAGE`, `NODE_EXPORTER_IMAGE`, and `MONITORING_ENV_FILE`.
- Produces: loopback Grafana/Prometheus access, app/node EC2 discovery, static private dependency targets, and a CloudWatch datasource.

- [ ] **Step 1: Add failing monitoring tests**

Require app EC2 discovery in `ap-northeast-2` filtered by `Project=airbob`, `Environment=performance-lab`, `Service=app`, and running state. Require a second EC2 discovery job for node exporters tagged `Monitoring=node-exporter`. Require these static targets:

```text
redis-general.lab.airbob.internal:9121
redis-cache.lab.airbob.internal:9122
kafka.lab.airbob.internal:7071
connect.lab.airbob.internal:9404
elasticsearch.lab.airbob.internal:9114
monitoring.lab.airbob.internal:9100
```

Assert Redis labels remain `general|cache`, Grafana has Prometheus UID `prometheus` and CloudWatch UID `cloudwatch`, and the Compose file publishes only `127.0.0.1:9090:9090`, `127.0.0.1:3000:3000`, and VPC node exporter `9100:9100`. Extend `RedisMonitoringConfigurationTest` here so its AWS branch reads `prometheus.aws.yml` and requires `namespace=general|cache` plus `instance=redis-general|redis-cache` for the two AWS Redis targets.

- [ ] **Step 2: Confirm RED**

```bash
./gradlew test --tests "kr.kro.airbob.common.monitoring.AwsMonitoringBundleConfigurationTest" --tests "kr.kro.airbob.common.monitoring.GrafanaDashboardConfigurationTest" --tests "kr.kro.airbob.common.monitoring.RedisMonitoringConfigurationTest"
```

Expected: failure because AWS monitoring config is absent.

- [ ] **Step 3: Implement Prometheus discovery**

Use `ec2_sd_configs` with `region: ap-northeast-2`, `port: 8080` for app and `port: 9100` for node exporters. Add keep relabels for every required tag and set the app `metrics_path` to `/actuator/prometheus`. Static dependency jobs must use the exact DNS/port list from Step 1.

- [ ] **Step 4: Implement Grafana/Compose provisioning**

The CloudWatch datasource is:

```yaml
apiVersion: 1
datasources:
  - name: CloudWatch
    uid: cloudwatch
    type: cloudwatch
    access: proxy
    jsonData:
      authType: default
      defaultRegion: ap-northeast-2
```

Mount only the existing Prometheus datasource, the AWS CloudWatch datasource, dashboard provider, and vendored dashboards. Prometheus uses `512M`; Grafana uses `384M`; node exporter uses `128M`; memory/swap limits match. Require a non-empty Grafana admin password in the runtime env and disable signup, anonymous auth, plugin auto-update, and UI dashboard updates.

- [ ] **Step 5: Verify and commit**

```bash
bash infra/aws/scripts/verify-service-bundle.sh infra/aws/bundles/monitoring/compose.yml infra/aws/tests/fixtures/images.env
./gradlew test --tests "kr.kro.airbob.common.monitoring.AwsMonitoringBundleConfigurationTest" --tests "kr.kro.airbob.common.monitoring.GrafanaDashboardConfigurationTest" --tests "kr.kro.airbob.common.monitoring.RedisMonitoringConfigurationTest"
git diff --check
```

Commit:

```bash
git add infra/aws/bundles/monitoring/compose.yml monitoring/prometheus/prometheus.aws.yml monitoring/grafana/provisioning/datasources/cloudwatch.aws.yml src/test/java/kr/kro/airbob/common/monitoring/AwsMonitoringBundleConfigurationTest.java src/test/java/kr/kro/airbob/common/monitoring/RedisMonitoringConfigurationTest.java
git commit -m "feat: add AWS monitoring service bundle"
```

---

### Task 7: Bundle Manifest, Checksum Package, and CI Gate

**Files:**
- Create: `infra/aws/bundles/manifest.json`
- Create: `infra/aws/scripts/package-service-bundles.sh`
- Create: `infra/aws/tests/package-service-bundles-test.sh`
- Create: `infra/aws/tests/all-service-bundles-test.sh`
- Modify: `.github/workflows/ci.yml`
- Modify: `docs/performance/aws-performance-lab.md`
- Modify: `docs/superpowers/specs/2026-08-14-ephemeral-aws-performance-lab-design.md`

**Interfaces:**
- Consumes: a full 40-character Git commit and all six validated bundle directories.
- Produces: `airbob-service-bundles-<commit>.tar.gz`, matching `.sha256`, a release manifest, and a CI static gate.

- [ ] **Step 1: Write failing aggregate/package tests**

The aggregate test loops over all six Compose files and calls `verify-service-bundle.sh` with the digest fixture. It rejects any `latest`, any `image:` without `${..._IMAGE}`, plaintext `password:` values in bundle files, and any Redis service count other than two.

The package test runs in a temporary directory, requires a full commit matching `^[0-9a-f]{40}$`, verifies the checksum, lists the archive, and requires every file named in `manifest.json` to be present. It must also prove that `runtime.env`, `.env`, key files, and credential JSON are absent.

- [ ] **Step 2: Confirm RED**

```bash
bash infra/aws/tests/all-service-bundles-test.sh
bash infra/aws/tests/package-service-bundles-test.sh
```

Expected: failure because manifest/package scripts are absent.

- [ ] **Step 3: Create the manifest and package script**

`manifest.json` uses `schemaVersion: 1`, lists the six bundle names, every required Compose/config file, and the ten image-variable names from Task 1. The package script accepts:

```text
package-service-bundles.sh <40-char-commit> <output-directory>
```

It runs the aggregate verifier first, creates the archive from explicit manifest paths rather than a directory wildcard, calculates SHA-256 with `sha256sum` or macOS `shasum -a 256`, and writes a JSON release manifest containing the commit, archive filename, SHA-256, schema version, and file list. It must not contact AWS.

- [ ] **Step 4: Add the CI gate**

Add this step after checkout and before the Gradle build:

```yaml
- name: Verify AWS service bundle contracts
  run: |
    bash infra/aws/tests/service-bundle-verifier-test.sh
    bash infra/aws/tests/app-runtime-env-test.sh
    bash infra/aws/tests/all-service-bundles-test.sh
    bash infra/aws/tests/package-service-bundles-test.sh
```

Do not add AWS credentials, Docker image pulls, or AWS API calls.

- [ ] **Step 5: Update implemented status precisely**

Mark the design’s Debezium worker/connector template and Prometheus target-definition bullet implemented. Keep immutable image publication, Terraform, AWS deployment, DNS migration, and evidence pending. In `docs/performance/aws-performance-lab.md`, add service bundle contracts as implemented and image publication as pending.

- [ ] **Step 6: Run final verification**

```bash
bash infra/aws/tests/service-bundle-verifier-test.sh
bash infra/aws/tests/app-runtime-env-test.sh
bash infra/aws/tests/all-service-bundles-test.sh
bash infra/aws/tests/package-service-bundles-test.sh
./gradlew test --tests "kr.kro.airbob.common.monitoring.*"
git diff --check
```

If `actionlint` is unavailable, record that fact without installing or substituting another workflow validator.

- [ ] **Step 7: Commit**

```bash
git add infra/aws/bundles/manifest.json infra/aws/scripts/package-service-bundles.sh infra/aws/tests .github/workflows/ci.yml docs/performance/aws-performance-lab.md docs/superpowers/specs/2026-08-14-ephemeral-aws-performance-lab-design.md
git commit -m "feat: package verified AWS service bundles"
```

## Self-Review Checklist

- Every bundle is independently resolvable with `docker compose config` and a digest fixture without pulling or starting images.
- No bundle contains `latest`, a tag-only image, a plaintext secret, a runtime `.env`, an AWS key, or a service-account credential.
- Redis remains exactly two Redis containers and two Redis exporters on one host.
- General/cache Redis persistence, eviction, limits, ports, and exporter labels match the approved design.
- Kafka advertises the VPC DNS name to other EC2 hosts and keeps `kafka:19092` only for its own Docker network.
- Every Debezium Kafka client path uses the VPC Kafka name and `snapshot.mode=no_data`.
- The connector template preserves the existing outbox EventRouter contract and contains no credential value.
- Prometheus uses EC2 discovery for ASG churn and static private DNS for singleton dependencies.
- Grafana/Prometheus have no public bind; Debezium REST is loopback-only.
- Standalone Compose limits use `mem_limit`/`memswap_limit`; the app CPU limit is `2.0`.
- Packaging uses an explicit manifest and SHA-256 and cannot include runtime secret files.
- Documentation marks only bundle/config contracts implemented; image publication and all AWS mutations remain pending.
