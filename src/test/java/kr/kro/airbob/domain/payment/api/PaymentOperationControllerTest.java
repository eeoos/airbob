package kr.kro.airbob.domain.payment.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Detail;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.NextAction;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Status;
import kr.kro.airbob.domain.payment.service.PaymentOperationQueryService;

@ExtendWith(MockitoExtension.class)
class PaymentOperationControllerTest {

	@Mock private PaymentOperationQueryService queryService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		UserContext.set(new UserInfo(10L));
		ObjectMapper objectMapper = new ObjectMapper()
			.findAndRegisterModules()
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		mockMvc = MockMvcBuilders.standaloneSetup(new PaymentOperationController(queryService))
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
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
			Instant.parse("2026-08-14T01:02:03Z"),
			NextAction.START_NEW_CHECKOUT,
			null,
			"결제가 완료되지 않았습니다. 새 견적을 받은 뒤 예약을 다시 진행해 주세요.",
			Instant.parse("2026-08-14T01:03:00Z"),
			"PAYMENT_DECLINED");
		given(queryService.find(eq(operationUid), eq(10L))).willReturn(detail);

		mockMvc.perform(get("/api/v1/payment-operations/{operationId}", operationUid))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.operation_id").value(operationUid.toString()))
			.andExpect(jsonPath("$.data.order_id").value(orderId.toString()))
			.andExpect(jsonPath("$.data.failure_code").value("PROVIDER_DECLINED"))
			.andExpect(jsonPath("$.data.user_failure_code").value("PAYMENT_DECLINED"))
			.andExpect(jsonPath("$.data.updated_at").value("2026-08-14T01:02:03Z"))
			.andExpect(jsonPath("$.data.next_action").value("START_NEW_CHECKOUT"))
			.andExpect(jsonPath("$.data.retry_after_seconds").isEmpty())
			.andExpect(jsonPath("$.data.user_message")
				.value("결제가 완료되지 않았습니다. 새 견적을 받은 뒤 예약을 다시 진행해 주세요."))
			.andExpect(jsonPath("$.data.server_time").value("2026-08-14T01:03:00Z"))
			.andExpect(jsonPath("$.data.payment_key").doesNotExist())
			.andExpect(jsonPath("$.data.failure_message").doesNotExist())
			.andExpect(jsonPath("$.data.provider_action").doesNotExist())
			.andExpect(jsonPath("$.data.next_attempt_at").doesNotExist())
			.andExpect(jsonPath("$.data.lease_owner").doesNotExist())
			.andExpect(jsonPath("$.data.lease_expires_at").doesNotExist())
			.andExpect(jsonPath("$.data.dispatch_generation").doesNotExist());

		then(queryService).should().find(operationUid, 10L);
	}
}
