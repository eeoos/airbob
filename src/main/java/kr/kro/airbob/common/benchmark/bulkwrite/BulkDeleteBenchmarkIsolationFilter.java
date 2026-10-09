package kr.kro.airbob.common.benchmark.bulkwrite;

import java.io.IOException;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Session, active-admin and benchmark-token checks still apply to the allowed benchmark APIs. */
@Component
@Profile("bulk-delete-benchmark")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BulkDeleteBenchmarkIsolationFilter extends OncePerRequestFilter {

	private final BulkDeleteBenchmarkRuntimeIdentity identity;

	public BulkDeleteBenchmarkIsolationFilter(BulkDeleteBenchmarkRuntimeIdentity identity) {
		this.identity = identity;
	}

	private static final Set<String> READS = Set.of(
		"/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness", "/actuator/prometheus",
		"/api/v2/admin/benchmarks/bulk-write/runtime");
	private static final Set<String> WRITES = Set.of(
		"/api/v1/auth/login", "/api/v1/auth/logout",
		"/api/v2/admin/benchmarks/bulk-write/wishlist-delete",
		"/api/v2/admin/benchmarks/bulk-write/accommodation-amenity-delete");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		boolean allowed = switch (request.getMethod()) {
			case "GET" -> READS.contains(request.getRequestURI());
			case "POST" -> WRITES.contains(request.getRequestURI());
			default -> false;
		};
		if (allowed && request.getMethod().equals("POST")
			&& request.getRequestURI().startsWith("/api/v2/admin/benchmarks/bulk-write/")
			&& !identity.value().equals(request.getHeader(BulkDeleteBenchmarkRuntimeIdentity.HEADER_NAME))) {
			response.setStatus(HttpServletResponse.SC_CONFLICT);
			return;
		}
		if (allowed) {
			chain.doFilter(request, response);
		} else {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		}
	}
}
