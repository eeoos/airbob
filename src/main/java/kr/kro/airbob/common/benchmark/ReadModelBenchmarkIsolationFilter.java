package kr.kro.airbob.common.benchmark;

import java.io.IOException;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.kro.airbob.common.exception.BaseException;

@Component
@Profile("read-model-benchmark")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ReadModelBenchmarkIsolationFilter extends OncePerRequestFilter {

	private static final String HEALTH_PATH = "/actuator/health";
	private static final String PROMETHEUS_PATH = "/actuator/prometheus";
	private static final String LOGIN_PATH = "/api/v1/auth/login";
	private static final String RUNTIME_ASSERTION_PATH =
		"/api/v2/benchmark/read-model/runtime-assertion";
	private static final Pattern TARGET_PATH = Pattern.compile(
		"^/api/v[12]/(?:accommodations/\\d+/reviews/summary|members/wishlists|admin/stats/revenue)$"
	);

	private final BenchmarkAccessGuard accessGuard;

	public ReadModelBenchmarkIsolationFilter(BenchmarkAccessGuard accessGuard) {
		this.accessGuard = accessGuard;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String method = request.getMethod();
		String path = request.getRequestURI();

		if (isOperationalRequest(method, path)) {
			filterChain.doFilter(request, response);
			return;
		}

		if ("GET".equals(method) && TARGET_PATH.matcher(path).matches()) {
			if (hasValidBenchmarkToken(request)) {
				filterChain.doFilter(request, response);
			} else {
				response.sendError(HttpServletResponse.SC_FORBIDDEN);
			}
			return;
		}

		response.sendError(HttpServletResponse.SC_NOT_FOUND);
	}

	private boolean isOperationalRequest(String method, String path) {
		return ("GET".equals(method) && (HEALTH_PATH.equals(path) || PROMETHEUS_PATH.equals(path)))
			|| ("POST".equals(method) && (LOGIN_PATH.equals(path) || RUNTIME_ASSERTION_PATH.equals(path)));
	}

	private boolean hasValidBenchmarkToken(HttpServletRequest request) {
		try {
			accessGuard.verify(request.getHeader(BenchmarkAccessGuard.HEADER_NAME));
			return true;
		} catch (BaseException exception) {
			return false;
		}
	}
}
