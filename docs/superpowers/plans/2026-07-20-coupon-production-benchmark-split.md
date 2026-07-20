# Coupon Production and Benchmark Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Redis Lua the only production coupon issuance path while exposing the Redisson implementation through a session- and token-protected benchmark API.

**Architecture:** `CouponController` keeps the strategy-neutral v1 contract and delegates only to `CouponLuaIssueService`. A profile-gated `CouponBenchmarkController` exposes the existing Redisson service at v2, while `SessionAuthFilter` establishes the member identity and `BenchmarkAccessGuard` enforces the shared benchmark token. k6 maps `lua` and `lock` variants to their explicit endpoints without changing result schemas or metric names.

**Tech Stack:** Java 21, Spring Boot 3.5.8, Spring MVC, Spring Profiles, Redisson, Redis Lua, JUnit 5, Mockito, AssertJ, Testcontainers, k6 JavaScript.

## Global Constraints

- Production issuance is exactly `POST /api/v1/coupons/{couponId}/issue` and calls `CouponLuaIssueService`.
- Benchmark issuance is exactly `POST /api/v2/coupons/{couponId}/issue` and calls `CouponLockIssueService`.
- Remove `/issue/lua` and `/issue/lock` without compatibility aliases.
- The benchmark API requires the `coupon-benchmark` profile, `benchmark.read-model.enabled=true`, a member `SESSION_ID`, and `X-Benchmark-Token` matching `BENCHMARK_READ_MODEL_TOKEN`.
- Reuse the existing `benchmark.read-model` property prefix; do not introduce `benchmark.coupon` or a second token.
- Keep `CouponLuaIssueService`, `CouponRedisStockManager`, `CouponStockPreparationService`, `CouponIssueTransactionService`, and the admin stock preparation API available in normal profiles.
- Do not change the Lua scripts, Redisson lock timing, database schema, coupon error codes, compensation order, custom metric names, k6 fixture schema, or k6 result artifact schema.
- Preserve all pre-existing worktree edits. In particular, do not restore deleted `asg-scale-test.js` or `search-load-test.js`, and do not rewrite the dirty read-model k6 files.
- Stage only the files listed in each task and inspect `git diff --cached` before every commit.

## File Map

- `CouponController.java`: production coupon reads and Lua-only issuance.
- `CouponBenchmarkController.java`: profile/property/token-gated v2 Redisson issuance.
- `CouponLockIssueService.java`, `CouponLockManager.java`: coupon Redisson components available only in `coupon-benchmark`.
- `BenchmarkAccessGuard.java`, `application-coupon-benchmark.yaml`: shared token guard and strict profile configuration.
- `WebMvcConfig.java`: applies session authentication to the v2 coupon path without making all v2 APIs private.
- `coupon-benchmark-fixture.js`: pure variant-to-request-target mapping for k6.
- `coupon-issuance-comparison.js`: sends the request using the helper-provided path, metric name, and headers.
- `load-test/README.md`: operational instructions for local and AWS benchmark runs.

---

### Task 1: Make the production controller Lua-only

**Files:**

- Modify: `src/test/java/kr/kro/airbob/domain/coupon/api/CouponControllerTest.java`
- Modify: `src/main/java/kr/kro/airbob/domain/coupon/api/CouponController.java`

**Interfaces:**

- Consumes: `void CouponLuaIssueService.issue(Long couponId, Long memberId)`
- Produces: `ResponseEntity<ApiResponse<Void>> CouponController.issueCoupon(Long couponId)`
- Produces: `POST /api/v1/coupons/{couponId}/issue`

- [ ] **Step 1: Rewrite the controller contract test first**

Remove the `CouponLockIssueService` mock, construct the controller with `(couponService, luaIssueService)`, and replace the two strategy tests with the following exact contracts:

```java
@Test
@DisplayName("운영 발급 URL은 Lua 서비스로 발급하고 201을 반환한다")
void issuesCouponWithLua() throws Exception {
	mockMvc.perform(post("/api/v1/coupons/1/issue"))
		.andExpect(status().isCreated())
		.andExpect(jsonPath("$.success").value(true));

	verify(luaIssueService).issue(1L, 10L);
}

@Test
@DisplayName("운영 API는 동시성 전략 suffix를 노출하지 않는다")
void strategySuffixEndpointsAreNotMapped() throws Exception {
	mockMvc.perform(post("/api/v1/coupons/1/issue/lua"))
		.andExpect(status().isNotFound());
	mockMvc.perform(post("/api/v1/coupons/1/issue/lock"))
		.andExpect(status().isNotFound());
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./gradlew test --tests "kr.kro.airbob.domain.coupon.api.CouponControllerTest"
```

Expected: compilation fails because the test calls the new two-argument constructor, or the neutral `/issue` assertion fails before the production controller is changed.

- [ ] **Step 3: Implement the minimal production controller change**

Remove the lock service field and both strategy-specific methods. Keep the existing coupon-list method and use this issuance method:

```java
private final CouponService couponService;
private final CouponLuaIssueService luaIssueService;

@PostMapping("/v1/coupons/{couponId}/issue")
public ResponseEntity<ApiResponse<Void>> issueCoupon(@PathVariable Long couponId) {
	Long memberId = UserContext.get().id();
	luaIssueService.issue(couponId, memberId);
	return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
}
```

Delete the unused `CouponLockIssueService` import.

- [ ] **Step 4: Re-run the focused test and verify GREEN**

Run the command from Step 2.

Expected: `CouponControllerTest` passes, including `404` for both removed suffix paths.

- [ ] **Step 5: Commit the production API contract**

```bash
git add src/main/java/kr/kro/airbob/domain/coupon/api/CouponController.java \
  src/test/java/kr/kro/airbob/domain/coupon/api/CouponControllerTest.java
git diff --cached --check
git commit -m "refactor: 쿠폰 운영 발급을 Lua 경로로 단일화"
```

---

### Task 2: Isolate Redisson coupon beans behind the benchmark profile

**Files:**

- Create: `src/main/resources/application-coupon-benchmark.yaml`
- Create: `src/test/java/kr/kro/airbob/domain/coupon/service/CouponBenchmarkComponentProfileTest.java`
- Modify: `src/test/java/kr/kro/airbob/common/benchmark/BenchmarkProfileConfigurationTest.java`
- Modify: `src/main/java/kr/kro/airbob/common/benchmark/BenchmarkAccessGuard.java`
- Modify: `src/main/java/kr/kro/airbob/domain/coupon/service/CouponLockIssueService.java`
- Modify: `src/main/java/kr/kro/airbob/domain/coupon/service/CouponLockManager.java`
- Modify: `src/test/java/kr/kro/airbob/domain/coupon/CouponConcurrencyTest.java`

**Interfaces:**

- Produces: profile `coupon-benchmark`
- Produces: `benchmark.read-model.enabled=true`
- Produces: `benchmark.read-model.token=${BENCHMARK_READ_MODEL_TOKEN}`
- Preserves: `void CouponLockIssueService.issue(Long couponId, Long memberId)`

- [ ] **Step 1: Add failing profile configuration assertions**

Extend `benchmarkProfilesUseTheSameReadModelSettings()` with the coupon profile:

```java
assertReadModelSettings("application-nplus1-benchmark.yaml");
assertReadModelSettings("application-read-model-benchmark.yaml");
assertReadModelSettings("application-coupon-benchmark.yaml");
```

Add a guard context runner and profile assertion to the same test class:

```java
private final ApplicationContextRunner guardContextRunner = new ApplicationContextRunner()
	.withUserConfiguration(GuardConfiguration.class);

@Test
@DisplayName("공통 토큰 가드는 coupon benchmark 프로필에서도 생성된다")
void couponBenchmarkProfileCreatesSharedGuard() {
	guardContextRunner
		.withInitializer(context -> context.getEnvironment().setActiveProfiles("coupon-benchmark"))
		.withPropertyValues("benchmark.read-model.token=test-token")
		.run(context -> assertThat(context).hasSingleBean(BenchmarkAccessGuard.class));
}

@Configuration(proxyBeanMethods = false)
@Import(BenchmarkAccessGuard.class)
static class GuardConfiguration {
}
```

Add the required imports for `ApplicationContextRunner`, `Configuration`, and `Import`.

- [ ] **Step 2: Add a failing bean-isolation test**

Create `CouponBenchmarkComponentProfileTest` with this complete structure:

```java
package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;

@DisplayName("쿠폰 Redisson 벤치마크 빈 프로필 테스트")
class CouponBenchmarkComponentProfileTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	@DisplayName("정상 프로필에서는 쿠폰 Redisson 발급 빈을 만들지 않는다")
	void normalProfileExcludesRedissonCouponBeans() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(CouponLockIssueService.class)
			.doesNotHaveBean(CouponLockManager.class));
	}

	@Test
	@DisplayName("coupon benchmark 프로필에서는 쿠폰 Redisson 발급 빈을 만든다")
	void benchmarkProfileCreatesRedissonCouponBeans() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("coupon-benchmark"))
			.run(context -> assertThat(context)
				.hasSingleBean(CouponLockIssueService.class)
				.hasSingleBean(CouponLockManager.class));
	}

	@Configuration(proxyBeanMethods = false)
	@Import({CouponLockIssueService.class, CouponLockManager.class})
	static class TestConfiguration {

		@Bean
		CouponIssueTransactionService couponIssueTransactionService() {
			return mock(CouponIssueTransactionService.class);
		}

		@Bean
		CouponIssueMetricRecorder couponIssueMetricRecorder() {
			return mock(CouponIssueMetricRecorder.class);
		}

		@Bean
		RedissonClient redissonClient() {
			return mock(RedissonClient.class);
		}
	}
}
```

- [ ] **Step 3: Run both tests and verify RED**

```bash
./gradlew test \
  --tests "kr.kro.airbob.common.benchmark.BenchmarkProfileConfigurationTest" \
  --tests "kr.kro.airbob.domain.coupon.service.CouponBenchmarkComponentProfileTest"
```

Expected: the configuration test cannot load `application-coupon-benchmark.yaml`, and the default-profile component test finds Redisson coupon beans.

- [ ] **Step 4: Add the profile configuration and annotations**

Create the strict profile file:

```yaml
benchmark:
  read-model:
    enabled: true
    token: ${BENCHMARK_READ_MODEL_TOKEN}
```

Change the common guard profile to:

```java
@Profile({"nplus1-benchmark", "read-model-benchmark", "coupon-benchmark"})
```

Add this annotation and its import to both `CouponLockIssueService` and `CouponLockManager`:

```java
import org.springframework.context.annotation.Profile;

@Profile("coupon-benchmark")
```

Keep `CouponIssueTransactionService` and `CouponRedisStockManager` unprofiled because Lua production issuance uses them.

- [ ] **Step 5: Make the existing concurrency comparison opt into the profile**

Change its test annotations to resolve the strict token placeholder while retaining the existing test profile:

```java
@SpringBootTest(properties = {
	"spring.cloud.aws.s3.enabled=false",
	"benchmark.read-model.enabled=true",
	"benchmark.read-model.token=test-token"
})
@ActiveProfiles({"test", "coupon-benchmark"})
class CouponConcurrencyTest {
```

Do not change any lock/Lua concurrency scenario or assertion.

- [ ] **Step 6: Re-run profile tests and concurrency regression**

```bash
./gradlew test \
  --tests "kr.kro.airbob.common.benchmark.BenchmarkProfileConfigurationTest" \
  --tests "kr.kro.airbob.domain.coupon.service.CouponBenchmarkComponentProfileTest"
./gradlew test --tests "kr.kro.airbob.domain.coupon.CouponConcurrencyTest"
```

Expected: all selected tests pass. The second command may start MySQL and Redis Testcontainers.

- [ ] **Step 7: Commit profile isolation**

```bash
git add src/main/resources/application-coupon-benchmark.yaml \
  src/main/java/kr/kro/airbob/common/benchmark/BenchmarkAccessGuard.java \
  src/main/java/kr/kro/airbob/domain/coupon/service/CouponLockIssueService.java \
  src/main/java/kr/kro/airbob/domain/coupon/service/CouponLockManager.java \
  src/test/java/kr/kro/airbob/common/benchmark/BenchmarkProfileConfigurationTest.java \
  src/test/java/kr/kro/airbob/domain/coupon/service/CouponBenchmarkComponentProfileTest.java \
  src/test/java/kr/kro/airbob/domain/coupon/CouponConcurrencyTest.java
git diff --cached --check
git commit -m "refactor: Redisson 쿠폰 발급을 벤치마크 프로필로 격리"
```

---

### Task 3: Expose and protect the v2 Redisson benchmark API

**Files:**

- Create: `src/main/java/kr/kro/airbob/domain/coupon/api/CouponBenchmarkController.java`
- Create: `src/test/java/kr/kro/airbob/domain/coupon/api/CouponBenchmarkControllerTest.java`
- Create: `src/test/java/kr/kro/airbob/domain/coupon/api/CouponBenchmarkAccessTest.java`
- Modify: `src/main/java/kr/kro/airbob/config/WebMvcConfig.java`
- Modify: `src/test/java/kr/kro/airbob/config/WebMvcConfigBenchmarkProtectionTest.java`

**Interfaces:**

- Consumes: `BenchmarkAccessGuard.HEADER_NAME`, whose value is `X-Benchmark-Token`
- Consumes: authenticated `UserContext.get().id()`
- Produces: `POST /api/v2/coupons/{couponId}/issue`
- Produces: `201 ApiResponse.success()` after a committed Redisson issuance

- [ ] **Step 1: Add failing controller gate and delegation tests**

Create `CouponBenchmarkControllerTest` following the established benchmark-controller pattern. Its context tests must express all four combinations:

```java
contextRunner.run(context -> assertThat(context)
	.doesNotHaveBean(CouponBenchmarkController.class));

contextRunner.withPropertyValues("benchmark.read-model.enabled=true")
	.run(context -> assertThat(context)
		.doesNotHaveBean(CouponBenchmarkController.class));

contextRunner
	.withInitializer(context -> context.getEnvironment().setActiveProfiles("coupon-benchmark"))
	.run(context -> assertThat(context)
		.doesNotHaveBean(CouponBenchmarkController.class));

contextRunner
	.withInitializer(context -> context.getEnvironment().setActiveProfiles("coupon-benchmark"))
	.withPropertyValues("benchmark.read-model.enabled=true")
	.run(context -> assertThat(context)
		.hasSingleBean(CouponBenchmarkController.class));
```

Use this exact direct-call contract to prove token checking precedes service delegation:

```java
CouponLockIssueService service = mock(CouponLockIssueService.class);
BenchmarkAccessGuard guard = mock(BenchmarkAccessGuard.class);
CouponBenchmarkController controller = new CouponBenchmarkController(service, guard);
UserContext.set(new UserInfo(7L));

try {
	var response = controller.issueCouponWithLock(1L, "secret-token");
	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	InOrder order = inOrder(guard, service);
	order.verify(guard).verify("secret-token");
	order.verify(service).issue(1L, 7L);
} finally {
	UserContext.clear();
}
```

The test configuration must import `CouponBenchmarkController` and provide mock `CouponLockIssueService` and `BenchmarkAccessGuard` beans.

- [ ] **Step 2: Add failing end-to-end credential tests**

Create `CouponBenchmarkAccessTest` with `MockMvcBuilders.standaloneSetup`, a real `SessionAuthFilter`, a real `BenchmarkAccessGuard("secret-token")`, and mocked Redis/session dependencies. Use this helper for authenticated cases:

```java
private void authenticate() {
	given(redisTemplate.hasKey("SESSION:valid-session")).willReturn(true);
	given(redisTemplate.opsForValue()).willReturn(valueOperations);
	given(valueOperations.get("SESSION:valid-session")).willReturn(10L);
}
```

Configure MockMvc as follows:

```java
SessionAuthFilter sessionAuthFilter = new SessionAuthFilter(redisTemplate, new ObjectMapper());
CouponBenchmarkController controller = new CouponBenchmarkController(
	lockIssueService,
	new BenchmarkAccessGuard("secret-token")
);
mockMvc = MockMvcBuilders.standaloneSetup(controller)
	.setControllerAdvice(new GlobalExceptionHandler())
	.addFilters(sessionAuthFilter)
	.build();
```

Add these four exact HTTP contracts:

```java
mockMvc.perform(post("/api/v2/coupons/1/issue")
		.header(BenchmarkAccessGuard.HEADER_NAME, "secret-token"))
	.andExpect(status().isUnauthorized())
	.andExpect(jsonPath("$.error.code").value("M004"));

authenticate();
mockMvc.perform(post("/api/v2/coupons/1/issue")
		.cookie(new Cookie("SESSION_ID", "valid-session")))
	.andExpect(status().isForbidden())
	.andExpect(jsonPath("$.error.code").value("B001"));

authenticate();
mockMvc.perform(post("/api/v2/coupons/1/issue")
		.cookie(new Cookie("SESSION_ID", "valid-session"))
		.header(BenchmarkAccessGuard.HEADER_NAME, "wrong-token"))
	.andExpect(status().isForbidden())
	.andExpect(jsonPath("$.error.code").value("B001"));

authenticate();
mockMvc.perform(post("/api/v2/coupons/1/issue")
		.cookie(new Cookie("SESSION_ID", "valid-session"))
		.header(BenchmarkAccessGuard.HEADER_NAME, "secret-token"))
	.andExpect(status().isCreated())
	.andExpect(jsonPath("$.success").value(true));
verify(lockIssueService).issue(1L, 10L);
```

For the first three tests, also assert `verifyNoInteractions(lockIssueService)`.

- [ ] **Step 3: Add the failing session-filter mapping assertion**

Add this method to `WebMvcConfigBenchmarkProtectionTest`:

```java
@Test
@DisplayName("v2 쿠폰 benchmark API에 세션 인증을 적용한다")
void v2CouponBenchmarkPathUsesSessionAuthentication() {
	WebMvcConfig config = new WebMvcConfig(
		mock(CursorParamArgumentResolver.class),
		mock(SessionAuthFilter.class),
		mock(AdminAuthInterceptor.class),
		mock(QueryCountInterceptor.class)
	);

	assertThat(config.sessionFilter().getUrlPatterns())
		.contains("/api/v2/coupons/*");
}
```

- [ ] **Step 4: Run the focused tests and verify RED**

```bash
./gradlew test \
  --tests "kr.kro.airbob.domain.coupon.api.CouponBenchmarkControllerTest" \
  --tests "kr.kro.airbob.domain.coupon.api.CouponBenchmarkAccessTest" \
  --tests "kr.kro.airbob.config.WebMvcConfigBenchmarkProtectionTest"
```

Expected: compilation fails because `CouponBenchmarkController` does not exist, or the filter mapping assertion fails.

- [ ] **Step 5: Implement the benchmark controller**

Create this controller:

```java
package kr.kro.airbob.domain.coupon.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.coupon.service.CouponLockIssueService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Profile("coupon-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
@RequestMapping("/api/v2/coupons")
public class CouponBenchmarkController {

	private final CouponLockIssueService lockIssueService;
	private final BenchmarkAccessGuard accessGuard;

	@PostMapping("/{couponId}/issue")
	public ResponseEntity<ApiResponse<Void>> issueCouponWithLock(
		@PathVariable Long couponId,
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken
	) {
		accessGuard.verify(benchmarkToken);
		Long memberId = UserContext.get().id();
		lockIssueService.issue(couponId, memberId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
	}
}
```

- [ ] **Step 6: Register only the v2 coupon path with the session filter**

Change the registration to:

```java
bean.addUrlPatterns(
	"/api/v1/*",
	"/api/v2/members/*",
	"/api/v2/admin/*",
	"/api/v2/coupons/*"
);
```

Do not add `/api/v2/*` and do not add the coupon route to `AdminAuthInterceptor`.

- [ ] **Step 7: Re-run the focused tests and verify GREEN**

Run the command from Step 4.

Expected: all controller gating, session, token, delegation, and filter mapping tests pass.

- [ ] **Step 8: Commit the protected benchmark API**

```bash
git add src/main/java/kr/kro/airbob/domain/coupon/api/CouponBenchmarkController.java \
  src/main/java/kr/kro/airbob/config/WebMvcConfig.java \
  src/test/java/kr/kro/airbob/domain/coupon/api/CouponBenchmarkControllerTest.java \
  src/test/java/kr/kro/airbob/domain/coupon/api/CouponBenchmarkAccessTest.java \
  src/test/java/kr/kro/airbob/config/WebMvcConfigBenchmarkProtectionTest.java
git diff --cached --check
git commit -m "feat: Redisson 쿠폰 벤치마크 API 추가"
```

---

### Task 4: Route k6 variants to the new APIs

**Files:**

- Modify: `load-test/k6/test/coupon-benchmark-fixture-test.js`
- Modify: `load-test/k6/lib/coupon-benchmark-fixture.js`
- Modify: `load-test/k6/coupon-issuance-comparison.js`

**Interfaces:**

- Produces: `buildCouponIssueTarget(variant, couponId, benchmarkToken)`
- Produces: `{ path: string, metricName: string, headers: object }`
- Preserves: `VARIANT=lua|lock`, metric tags, outcome counters, fixture schema, and summary artifact fields.

- [ ] **Step 1: Add failing pure-helper checks**

Import `buildCouponIssueTarget` and construct these values before the existing `check` call:

```javascript
const luaTarget = buildCouponIssueTarget('lua', 1);
const lockTarget = buildCouponIssueTarget('lock', 1, ' secret-token ');
```

Add these checks:

```javascript
'lua uses the production v1 endpoint': () => (
  luaTarget.path === '/api/v1/coupons/1/issue'
  && luaTarget.metricName === 'POST /api/v1/coupons/{couponId}/issue'
  && Object.keys(luaTarget.headers).length === 0
),
'lock uses the benchmark v2 endpoint and trimmed token': () => (
  lockTarget.path === '/api/v2/coupons/1/issue'
  && lockTarget.metricName === 'POST /api/v2/coupons/{couponId}/issue'
  && lockTarget.headers['X-Benchmark-Token'] === 'secret-token'
),
'lock rejects a missing benchmark token': () => rejects(() => (
  buildCouponIssueTarget('lock', 1)
)),
'lock rejects a blank benchmark token': () => rejects(() => (
  buildCouponIssueTarget('lock', 1, ' ')
)),
'lua does not require a benchmark token': () => (
  buildCouponIssueTarget('lua', 1).path === '/api/v1/coupons/1/issue'
),
```

- [ ] **Step 2: Run the helper test and verify RED**

```bash
k6 run --quiet load-test/k6/test/coupon-benchmark-fixture-test.js
```

Expected: module import fails because `buildCouponIssueTarget` has not been exported.

- [ ] **Step 3: Implement the target builder**

Add this function to `coupon-benchmark-fixture.js`:

```javascript
export function buildCouponIssueTarget(variant, couponId, benchmarkToken) {
  const parsedVariant = parseVariant(variant);
  const parsedCouponId = parsePositiveInteger(couponId, 'COUPON_ID');

  if (parsedVariant === 'lua') {
    return {
      path: `/api/v1/coupons/${parsedCouponId}/issue`,
      metricName: 'POST /api/v1/coupons/{couponId}/issue',
      headers: {},
    };
  }

  const token = parseRequiredText(benchmarkToken, 'BENCHMARK_READ_MODEL_TOKEN');
  return {
    path: `/api/v2/coupons/${parsedCouponId}/issue`,
    metricName: 'POST /api/v2/coupons/{couponId}/issue',
    headers: { 'X-Benchmark-Token': token },
  };
}
```

- [ ] **Step 4: Re-run the helper test and verify GREEN**

Run the command from Step 2.

Expected: every k6 `check` passes.

- [ ] **Step 5: Wire the helper into the k6 entry point**

Add `buildCouponIssueTarget` to the fixture-helper import. After parsing `VARIANT` and `COUPON_ID`, construct:

```javascript
const ISSUE_TARGET = buildCouponIssueTarget(
  VARIANT,
  COUPON_ID,
  __ENV.BENCHMARK_READ_MODEL_TOKEN,
);
```

Replace the request construction with:

```javascript
const response = http.post(
  `${BASE_URL}${ISSUE_TARGET.path}`,
  null,
  {
    cookies: { SESSION_ID: sessionId },
    headers: ISSUE_TARGET.headers,
    timeout: REQUEST_TIMEOUT,
    tags: {
      ...metricTags,
      name: ISSUE_TARGET.metricName,
    },
  },
);
```

Do not add the token to metric tags, stdout, or the JSON artifact.

- [ ] **Step 6: Validate both init paths without sending traffic**

```bash
env BASE_URL=http://localhost:8080 \
  SESSION_FIXTURE="$PWD/load-test/fixtures/coupon-sessions.example.json" \
  VARIANT=lua COUPON_ID=1 COUPON_STOCK=1 APP_VERSION=test \
  APP_INSTANCE_COUNT=1 ROUND=1 RUN_ORDER=1 RUN_LABEL=inspect-lua \
  RATE=1 DURATION=1s \
  k6 inspect --include-system-env-vars load-test/k6/coupon-issuance-comparison.js

env BASE_URL=http://localhost:8080 \
  SESSION_FIXTURE="$PWD/load-test/fixtures/coupon-sessions.example.json" \
  VARIANT=lock BENCHMARK_READ_MODEL_TOKEN=test-token \
  COUPON_ID=1 COUPON_STOCK=1 APP_VERSION=test APP_INSTANCE_COUNT=1 \
  ROUND=1 RUN_ORDER=1 RUN_LABEL=inspect-lock RATE=1 DURATION=1s \
  k6 inspect --include-system-env-vars load-test/k6/coupon-issuance-comparison.js
```

Expected: both commands exit `0`; `k6 inspect` does not make HTTP requests.

- [ ] **Step 7: Commit the k6 endpoint mapping**

```bash
git add load-test/k6/lib/coupon-benchmark-fixture.js \
  load-test/k6/test/coupon-benchmark-fixture-test.js \
  load-test/k6/coupon-issuance-comparison.js
git diff --cached --check
git commit -m "test: 쿠폰 운영과 벤치마크 부하 경로 분리"
```

---

### Task 5: Update the coupon benchmark runbook and verify the feature

**Files:**

- Modify: `load-test/README.md`
- Verify: all Java and k6 files changed by Tasks 1-4.

**Interfaces:**

- Documents: local and AWS activation of `coupon-benchmark`.
- Documents: `BENCHMARK_READ_MODEL_TOKEN` is required only by the lock k6 variant.

- [ ] **Step 1: Rewrite the runbook endpoint contract**

Replace the opening endpoint list with:

```markdown
- `VARIANT=lua`: `POST /api/v1/coupons/{couponId}/issue` — 운영 Lua 경로
- `VARIANT=lock`: `POST /api/v2/coupons/{couponId}/issue` — Redisson 벤치마크 경로
```

Add this activation section before fixture preparation:

````markdown
## 애플리케이션 실행 조건

Lua 운영 경로는 일반 운영 프로필에서 그대로 사용한다. Redisson 비교 경로까지 같은 인스턴스에서 측정하려면 `coupon-benchmark` 프로필과 서버의 `BENCHMARK_READ_MODEL_TOKEN`을 함께 설정한다.

```bash
read -rsp 'Benchmark API token: ' BENCHMARK_READ_MODEL_TOKEN
export BENCHMARK_READ_MODEL_TOKEN
export SPRING_PROFILES_ACTIVE=dev,coupon-benchmark
./gradlew bootRun
```

AWS에서도 lock variant를 호출할 인스턴스에 `coupon-benchmark`와 같은 토큰을 설정해야 한다. 일반 사용자 트래픽과 분리된 벤치마크 인스턴스에서만 이 프로필을 활성화한다.
````

Before the k6 examples, add:

```bash
read -rsp 'Benchmark API token: ' BENCHMARK_READ_MODEL_TOKEN
export BENCHMARK_READ_MODEL_TOKEN
```

Add this environment-variable row:

```markdown
| `BENCHMARK_READ_MODEL_TOKEN` | lock v2 요청의 `X-Benchmark-Token`; lua에서는 사용하지 않음 | lock에서 필수 |
```

Remove every live instruction containing `/issue/lock` or `/issue/lua`. Keep the separate-coupon, Lua-only prepare, alternating-order, metrics, and consistency instructions.

Add this metric interpretation note beside the Micrometer metric list:

```markdown
API 경로 변경으로 Spring HTTP 메트릭의 `uri` 태그는 기존 suffix 경로와 이어지지 않는다. 전후 비교는 `coupon.issue.duration`, `coupon.lock.*`, `coupon.lua.duration`, `coupon.database.issue.duration`과 k6 결과 JSON을 기준으로 한다.
```

- [ ] **Step 2: Run focused Java tests**

```bash
./gradlew test \
  --tests "kr.kro.airbob.common.benchmark.*" \
  --tests "kr.kro.airbob.config.WebMvcConfigBenchmarkProtectionTest" \
  --tests "kr.kro.airbob.domain.coupon.api.*" \
  --tests "kr.kro.airbob.domain.coupon.service.CouponBenchmarkComponentProfileTest" \
  --tests "kr.kro.airbob.domain.coupon.service.CouponLockIssueServiceTest" \
  --tests "kr.kro.airbob.domain.coupon.service.CouponLockManagerTest" \
  --tests "kr.kro.airbob.domain.coupon.service.CouponLuaIssueServiceTest" \
  --tests "kr.kro.airbob.domain.coupon.service.CouponIssueTransactionServiceTest"
```

Expected: all selected tests pass.

- [ ] **Step 3: Run all k6 helper tests**

```bash
for test_file in load-test/k6/test/*.js; do
  k6 run --quiet "$test_file" || exit 1
done
```

Expected: all helper test scripts exit `0` and all checks pass.

- [ ] **Step 4: Compile and run the full Java suite**

```bash
./gradlew compileJava
./gradlew test
```

Expected: both commands exit `0`. If an external container dependency prevents the full suite, report the exact failing class separately from the focused green result.

- [ ] **Step 5: Audit the live contract and final diff**

```bash
if rg -n "/issue/(lock|lua)" src/main load-test; then
  exit 1
fi
test_matches="$(rg -l "/issue/(lock|lua)" src/test || true)"
test_match_count="$(rg -n "/issue/(lock|lua)" src/test | wc -l | tr -d ' ')"
test "$test_matches" = "src/test/java/kr/kro/airbob/domain/coupon/api/CouponControllerTest.java"
test "$test_match_count" -eq 2
git diff --check
git status --short
git diff --stat
```

Expected: `/issue/(lock|lua)` has zero matches in `src/main` and `load-test`. Under `src/test`, only `src/test/java/kr/kro/airbob/domain/coupon/api/CouponControllerTest.java` matches and it has exactly two matches (the negative `404` assertions). The whitespace check exits `0`, and the diff contains only the approved feature plus pre-existing worktree edits.

- [ ] **Step 6: Commit the runbook**

```bash
git add load-test/README.md
git diff --cached --check
git commit -m "docs: 쿠폰 벤치마크 실행 방법 갱신"
```
