package kr.kro.airbob.core.api.controller.v1.reservation;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import kr.kro.airbob.core.api.controller.v1.payment.request.PaymentRequest;
import kr.kro.airbob.core.api.controller.v1.payment.response.PaymentOperationResponse.Cancellation;
import kr.kro.airbob.core.api.controller.v1.payment.response.PaymentOperationResponse.Status;
import kr.kro.airbob.core.api.security.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.core.domain.payment.PaymentCancellationCommandService;
import kr.kro.airbob.core.domain.payment.PaymentOperationReceipt;
import kr.kro.airbob.core.domain.payment.PaymentOperationStatus;
import kr.kro.airbob.core.domain.reservation.ReservationCheckoutService;
import kr.kro.airbob.core.domain.reservation.ReservationHoldCommandService;
import kr.kro.airbob.core.domain.reservation.ReservationQuoteService;
import kr.kro.airbob.core.support.context.UserContext;
import kr.kro.airbob.core.support.context.UserInfo;

@ExtendWith(MockitoExtension.class)
class ReservationCommandControllerTest {

	private static final long MEMBER_ID = 7L;

	@Mock private ReservationCheckoutService checkoutService;
	@Mock private ReservationQuoteService quoteService;
	@Mock private ReservationHoldCommandService holdCommandService;
	@Mock private PaymentCancellationCommandService cancellationCommandService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		UserContext.set(new UserInfo(MEMBER_ID));
		ObjectMapper objectMapper = new ObjectMapper()
			.findAndRegisterModules()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		mockMvc = MockMvcBuilders.standaloneSetup(
			new ReservationCommandController(quoteService, checkoutService, holdCommandService,
				cancellationCommandService))
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
			.build();
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
	}

	@Test
	void paidCancellationKeepsTheV1EmptyEnvelopeAndExposesThePollingLocation() throws Exception {
		String reservationUid = "6df13da6-735a-4a4a-a8bc-3b8acbdac9bf";
		UUID operationUid = UUID.fromString("6735cde3-c4c3-4f44-9a56-54cc2bf75baa");
		PaymentRequest.Cancel request = new PaymentRequest.Cancel("게스트 요청", null);
		String statusUrl = "/api/v1/payment-operations/" + operationUid;
		PaymentOperationReceipt cancellation = new PaymentOperationReceipt(
			operationUid,
			PaymentOperationStatus.QUEUED,
			false
		);
		given(cancellationCommandService.requestCancellation(reservationUid, request.toCancellation(), MEMBER_ID))
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

		then(cancellationCommandService).should()
			.requestCancellation(reservationUid, request.toCancellation(), MEMBER_ID);
	}

	@Test
	void complimentaryCancellationKeepsTheSameV1EnvelopeWithoutAPollingLocation() throws Exception {
		String reservationUid = "6df13da6-735a-4a4a-a8bc-3b8acbdac9bf";
		PaymentRequest.Cancel request = new PaymentRequest.Cancel("0원 예약 취소", null);
		given(cancellationCommandService.requestCancellation(reservationUid, request.toCancellation(), MEMBER_ID))
			.willReturn(PaymentOperationReceipt.completedWithoutOperation());

		mockMvc.perform(post("/api/v1/reservations/{reservationUid}", reservationUid)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"cancel_reason":"0원 예약 취소","cancel_amount":null}
					"""))
			.andExpect(status().isAccepted())
			.andExpect(header().doesNotExist(HttpHeaders.LOCATION))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().string("{\"success\":true}"));

		then(cancellationCommandService).should()
			.requestCancellation(reservationUid, request.toCancellation(), MEMBER_ID);
	}
}
