package kr.kro.airbob.domain.coupon.api;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;
import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.coupon.common.CouponIssueStrategy;
import kr.kro.airbob.domain.coupon.exception.CouponIssueContendedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotIssuableException;
import kr.kro.airbob.domain.coupon.service.CouponDbIssueService;
import kr.kro.airbob.domain.coupon.service.CouponSkipLockedIssueService;

@ExtendWith(MockitoExtension.class)
@DisplayName("쿠폰 벤치마크 API 접근 제어와 고정 전략 테스트")
class CouponBenchmarkAccessTest {

	@Mock private CouponDbIssueService dbIssueService;
	@Mock private CouponSkipLockedIssueService skip;
	@Mock private RedisTemplate<String, Object> redisTemplate;
	@Mock private ValueOperations<String, Object> valueOperations;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		var controller = new CouponBenchmarkController(
			dbIssueService, new BenchmarkAccessGuard("secret-token"), skip);
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.addFilters(new SessionAuthFilter(redisTemplate, new ObjectMapper()))
			.build();
	}

	@ParameterizedTest @EnumSource(CouponIssueStrategy.class)
	void rejectsBenchmarkTokenWithoutSession(CouponIssueStrategy strategy) throws Exception {
		mockMvc.perform(post(path(strategy))
				.header(BenchmarkAccessGuard.HEADER_NAME, "secret-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("M004"));
		verifyNoInteractions(dbIssueService, skip);
	}

	@ParameterizedTest @EnumSource(CouponIssueStrategy.class)
	void rejectsMissingBenchmarkToken(CouponIssueStrategy strategy) throws Exception {
		authenticate();
		mockMvc.perform(post(path(strategy)).cookie(new Cookie("SESSION_ID", "valid-session")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("B001"));
		verifyNoInteractions(dbIssueService, skip);
	}

	@ParameterizedTest @EnumSource(CouponIssueStrategy.class)
	void rejectsWrongBenchmarkToken(CouponIssueStrategy strategy) throws Exception {
		authenticate();
		mockMvc.perform(post(path(strategy))
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.header(BenchmarkAccessGuard.HEADER_NAME, "wrong-token"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("B001"));
		verifyNoInteractions(dbIssueService, skip);
	}

	@ParameterizedTest @EnumSource(CouponIssueStrategy.class)
	void routeSelectsFixedStrategyEvenWithConflictingQueryParameter(CouponIssueStrategy strategy) throws Exception {
		authenticate();
		mockMvc.perform(post(path(strategy))
				.param("variant", strategy == CouponIssueStrategy.DB ? "skip_surrogate_rr" : "db")
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.header(BenchmarkAccessGuard.HEADER_NAME, "secret-token"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true));
		if (strategy == CouponIssueStrategy.DB) {
			verify(dbIssueService).issue(1L, 10L);
			verifyNoInteractions(skip);
		} else {
			verify(skip).issue(1L, 10L, strategy);
			verifyNoInteractions(dbIssueService);
		}
		verifyNoMoreInteractions(dbIssueService, skip);
	}

	@Test
	void returnsMismatchRejectedInsideIssuanceTransaction() throws Exception {
		authenticate();
		willThrow(new CouponNotIssuableException()).given(dbIssueService).issue(1L, 10L);
		mockMvc.perform(post(path(CouponIssueStrategy.DB))
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.header(BenchmarkAccessGuard.HEADER_NAME, "secret-token"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CP005"));
		verifyNoInteractions(skip);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/v2/coupons/1/issue?variant=db", "/api/v2/coupons/1/issue/lua"})
	void rejectsRemovedOrUnknownRoutes(String path) throws Exception {
		authenticate();
		mockMvc.perform(post(path)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.header(BenchmarkAccessGuard.HEADER_NAME, "secret-token"))
			.andExpect(status().isNotFound());
		verifyNoInteractions(dbIssueService, skip);
	}

	@Test
	void reportsContentionSeparatelyFromSoldOut() throws Exception {
		authenticate();
		willThrow(new CouponIssueContendedException())
			.given(skip).issue(1L, 10L, CouponIssueStrategy.SKIP_SURROGATE_RC);
		mockMvc.perform(post(path(CouponIssueStrategy.SKIP_SURROGATE_RC))
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.header(BenchmarkAccessGuard.HEADER_NAME, "secret-token"))
			.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("CP012"));
		verifyNoInteractions(dbIssueService);
	}

	private String path(CouponIssueStrategy strategy) {
		return "/api/v2/coupons/1/issue/" + strategy.variant().replace('_', '-');
	}

	private void authenticate() {
		given(redisTemplate.hasKey("MEMBER_SESSION_ACTIVE:10")).willReturn(true);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("SESSION:valid-session")).willReturn(10L);
	}
}
