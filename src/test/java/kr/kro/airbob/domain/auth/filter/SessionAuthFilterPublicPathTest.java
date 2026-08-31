package kr.kro.airbob.domain.auth.filter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

@DisplayName("세션 인증 필터 공개 경로 테스트")
class SessionAuthFilterPublicPathTest {

	@Test
	@DisplayName("예약 쓰기 API는 익명 요청을 거절한다")
	void anonymousReservationWritesRequireAuthentication() throws Exception {
		for (RequestTarget target : new RequestTarget[] {
			new RequestTarget("POST", "/api/v1/reservation-quotes"),
			new RequestTarget("POST", "/api/v1/reservations"),
			new RequestTarget("DELETE", "/api/v1/reservations/reservation-uid/hold"),
			new RequestTarget("POST", "/api/v1/reservations/reservation-uid/payment-attempts")
		}) {
			SessionAuthFilter filter = createFilter();
			MockHttpServletRequest request = new MockHttpServletRequest(target.method(), target.path());
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			filter.doFilter(request, response, chain);

			assertThat(chain.getRequest()).as(target.path()).isNull();
			assertThat(response.getStatus()).as(target.path()).isEqualTo(401);
		}
	}

	@Test
	@DisplayName("숙소 예약 가능 정보 GET은 익명 요청을 필터 체인에 전달한다")
	void anonymousAccommodationAvailabilityGetPassesFilterChain() throws Exception {
		SessionAuthFilter filter = createFilter();
		MockHttpServletRequest request = new MockHttpServletRequest(
			"GET",
			"/api/v1/accommodations/42/availability"
		);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest()).isSameAs(request);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("v2 리뷰 요약 GET은 익명 요청을 필터 체인에 전달한다")
	void anonymousV2ReviewSummaryGetPassesFilterChain() throws Exception {
		SessionAuthFilter filter = createFilter();
		MockHttpServletRequest request = new MockHttpServletRequest(
			"GET",
			"/api/v2/accommodations/42/reviews/summary"
		);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest()).isSameAs(request);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("숙소 상세 before 벤치마크 GET은 익명 요청도 토큰 검증 단계로 전달한다")
	void anonymousAccommodationDetailBenchmarkGetPassesFilterChain() throws Exception {
		SessionAuthFilter filter = createFilter();
		MockHttpServletRequest request = new MockHttpServletRequest(
			"GET",
			"/api/v2/accommodations/42"
		);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest()).isSameAs(request);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("read-model 런타임 검증 POST는 세션 없이 벤치마크 토큰 검증 단계로 전달한다")
	void anonymousReadModelRuntimeAssertionPostPassesFilterChain() throws Exception {
		SessionAuthFilter filter = createFilter();
		MockHttpServletRequest request = new MockHttpServletRequest(
			"POST",
			"/api/v2/benchmark/read-model/runtime-assertion"
		);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest()).isSameAs(request);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("삭제된 CPU burn 경로는 더 이상 익명 공개 경로가 아니다")
	void staleCpuBurnPathRequiresAuthentication() throws Exception {
		SessionAuthFilter filter = createFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test/cpu-burn");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest()).isNull();
		assertThat(response.getStatus()).isEqualTo(401);
	}

	@Test
	@DisplayName("정방향 세션만 남고 회원별 활성 키가 없으면 인증을 거부한다")
	@SuppressWarnings("unchecked")
	void sessionWithoutActiveMemberKeyIsRejected() throws Exception {
		RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
		ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get("SESSION:valid-session")).thenReturn(10L);
		SessionAuthFilter filter = new SessionAuthFilter(redisTemplate, new ObjectMapper());
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
		request.setCookies(new Cookie("SESSION_ID", "valid-session"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest()).isNull();
		assertThat(response.getStatus()).isEqualTo(401);
		verify(redisTemplate).hasKey("MEMBER_SESSION_ACTIVE:10");
	}

	@Test
	@DisplayName("유효한 세션은 존재 여부 조회 없이 단일 GET으로 인증한다")
	@SuppressWarnings("unchecked")
	void validSessionIsAuthenticatedWithSingleGet() throws Exception {
		RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
		ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get("SESSION:valid-session")).thenReturn(10L);
		when(redisTemplate.hasKey("MEMBER_SESSION_ACTIVE:10")).thenReturn(true);
		SessionAuthFilter filter = new SessionAuthFilter(redisTemplate, new ObjectMapper());
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
		request.setCookies(new Cookie("SESSION_ID", "valid-session"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest()).isSameAs(request);
		assertThat(response.getStatus()).isEqualTo(200);
		verify(valueOperations).get("SESSION:valid-session");
		verify(redisTemplate, never()).hasKey("SESSION:valid-session");
	}

	@SuppressWarnings("unchecked")
	private SessionAuthFilter createFilter() {
		RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
		return new SessionAuthFilter(redisTemplate, new ObjectMapper());
	}

	private record RequestTarget(String method, String path) {}
}
