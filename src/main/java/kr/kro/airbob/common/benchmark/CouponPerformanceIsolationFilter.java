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

/** Coupon experiments keep the normal session/admin/token checks on their allowed routes. */
@Component
@Profile("coupon-performance")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CouponPerformanceIsolationFilter extends OncePerRequestFilter {

	private static final String FIXTURES = "/api/v1/admin/coupons/benchmark/fixtures";
	private static final Pattern ISSUE = Pattern.compile("/api/v[12]/coupons/[1-9][0-9]*/issue");
	private static final Pattern PREPARE = Pattern.compile("/api/v1/admin/coupons/[1-9][0-9]*/stock/prepare");
	private static final Pattern FIXTURE = Pattern.compile(FIXTURES + "/[1-9][0-9]*");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String path = request.getRequestURI();
		String method = request.getMethod();
		boolean allowed = switch (method) {
			case "GET" -> path.equals("/actuator/health") || path.equals("/actuator/health/readiness")
				|| path.equals("/actuator/health/liveness")
				|| path.equals("/actuator/prometheus") || FIXTURE.matcher(path).matches();
			case "POST" -> path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/logout")
				|| path.equals(FIXTURES) || ISSUE.matcher(path).matches() || PREPARE.matcher(path).matches();
			case "DELETE" -> FIXTURE.matcher(path).matches();
			default -> false;
		};
		if (allowed) {
			chain.doFilter(request, response);
		} else {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		}
	}
}
