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
import kr.kro.airbob.domain.coupon.service.CouponLockIssueService;
import kr.kro.airbob.domain.coupon.service.CouponLuaIssueService;
import kr.kro.airbob.domain.coupon.service.CouponService;

@ExtendWith(MockitoExtension.class)
class CouponControllerTest {

	@Mock
	private CouponService couponService;
	@Mock
	private CouponLockIssueService lockIssueService;
	@Mock
	private CouponLuaIssueService luaIssueService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		UserContext.set(new UserInfo(10L));
		mockMvc = MockMvcBuilders.standaloneSetup(
			new CouponController(couponService, lockIssueService, luaIssueService)).build();
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
	}

	@Test
	@DisplayName("분산 락 발급 URL은 락 전용 서비스를 호출하고 DB 완료 후 201을 반환한다")
	void issuesWithLockEndpoint() throws Exception {
		mockMvc.perform(post("/api/v1/coupons/1/issue/lock"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true));

		verify(lockIssueService).issue(1L, 10L);
	}

	@Test
	@DisplayName("Lua 발급 URL은 Lua 전용 서비스를 호출하고 DB 완료 후 201을 반환한다")
	void issuesWithLuaEndpoint() throws Exception {
		mockMvc.perform(post("/api/v1/coupons/1/issue/lua"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true));

		verify(luaIssueService).issue(1L, 10L);
	}
}
