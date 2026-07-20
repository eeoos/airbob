package kr.kro.airbob.domain.coupon.api;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import kr.kro.airbob.domain.coupon.service.CouponLockIssueService;

@ExtendWith(MockitoExtension.class)
@DisplayName("Redisson 쿠폰 벤치마크 API 접근 제어 테스트")
class CouponBenchmarkAccessTest {

	@Mock
	private CouponLockIssueService lockIssueService;
	@Mock
	private RedisTemplate<String, Object> redisTemplate;
	@Mock
	private ValueOperations<String, Object> valueOperations;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		SessionAuthFilter sessionAuthFilter = new SessionAuthFilter(redisTemplate, new ObjectMapper());
		CouponBenchmarkController controller = new CouponBenchmarkController(
			lockIssueService,
			new BenchmarkAccessGuard("secret-token")
		);
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.addFilters(sessionAuthFilter)
			.build();
	}

	@Test
	@DisplayName("세션 없이 벤치마크 토큰만 보내면 401을 반환한다")
	void rejectsBenchmarkTokenWithoutSession() throws Exception {
		mockMvc.perform(post("/api/v2/coupons/1/issue")
				.header(BenchmarkAccessGuard.HEADER_NAME, "secret-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("M004"));

		verifyNoInteractions(lockIssueService);
	}

	@Test
	@DisplayName("세션은 유효하지만 벤치마크 토큰이 없으면 403을 반환한다")
	void rejectsMissingBenchmarkToken() throws Exception {
		authenticate();

		mockMvc.perform(post("/api/v2/coupons/1/issue")
				.cookie(new Cookie("SESSION_ID", "valid-session")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("B001"));

		verifyNoInteractions(lockIssueService);
	}

	@Test
	@DisplayName("세션은 유효하지만 벤치마크 토큰이 틀리면 403을 반환한다")
	void rejectsWrongBenchmarkToken() throws Exception {
		authenticate();

		mockMvc.perform(post("/api/v2/coupons/1/issue")
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.header(BenchmarkAccessGuard.HEADER_NAME, "wrong-token"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("B001"));

		verifyNoInteractions(lockIssueService);
	}

	@Test
	@DisplayName("유효한 세션과 벤치마크 토큰으로 발급하면 201을 반환한다")
	void issuesCouponWithSessionAndBenchmarkToken() throws Exception {
		authenticate();

		mockMvc.perform(post("/api/v2/coupons/1/issue")
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.header(BenchmarkAccessGuard.HEADER_NAME, "secret-token"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true));
		verify(lockIssueService).issue(1L, 10L);
	}

	private void authenticate() {
		given(redisTemplate.hasKey("SESSION:valid-session")).willReturn(true);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("SESSION:valid-session")).willReturn(10L);
	}
}
