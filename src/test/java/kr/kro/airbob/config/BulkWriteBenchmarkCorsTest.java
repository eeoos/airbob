package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import kr.kro.airbob.common.monitoring.QueryCountInterceptor;
import kr.kro.airbob.cursor.resolver.CursorParamArgumentResolver;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.interceptor.AdminAuthInterceptor;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;

@DisplayName("대량 쓰기 벤치마크 cross-origin 차단 테스트")
class BulkWriteBenchmarkCorsTest {

	private static final String ALLOWED_ORIGIN = "http://localhost:3000";
	private static final String BENCHMARK_PATH =
		"/api/v2/admin/benchmarks/bulk-write/wishlist-delete";

	private final CorsFilter corsFilter = createWebMvcConfig().corsFilter().getFilter();

	@Test
	@DisplayName("허용 origin의 benchmark preflight 요청도 거부한다")
	void benchmarkPreflightIsDenied() throws ServletException, IOException {
		MockHttpServletRequest request = request("OPTIONS", BENCHMARK_PATH);
		request.addHeader(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
		request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		corsFilter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
		verifyNoInteractions(chain);
	}

	@Test
	@DisplayName("credential을 포함한 benchmark cross-origin 요청을 거부한다")
	void credentialedBenchmarkCorsRequestIsDenied() throws ServletException, IOException {
		MockHttpServletRequest request = request("POST", BENCHMARK_PATH);
		request.addHeader(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
		request.addHeader(HttpHeaders.COOKIE, "SESSION=sentinel-session");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		corsFilter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
		verifyNoInteractions(chain);
	}

	@Test
	@DisplayName("Origin이 없는 직접 benchmark 요청은 CORS 차단 없이 통과시킨다")
	void directBenchmarkRequestPassesCorsFilter() throws ServletException, IOException {
		MockHttpServletRequest request = request("POST", BENCHMARK_PATH);
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		corsFilter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
	}

	@Test
	@DisplayName("기존 API 경로의 허용 origin 정책은 유지한다")
	void otherRoutesRetainExistingCorsPolicy() throws ServletException, IOException {
		MockHttpServletRequest request = request("GET", "/api/v1/accommodations");
		request.addHeader(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		corsFilter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
		assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
	}

	private WebMvcConfig createWebMvcConfig() {
		return new WebMvcConfig(
			mock(CurrentMemberIdArgumentResolver.class),
			mock(CursorParamArgumentResolver.class),
			mock(SessionAuthFilter.class),
			mock(AdminAuthInterceptor.class),
			mock(QueryCountInterceptor.class)
		);
	}

	private MockHttpServletRequest request(String method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setRequestURI(path);
		return request;
	}
}
