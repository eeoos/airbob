package kr.kro.airbob.domain.coupon.api;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.service.CouponCampaignReadBenchmarkService;
import kr.kro.airbob.domain.coupon.service.CouponQueryService;

@ExtendWith(MockitoExtension.class)
class CouponCampaignReadBenchmarkAccessTest {
	@Mock private CouponQueryService baseline;
	@Mock private CouponCampaignReadBenchmarkService candidate;
	@Mock private RedisTemplate<String, Object> redis;
	@Mock private ValueOperations<String, Object> values;
	private MockMvc mvc;

	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new CouponCampaignReadBenchmarkController(
			baseline, candidate, new BenchmarkAccessGuard("test-token")))
			.setControllerAdvice(new GlobalExceptionHandler())
			.addFilters(new SessionAuthFilter(redis, new ObjectMapper())).build();
	}

	@ParameterizedTest @ValueSource(strings = {"count", "exists"})
	void rejectsMissingSession(String variant) throws Exception {
		mvc.perform(get(path(variant)).header(BenchmarkAccessGuard.HEADER_NAME, "test-token"))
			.andExpect(status().isUnauthorized());
		verifyNoInteractions(baseline, candidate);
	}

	@ParameterizedTest @ValueSource(strings = {"count", "exists"})
	void rejectsMissingToken(String variant) throws Exception {
		authenticate();
		mvc.perform(get(path(variant)).cookie(new Cookie("SESSION_ID", "session")))
			.andExpect(status().isForbidden());
		verifyNoInteractions(baseline, candidate);
	}

	@ParameterizedTest @ValueSource(strings = {"count", "exists"})
	void returnsSameResponseEnvelope(String variant) throws Exception {
		authenticate();
		var empty = new CouponResponse.CouponInfos(List.of());
		if (variant.equals("count")) given(baseline.findCouponCampaigns()).willReturn(empty);
		else given(candidate.findWithRemainingUnits()).willReturn(empty);
		mvc.perform(get(path(variant)).cookie(new Cookie("SESSION_ID", "session"))
				.header(BenchmarkAccessGuard.HEADER_NAME, "test-token"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.infos").isEmpty());
		if (variant.equals("count")) verifyNoInteractions(candidate);
		else verifyNoInteractions(baseline);
	}

	private void authenticate() {
		given(redis.opsForValue()).willReturn(values);
		given(values.get("SESSION:session")).willReturn(10L);
		given(redis.hasKey("MEMBER_SESSION_ACTIVE:10")).willReturn(true);
	}

	private String path(String variant) { return "/api/v2/coupons/campaigns/" + variant; }
}
