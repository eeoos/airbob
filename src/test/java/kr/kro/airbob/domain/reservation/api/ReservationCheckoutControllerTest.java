package kr.kro.airbob.domain.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
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
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.service.ReservationHoldCommandService;
import kr.kro.airbob.domain.reservation.service.ReservationQuoteService;
import kr.kro.airbob.domain.reservation.service.ReservationService;

@ExtendWith(MockitoExtension.class)
@DisplayName("예약 견적·checkout API 테스트")
class ReservationCheckoutControllerTest {

	private static final long MEMBER_ID = 7L;
	private static final String QUOTE_UID = "fa1e54c6-201c-4d09-98b8-68eedfa921ae";
	private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");

	@Mock private ReservationQuoteService quoteService;
	@Mock private ReservationService reservationService;
	@Mock private ReservationHoldCommandService holdCommandService;

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
	@DisplayName("POST /api/v1/reservation-quotes는 재고를 잡지 않는 5분 견적을 201로 반환한다")
	void createsNoHoldQuote() throws Exception {
		ReservationRequest.Quote request = new ReservationRequest.Quote(
			31L,
			LocalDate.of(2026, 9, 10),
			LocalDate.of(2026, 9, 13),
			2,
			55L
		);
		ReservationResponse.Quote response = new ReservationResponse.Quote(
			UUID.fromString(QUOTE_UID),
			31L,
			"한강 전망 숙소",
			request.checkInDate(),
			request.checkOutDate(),
			2,
			120_000L,
			3L,
			360_000L,
			30_000L,
			330_000L,
			"KRW",
			true,
			false,
			NOW.plusSeconds(5 * 60),
			NOW
		);
		given(quoteService.createQuote(request, MEMBER_ID)).willReturn(response);

		mockMvc.perform(post("/api/v1/reservation-quotes")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"accommodation_id":31,"check_in_date":"2026-09-10",\
					 "check_out_date":"2026-09-13","guest_count":2,"coupon_id":55}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.quote_uid").value(QUOTE_UID))
			.andExpect(jsonPath("$.data.nightly_price").value(120_000))
			.andExpect(jsonPath("$.data.nights").value(3))
			.andExpect(jsonPath("$.data.subtotal").value(360_000))
			.andExpect(jsonPath("$.data.discount_amount").value(30_000))
			.andExpect(jsonPath("$.data.amount").value(330_000))
			.andExpect(jsonPath("$.data.payment_required").value(true))
			.andExpect(jsonPath("$.data.inventory_held").value(false))
			.andExpect(jsonPath("$.data.quote_expires_at").value("2026-08-25T03:05:00Z"))
			.andExpect(jsonPath("$.data.server_time").value("2026-08-25T03:00:00Z"));

		then(quoteService).should().createQuote(request, MEMBER_ID);
		then(reservationService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("POST /api/v1/reservations는 quote와 요청사항만 받아 표준 멱등성 키로 checkout한다")
	void checksOutQuoteWithRequestMessageAndIdempotencyKey() throws Exception {
		String idempotencyKey = "quote-checkout-key-2026";
		ReservationRequest.Checkout request = new ReservationRequest.Checkout(
			UUID.fromString(QUOTE_UID),
			"늦은 체크인 예정입니다"
		);
		ReservationResponse.Ready response = ReservationResponse.Ready.builder()
			.reservationUid(UUID.fromString("8ff131af-946c-4fe1-889d-e7cd7deaeec0").toString())
			.orderName("한강 전망 숙소")
			.checkIn(LocalDate.of(2026, 9, 10))
			.checkOut(LocalDate.of(2026, 9, 13))
			.guestCount(2)
			.subtotal(360_000L)
			.discountAmount(30_000L)
			.amount(330_000L)
			.currency("KRW")
			.status(ReservationStatus.PAYMENT_PENDING)
			.paymentRequired(true)
			.paymentAllowed(true)
			.holdExpiresAt(NOW.plusSeconds(15 * 60))
			.serverTime(NOW)
			.build();
		given(reservationService.createPendingReservation(request, MEMBER_ID, idempotencyKey))
			.willReturn(response);

		var result = mockMvc.perform(post("/api/v1/reservations")
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"quote_uid":"fa1e54c6-201c-4d09-98b8-68eedfa921ae",\
					 "request_message":"늦은 체크인 예정입니다"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.reservation_uid")
				.value("8ff131af-946c-4fe1-889d-e7cd7deaeec0"))
			.andExpect(jsonPath("$.data.amount").value(330_000))
			.andReturn();

		assertThat(result.getResponse().getContentAsString()).doesNotContain(idempotencyKey);
		then(reservationService).should()
			.createPendingReservation(request, MEMBER_ID, idempotencyKey);
		then(quoteService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("checkout body에는 견적 식별자와 예약 요청사항 외 가격·날짜 입력이 없다")
	void checkoutBodyContainsOnlyQuoteUidAndRequestMessage() {
		assertThat(Arrays.stream(ReservationRequest.Checkout.class.getRecordComponents())
			.map(component -> component.getName()))
			.containsExactly("quoteUid", "requestMessage");
	}

	@Test
	@DisplayName("checkout은 Idempotency-Key가 없으면 400으로 거절한다")
	void rejectsMissingIdempotencyKey() throws Exception {
		mockMvc.perform(post("/api/v1/reservations")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"quote_uid":"fa1e54c6-201c-4d09-98b8-68eedfa921ae"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("C001"));

		then(reservationService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("checkout은 UUID가 아닌 quote 식별자를 400으로 거절한다")
	void rejectsMalformedQuoteUid() throws Exception {
		mockMvc.perform(post("/api/v1/reservations")
				.header("Idempotency-Key", "malformed-quote-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"quote_uid":"not-a-uuid"}
					"""))
			.andExpect(status().isBadRequest());

		then(reservationService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("기존 직접 생성 body는 V1 checkout 계약에서 400으로 거절한다")
	void rejectsLegacyDirectCreateBody() throws Exception {
		mockMvc.perform(post("/api/v1/reservations")
				.header("Idempotency-Key", "legacy-direct-create")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"accommodation_id":31,"check_in_date":"2026-09-10",\
					 "check_out_date":"2026-09-13","guest_count":2}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false));

		then(reservationService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("예약 V2 견적과 checkout 경로는 존재하지 않는다")
	void reservationV2RoutesDoNotExist() throws Exception {
		mockMvc.perform(post("/api/v2/reservation-quotes")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v2/reservations")
				.header("Idempotency-Key", "removed-v2-checkout")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isNotFound());

		then(quoteService).shouldHaveNoInteractions();
		then(reservationService).shouldHaveNoInteractions();
	}
}
