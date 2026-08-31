package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ReadModelBenchmarkIsolationFilterTest {

	private static final String TOKEN = "read-model-secret";
	private final ReadModelBenchmarkIsolationFilter filter =
		new ReadModelBenchmarkIsolationFilter(new BenchmarkAccessGuard(TOKEN));

	@Test
	@DisplayName("필터는 read-model-benchmark 프로필에서만 활성화된다")
	void filterIsScopedToTheReadModelBenchmarkProfile() {
		Profile profile = ReadModelBenchmarkIsolationFilter.class.getAnnotation(Profile.class);

		assertThat(profile).isNotNull();
		assertThat(profile.value()).containsExactly("read-model-benchmark");
	}

	@ParameterizedTest
	@MethodSource("operationalRequests")
	@DisplayName("헬스·메트릭 조회, 로그인, 런타임 검증 요청은 후속 처리로 전달한다")
	void operationalRequestsPass(String method, String path) throws Exception {
		FilterResult result = invoke(method, path, TOKEN);

		assertThat(result.chainInvoked()).isTrue();
		assertThat(result.response().getStatus()).isEqualTo(200);
	}

	@ParameterizedTest
	@MethodSource("targetPaths")
	@DisplayName("유효한 토큰이 있는 v1/v2 읽기 모델 대상 GET만 전달한다")
	void targetReadsWithAValidTokenPass(String path) throws Exception {
		FilterResult result = invoke("GET", path, TOKEN);

		assertThat(result.chainInvoked()).isTrue();
		assertThat(result.response().getStatus()).isEqualTo(200);
	}

	@ParameterizedTest
	@MethodSource("targetPaths")
	@DisplayName("공개 v1 경로를 포함한 읽기 모델 대상도 토큰이 없거나 다르면 거부한다")
	void targetReadsWithoutAValidTokenAreRejected(String path) throws Exception {
		FilterResult missing = invoke("GET", path, null);
		FilterResult wrong = invoke("GET", path, "wrong-token");

		assertThat(missing.chainInvoked()).isFalse();
		assertThat(missing.response().getStatus()).isEqualTo(403);
		assertThat(wrong.chainInvoked()).isFalse();
		assertThat(wrong.response().getStatus()).isEqualTo(403);
	}

	@ParameterizedTest
	@MethodSource("blockedRequests")
	@DisplayName("회원가입과 기타 API 및 허용 경로의 다른 메서드는 컨트롤러 전에 차단한다")
	void allOtherRoutesAndMethodsAreBlocked(String method, String path) throws Exception {
		FilterResult result = invoke(method, path, TOKEN);

		assertThat(result.chainInvoked()).isFalse();
		assertThat(result.response().getStatus()).isEqualTo(404);
	}

	private FilterResult invoke(String method, String path, String token) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		if (token != null) {
			request.addHeader(BenchmarkAccessGuard.HEADER_NAME, token);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean chainInvoked = new AtomicBoolean();

		filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainInvoked.set(true));

		return new FilterResult(chainInvoked.get(), response);
	}

	private static Stream<Arguments> operationalRequests() {
		return Stream.of(
			Arguments.of("GET", "/actuator/health"),
			Arguments.of("GET", "/actuator/prometheus"),
			Arguments.of("POST", "/api/v1/auth/login"),
			Arguments.of("POST", "/api/v2/benchmark/read-model/runtime-assertion")
		);
	}

	private static Stream<String> targetPaths() {
		return Stream.of(
			"/api/v1/accommodations/42/reviews/summary",
			"/api/v2/accommodations/42/reviews/summary",
			"/api/v1/members/wishlists",
			"/api/v2/members/wishlists",
			"/api/v1/admin/stats/revenue",
			"/api/v2/admin/stats/revenue"
		);
	}

	private static Stream<Arguments> blockedRequests() {
		return Stream.of(
			Arguments.of("POST", "/api/v1/members"),
			Arguments.of("POST", "/api/v1/members/wishlists"),
			Arguments.of("POST", "/api/v1/admin/stats/revenue/recompute"),
			Arguments.of("GET", "/api/v1/accommodations/42"),
			Arguments.of("GET", "/actuator/info"),
			Arguments.of("POST", "/actuator/health"),
			Arguments.of("POST", "/actuator/prometheus"),
			Arguments.of("POST", "/api/v1/accommodations/42/reviews/summary")
		);
	}

	private record FilterResult(boolean chainInvoked, MockHttpServletResponse response) {
	}
}
