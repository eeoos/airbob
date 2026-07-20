package kr.kro.airbob.domain.auth.filter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

@DisplayName("세션 인증 필터 공개 경로 테스트")
class SessionAuthFilterPublicPathTest {

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

	@SuppressWarnings("unchecked")
	private SessionAuthFilter createFilter() {
		RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
		return new SessionAuthFilter(redisTemplate, new ObjectMapper());
	}
}
