package kr.kro.airbob.domain.coupon.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
			new CouponAdminController(couponService, preparationService))
			.setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
				new com.fasterxml.jackson.databind.ObjectMapper()
					.setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)))
			.build();
	}

	@Test
	@DisplayName("관리자는 MySQL 쿠폰 재고를 발급 시작 전에 준비한다")
	void preparesCouponStock() throws Exception {
		mockMvc.perform(post("/api/v1/admin/coupons/1/stock/prepare"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		verify(preparationService).prepare(1L);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"total_quantity\":null}", "{\"total_quantity\":0}", "{\"total_quantity\":-1}"})
	void rejectsMissingOrNonpositiveQuantity(String body) throws Exception {
		mockMvc.perform(patch("/api/v1/admin/coupons/1/quantity")
			.contentType("application/json").content(body)).andExpect(status().isBadRequest());
		verifyNoInteractions(couponService);
	}

	@Test
	void acceptsPositiveTargetQuantity() throws Exception {
		mockMvc.perform(patch("/api/v1/admin/coupons/1/quantity")
			.contentType("application/json").content("{\"total_quantity\":100}"))
			.andExpect(status().isOk());
		verify(couponService).changeQuantity(1L, 100);
	}
}
