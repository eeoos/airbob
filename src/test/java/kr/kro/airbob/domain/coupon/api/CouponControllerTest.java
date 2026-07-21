package kr.kro.airbob.domain.coupon.api;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.domain.coupon.service.CouponLuaIssueService;
import kr.kro.airbob.domain.coupon.service.CouponService;

@ExtendWith(MockitoExtension.class)
class CouponControllerTest {

	@Mock
	private CouponService couponService;
	@Mock
	private CouponLuaIssueService luaIssueService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		UserContext.set(new UserInfo(10L));
		mockMvc = MockMvcBuilders.standaloneSetup(
			new CouponController(couponService, luaIssueService)).build();
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
	@DisplayName("운영 API는 동시성 전략 suffix를 노출하지 않는다")
	void strategySuffixEndpointsAreNotMapped() throws Exception {
		mockMvc.perform(post("/api/v1/coupons/1/issue/lua"))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v1/coupons/1/issue/lock"))
			.andExpect(status().isNotFound());
	}
}
