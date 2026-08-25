package kr.kro.airbob.domain.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Cancellation;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Status;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.service.ReservationService;

@ExtendWith(MockitoExtension.class)
class ReservationControllerTest {

	private static final long MEMBER_ID = 7L;

	@Mock private ReservationService reservationService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		UserContext.set(new UserInfo(MEMBER_ID));
		ObjectMapper objectMapper = new ObjectMapper()
			.findAndRegisterModules()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		mockMvc = MockMvcBuilders.standaloneSetup(new ReservationController(reservationService))
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
			.build();
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
	}

	@Test
	void creationUsesTheStandardIdempotencyHeaderAndKeepsTheV1SuccessStatus() throws Exception {
		String idempotencyKey = "checkout-secret-key-that-must-not-be-returned";
		ReservationRequest.Create request = new ReservationRequest.Create(
			31L,
			LocalDate.of(2026, 9, 10),
			LocalDate.of(2026, 9, 12),
			2,
			null,
			"늦은 체크인"
		);

		var result = mockMvc.perform(post("/api/v1/reservations")
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"accommodation_id":31,"check_in_date":"2026-09-10","check_out_date":"2026-09-12",\
					 "guest_count":2,"coupon_id":null,"request_message":"늦은 체크인"}
					"""))
			.andExpect(status().isOk())
			.andReturn();

		assertThat(result.getResponse().getContentAsString()).doesNotContain(idempotencyKey);
		then(reservationService).should()
			.createPendingReservation(request, MEMBER_ID, idempotencyKey);
	}

	@Test
	void creationWithoutTheNewHeaderKeepsTheExistingV1Contract() throws Exception {
		ReservationRequest.Create request = new ReservationRequest.Create(
			31L,
			LocalDate.of(2026, 9, 10),
			LocalDate.of(2026, 9, 12),
			2
		);

		mockMvc.perform(post("/api/v1/reservations")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"accommodation_id":31,"check_in_date":"2026-09-10",\
					 "check_out_date":"2026-09-12","guest_count":2}
					"""))
			.andExpect(status().isOk());

		then(reservationService).should()
			.createPendingReservation(request, MEMBER_ID, null);
	}

	@Test
	void paidCancellationKeepsTheV1EmptyEnvelopeAndExposesThePollingLocation() throws Exception {
		String reservationUid = "6df13da6-735a-4a4a-a8bc-3b8acbdac9bf";
		UUID operationUid = UUID.fromString("6735cde3-c4c3-4f44-9a56-54cc2bf75baa");
		PaymentRequest.Cancel request = new PaymentRequest.Cancel("게스트 요청", null);
		String statusUrl = "/api/v1/payment-operations/" + operationUid;
		Cancellation cancellation = new Cancellation(
			operationUid,
			Status.PENDING,
			statusUrl,
			false
		);
		given(reservationService.cancelReservation(reservationUid, request, MEMBER_ID))
			.willReturn(cancellation);

		mockMvc.perform(post("/api/v1/reservations/{reservationUid}", reservationUid)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"cancel_reason":"게스트 요청","cancel_amount":null}
					"""))
			.andExpect(status().isAccepted())
			.andExpect(header().string(HttpHeaders.LOCATION, statusUrl))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().string("{\"success\":true}"));

		then(reservationService).should()
			.cancelReservation(reservationUid, request, MEMBER_ID);
	}

	@Test
	void complimentaryCancellationKeepsTheSameV1EnvelopeWithoutAPollingLocation() throws Exception {
		String reservationUid = "6df13da6-735a-4a4a-a8bc-3b8acbdac9bf";
		PaymentRequest.Cancel request = new PaymentRequest.Cancel("0원 예약 취소", null);
		given(reservationService.cancelReservation(reservationUid, request, MEMBER_ID))
			.willReturn(Cancellation.completed());

		mockMvc.perform(post("/api/v1/reservations/{reservationUid}", reservationUid)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"cancel_reason":"0원 예약 취소","cancel_amount":null}
					"""))
			.andExpect(status().isAccepted())
			.andExpect(header().doesNotExist(HttpHeaders.LOCATION))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().string("{\"success\":true}"));

		then(reservationService).should()
			.cancelReservation(reservationUid, request, MEMBER_ID);
	}
}
