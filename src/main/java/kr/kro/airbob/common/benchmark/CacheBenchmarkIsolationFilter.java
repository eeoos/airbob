package kr.kro.airbob.common.benchmark;

import java.io.IOException;
import java.util.Set;
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

/**
 * Isolates the anonymous before/after detail experiment from every business write route.
 */
@Component
@Profile("cache-benchmark")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CacheBenchmarkIsolationFilter extends OncePerRequestFilter {

	private static final Set<String> OPERATIONAL_PATHS = Set.of(
		"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
		"/actuator/prometheus"
	);
	private static final Pattern DETAIL_PATH = Pattern.compile("^/api/v[12]/accommodations/\\d+$");

	private final BenchmarkAccessGuard accessGuard;

	public CacheBenchmarkIsolationFilter(BenchmarkAccessGuard accessGuard) {
		this.accessGuard = accessGuard;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String path = request.getRequestURI();
		if (!"GET".equals(request.getMethod())) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		if (OPERATIONAL_PATHS.contains(path)) {
			filterChain.doFilter(request, response);
			return;
		}
		if (!DETAIL_PATH.matcher(path).matches() && !CacheBenchmarkRuntimeController.PATH.equals(path)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		try {
			accessGuard.verify(request.getHeader(BenchmarkAccessGuard.HEADER_NAME));
		} catch (BaseException exception) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}
		filterChain.doFilter(request, response);
	}
}
