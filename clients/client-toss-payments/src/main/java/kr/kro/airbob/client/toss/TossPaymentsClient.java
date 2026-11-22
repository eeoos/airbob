package kr.kro.airbob.client.toss;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP transport for the Toss Payments core API (confirm, cancel, payment lookup).
 *
 * <p>It does not interpret outcomes. Callers classify them:
 * <ul>
 *   <li>4xx/5xx: {@link TossPaymentsApiException} with the status and the provider error code
 *   ({@code code}, else {@code failure.code}, else {@link TossPaymentsApiException#UNKNOWN_ERROR}).</li>
 *   <li>Unreadable or malformed error body: {@link TossPaymentsResponseParsingException}.</li>
 *   <li>Transport failures and unreadable success bodies: Spring's
 *   {@link org.springframework.web.client.RestClientException}, with the original cause chain
 *   (for example {@link java.net.ConnectException}) intact.</li>
 * </ul>
 */
public class TossPaymentsClient {

	private static final Logger log = LoggerFactory.getLogger(TossPaymentsClient.class);

	static final String CONFIRM_PATH = "/v1/payments/confirm";
	static final String CANCEL_PATH = "/v1/payments/{paymentKey}/cancel";
	static final String PAYMENT_PATH = "/v1/payments/{paymentKey}";
	static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
	private static final String BASIC_DELIMITER = ":";
	private static final String AUTH_HEADER_PREFIX = "Basic ";

	private final RestClient restClient;
	private final ObjectMapper objectMapper;

	public TossPaymentsClient(RestClient restClient, ObjectMapper objectMapper) {
		this.restClient = restClient;
		this.objectMapper = objectMapper;
	}

	public static TossPaymentsClient create(
		String baseUrl,
		String secretKey,
		Duration connectTimeout,
		Duration readTimeout
	) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(connectTimeout)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);

		RestClient restClient = RestClient.builder()
			.requestFactory(requestFactory)
			.baseUrl(baseUrl)
			.defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(secretKey))
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.build();
		return new TossPaymentsClient(restClient, new ObjectMapper());
	}

	public TossPaymentResponse confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("paymentKey", paymentKey);
		payload.put("orderId", orderId);
		payload.put("amount", amount);

		return restClient.post()
			.uri(CONFIRM_PATH)
			.header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
			.body(payload)
			.retrieve()
			.onStatus(HttpStatusCode::isError, (request, response) -> {
				throw apiException(response);
			})
			.body(TossPaymentResponse.class);
	}

	public TossPaymentResponse cancel(
		String paymentKey,
		String cancelReason,
		long cancelAmount,
		String idempotencyKey
	) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("cancelReason", cancelReason);
		payload.put("cancelAmount", cancelAmount);

		return restClient.post()
			.uri(CANCEL_PATH, paymentKey)
			.header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
			.body(payload)
			.retrieve()
			.onStatus(HttpStatusCode::isError, (request, response) -> {
				throw apiException(response);
			})
			.body(TossPaymentResponse.class);
	}

	public TossPaymentResponse getPayment(String paymentKey) {
		return restClient.get()
			.uri(PAYMENT_PATH, paymentKey)
			.retrieve()
			.onStatus(HttpStatusCode::isError, (request, response) -> {
				throw apiException(response);
			})
			.body(TossPaymentResponse.class);
	}

	private TossPaymentsApiException apiException(ClientHttpResponse response) throws IOException {
		return new TossPaymentsApiException(response.getStatusCode().value(), readErrorCode(response));
	}

	private String readErrorCode(ClientHttpResponse response) {
		String body;
		try {
			body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.error("토스페이먼츠 에러 응답 읽기 실패: type={}", e.getClass().getSimpleName());
			throw new TossPaymentsResponseParsingException();
		}
		try {
			JsonNode root = objectMapper.readTree(body);
			String topLevelCode = root.path("code").asText(null);
			if (topLevelCode != null) {
				return topLevelCode;
			}
			return root.path("failure").path("code").asText(TossPaymentsApiException.UNKNOWN_ERROR);
		} catch (JsonProcessingException e) {
			log.error("토스페이먼츠 에러 응답 파싱 실패: type={}", e.getClass().getSimpleName());
			throw new TossPaymentsResponseParsingException();
		}
	}

	private static String basicAuth(String secretKey) {
		String encoded = Base64.getEncoder()
			.encodeToString((secretKey + BASIC_DELIMITER).getBytes(StandardCharsets.UTF_8));
		return AUTH_HEADER_PREFIX + encoded;
	}
}
