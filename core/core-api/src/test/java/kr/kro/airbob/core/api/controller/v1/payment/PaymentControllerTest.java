package kr.kro.airbob.core.api.controller.v1.payment;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import kr.kro.airbob.core.api.controller.v1.payment.request.PaymentRequest;
import kr.kro.airbob.core.api.controller.v1.payment.response.PaymentOperationResponse.Accepted;
import kr.kro.airbob.core.api.controller.v1.payment.response.PaymentOperationResponse.Status;
import kr.kro.airbob.core.api.security.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.core.domain.payment.PaymentOperationCommandService;
import kr.kro.airbob.core.domain.payment.PaymentOperationReceipt;
import kr.kro.airbob.core.domain.payment.PaymentOperationStatus;
import kr.kro.airbob.core.domain.payment.PaymentQueryService;
import kr.kro.airbob.core.support.context.UserContext;
import kr.kro.airbob.core.support.context.UserInfo;
import kr.kro.airbob.core.support.response.ApiResponse;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

	@Mock
	private PaymentOperationCommandService commandService;
	@Mock
	private PaymentQueryService paymentQueryService;

	private MockMvc mockMvc;
	private PaymentController controller;

	@BeforeEach
	void setUp() {
		UserContext.set(new UserInfo(10L));
		controller = new PaymentController(commandService, paymentQueryService);
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.build();
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
	}

	@Test
	void confirmPaymentReturnsTheServiceResultInAnAcceptedEnvelope() {
		UUID reservationUid = UUID.fromString("6df13da6-735a-4a4a-a8bc-3b8acbdac9bf");
		UUID paymentAttemptId = UUID.fromString("b72b0711-c957-44ee-a9ee-19aa2c6d93a5");
		UUID operationUid = UUID.fromString("6735cde3-c4c3-4f44-9a56-54cc2bf75baa");
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 100_000, paymentAttemptId);
		Accepted accepted = new Accepted(operationUid, Status.PENDING,
			"/api/v1/payment-operations/" + operationUid);
		PaymentOperationReceipt receipt = new PaymentOperationReceipt(
			operationUid, PaymentOperationStatus.QUEUED, false);
		given(commandService.requestConfirmation(eq(request.toConfirmation()), eq(10L))).willReturn(receipt);

		ResponseEntity<ApiResponse<Accepted>> response = controller.confirmPayment(request, 10L);

		org.assertj.core.api.Assertions.assertThat(response.getStatusCode())
			.isEqualTo(org.springframework.http.HttpStatus.ACCEPTED);
		org.assertj.core.api.Assertions.assertThat(response.getBody().getData()).isEqualTo(accepted);
		then(commandService).should().requestConfirmation(request.toConfirmation(), 10L);
	}

	@Test
	void confirmPaymentForwardsTheRequiredPaymentAttemptId() {
		UUID reservationUid = UUID.fromString("6df13da6-735a-4a4a-a8bc-3b8acbdac9bf");
		UUID paymentAttemptId = UUID.fromString("b72b0711-c957-44ee-a9ee-19aa2c6d93a5");
		UUID operationUid = UUID.fromString("6735cde3-c4c3-4f44-9a56-54cc2bf75baa");
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 100_000, paymentAttemptId);
		Accepted accepted = new Accepted(operationUid, Status.PENDING,
			"/api/v1/payment-operations/" + operationUid);
		PaymentOperationReceipt receipt = new PaymentOperationReceipt(
			operationUid, PaymentOperationStatus.QUEUED, false);
		given(commandService.requestConfirmation(eq(request.toConfirmation()), eq(10L))).willReturn(receipt);

		ResponseEntity<ApiResponse<Accepted>> response = controller.confirmPayment(request, 10L);

		org.assertj.core.api.Assertions.assertThat(response.getStatusCode())
			.isEqualTo(org.springframework.http.HttpStatus.ACCEPTED);
		org.assertj.core.api.Assertions.assertThat(response.getBody().getData()).isEqualTo(accepted);
		then(commandService).should().requestConfirmation(request.toConfirmation(), 10L);
	}

	@Test
	void confirmPaymentAcceptsTheAuthenticatedRequestAtThePublicRoute() throws Exception {
		UUID reservationUid = UUID.fromString("6df13da6-735a-4a4a-a8bc-3b8acbdac9bf");
		UUID paymentAttemptId = UUID.fromString("b72b0711-c957-44ee-a9ee-19aa2c6d93a5");
		UUID operationUid = UUID.fromString("6735cde3-c4c3-4f44-9a56-54cc2bf75baa");
		Accepted accepted = new Accepted(operationUid, Status.PENDING,
			"/api/v1/payment-operations/" + operationUid);
		PaymentOperationReceipt receipt = new PaymentOperationReceipt(
			operationUid, PaymentOperationStatus.QUEUED, false);
		given(commandService.requestConfirmation(org.mockito.ArgumentMatchers.any(), eq(10L))).willReturn(receipt);

		mockMvc.perform(post("/api/v1/payments/confirm")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"paymentKey":"payment-key","orderId":"%s","amount":100000,"paymentAttemptId":"%s"}
				""".formatted(reservationUid, paymentAttemptId)))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.operationId").value(operationUid.toString()))
			.andExpect(jsonPath("$.data.statusUrl").value("/api/v1/payment-operations/" + operationUid));
	}

	@Test
	void confirmPaymentRejectsMissingPaymentAttemptBeforeCallingTheService() throws Exception {
		UUID reservationUid = UUID.fromString("6df13da6-735a-4a4a-a8bc-3b8acbdac9bf");

		mockMvc.perform(post("/api/v1/payments/confirm")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"paymentKey":"payment-key","orderId":"%s","amount":100000}
				""".formatted(reservationUid)))
			.andExpect(status().isBadRequest());

		then(commandService).shouldHaveNoInteractions();
	}
}
