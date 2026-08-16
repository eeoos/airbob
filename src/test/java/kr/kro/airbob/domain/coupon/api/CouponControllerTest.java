package kr.kro.airbob.domain.coupon.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.coupon.common.CouponIssuanceStatus;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.common.MemberCouponStatus;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.service.CouponLuaIssueService;
import kr.kro.airbob.domain.coupon.service.CouponQueryService;

@ExtendWith(MockitoExtension.class)
class CouponControllerTest {

	@Mock
	private CouponQueryService couponQueryService;
	@Mock
	private CouponLuaIssueService luaIssueService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		UserContext.set(new UserInfo(10L));
		ObjectMapper objectMapper = new ObjectMapper()
			.findAndRegisterModules()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		mockMvc = MockMvcBuilders.standaloneSetup(
			new CouponController(couponQueryService, luaIssueService))
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
			.build();
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
	}

	@Test
	@DisplayName("운영 발급 URL은 Lua 서비스로 발급하고 201을 반환한다")
	void issuesCouponWithLua() throws Exception {
		mockMvc.perform(post("/api/v1/coupons/1/issue"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true));

		verify(luaIssueService).issue(1L, 10L);
	}

	@Test
	@DisplayName("쿠폰 캠페인 목록에 현재 발급 상태를 포함한다")
	void findsCouponCampaigns() throws Exception {
		LocalDateTime issueStartAt = LocalDateTime.of(2026, 8, 20, 10, 0);
		CouponResponse.CouponInfo info = new CouponResponse.CouponInfo(
			1L,
			"오전 10시 선착순 쿠폰",
			null,
			DiscountType.FIXED_AMOUNT,
			10_000,
			50_000,
			null,
			issueStartAt,
			issueStartAt.plusMinutes(10),
			issueStartAt,
			issueStartAt.plusDays(30),
			100,
			0,
			CouponIssuanceStatus.UPCOMING);
		when(couponQueryService.findCouponCampaigns())
			.thenReturn(new CouponResponse.CouponInfos(List.of(info)));

		mockMvc.perform(get("/api/v1/coupons"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.infos[0].id").value(1L))
			.andExpect(jsonPath("$.data.infos[0].issuance_status").value("UPCOMING"));

		verify(couponQueryService).findCouponCampaigns();
	}

	@Test
	@DisplayName("로그인 회원이 발급받은 쿠폰 목록을 조회한다")
	void findsMyCoupons() throws Exception {
		LocalDateTime usableFrom = LocalDateTime.of(2026, 8, 20, 10, 0);
		CouponResponse.MemberCouponInfo info = new CouponResponse.MemberCouponInfo(
			1L,
			"오전 10시 선착순 쿠폰",
			null,
			DiscountType.FIXED_AMOUNT,
			10_000,
			50_000,
			null,
			usableFrom,
			usableFrom.plusDays(30),
			MemberCouponStatus.AVAILABLE);
		when(couponQueryService.findMyCoupons(10L))
			.thenReturn(new CouponResponse.MemberCouponInfos(List.of(info)));

		mockMvc.perform(get("/api/v1/members/me/coupons"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.infos[0].coupon_id").value(1L))
			.andExpect(jsonPath("$.data.infos[0].status").value("AVAILABLE"));

		verify(couponQueryService).findMyCoupons(10L);
	}

	@Test
	@DisplayName("운영 API는 동시성 전략 suffix를 노출하지 않는다")
	void strategySuffixEndpointsAreNotMapped() throws Exception {
		mockMvc.perform(post("/api/v1/coupons/1/issue/lua"))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v1/coupons/1/issue/lock"))
			.andExpect(status().isNotFound());
	}
}
