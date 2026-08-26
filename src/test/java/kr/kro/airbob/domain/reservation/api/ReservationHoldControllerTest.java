package kr.kro.airbob.domain.reservation.api;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.service.ReservationHoldCommandService;
import kr.kro.airbob.domain.reservation.service.ReservationQuoteService;
import kr.kro.airbob.domain.reservation.service.ReservationService;

@ExtendWith(MockitoExtension.class)
@DisplayName("예약 보유 API 테스트")
class ReservationHoldControllerTest {

	private static final long MEMBER_ID = 7L;
	private static final String RESERVATION_UID = "8ff131af-946c-4fe1-889d-e7cd7deaeec0";
	private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");

	@Mock private ReservationHoldCommandService holdCommandService;
	@Mock private ReservationQuoteService quoteService;
	@Mock private ReservationService reservationService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		UserContext.set(new UserInfo(MEMBER_ID));
		ObjectMapper objectMapper = new ObjectMapper()
			.findAndRegisterModules()
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		mockMvc = MockMvcBuilders.standaloneSetup(
			new ReservationController(quoteService, reservationService, holdCommandService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
			.build();
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
	}

	@Test
	@DisplayName("DELETE hold는 해제 여부를 안전하게 반환하고 캐시하지 않는다")
	void releasesHold() throws Exception {
		var response = new ReservationResponse.HoldRelease(
			RESERVATION_UID, ReservationStatus.EXPIRED, true, NOW);
		given(holdCommandService.releaseHold(RESERVATION_UID, MEMBER_ID)).willReturn(response);

		mockMvc.perform(delete("/api/v1/reservations/{reservationUid}/hold", RESERVATION_UID))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.data.reservation_uid").value(RESERVATION_UID))
			.andExpect(jsonPath("$.data.status").value("EXPIRED"))
			.andExpect(jsonPath("$.data.released_now").value(true))
			.andExpect(jsonPath("$.data.server_time").value("2026-08-25T03:00:00Z"));

		then(holdCommandService).should().releaseHold(RESERVATION_UID, MEMBER_ID);
	}

	@Test
	@DisplayName("POST payment-attempts는 PG 시작에 필요한 공개 필드만 반환하고 캐시하지 않는다")
	void beginsPaymentAttempt() throws Exception {
		UUID paymentAttemptId = UUID.fromString("fa1e54c6-201c-4d09-98b8-68eedfa921ae");
		var response = new ReservationResponse.PaymentAttemptReady(
			paymentAttemptId,
			RESERVATION_UID,
			330_000L,
			"KRW",
			NOW.plusSeconds(900),
			900,
			NOW
		);
		given(holdCommandService.beginPaymentAttempt(RESERVATION_UID, MEMBER_ID)).willReturn(response);

		mockMvc.perform(post(
				"/api/v1/reservations/{reservationUid}/payment-attempts", RESERVATION_UID))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.data.payment_attempt_id").value(paymentAttemptId.toString()))
			.andExpect(jsonPath("$.data.order_id").value(RESERVATION_UID))
			.andExpect(jsonPath("$.data.amount").value(330_000))
			.andExpect(jsonPath("$.data.currency").value("KRW"))
			.andExpect(jsonPath("$.data.hold_expires_at").value("2026-08-25T03:15:00Z"))
			.andExpect(jsonPath("$.data.remaining_seconds").value(900))
			.andExpect(jsonPath("$.data.server_time").value("2026-08-25T03:00:00Z"))
			.andExpect(jsonPath("$.data.payment_attempt_started_at").doesNotExist())
			.andExpect(jsonPath("$.data.payment_attempt_consumed_at").doesNotExist());

		then(holdCommandService).should().beginPaymentAttempt(RESERVATION_UID, MEMBER_ID);
	}

	@Test
	@DisplayName("예약 V2 hold와 payment-attempt 경로는 존재하지 않는다")
	void reservationV2HoldRoutesDoNotExist() throws Exception {
		mockMvc.perform(delete("/api/v2/reservations/{reservationUid}/hold", RESERVATION_UID))
			.andExpect(status().isNotFound());
		mockMvc.perform(post(
				"/api/v2/reservations/{reservationUid}/payment-attempts", RESERVATION_UID))
			.andExpect(status().isNotFound());

		then(holdCommandService).shouldHaveNoInteractions();
	}
}
