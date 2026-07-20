package kr.kro.airbob.domain.coupon.api;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import kr.kro.airbob.domain.coupon.service.CouponService;
import kr.kro.airbob.domain.coupon.service.CouponStockPreparationService;

@ExtendWith(MockitoExtension.class)
class CouponAdminControllerTest {

	@Mock
	private CouponService couponService;
	@Mock
	private CouponStockPreparationService preparationService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(
			new CouponAdminController(couponService, preparationService)).build();
	}

	@Test
	@DisplayName("관리자는 Lua 쿠폰 재고를 발급 시작 전에 준비한다")
	void preparesCouponStock() throws Exception {
		mockMvc.perform(post("/api/v1/admin/coupons/1/stock/prepare"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		verify(preparationService).prepare(1L);
	}
}
