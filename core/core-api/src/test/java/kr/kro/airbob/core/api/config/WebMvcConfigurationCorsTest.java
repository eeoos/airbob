package kr.kro.airbob.core.api.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import kr.kro.airbob.core.api.security.AdminAuthInterceptor;
import kr.kro.airbob.core.api.security.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.core.api.security.SessionAuthFilter;
import kr.kro.airbob.core.support.cursor.resolver.CursorParamArgumentResolver;
import kr.kro.airbob.core.support.monitoring.QueryCountInterceptor;

@DisplayName("일반 API CORS 설정 테스트")
class WebMvcConfigurationCorsTest {

	private static final String ALLOWED_ORIGIN = "http://localhost:3000";

	private final CorsFilter corsFilter = createWebMvcConfig().corsFilter().getFilter();

	@Test
	@DisplayName("기존 API 경로의 허용 origin 정책은 유지한다")
	void otherRoutesRetainExistingCorsPolicy() throws ServletException, IOException {
		MockHttpServletRequest request = request("GET", "/api/v1/accommodations");
		request.addHeader(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		corsFilter.doFilter(request, response, chain);

		then(chain).should().doFilter(request, response);
		assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
		assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
	}

	private WebMvcConfiguration createWebMvcConfig() {
		return new WebMvcConfiguration(
			mock(CurrentMemberIdArgumentResolver.class),
			List.of(mock(CursorParamArgumentResolver.class)),
			mock(SessionAuthFilter.class),
			mock(AdminAuthInterceptor.class),
			mock(QueryCountInterceptor.class));
	}

	private MockHttpServletRequest request(String method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setRequestURI(path);
		return request;
	}
}
