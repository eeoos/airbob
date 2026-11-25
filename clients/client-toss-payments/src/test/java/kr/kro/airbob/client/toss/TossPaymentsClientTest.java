package kr.kro.airbob.client.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.ObjectMapper;

@DisplayName("Toss Payments client")
class TossPaymentsClientTest {

	private static final String BASE_URL = "https://api.example.com";

	private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final TossPaymentsClient client = new TossPaymentsClient(builder.build(), new ObjectMapper());

	@Test
	void confirmPostsTheIdempotentCommandAndParsesTheResponse() {
		server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Idempotency-Key", "confirm-key"))
			.andExpect(content().json("{\"paymentKey\":\"pk_test\",\"orderId\":\"order-1\",\"amount\":100000}", true))
			.andRespond(withSuccess("""
				{"paymentKey":"pk_test","orderId":"order-1","totalAmount":100000,"balanceAmount":100000,
				 "method":"카드","status":"DONE","approvedAt":"2026-11-22T10:15:30+09:00","unknownField":1}
				""", MediaType.APPLICATION_JSON));

		TossPaymentResponse response = client.confirm("pk_test", "order-1", 100_000L, "confirm-key");

		assertThat(response.getPaymentKey()).isEqualTo("pk_test");
		assertThat(response.getTotalAmount()).isEqualTo(100_000L);
		assertThat(response.getStatus()).isEqualTo("DONE");
		assertThat(response.getApprovedAt()).isEqualTo(ZonedDateTime.parse("2026-11-22T10:15:30+09:00"));
		server.verify();
	}

	@Test
	void cancelPostsToThePaymentWithTheIdempotencyKey() {
		server.expect(requestTo(BASE_URL + "/v1/payments/pk_test/cancel"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Idempotency-Key", "cancel-key"))
			.andExpect(content().json("{\"cancelReason\":\"guest request\",\"cancelAmount\":5000}", true))
			.andRespond(
				withSuccess("{\"paymentKey\":\"pk_test\",\"status\":\"CANCELED\"}", MediaType.APPLICATION_JSON));

		assertThat(client.cancel("pk_test", "guest request", 5_000L, "cancel-key").getStatus())
			.isEqualTo("CANCELED");
		server.verify();
	}

	@Test
	void getPaymentReadsThePaymentByKey() {
		server.expect(requestTo(BASE_URL + "/v1/payments/pk_test"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"paymentKey\":\"pk_test\",\"status\":\"DONE\"}", MediaType.APPLICATION_JSON));

		assertThat(client.getPayment("pk_test").getStatus()).isEqualTo("DONE");
		server.verify();
	}

	@Test
	void errorStatusCarriesTheTopLevelProviderCode() {
		server.expect(requestTo(BASE_URL + "/v1/payments/pk_test"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"provider detail\"}"));

		assertThatThrownBy(() -> client.getPayment("pk_test"))
			.isInstanceOfSatisfying(TossPaymentsApiException.class, failure -> {
				assertThat(failure.statusCode()).isEqualTo(404);
				assertThat(failure.code()).isEqualTo("NOT_FOUND_PAYMENT");
				assertThat(failure).hasMessage(null);
			});
	}

	@Test
	void errorStatusFallsBackToTheFailureCodeAndThenToUnknown() {
		server.expect(requestTo(BASE_URL + "/v1/payments/pk_test"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"failure\":{\"code\":\"REJECT_CARD_PAYMENT\"}}"));
		server.expect(requestTo(BASE_URL + "/v1/payments/pk_test"))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
				.body("{}"));

		assertThatThrownBy(() -> client.getPayment("pk_test"))
			.isInstanceOfSatisfying(TossPaymentsApiException.class,
				failure -> assertThat(failure.code()).isEqualTo("REJECT_CARD_PAYMENT"));
		assertThatThrownBy(() -> client.getPayment("pk_test"))
			.isInstanceOfSatisfying(TossPaymentsApiException.class, failure -> {
				assertThat(failure.statusCode()).isEqualTo(500);
				assertThat(failure.code()).isEqualTo(TossPaymentsApiException.UNKNOWN_ERROR);
			});
	}

	@Test
	void malformedErrorBodyIsAParsingFailureWithoutTheRawBody() {
		String rawSecret = "superSecretPaymentKey123";
		server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(rawSecret));

		assertThatThrownBy(() -> client.confirm("pk_test", "order-1", 1L, "key"))
			.isInstanceOf(TossPaymentsResponseParsingException.class)
			.hasMessageNotContaining(rawSecret);
	}

	@Test
	void transportFailureKeepsTheCauseChainForClassification() {
		RestClient failing = RestClient.builder()
			.baseUrl(BASE_URL)
			.requestFactory((uri, httpMethod) -> {
				throw new ConnectException("connection refused");
			})
			.build();

		assertThatThrownBy(() -> new TossPaymentsClient(failing, new ObjectMapper()).getPayment("pk_test"))
			.isInstanceOf(RestClientException.class)
			.hasRootCauseInstanceOf(ConnectException.class);
	}

	@Test
	void createConfiguresBaseUrlBasicAuthAndTimeouts() {
		TossPaymentsClient created = TossPaymentsClient.create(
			BASE_URL, "test_sk", Duration.ofSeconds(2), Duration.ofSeconds(10));

		RestClient restClient = (RestClient)ReflectionTestUtils.getField(created, "restClient");
		Object requestFactory = ReflectionTestUtils.getField(restClient, "clientRequestFactory");
		assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);
		HttpClient httpClient = (HttpClient)ReflectionTestUtils.getField(requestFactory, "httpClient");
		assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(2));
		assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(Duration.ofSeconds(10));

		HttpHeaders defaultHeaders = (HttpHeaders)ReflectionTestUtils.getField(restClient, "defaultHeaders");
		// Base64("test_sk:")
		assertThat(defaultHeaders.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Basic dGVzdF9zazo=");
		assertThat(defaultHeaders.getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
	}
}
