package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.backoff.Sleeper;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.exception.code.PaymentConfirmErrorCode;

@DisplayName("TossPaymentsAdapter 테스트")
class TossPaymentsAdapterTest {
	private MockRestServiceServer server;
	private TossPaymentsAdapter adapter;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://api.example.com");
		server = MockRestServiceServer.bindTo(builder).build();
		adapter = new TossPaymentsAdapter(builder.build(), new ObjectMapper());
	}

	@Test
	@DisplayName("같은 결제 승인 재시도는 동일한 멱등키를 사용한다")
	void confirmationUsesStableIdempotencyKey() {
		String orderId = "reservation-order-123";

		server.expect(requestTo("https://api.example.com/v1/payments/confirm"))
			.andExpect(header(
				TossPaymentsAdapter.IDEMPOTENCY_KEY_HEADER,
				TossPaymentsAdapter.CONFIRMATION_IDEMPOTENCY_KEY_PREFIX + orderId
			))
			.andRespond(withSuccess(
				"{\"paymentKey\":\"payment-key-123\",\"orderId\":\"reservation-order-123\",\"status\":\"DONE\"}",
				MediaType.APPLICATION_JSON
			));

		adapter.confirmPayment("payment-key-123", orderId, 100_000);

		server.verify();
	}

	@Test
	@DisplayName("같은 결제 취소 재시도는 동일한 멱등키를 사용한다")
	void cancellationUsesStableIdempotencyKey() {
		String paymentKey = "payment-key-123";

		server.expect(requestTo("https://api.example.com/v1/payments/payment-key-123/cancel"))
			.andExpect(header(
				TossPaymentsAdapter.IDEMPOTENCY_KEY_HEADER,
				TossPaymentsAdapter.CANCELLATION_IDEMPOTENCY_KEY_PREFIX + paymentKey
			))
			.andRespond(withSuccess(
				"{\"status\":\"CANCELED\",\"balanceAmount\":0}",
				MediaType.APPLICATION_JSON
			));

		adapter.cancelPayment(paymentKey, "사용자 요청", null);

		server.verify();
	}

	@Test
	@DisplayName("처리 중인 결제 승인 멱등 요청은 최종 실패로 확정하지 않는다")
	void confirmationInProgressIsRetryable() {
		server.expect(requestTo("https://api.example.com/v1/payments/confirm"))
			.andRespond(withStatus(HttpStatus.CONFLICT)
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
					{"code":"IDEMPOTENT_REQUEST_PROCESSING","message":"이전 요청을 처리하고 있어요."}
					"""));

		assertThatThrownBy(() -> adapter.confirmPayment("payment-key-123", "reservation-order-123", 100_000))
			.isInstanceOf(ResourceAccessException.class)
			.hasMessageContaining("IDEMPOTENT_REQUEST_PROCESSING");

		server.verify();
	}

	@Test
	@DisplayName("처리 중인 결제 승인은 같은 멱등키로 재시도한 뒤 성공한다")
	void retriesConfirmationInProgressWithStableIdempotencyKey() {
		String orderId = "reservation-order-123";
		for (int attempt = 0; attempt < 2; attempt++) {
			server.expect(requestTo("https://api.example.com/v1/payments/confirm"))
				.andExpect(header("Idempotency-Key", "airbob-confirm-" + orderId))
				.andRespond(withStatus(HttpStatus.CONFLICT)
					.contentType(MediaType.APPLICATION_JSON)
					.body("""
						{"code":"IDEMPOTENT_REQUEST_PROCESSING","message":"이전 요청을 처리하고 있어요."}
						"""));
		}
		server.expect(requestTo("https://api.example.com/v1/payments/confirm"))
			.andExpect(header("Idempotency-Key", "airbob-confirm-" + orderId))
			.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.register(RetryProxyConfig.class);
			context.registerBean(TossPaymentsAdapter.class, () -> adapter);
			context.registerBean(Sleeper.class, () -> backOffPeriod -> { });
			context.refresh();

			TossPaymentsAdapter retryingAdapter = context.getBean(TossPaymentsAdapter.class);
			assertThat(retryingAdapter.confirmPayment("payment-key-123", orderId, 100_000))
				.isNotNull();
		}
		server.verify();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableRetry
	static class RetryProxyConfig {
	}

	@Test
	@DisplayName("처리 중인 결제 취소 멱등 요청은 최종 실패로 확정하지 않는다")
	void cancellationInProgressIsRetryable() {
		server.expect(requestTo("https://api.example.com/v1/payments/payment-key-123/cancel"))
			.andRespond(withStatus(HttpStatus.CONFLICT)
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
					{"code":"IDEMPOTENT_REQUEST_PROCESSING","message":"이전 요청을 처리하고 있어요."}
					"""));

		assertThatThrownBy(() -> adapter.cancelPayment("payment-key-123", "사용자 요청", null))
			.isInstanceOf(ResourceAccessException.class)
			.hasMessageContaining("IDEMPOTENT_REQUEST_PROCESSING");

		server.verify();
	}

	@Test
	@DisplayName("토스의 최상위 오류 코드를 결제 승인 오류로 매핑한다")
	void mapsTopLevelConfirmationErrorCode() {
		server.expect(requestTo("https://api.example.com/v1/payments/confirm"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
					{"code":"INVALID_REQUEST","message":"잘못된 요청입니다."}
					"""));

		assertThatThrownBy(() -> adapter.confirmPayment("payment-key-123", "reservation-order-123", 100_000))
			.isInstanceOfSatisfying(TossPaymentException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(PaymentConfirmErrorCode.INVALID_REQUEST));

		server.verify();
	}

	@Nested
	@DisplayName("상수 값 검증 테스트")
	class ConstantsTest {

		@Test
		@DisplayName("VALID_HOURS_VALUE는 24이다")
		void VALID_HOURS_VALUE_검증() {
			assertThat(TossPaymentsAdapter.VALID_HOURS_VALUE).isEqualTo(24);
		}

		@Test
		@DisplayName("CONFIRM_PATH가 올바르게 설정되어 있다")
		void CONFIRM_PATH_검증() {
			assertThat(TossPaymentsAdapter.CONFIRM_PATH).isEqualTo("/v1/payments/confirm");
		}

		@Test
		@DisplayName("CANCEL_PATH가 올바르게 설정되어 있다")
		void CANCEL_PATH_검증() {
			assertThat(TossPaymentsAdapter.CANCEL_PATH).isEqualTo("/v1/payments/{paymentKey}/cancel");
		}

		@Test
		@DisplayName("VIRTUAL_ACCOUNTS_PATH가 올바르게 설정되어 있다")
		void VIRTUAL_ACCOUNTS_PATH_검증() {
			assertThat(TossPaymentsAdapter.VIRTUAL_ACCOUNTS_PATH).isEqualTo("/v1/virtual-accounts");
		}

		@Test
		@DisplayName("GET_PATH_BY_PAYMENT_KEY가 올바르게 설정되어 있다")
		void GET_PATH_BY_PAYMENT_KEY_검증() {
			assertThat(TossPaymentsAdapter.GET_PATH_BY_PAYMENT_KEY).isEqualTo("/v1/payments/{paymentKey}");
		}

		@Test
		@DisplayName("GET_PATH_BY_ORDER_ID가 올바르게 설정되어 있다")
		void GET_PATH_BY_ORDER_ID_검증() {
			assertThat(TossPaymentsAdapter.GET_PATH_BY_ORDER_ID).isEqualTo("/v1/payments/orders/{orderId}");
		}

		@Test
		@DisplayName("PAYMENT_KEY 상수가 올바르게 설정되어 있다")
		void PAYMENT_KEY_검증() {
			assertThat(TossPaymentsAdapter.PAYMENT_KEY).isEqualTo("paymentKey");
		}

		@Test
		@DisplayName("ORDER_ID 상수가 올바르게 설정되어 있다")
		void ORDER_ID_검증() {
			assertThat(TossPaymentsAdapter.ORDER_ID).isEqualTo("orderId");
		}

		@Test
		@DisplayName("AMOUNT 상수가 올바르게 설정되어 있다")
		void AMOUNT_검증() {
			assertThat(TossPaymentsAdapter.AMOUNT).isEqualTo("amount");
		}

		@Test
		@DisplayName("CANCEL_REASON 상수가 올바르게 설정되어 있다")
		void CANCEL_REASON_검증() {
			assertThat(TossPaymentsAdapter.CANCEL_REASON).isEqualTo("cancelReason");
		}

		@Test
		@DisplayName("CANCEL_AMOUNT 상수가 올바르게 설정되어 있다")
		void CANCEL_AMOUNT_검증() {
			assertThat(TossPaymentsAdapter.CANCEL_AMOUNT).isEqualTo("cancelAmount");
		}

		@Test
		@DisplayName("BANK 상수가 올바르게 설정되어 있다")
		void BANK_검증() {
			assertThat(TossPaymentsAdapter.BANK).isEqualTo("bank");
		}

		@Test
		@DisplayName("CUSTOMER_NAME 상수가 올바르게 설정되어 있다")
		void CUSTOMER_NAME_검증() {
			assertThat(TossPaymentsAdapter.CUSTOMER_NAME).isEqualTo("customerName");
		}

		@Test
		@DisplayName("VALID_HOURS 상수가 올바르게 설정되어 있다")
		void VALID_HOURS_검증() {
			assertThat(TossPaymentsAdapter.VALID_HOURS).isEqualTo("validHours");
		}

		@Test
		@DisplayName("UNKNOWN_ERROR 상수가 올바르게 설정되어 있다")
		void UNKNOWN_ERROR_검증() {
			assertThat(TossPaymentsAdapter.UNKNOWN_ERROR).isEqualTo("UNKNOWN_ERROR");
		}

		@Test
		@DisplayName("TOSS_API_SERVER_ERROR 상수가 올바르게 설정되어 있다")
		void TOSS_API_SERVER_ERROR_검증() {
			assertThat(TossPaymentsAdapter.TOSS_API_SERVER_ERROR).isEqualTo("토스 페이먼츠 API 서버 에러: ");
		}
	}
}
