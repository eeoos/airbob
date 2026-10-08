package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

class CouponPerformanceIsolationFilterTest {

	@ParameterizedTest
	@CsvSource({"GET,/actuator/health", "GET,/actuator/health/readiness", "GET,/actuator/prometheus",
		"POST,/api/v1/auth/login", "POST,/api/v1/auth/logout", "POST,/api/v1/coupons/1/issue",
		"POST,/api/v2/coupons/1/issue", "POST,/api/v1/admin/coupons/benchmark/fixtures",
		"GET,/api/v1/admin/coupons/benchmark/fixtures/1", "DELETE,/api/v1/admin/coupons/benchmark/fixtures/1",
		"POST,/api/v1/admin/coupons/1/stock/prepare"})
	void leavesCouponTrafficForTheExistingSessionAdminAndTokenGuards(String method, String path) throws Exception {
		var request = new MockHttpServletRequest(method, path);
		var response = new MockHttpServletResponse();
		var chain = mock(FilterChain.class);
		new CouponPerformanceIsolationFilter().doFilter(request, response, chain);
		verify(chain).doFilter(request, response);
	}

	@ParameterizedTest
	@CsvSource({"POST,/api/v1/reservations/quotes", "POST,/api/v1/reservations/checkout",
		"POST,/api/v1/payments/confirm", "GET,/api/v1/accommodations/1/availability", "POST,/api/v1/members",
		"DELETE,/api/v1/admin/coupons/1", "PUT,/api/v1/coupons/1/issue", "GET,/actuator/env"})
	void rejectsUnrelatedRoutesWhileReservationInventoryIsDisabled(String method, String path) throws Exception {
		var response = new MockHttpServletResponse();
		var chain = mock(FilterChain.class);
		new CouponPerformanceIsolationFilter().doFilter(new MockHttpServletRequest(method, path), response, chain);
		assertThat(response.getStatus()).isEqualTo(404);
		verifyNoInteractions(chain);
	}
}
