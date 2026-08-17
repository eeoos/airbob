# AWS Performance Lab Phase 0 Application Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AWS 성능 실험실이 범용 Redis와 숙소 상세 Redis를 확실히 분리하고, 동일 이미지로 cache A/B를 수행하며, 측정 정책에 따라 백그라운드 작업과 외부 부작용을 fail-closed 방식으로 차단하게 한다.

**Architecture:** 기존 범용 Spring Redis/Redisson과 숙소 상세 전용 Lettuce/Redisson 경계는 유지한다. `performance-lab` profile은 Toss·Google·Slack·S3 외부 부작용을 끄고, `traffic-benchmark` profile은 이를 포함하면서 scheduler와 Kafka consumer를 추가로 끈다. Terraform이나 AWS resource는 이 계획에서 만들지 않으며, 이 계약을 먼저 통과해야 후속 lab 인프라가 앱을 실행할 수 있다.

**Tech Stack:** Spring Boot 3.5.8, Java 21, Spring Data Redis, Redisson, Testcontainers Redis, JUnit 5, AssertJ, Gradle

## Global Constraints

- 기준 브랜치는 `perf/accommodation-cache`; 작업 브랜치는 `infra/aws-performance-lab`이다.
- Redis topology는 한 EC2의 범용 `redis`와 숙소 상세 `redis-cache` 두 컨테이너뿐이다. 인증·락·쿠폰·최근 본 숙소를 추가 분리하지 않는다.
- AWS 범용 endpoint는 `${REDIS_HOST}:${REDIS_PORT}`, 숙소 상세 endpoint는 `${ACCOMMODATION_DETAIL_CACHE_REDIS_HOST}:${ACCOMMODATION_DETAIL_CACHE_REDIS_PORT}`이며 어느 값도 다른 endpoint로 fallback하지 않는다.
- `performance-lab`에서는 Toss HTTP, Google HTTP, Slack HTTP와 application image S3 write/delete가 실제 외부 시스템에 도달하면 안 된다.
- `integrated-smoke`는 `aws,performance-lab`; `isolated-read`는 `aws,traffic-benchmark`를 활성화한다. `traffic-benchmark`는 profile group으로 `performance-lab`을 반드시 포함한다.
- `traffic-benchmark`에서는 Spring scheduler, Kafka listener와 retry-topic scheduler가 기동하지 않아야 한다.
- 숙소 상세 cache A/B는 동일 image에서 `ACCOMMODATION_DETAIL_CACHE_ENABLED=true|false`만 바꾼다. disabled일 때 Redis/Redisson을 전혀 호출하지 않고 loader를 직접 실행한다.
- 기존 production/dev/OCI 기본 동작은 유지한다. 새 guard property의 기본값은 모두 `true`다.
- 실패나 예상하지 못한 현재 코드 계약이 발견되면 대체 endpoint, profile 또는 mock 경로를 임의로 만들지 말고 중단해 사용자에게 묻는다.
- 각 task는 테스트 실패를 먼저 확인하고 최소 구현 후 전체 관련 테스트를 통과시킨 뒤 독립 커밋한다.

---

### Task 1: AWS Redis Endpoint Fail-Fast Contract

**Files:**
- Modify: `src/main/resources/application-aws.yaml`
- Create: `src/main/java/kr/kro/airbob/config/PerformanceLabRedisEndpointConfiguration.java`
- Create: `src/test/java/kr/kro/airbob/config/PerformanceLabRedisEndpointConfigurationTest.java`
- Create: `src/test/java/kr/kro/airbob/domain/accommodation/cache/PerformanceLabRedisEndpointIsolationIntegrationTest.java`
- Modify: `src/test/java/kr/kro/airbob/common/monitoring/RedisMonitoringConfigurationTest.java`

**Interfaces:**
- Consumes: Spring Boot `RedisProperties` for the general endpoint and existing `AccommodationDetailRedisProperties` for the cache endpoint.
- Produces: `performanceLabRedisEndpointValidator`, an `InitializingBean` active only under `performance-lab`, which fails startup when the normalized host/port tuple is identical.

- [ ] **Step 1: Reverse the configuration regression test**

Change the AWS assertion in `RedisMonitoringConfigurationTest.applicationProfilesPointTheCacheClientAtTheDedicatedRedis` to require explicit variables and a configurable general port:

```java
assertProfileCacheEndpoint(
    "application-aws.yaml",
    "${ACCOMMODATION_DETAIL_CACHE_REDIS_HOST}",
    "${ACCOMMODATION_DETAIL_CACHE_REDIS_PORT}"
);
assertThat(awsProperty("spring.data.redis.port"))
    .isEqualTo("${REDIS_PORT}");
```

Keep the OCI assertion unchanged because OCI already injects `ACCOMMODATION_DETAIL_CACHE_REDIS_HOST=redis-cache` and is outside this lab profile.

- [ ] **Step 2: Add the endpoint validator tests**

Use `ApplicationContextRunner` with `PerformanceLabRedisEndpointConfiguration` and `AccommodationDetailRedisConfig`. Prove these exact cases:

```java
@Test
void rejectsTheSameGeneralAndCacheEndpoint() {
    runner
        .withPropertyValues(
            "spring.data.redis.host=redis.internal",
            "spring.data.redis.port=6379",
            "accommodation.detail-cache.redis.host=redis.internal",
            "accommodation.detail-cache.redis.port=6379")
        .run(context -> assertThat(context).hasFailed());
}

@Test
void acceptsTwoPortsOnTheSameRedisHost() {
    runner
        .withPropertyValues(
            "spring.data.redis.host=redis.internal",
            "spring.data.redis.port=6379",
            "accommodation.detail-cache.redis.host=redis.internal",
            "accommodation.detail-cache.redis.port=6380")
        .run(context -> assertThat(context).hasNotFailed());
}
```

Also prove host comparison is case-insensitive and trims surrounding whitespace.

- [ ] **Step 3: Run the tests and confirm the intended failure**

Run:

```bash
./gradlew test --tests "kr.kro.airbob.common.monitoring.RedisMonitoringConfigurationTest" --tests "kr.kro.airbob.config.PerformanceLabRedisEndpointConfigurationTest"
```

Expected: compilation fails because the validator class is absent, or the AWS profile assertion fails because it still contains cache→general fallback.

- [ ] **Step 4: Make AWS endpoints explicit**

Change only the AWS profile Redis properties to:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}

accommodation:
  detail-cache:
    redis:
      host: ${ACCOMMODATION_DETAIL_CACHE_REDIS_HOST}
      port: ${ACCOMMODATION_DETAIL_CACHE_REDIS_PORT}
```

- [ ] **Step 5: Implement the performance-lab validator**

Create a profile-scoped configuration with this contract:

```java
@Configuration(proxyBeanMethods = false)
@Profile("performance-lab")
@EnableConfigurationProperties({RedisProperties.class, AccommodationDetailRedisProperties.class})
public class PerformanceLabRedisEndpointConfiguration {

    @Bean
    InitializingBean performanceLabRedisEndpointValidator(
        RedisProperties general,
        AccommodationDetailRedisProperties cache
    ) {
        return () -> {
            String generalHost = normalize(general.getHost());
            String cacheHost = normalize(cache.host());
            boolean sameEndpoint = generalHost.equals(cacheHost)
                && general.getPort() == cache.port();
            Assert.state(!sameEndpoint,
                "performance-lab requires distinct general and accommodation cache Redis endpoints");
        };
    }

    private String normalize(String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 6: Verify the contract passes**

Run the Task 1 Gradle command again. Expected: PASS.

- [ ] **Step 7: Prove physical routing with two Redis containers**

Create two `redis:7.2-alpine` Testcontainers and an `ApplicationContextRunner` containing `RedisAutoConfiguration`, `RedisConfig`, `AccommodationDetailRedisConfig`, `AccommodationDetailCacheConfiguration` and the new validator. Inject each mapped port into its corresponding property, then execute this contract from the `kr.kro.airbob.domain.accommodation.cache` test package:

```java
generalRedisTemplate.opsForValue().set("SESSION:lab-member", "general");
cacheRedisTemplate.opsForValue().set(
    "airbob:cache:accommodation-detail:v1:1", "cache");

assertThat(generalRedisTemplate.hasKey("SESSION:lab-member")).isTrue();
assertThat(cacheRedisTemplate.hasKey("SESSION:lab-member")).isFalse();
assertThat(cacheRedisTemplate.hasKey(
    "airbob:cache:accommodation-detail:v1:1")).isTrue();
assertThat(generalRedisTemplate.hasKey(
    "airbob:cache:accommodation-detail:v1:1")).isFalse();

cacheRedisTemplate.getConnectionFactory().getConnection()
    .serverCommands().flushDb();

assertThat(generalRedisTemplate.hasKey("SESSION:lab-member")).isTrue();
```

Obtain the dedicated `StringRedisTemplate` from the existing `AccommodationDetailRedisClient` inside the same package; do not introduce a third public cache client bean solely for the test.

Run:

```bash
./gradlew test --tests "kr.kro.airbob.domain.accommodation.cache.PerformanceLabRedisEndpointIsolationIntegrationTest"
```

Expected: PASS when Docker is available. If Docker is unavailable, report that blocker rather than replacing this with a single-Redis or mocked test.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/application-aws.yaml src/main/java/kr/kro/airbob/config/PerformanceLabRedisEndpointConfiguration.java src/test/java/kr/kro/airbob/config/PerformanceLabRedisEndpointConfigurationTest.java src/test/java/kr/kro/airbob/domain/accommodation/cache/PerformanceLabRedisEndpointIsolationIntegrationTest.java src/test/java/kr/kro/airbob/common/monitoring/RedisMonitoringConfigurationTest.java
git commit -m "feat: fail fast on shared AWS Redis endpoints"
```

---

### Task 2: Same-Image Accommodation Cache Toggle

**Files:**
- Modify: `src/main/resources/application.yaml`
- Modify: `src/main/java/kr/kro/airbob/domain/accommodation/cache/AccommodationDetailCacheProperties.java`
- Modify: `src/main/java/kr/kro/airbob/domain/accommodation/cache/AccommodationDetailCache.java`
- Modify: `src/test/java/kr/kro/airbob/domain/accommodation/cache/AccommodationDetailCachePropertiesTest.java`
- Modify: `src/test/java/kr/kro/airbob/domain/accommodation/cache/AccommodationDetailCacheTest.java`
- Modify: every test constructing `AccommodationDetailCacheProperties` directly

**Interfaces:**
- Consumes: `ACCOMMODATION_DETAIL_CACHE_ENABLED`, default `true`.
- Produces: `AccommodationDetailCacheProperties.enabled()` and cache bypass behavior that never touches Redis/Redisson when false.

- [ ] **Step 1: Add failing disabled-cache behavior tests**

In `AccommodationDetailCacheTest`, construct properties with `enabled=false` and prove:

```java
AccommodationDetailSnapshot result = cache.getOrLoad(1L, () -> expected);

assertThat(result).isSameAs(expected);
then(redisClient).shouldHaveNoInteractions();
then(redissonClient).shouldHaveNoInteractions();
```

Add a second test calling `cache.evict(...)` while disabled and assert both clients and the metric recorder receive no cache-operation calls.

- [ ] **Step 2: Add the property binding assertion**

Update `AccommodationDetailCachePropertiesTest` to require `enabled` and assert a constructed disabled property returns `false`.

- [ ] **Step 3: Run the cache tests and confirm failure**

Run:

```bash
./gradlew test --tests "kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCachePropertiesTest" --tests "kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheTest"
```

Expected: compilation fails because `enabled` does not exist or Redis interactions occur.

- [ ] **Step 4: Bind the toggle**

Add this property before the timing values:

```yaml
accommodation:
  detail-cache:
    enabled: ${ACCOMMODATION_DETAIL_CACHE_ENABLED:true}
```

Add `boolean enabled` as the first record component of `AccommodationDetailCacheProperties`. Update every direct constructor call with `true`, except the explicit bypass tests which use `false`.

- [ ] **Step 5: Implement the minimal bypass**

At the start of the two public operations:

```java
public AccommodationDetailSnapshot getOrLoad(Long accommodationId,
    Supplier<AccommodationDetailSnapshot> loader) {
    if (!properties.enabled()) {
        return timedUncachedLoad(loader);
    }
    // existing logic unchanged
}

public void evict(Long accommodationId,
    AccommodationDetailCacheInvalidationReason reason) {
    if (!properties.enabled()) {
        return;
    }
    // existing logic unchanged
}
```

Do not destroy the dedicated Redis beans when disabled; the A/B experiment changes only cache use, not infrastructure topology.

- [ ] **Step 6: Run focused and concurrency tests**

Run:

```bash
./gradlew test --tests "kr.kro.airbob.domain.accommodation.cache.*"
```

Expected: PASS, including concurrency and invalidation race tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/application.yaml src/main/java/kr/kro/airbob/domain/accommodation/cache/AccommodationDetailCacheProperties.java src/main/java/kr/kro/airbob/domain/accommodation/cache/AccommodationDetailCache.java src/test/java/kr/kro/airbob/config/AccommodationDetailRedisConfigTest.java src/test/java/kr/kro/airbob/domain/accommodation/cache
git commit -m "feat: add accommodation cache experiment toggle"
```

---

### Task 3: Performance-Lab External Side-Effect Guards

**Files:**
- Create: `src/main/resources/application-performance-lab.yaml`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/main/java/kr/kro/airbob/domain/payment/service/TossPaymentsAdapter.java`
- Modify: `src/main/java/kr/kro/airbob/domain/image/service/S3ImageUploader.java`
- Modify: `src/main/java/kr/kro/airbob/geo/impl/GoogleGeocodingService.java`
- Modify: `src/test/java/kr/kro/airbob/domain/payment/service/TossPaymentsAdapterTest.java`
- Create: `src/test/java/kr/kro/airbob/domain/image/service/S3ImageUploaderGuardTest.java`
- Modify: `src/test/java/kr/kro/airbob/geo/impl/GoogleGeocodingServiceTest.java`
- Create: `src/test/java/kr/kro/airbob/config/PerformanceLabProfileConfigurationTest.java`

**Interfaces:**
- Consumes: `payment.toss.enabled`, `google.api.enabled`, `cloud.aws.s3.write-enabled`, and existing `slack.notification.enabled`.
- Produces: default-enabled production behavior and a `performance-lab` profile where all four integrations are disabled before network/storage clients are touched.

- [ ] **Step 1: Add failing zero-interaction tests**

Add the following behavioral cases:

```java
assertThatThrownBy(() -> disabledToss.confirmPayment("p", "o", 1))
    .isInstanceOf(IllegalStateException.class)
    .hasMessageContaining("Toss Payments is disabled");
server.verify(); // no request expectation
```

```java
assertThatThrownBy(() -> disabledUploader.upload(file, "lab"))
    .isInstanceOf(IllegalStateException.class)
    .hasMessageContaining("S3 writes are disabled");
then(s3Template).shouldHaveNoInteractions();
```

```java
GeocodeResult result = disabledService.getCoordinates("서울");
assertThat(result.success()).isFalse();
then(restClient).shouldHaveNoInteractions();
```

For S3 delete, require a no-op with zero `S3Template` interactions. For Toss, cover confirm, cancel, both lookup entry points and virtual-account issue through a parameterized or compact helper test.

- [ ] **Step 2: Run guard tests and confirm failure**

Run:

```bash
./gradlew test --tests "kr.kro.airbob.domain.payment.service.TossPaymentsAdapterTest" --tests "kr.kro.airbob.domain.image.service.S3ImageUploaderGuardTest" --tests "kr.kro.airbob.geo.impl.GoogleGeocodingServiceTest"
```

Expected: constructors/properties or guard behavior are absent.

- [ ] **Step 3: Add default-enabled properties**

Add only these defaults to `application.yaml`:

```yaml
google:
  api:
    enabled: ${GOOGLE_API_ENABLED:true}

payment:
  toss:
    enabled: ${TOSS_PAYMENTS_ENABLED:true}

cloud:
  aws:
    s3:
      write-enabled: ${AWS_S3_WRITE_ENABLED:true}
```

- [ ] **Step 4: Guard Toss before building any request**

Inject `@Value("${payment.toss.enabled:true}") boolean enabled` into the adapter constructor, store it, and call:

```java
private void requireEnabled() {
    if (!enabled) {
        throw new IllegalStateException("Toss Payments is disabled in this runtime profile");
    }
}
```

at the top of `confirmPayment`, `cancelPayment`, private `getPayment`, and `issueVirtualAccount`. Update direct test construction to pass `true`, and use `false` only in guard tests.

- [ ] **Step 5: Guard S3 and Google before their clients**

Add `cloud.aws.s3.write-enabled` to `S3ImageUploader`; reject upload and return immediately from delete when false. Add `google.api.enabled` to `GoogleGeocodingService`; return `GeocodeResult.fail()` before constructing the URL when false. Keep all existing exception behavior when enabled.

- [ ] **Step 6: Create the performance-lab profile**

Create exactly this fail-closed profile overlay:

```yaml
payment:
  toss:
    enabled: false
    secret-key: performance-lab-disabled
    base-url: http://127.0.0.1

google:
  api:
    enabled: false
    key: performance-lab-disabled

slack:
  webhook:
    url: ""
  notification:
    enabled: false

cloud:
  aws:
    s3:
      write-enabled: false
      bucket: performance-lab-disabled
  cloudfront:
    domain: https://invalid.performance-lab
```

Add a YAML property-source test asserting every guard is false and that the profile contains no production hostname, webhook or secret placeholder.

- [ ] **Step 7: Run all external integration unit tests**

Run the Step 2 command plus:

```bash
./gradlew test --tests "kr.kro.airbob.config.PerformanceLabProfileConfigurationTest" --tests "kr.kro.airbob.outbox.*"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/application.yaml src/main/resources/application-performance-lab.yaml src/main/java/kr/kro/airbob/domain/payment/service/TossPaymentsAdapter.java src/main/java/kr/kro/airbob/domain/image/service/S3ImageUploader.java src/main/java/kr/kro/airbob/geo/impl/GoogleGeocodingService.java src/test/java/kr/kro/airbob/domain/payment/service/TossPaymentsAdapterTest.java src/test/java/kr/kro/airbob/domain/image/service/S3ImageUploaderGuardTest.java src/test/java/kr/kro/airbob/geo/impl/GoogleGeocodingServiceTest.java src/test/java/kr/kro/airbob/config/PerformanceLabProfileConfigurationTest.java
git commit -m "feat: block external side effects in performance lab"
```

---

### Task 4: Isolated-Read Background Work Policy

**Files:**
- Create: `src/main/resources/application-traffic-benchmark.yaml`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/main/java/kr/kro/airbob/config/SchedulingConfig.java`
- Modify: `src/main/java/kr/kro/airbob/config/KafkaConfig.java`
- Modify: `src/test/java/kr/kro/airbob/config/SchedulingConfigTest.java`
- Modify: `src/test/java/kr/kro/airbob/config/KafkaConfigTest.java`
- Create: `src/test/java/kr/kro/airbob/config/TrafficBenchmarkProfileConfigurationTest.java`

**Interfaces:**
- Consumes: active Spring profile `traffic-benchmark`.
- Produces: a profile group that always includes `performance-lab`, does not create `SchedulingConfig` or `KafkaConfig`, and sets Kafka listener auto-startup false as a second fail-closed guard.

- [ ] **Step 1: Add failing profile-context tests**

Follow the existing `SchedulingConfigTest` and `BulkWriteBenchmarkSchedulingTest` `ApplicationContextRunner` pattern. Add assertions equivalent to:

```java
runner.withPropertyValues("spring.profiles.active=traffic-benchmark")
    .run(context -> {
        assertThat(context).doesNotHaveBean(SchedulingConfig.class);
        assertThat(context).doesNotHaveBean(KafkaConfig.class);
    });
```

Use a YAML property-source assertion to require:

```text
spring.kafka.listener.auto-startup=false
spring.profiles.group.traffic-benchmark contains performance-lab
```

Add a control case proving `performance-lab` alone still creates scheduler/Kafka configuration for integrated smoke.

- [ ] **Step 2: Run the profile tests and confirm failure**

Run:

```bash
./gradlew test --tests "kr.kro.airbob.config.SchedulingConfigTest" --tests "kr.kro.airbob.config.KafkaConfigTest" --tests "kr.kro.airbob.config.TrafficBenchmarkProfileConfigurationTest"
```

Expected: `traffic-benchmark` still permits the configurations or its YAML/profile group is missing.

- [ ] **Step 3: Make profile annotations explicit**

Use these exact expressions:

```java
@Profile("!bulk-write-benchmark & !traffic-benchmark & !test")
public class SchedulingConfig { }
```

```java
@Configuration
@Profile("!traffic-benchmark")
@EnableKafkaRetryTopic
public class KafkaConfig { }
```

Do not annotate all seven consumers individually; disabling `KafkaConfig` removes the retry scheduler, and the YAML listener guard prevents listener container startup if another Kafka configuration is introduced later.

- [ ] **Step 4: Add profile group and overlay**

In base `application.yaml` add:

```yaml
spring:
  profiles:
    group:
      traffic-benchmark:
        - performance-lab
```

Create:

```yaml
spring:
  kafka:
    listener:
      auto-startup: false
```

in `application-traffic-benchmark.yaml`.

- [ ] **Step 5: Verify scheduler/Kafka policy**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 6: Run the complete Phase 0 regression suite**

Run:

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL with every pre-existing test and new Phase 0 test passing.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/application.yaml src/main/resources/application-traffic-benchmark.yaml src/main/java/kr/kro/airbob/config/SchedulingConfig.java src/main/java/kr/kro/airbob/config/KafkaConfig.java src/test/java/kr/kro/airbob/config/SchedulingConfigTest.java src/test/java/kr/kro/airbob/config/KafkaConfigTest.java src/test/java/kr/kro/airbob/config/TrafficBenchmarkProfileConfigurationTest.java
git commit -m "feat: isolate read benchmark background work"
```

---

### Task 5: Phase 0 Contract Documentation and Gate

**Files:**
- Modify: `docs/superpowers/specs/2026-08-14-ephemeral-aws-performance-lab-design.md`
- Create: `docs/performance/aws-performance-lab.md`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: the four completed Phase 0 contracts and their exact profile combinations.
- Produces: an operator-facing profile matrix and a CI gate that runs the targeted tests before any Terraform/bundle change can merge.

- [ ] **Step 1: Add a documentation contract test through CI commands**

Extend CI with a named step after the normal Gradle test/build step:

```yaml
- name: Verify AWS performance-lab application contracts
  run: |
    ./gradlew test \
      --tests "kr.kro.airbob.config.PerformanceLabRedisEndpointConfigurationTest" \
      --tests "kr.kro.airbob.config.PerformanceLabProfileConfigurationTest" \
      --tests "kr.kro.airbob.config.TrafficBenchmarkProfileConfigurationTest" \
      --tests "kr.kro.airbob.common.monitoring.RedisMonitoringConfigurationTest"
```

- [ ] **Step 2: Document only implemented behavior**

Create `docs/performance/aws-performance-lab.md` with this status table:

```markdown
| Capability | Status |
|---|---|
| Redis general/cache endpoint fail-fast | Implemented |
| Same-image accommodation cache toggle | Implemented |
| External Toss/Google/Slack/S3 side-effect block | Implemented |
| Scheduler/Kafka isolated-read policy | Implemented |
| Terraform foundation/lab | Not implemented yet |
| Route 53 cutover | Not executed |
| AWS performance evidence | Not collected |
```

Document the two exact combinations:

```text
integrated-smoke: SPRING_PROFILES_ACTIVE=aws,performance-lab
isolated-read:    SPRING_PROFILES_ACTIVE=aws,traffic-benchmark
```

State that `application-traffic-benchmark.yaml` includes `performance-lab` through a profile group, that cache reset means `FLUSHDB` on the dedicated cache Redis only, and that no HTTP reset endpoint exists.

- [ ] **Step 3: Update design status without claiming AWS completion**

Mark only Phase 0 bullets as implemented in the design. Keep the document’s global status as implementation-in-progress and keep Terraform, DNS migration and evidence claims pending.

- [ ] **Step 4: Verify CI YAML and full tests**

Run:

```bash
./gradlew test
git diff --check
```

If `actionlint` is installed, run `actionlint .github/workflows/ci.yml`; otherwise validate the workflow syntax later in the immutable-bundle plan where actionlint is pinned. Do not silently replace it with a different validator.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml docs/performance/aws-performance-lab.md docs/superpowers/specs/2026-08-14-ephemeral-aws-performance-lab-design.md
git commit -m "docs: define AWS performance lab application gate"
```

## Self-Review Checklist

- Every Phase 0 requirement in the approved design maps to exactly one task above.
- No task creates a third Redis endpoint, a reset HTTP endpoint or a production external-call fallback.
- `performance-lab` and `traffic-benchmark` are distinct: integrated smoke keeps internal Kafka/scheduler flow, isolated read removes it.
- All direct `AccommodationDetailCacheProperties` and `TossPaymentsAdapter` constructor sites are included in compilation-driven updates.
- Every code task starts with an observed failing test, ends with focused verification and has an independent commit.
- Terraform, Docker bundle, Route 53, AWS apply and actual DNS mutation are intentionally deferred to their own implementation plans.
