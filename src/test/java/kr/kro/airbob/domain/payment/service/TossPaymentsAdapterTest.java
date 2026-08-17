package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationCommand;
import kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationFailureClassifier;
import kr.kro.airbob.domain.payment.service.gateway.PaymentGatewayResult;

@DisplayName("TossPaymentsAdapter tests")
class TossPaymentsAdapterTest {

	private static final String BASE_URL = "https://api.example.com";
	private static final String CONFIRM_URL = BASE_URL + "/v1/payments/confirm";
	private static final String INQUIRY_URL = BASE_URL + "/v1/payments/pk_test";
	private static final UUID OPERATION_UID = UUID.fromString("4dc96ec8-d45f-4688-bb75-560c71b88d5d");
	private static final String ORDER_ID = "5250ea1b-df85-46f4-a266-d1f34d4f2de9";
	private static final String PROVIDER_IDEMPOTENCY_KEY = "airbob-confirm-" + OPERATION_UID;

	private MockRestServiceServer server;
	private RestClient.Builder builder;
	private TossPaymentsAdapter adapter;

	@BeforeEach
	void setUp() {
		builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		adapter = adapter(builder.build());
	}

	@Test
	@DisplayName("비활성화된 토스 결제 어댑터는 어떤 요청도 전송하지 않는다")
	void disabledAdapterRejectsEveryExternalOperationBeforeSendingARequest() {
		TossPaymentsAdapter disabledAdapter = adapter(builder.build(), false);

		assertTossPaymentsDisabled(() -> disabledAdapter.confirm(command()));
		assertTossPaymentsDisabled(() -> disabledAdapter.inquire(command()));
		assertTossPaymentsDisabled(() -> disabledAdapter.confirmPayment("payment-key", "order-id", 1));
		assertTossPaymentsDisabled(() -> disabledAdapter.cancelPayment("payment-key", "cancel", null));
		assertTossPaymentsDisabled(() -> disabledAdapter.getPaymentByPaymentKey("payment-key"));
		assertTossPaymentsDisabled(() -> disabledAdapter.getPaymentByOrderId("order-id"));
		assertTossPaymentsDisabled(() -> disabledAdapter.issueVirtualAccount(null, "20", "guest"));

		server.verify();
	}

	@Test
	@DisplayName("Spring 설정의 토스 결제 비활성화 값이 관리 빈에 적용된다")
	void springManagedAdapterUsesDisabledPropertyBeforeSendingARequest() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().getPropertySources().addFirst(
				new MapPropertySource("payment-guard-test", Map.of("payment.toss.enabled", false))
			);
			context.registerBean("tossPaymentRestClient", RestClient.class, builder::build);
			context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
			context.registerBean(PaymentConfirmationFailureClassifier.class,
				PaymentConfirmationFailureClassifier::new);
			context.register(TossPaymentsAdapter.class);
			context.refresh();

			assertTossPaymentsDisabled(() -> context.getBean(TossPaymentsAdapter.class).confirm(command()));
		}

		server.verify();
	}

	private void assertTossPaymentsDisabled(ThrowableAssert.ThrowingCallable invocation) {
		assertThatThrownBy(invocation)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Toss Payments is disabled");
	}

	@Test
	void confirmationUsesOperationIdempotencyKeyAndReturnsNormalizedPayment() {
		server.expect(requestTo(CONFIRM_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Idempotency-Key", PROVIDER_IDEMPOTENCY_KEY))
			.andExpect(content().json("""
				{"paymentKey":"pk_test","orderId":"%s","amount":100000}
				""".formatted(ORDER_ID)))
			.andRespond(withSuccess(successBody("DONE"), MediaType.APPLICATION_JSON));

		PaymentGatewayResult result = adapter.confirm(command());

		assertThat(result).isEqualTo(new PaymentGatewayResult.Approved(new ConfirmedPayment(
			"pk_test",
			ORDER_ID,
			100_000L,
			100_000L,
			PaymentMethod.CARD,
			PaymentStatus.DONE,
			Instant.parse("2026-08-14T03:34:56Z"),
			new ConfirmedPayment.VirtualAccountDetails(
				"088",
				"1234567890",
				"홍길동",
				Instant.parse("2026-08-15T06:00:00Z")
			)
		)));
		server.verify();
	}

	@Test
	void allowListedConfirmationFailureIsDeclinedWithoutProviderMessage() {
		server.expect(requestTo(CONFIRM_URL))
			.andRespond(withStatus(HttpStatus.FORBIDDEN)
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
					{"code":"REJECT_CARD_PAYMENT","message":"provider account detail"}
					"""));

		PaymentGatewayResult result = adapter.confirm(command());

		assertThat(result).isInstanceOfSatisfying(PaymentGatewayResult.Declined.class, declined -> {
			assertThat(declined.code()).isEqualTo("REJECT_CARD_PAYMENT");
			assertThat(declined.message()).doesNotContain("provider account detail");
		});
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = {"ALREADY_PROCESSED_PAYMENT", "SOMETHING_NEW"})
	void ambiguousConfirmation4xxRequiresInquiry(String code) {
		server.expect(requestTo(CONFIRM_URL))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
					{"code":"%s","message":"provider detail"}
					""".formatted(code)));

		PaymentGatewayResult result = adapter.confirm(command());

		assertThat(result).isInstanceOf(PaymentGatewayResult.OutcomeUnknown.class);
		server.verify();
	}

	@Test
	void confirmation5xxRequiresInquiry() {
		server.expect(requestTo(CONFIRM_URL))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
					{"code":"PROVIDER_ERROR","message":"provider infrastructure detail"}
					"""));

		PaymentGatewayResult result = adapter.confirm(command());

		assertThat(result).isInstanceOfSatisfying(PaymentGatewayResult.OutcomeUnknown.class, unknown ->
			assertThat(unknown.message()).doesNotContain("provider infrastructure detail"));
		server.verify();
	}

	@Test
	void malformedConfirmationResponseRequiresInquiry() {
		server.expect(requestTo(CONFIRM_URL))
			.andRespond(withSuccess("{not-json", MediaType.APPLICATION_JSON));

		assertThat(adapter.confirm(command())).isInstanceOf(PaymentGatewayResult.OutcomeUnknown.class);
		server.verify();
	}

	@Test
	void mismatchedConfirmationResponseRequiresInquiry() {
		server.expect(requestTo(CONFIRM_URL))
			.andRespond(withSuccess(successBody("DONE").replace("pk_test", "different_payment"),
				MediaType.APPLICATION_JSON));

		assertThat(adapter.confirm(command())).isInstanceOf(PaymentGatewayResult.OutcomeUnknown.class);
		server.verify();
	}

	@Test
	void connectFailureIsRetryable() {
		TossPaymentsAdapter connectFailingAdapter = adapterThatFailsWith(new ConnectException("connection refused"));

		PaymentGatewayResult result = connectFailingAdapter.confirm(command());

		assertThat(result).isInstanceOf(PaymentGatewayResult.RetryableFailure.class);
	}

	@Test
	void connectTimeoutIsRetryable() {
		TossPaymentsAdapter connectTimeoutAdapter =
			adapterThatFailsWith(new HttpConnectTimeoutException("connect timed out"));

		PaymentGatewayResult result = connectTimeoutAdapter.confirm(command());

		assertThat(result).isInstanceOf(PaymentGatewayResult.RetryableFailure.class);
	}

	@Test
	void readTimeoutRequiresInquiry() {
		TossPaymentsAdapter timeoutAdapter = adapterThatFailsWith(new SocketTimeoutException("read timed out"));

		PaymentGatewayResult result = timeoutAdapter.confirm(command());

		assertThat(result).isInstanceOf(PaymentGatewayResult.OutcomeUnknown.class);
	}

	@Test
	void responseLossRequiresInquiry() {
		TossPaymentsAdapter responseLosingAdapter = adapterThatFailsWith(new IOException("connection reset"));

		PaymentGatewayResult result = responseLosingAdapter.confirm(command());

		assertThat(result).isInstanceOf(PaymentGatewayResult.OutcomeUnknown.class);
	}

	@Test
	void inquiry404IsNotFound() {
		server.expect(requestTo(INQUIRY_URL))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withStatus(HttpStatus.NOT_FOUND)
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
					{"code":"NOT_FOUND_PAYMENT","message":"provider detail"}
					"""));

		PaymentGatewayResult result = adapter.inquire(command());

		assertThat(result).isInstanceOfSatisfying(PaymentGatewayResult.NotFound.class, notFound -> {
			assertThat(notFound.code()).isEqualTo("NOT_FOUND_PAYMENT");
			assertThat(notFound.message()).doesNotContain("provider detail");
		});
		server.verify();
	}

	@Test
	void inquiryDoneIsApproved() {
		server.expect(requestTo(INQUIRY_URL))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(successBody("DONE"), MediaType.APPLICATION_JSON));

		assertThat(adapter.inquire(command())).isInstanceOf(PaymentGatewayResult.Approved.class);
		server.verify();
	}

	@Test
	void mismatchedInquiryResponseRequiresInquiry() {
		server.expect(requestTo(INQUIRY_URL))
			.andRespond(withSuccess(successBody("CANCELED").replace("pk_test", "different_payment"),
				MediaType.APPLICATION_JSON));

		assertThat(adapter.inquire(command())).isInstanceOf(PaymentGatewayResult.OutcomeUnknown.class);
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = {"ABORTED", "EXPIRED", "CANCELED"})
	void inquiryTerminalFailureIsDeclined(String status) {
		server.expect(requestTo(INQUIRY_URL))
			.andRespond(withSuccess(successBody(status), MediaType.APPLICATION_JSON));

		assertThat(adapter.inquire(command())).isInstanceOf(PaymentGatewayResult.Declined.class);
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = {"READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT", "PARTIAL_CANCELED", "NEW_STATUS"})
	void inquiryUnresolvedOrUnknownStatusRequiresInquiry(String status) {
		server.expect(requestTo(INQUIRY_URL))
			.andRespond(withSuccess(successBody(status), MediaType.APPLICATION_JSON));

		assertThat(adapter.inquire(command())).isInstanceOf(PaymentGatewayResult.OutcomeUnknown.class);
		server.verify();
	}

	@Test
	void cancellationUsesStablePaymentKeyIdempotencyKey() {
		server.expect(requestTo(BASE_URL + "/v1/payments/payment-key-123/cancel"))
			.andExpect(header("Idempotency-Key", "airbob-cancel-payment-key-123"))
			.andRespond(withSuccess(
				"{\"status\":\"CANCELED\",\"balanceAmount\":0}",
				MediaType.APPLICATION_JSON
			));

		adapter.cancelPayment("payment-key-123", "사용자 요청", null);

		server.verify();
	}

	@Test
	void cancellationInProgressRemainsRetryable() {
		server.expect(requestTo(BASE_URL + "/v1/payments/payment-key-123/cancel"))
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

	private TossPaymentsAdapter adapter(RestClient restClient) {
		return adapter(restClient, true);
	}

	private TossPaymentsAdapter adapter(RestClient restClient, boolean enabled) {
		return new TossPaymentsAdapter(
			restClient,
			new ObjectMapper(),
			new PaymentConfirmationFailureClassifier(),
			enabled
		);
	}

	private TossPaymentsAdapter adapterThatFailsWith(IOException failure) {
		RestClient restClient = RestClient.builder()
			.baseUrl(BASE_URL)
			.requestFactory((uri, httpMethod) -> {
				throw failure;
			})
			.build();
		return adapter(restClient);
	}

	private PaymentConfirmationCommand command() {
		return new PaymentConfirmationCommand(
			OPERATION_UID,
			"pk_test",
			ORDER_ID,
			100_000L,
			PROVIDER_IDEMPOTENCY_KEY
		);
	}

	private String successBody(String status) {
		return """
			{
			  "paymentKey":"pk_test",
			  "orderId":"%s",
			  "totalAmount":100000,
			  "balanceAmount":100000,
			  "method":"카드",
			  "status":"%s",
			  "approvedAt":"2026-08-14T12:34:56+09:00",
			  "virtualAccount":{
			    "bankCode":"088",
			    "accountNumber":"1234567890",
			    "customerName":"홍길동",
			    "dueDate":"2026-08-15T15:00:00+09:00"
			  }
			}
			""".formatted(ORDER_ID, status);
	}
}
