package kr.kro.airbob.domain.coupon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import kr.kro.airbob.domain.coupon.service.CouponService;
import kr.kro.airbob.domain.coupon.dto.CouponRequest;
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
				new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
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

	@Test
	void acceptsExplicitOffsetsAsInstantsBeforeCallingTheService() throws Exception {
		when(couponService.createCoupon(any())).thenReturn(101L);
		mockMvc.perform(post("/api/v1/admin/coupons").contentType("application/json").content("""
			{"name":"new year","discount_type":"FIXED_AMOUNT","discount_value":1000,
			 "issue_start_at":"2027-01-01T00:30:00.123456+09:00","issue_end_at":"2027-01-01T01:30:00.123456+09:00",
			 "usable_from":"2027-01-01T00:30:00.123456+09:00","usable_until":"2027-02-01T00:30:00.123456+09:00",
			 "is_active":true,"total_quantity":100}
			""")).andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(101));
		var command = ArgumentCaptor.forClass(CouponRequest.Create.class);
		verify(couponService).createCoupon(command.capture());
		assertThat(command.getValue().issueStartAt()).isEqualTo(Instant.parse("2026-12-31T15:30:00.123456Z"));
		assertThat(command.getValue().issueEndAt()).isEqualTo(Instant.parse("2026-12-31T16:30:00.123456Z"));
		assertThat(command.getValue().usableFrom()).isEqualTo(Instant.parse("2026-12-31T15:30:00.123456Z"));
		assertThat(command.getValue().usableUntil()).isEqualTo(Instant.parse("2027-01-31T15:30:00.123456Z"));
		assertThat(command.getValue().totalQuantity()).isEqualTo(100);
	}

	@Test
	void acceptsUtcUpdateDatesAndPreservesNullFields() throws Exception {
		mockMvc.perform(patch("/api/v1/admin/coupons/1").contentType("application/json").content("""
			{"issue_end_at":"2026-12-31T15:30:00.123456Z","total_quantity":200}
			""")).andExpect(status().isOk());
		var command = ArgumentCaptor.forClass(CouponRequest.Update.class);
		verify(couponService).updateCoupon(command.capture(), eq(1L));
		assertThat(command.getValue().issueEndAt()).isEqualTo(Instant.parse("2026-12-31T15:30:00.123456Z"));
		assertThat(command.getValue().issueStartAt()).isNull();
		assertThat(command.getValue().usableFrom()).isNull();
		assertThat(command.getValue().usableUntil()).isNull();
		assertThat(command.getValue().totalQuantity()).isEqualTo(200);
	}

	@ParameterizedTest
	@ValueSource(strings = {"2027-01-01T00:30:00", "2027-01-01"})
	void rejectsAmbiguousTimesWithoutAnOffset(String value) throws Exception {
		mockMvc.perform(patch("/api/v1/admin/coupons/1").contentType("application/json")
			.content("{\"issue_end_at\":\"" + value + "\"}"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(couponService);
	}
}
