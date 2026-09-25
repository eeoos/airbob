package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CacheBenchmarkIsolationFilterTest {

	private static final String TOKEN = "cache-benchmark-secret";
	private final CacheBenchmarkIsolationFilter filter =
		new CacheBenchmarkIsolationFilter(new BenchmarkAccessGuard(TOKEN));

	@Test
	void profileOffDoesNotInstallIsolationOrTokenGuard() {
		new ApplicationContextRunner()
			.withUserConfiguration(CacheBenchmarkIsolationFilter.class, BenchmarkAccessGuard.class)
			.run(context -> {
				assertThat(context).doesNotHaveBean(CacheBenchmarkIsolationFilter.class);
				assertThat(context).doesNotHaveBean(BenchmarkAccessGuard.class);
			});
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/v1/accommodations/42", "/api/v2/accommodations/42",
		"/api/v2/accommodations/42/review-summary-before"})
	void bothDetailReadsRequireTheSameValidToken(String path) throws Exception {
		assertResult("GET", path, TOKEN, true, 200);
		assertResult("GET", path, null, false, 403);
		assertResult("GET", path, "wrong-token", false, 403);
		assertResult("GET", path, " ", false, 403);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
		"/actuator/prometheus"
	})
	void operationalReadsNeedNoToken(String path) throws Exception {
		assertResult("GET", path, null, true, 200);
		assertResult("POST", path, TOKEN, false, 404);
	}

	@ParameterizedTest
	@MethodSource("blockedRequests")
	void noOtherRouteOrMethodCanReachBusinessCode(String method, String path) throws Exception {
		assertResult(method, path, TOKEN, false, 404);
	}

	private void assertResult(
		String method, String path, String token, boolean expectedChain, int expectedStatus
	) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		if (token != null) {
			request.addHeader(BenchmarkAccessGuard.HEADER_NAME, token);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean chainInvoked = new AtomicBoolean();
		filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainInvoked.set(true));
		assertThat(chainInvoked.get()).isEqualTo(expectedChain);
		assertThat(response.getStatus()).isEqualTo(expectedStatus);
	}

	private static Stream<Arguments> blockedRequests() {
		return Stream.of(
			Arguments.of("POST", "/api/v1/accommodations"),
			Arguments.of("PATCH", "/api/v1/accommodations/42"),
			Arguments.of("DELETE", "/api/v1/accommodations/42"),
			Arguments.of("POST", "/api/v2/accommodations/42"),
			Arguments.of("PUT", "/api/v2/accommodations/42"),
			Arguments.of("POST", "/api/v1/auth/login"),
			Arguments.of("POST", "/api/v1/members"),
			Arguments.of("POST", "/api/v1/reservation-quotes"),
			Arguments.of("POST", "/api/v1/reservations"),
			Arguments.of("POST", "/api/v1/payments/confirm"),
			Arguments.of("GET", "/api/v1/accommodations/42/availability"),
			Arguments.of("GET", "/api/v1/accommodations/42/reviews/summary"),
			Arguments.of("GET", "/api/v1/search/accommodations"),
			Arguments.of("GET", "/api/v1/accommodations/not-a-number"),
			Arguments.of("GET", "/api/v1/accommodations/42/"),
			Arguments.of("GET", "/actuator/env"),
			Arguments.of("GET", "/actuator/health/redis")
		);
	}
}
