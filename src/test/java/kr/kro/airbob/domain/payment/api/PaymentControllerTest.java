package kr.kro.airbob.domain.payment.api;

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

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Accepted;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Status;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.service.PaymentOperationCommandService;
import kr.kro.airbob.domain.payment.service.PaymentQueryService;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

	@Mock private PaymentOperationCommandService commandService;
	@Mock private PaymentQueryService paymentQueryService;

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
	void confirmPaymentReturnsTheExactServiceResponseInAnAcceptedEnvelope() {
		UUID reservationUid = UUID.fromString("6df13da6-735a-4a4a-a8bc-3b8acbdac9bf");
		UUID operationUid = UUID.fromString("6735cde3-c4c3-4f44-9a56-54cc2bf75baa");
		PaymentRequest.Confirm request = new PaymentRequest.Confirm("payment-key", reservationUid.toString(), 100_000);
		Accepted accepted = new Accepted(operationUid, Status.PENDING,
			"/api/v1/payment-operations/" + operationUid);
		given(commandService.requestConfirmation(eq(request), eq(10L))).willReturn(accepted);

		ResponseEntity<ApiResponse<Accepted>> response = controller.confirmPayment(request, 10L);

		org.assertj.core.api.Assertions.assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.ACCEPTED);
		org.assertj.core.api.Assertions.assertThat(response.getBody().getData()).isSameAs(accepted);
		then(commandService).should().requestConfirmation(request, 10L);
	}

	@Test
	void confirmPaymentAcceptsTheAuthenticatedRequestAtThePublicRoute() throws Exception {
		UUID reservationUid = UUID.fromString("6df13da6-735a-4a4a-a8bc-3b8acbdac9bf");
		UUID operationUid = UUID.fromString("6735cde3-c4c3-4f44-9a56-54cc2bf75baa");
		Accepted accepted = new Accepted(operationUid, Status.PENDING,
			"/api/v1/payment-operations/" + operationUid);
		given(commandService.requestConfirmation(org.mockito.ArgumentMatchers.any(), eq(10L))).willReturn(accepted);

		mockMvc.perform(post("/api/v1/payments/confirm")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"paymentKey":"payment-key","orderId":"%s","amount":100000}
					""".formatted(reservationUid)))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.operationId").value(operationUid.toString()))
			.andExpect(jsonPath("$.data.statusUrl").value("/api/v1/payment-operations/" + operationUid));
	}
}
