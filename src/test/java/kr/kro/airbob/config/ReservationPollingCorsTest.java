package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import kr.kro.airbob.common.monitoring.QueryCountInterceptor;
import kr.kro.airbob.cursor.resolver.CursorParamArgumentResolver;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.interceptor.AdminAuthInterceptor;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;

@DisplayName("결제 상태 조회 Location CORS 계약 테스트")
class ReservationPollingCorsTest {

	@Test
	@DisplayName("허용된 브라우저 origin은 비동기 취소 응답의 Location 헤더를 읽을 수 있다")
	void exposesPollingLocationToAllowedOrigin() throws Exception {
		CorsFilter corsFilter = new WebMvcConfig(
			mock(CurrentMemberIdArgumentResolver.class),
			List.of(mock(CursorParamArgumentResolver.class)),
			mock(SessionAuthFilter.class),
			mock(AdminAuthInterceptor.class),
			mock(QueryCountInterceptor.class)
		).corsFilter().getFilter();
		MockHttpServletRequest request = new MockHttpServletRequest(
			"POST", "/api/v1/reservations/reservation-uid");
		request.setRequestURI("/api/v1/reservations/reservation-uid");
		request.addHeader(HttpHeaders.ORIGIN, "http://localhost:3000");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = mock(MockFilterChain.class);

		corsFilter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
			.isEqualTo(HttpHeaders.LOCATION);
	}
}
