package kr.kro.airbob.domain.payment.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Detail;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Status;
import kr.kro.airbob.domain.payment.service.PaymentOperationQueryService;

@ExtendWith(MockitoExtension.class)
class PaymentOperationControllerTest {

	@Mock private PaymentOperationQueryService queryService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		UserContext.set(new UserInfo(10L));
		mockMvc = MockMvcBuilders.standaloneSetup(new PaymentOperationController(queryService))
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.build();
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
	}

	@Test
	void findReturnsTheOwnerOperationWithoutPaymentKey() throws Exception {
		UUID operationUid = UUID.fromString("6735cde3-c4c3-4f44-9a56-54cc2bf75baa");
		UUID orderId = UUID.fromString("6df13da6-735a-4a4a-a8bc-3b8acbdac9bf");
		Detail detail = new Detail(operationUid, orderId, Status.FAILED, "PROVIDER_DECLINED",
			Instant.parse("2026-08-14T01:02:03Z"));
		given(queryService.find(eq(operationUid), eq(10L))).willReturn(detail);

		mockMvc.perform(get("/api/v1/payment-operations/{operationId}", operationUid))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.operationId").value(operationUid.toString()))
			.andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
			.andExpect(jsonPath("$.data.failureCode").value("PROVIDER_DECLINED"))
			.andExpect(jsonPath("$.data.paymentKey").doesNotExist());

		then(queryService).should().find(operationUid, 10L);
	}
}
