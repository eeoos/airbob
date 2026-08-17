package kr.kro.airbob.domain.payment.service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.exception.TossPaymentResponseParsingException;
import kr.kro.airbob.domain.payment.exception.code.PaymentCancelErrorCode;
import kr.kro.airbob.domain.payment.exception.code.PaymentInquiryErrorCode;
import kr.kro.airbob.domain.payment.exception.code.VirtualAccountIssueErrorCode;
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationCommand;
import kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationFailureClassifier;
import kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationGateway;
import kr.kro.airbob.domain.payment.service.gateway.PaymentGatewayResult;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class TossPaymentsAdapter implements PaymentConfirmationGateway {

	public static final String PAYMENT_KEY = "paymentKey";
	public static final String ORDER_ID = "orderId";
	public static final String AMOUNT = "amount";
	public static final String CANCEL_REASON = "cancelReason";
	public static final String BANK = "bank";
	public static final String CUSTOMER_NAME = "customerName";
	public static final int VALID_HOURS_VALUE = 24;
	public static final String UNKNOWN_ERROR = "UNKNOWN_ERROR";
	public static final String IDEMPOTENT_REQUEST_PROCESSING = "IDEMPOTENT_REQUEST_PROCESSING";
	public static final String PARSING_FAILED_CODE = "PARSING_FAILED";
	public static final String CONFIRM_PATH = "/v1/payments/confirm";
	public static final String CANCEL_PATH = "/v1/payments/{paymentKey}/cancel";
	public static final String CANCEL_AMOUNT = "cancelAmount";
	public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
	public static final String CANCELLATION_IDEMPOTENCY_KEY_PREFIX = "airbob-cancel-";
	public static final String GET_PATH_BY_PAYMENT_KEY = "/v1/payments/{paymentKey}";
	public static final String GET_PATH_BY_ORDER_ID = "/v1/payments/orders/{orderId}";
	public static final String VALID_HOURS = "validHours";
	public static final String VIRTUAL_ACCOUNTS_PATH = "/v1/virtual-accounts";
	public static final String TOSS_API_SERVER_ERROR = "토스 페이먼츠 API 서버 에러: ";
	private static final String SAFE_UNKNOWN_MESSAGE = "결제 결과를 확인하고 있습니다.";
	private static final String SAFE_RETRYABLE_MESSAGE = "결제 서비스에 연결할 수 없어 다시 시도합니다.";
	private static final String SAFE_NOT_FOUND_MESSAGE = "결제 정보를 찾을 수 없습니다.";
	private static final String SAFE_TERMINAL_MESSAGE = "결제가 완료되지 않았습니다.";

	private final RestClient tossPaymentsRestClient;
	private final ObjectMapper objectMapper;
	private final PaymentConfirmationFailureClassifier confirmationFailureClassifier;

	public TossPaymentsAdapter(
		@Qualifier("tossPaymentRestClient") RestClient tossPaymentsRestClient,
		ObjectMapper objectMapper,
		PaymentConfirmationFailureClassifier confirmationFailureClassifier
	) {
		this.tossPaymentsRestClient = tossPaymentsRestClient;
		this.objectMapper = objectMapper;
		this.confirmationFailureClassifier = confirmationFailureClassifier;
	}

	@Override
	public PaymentGatewayResult confirm(PaymentConfirmationCommand command) {
		Map<String, Object> payload = new HashMap<>();
		payload.put(PAYMENT_KEY, command.paymentKey());
		payload.put(ORDER_ID, command.orderId());
		payload.put(AMOUNT, command.amount());

		try {
			TossPaymentResponse response = tossPaymentsRestClient.post()
				.uri(CONFIRM_PATH)
				.header(IDEMPOTENCY_KEY_HEADER, command.providerIdempotencyKey())
				.body(payload)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
					throw providerHttpException(httpResponse);
				})
				.body(TossPaymentResponse.class);

			return approvedOrUnknown(command, response);
		} catch (ProviderHttpException exception) {
			if (exception.statusCode() >= 500) {
				return outcomeUnknown("PROVIDER_ERROR");
			}
			return confirmationFailureClassifier.classify(exception.code(), null);
		} catch (TossPaymentResponseParsingException | RestClientException exception) {
			return classifyTransportFailure(exception);
		}
	}

	@Override
	public PaymentGatewayResult inquire(PaymentConfirmationCommand command) {
		try {
			TossPaymentResponse response = tossPaymentsRestClient.get()
				.uri(GET_PATH_BY_PAYMENT_KEY, command.paymentKey())
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
					throw providerHttpException(httpResponse);
				})
				.body(TossPaymentResponse.class);

			if (!isCorrelated(command, response)) {
				return outcomeUnknown("PAYMENT_RESPONSE_MISMATCH");
			}
			PaymentStatus status = knownStatus(response);
			if (status == null) {
				return outcomeUnknown("UNRECOGNIZED_PAYMENT_STATUS");
			}
			return switch (status) {
				case DONE -> approvedOrUnknown(command, response);
				case ABORTED, EXPIRED, CANCELED ->
					new PaymentGatewayResult.Declined(status.name(), SAFE_TERMINAL_MESSAGE);
				default -> outcomeUnknown(status.name());
			};
		} catch (ProviderHttpException exception) {
			if (exception.statusCode() == 404) {
				return new PaymentGatewayResult.NotFound(exception.code(), SAFE_NOT_FOUND_MESSAGE);
			}
			return outcomeUnknown(exception.code());
		} catch (TossPaymentResponseParsingException | RestClientException exception) {
			return classifyTransportFailure(exception);
		}
	}

	private PaymentGatewayResult approvedOrUnknown(
		PaymentConfirmationCommand command,
		TossPaymentResponse response
	) {
		if (!isCorrelated(command, response) || knownStatus(response) != PaymentStatus.DONE) {
			return outcomeUnknown("PAYMENT_RESPONSE_MISMATCH");
		}

		ConfirmedPayment confirmedPayment = normalize(response);
		if (confirmedPayment == null) {
			return outcomeUnknown("INCOMPLETE_PAYMENT_RESPONSE");
		}
		return new PaymentGatewayResult.Approved(confirmedPayment);
	}

	private boolean isCorrelated(PaymentConfirmationCommand command, TossPaymentResponse response) {
		return response != null
			&& Objects.equals(command.paymentKey(), response.getPaymentKey())
			&& Objects.equals(command.orderId(), response.getOrderId())
			&& response.getTotalAmount() != null
			&& command.amount() == response.getTotalAmount();
	}

	private ConfirmedPayment normalize(TossPaymentResponse response) {
		PaymentStatus status = knownStatus(response);
		if (response.getTotalAmount() == null
			|| response.getBalanceAmount() == null
			|| response.getMethod() == null
			|| response.getApprovedAt() == null
			|| status == null) {
			return null;
		}

		return new ConfirmedPayment(
			response.getPaymentKey(),
			response.getOrderId(),
			response.getTotalAmount(),
			response.getBalanceAmount(),
			PaymentMethod.fromDescription(response.getMethod()),
			status,
			response.getApprovedAt().toInstant(),
			normalize(response.getVirtualAccount())
		);
	}

	private ConfirmedPayment.VirtualAccountDetails normalize(TossPaymentResponse.VirtualAccount virtualAccount) {
		if (virtualAccount == null) {
			return null;
		}
		Instant dueDate = virtualAccount.getDueDate() == null ? null : virtualAccount.getDueDate().toInstant();
		return new ConfirmedPayment.VirtualAccountDetails(
			virtualAccount.getBankCode(),
			virtualAccount.getAccountNumber(),
			virtualAccount.getCustomerName(),
			dueDate
		);
	}

	private PaymentStatus knownStatus(TossPaymentResponse response) {
		if (response == null || response.getStatus() == null) {
			return null;
		}
		try {
			return PaymentStatus.from(response.getStatus());
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private PaymentGatewayResult classifyTransportFailure(Throwable exception) {
		if (hasCause(exception, ConnectException.class)
			|| hasCause(exception, HttpConnectTimeoutException.class)) {
			return new PaymentGatewayResult.RetryableFailure("CONNECTION_FAILED", SAFE_RETRYABLE_MESSAGE);
		}
		return outcomeUnknown("TRANSPORT_OUTCOME_UNKNOWN");
	}

	private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
		Throwable current = throwable;
		while (current != null) {
			if (causeType.isInstance(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private PaymentGatewayResult.OutcomeUnknown outcomeUnknown(String code) {
		return new PaymentGatewayResult.OutcomeUnknown(code, SAFE_UNKNOWN_MESSAGE);
	}

	private ProviderHttpException providerHttpException(ClientHttpResponse response) throws IOException {
		return new ProviderHttpException(response.getStatusCode().value(), readErrorCode(response));
	}

	private static final class ProviderHttpException extends RuntimeException {
		private final int statusCode;
		private final String code;

		private ProviderHttpException(int statusCode, String code) {
			this.statusCode = statusCode;
			this.code = code == null || code.isBlank() ? UNKNOWN_ERROR : code;
		}

		private int statusCode() {
			return statusCode;
		}

		private String code() {
			return code;
		}
	}

	@Retryable(
		retryFor = { ResourceAccessException.class },
		maxAttempts = 3,
		backoff = @Backoff(delay = 2000)
	)
	public TossPaymentResponse cancelPayment(String paymentKey, String cancelReason, Long cancelAmount) {
		Map<String, Object> payload = new HashMap<>();
		payload.put(CANCEL_REASON, cancelReason);

		if (cancelAmount != null) {
			payload.put(CANCEL_AMOUNT, cancelAmount);
		}

		return Objects.requireNonNull(
			tossPaymentsRestClient.post()
				.uri(CANCEL_PATH, paymentKey)
				.header(IDEMPOTENCY_KEY_HEADER, CANCELLATION_IDEMPOTENCY_KEY_PREFIX + paymentKey)
				.body(payload)
				.retrieve()
				.onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
					throw new ResourceAccessException(TOSS_API_SERVER_ERROR + response.getStatusCode());
				})
				.onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
					String errorCode = readErrorCode(response);
					throwIfIdempotentRequestIsProcessing(errorCode);
					throw new TossPaymentException(PaymentCancelErrorCode.fromErrorCode(errorCode));
				})
				.toEntity(TossPaymentResponse.class)
				.getBody()
		);
	}

	public TossPaymentResponse getPaymentByPaymentKey(String paymentKey) {
		return getPayment(GET_PATH_BY_PAYMENT_KEY, paymentKey);
	}

	public TossPaymentResponse getPaymentByOrderId(String orderId) {
		return getPayment(GET_PATH_BY_ORDER_ID, orderId);
	}

	// TODO: 도메인 재발급 후 웹훅 구현 필요
	@Retryable(
		retryFor = { ResourceAccessException.class },
		maxAttempts = 3,
		backoff = @Backoff(delay = 2000)
	)
	public TossPaymentResponse issueVirtualAccount(Reservation reservation,String bankCode, String customerName) {
		Map<String, Object> payload = new HashMap<>();
		payload.put(AMOUNT, reservation.getTotalPrice());
		payload.put(ORDER_ID, reservation.getReservationUid().toString());
		payload.put(BANK, bankCode);
		payload.put(CUSTOMER_NAME, customerName);
		payload.put(VALID_HOURS, VALID_HOURS_VALUE); // 24시간으로 제한

		return Objects.requireNonNull(
			tossPaymentsRestClient.post()
				.uri(VIRTUAL_ACCOUNTS_PATH)
				.body(payload)
				.retrieve()
				.onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
					throw new ResourceAccessException(TOSS_API_SERVER_ERROR + response.getStatusCode());
				})
				.onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
					String errorCode = readErrorCode(response);
					throw new TossPaymentException(VirtualAccountIssueErrorCode.fromErrorCode(errorCode));
				})
				.toEntity(TossPaymentResponse.class)
				.getBody()
		);
	}

	@Retryable(
		retryFor = { ResourceAccessException.class },
		maxAttempts = 3,
		backoff = @Backoff(delay = 2000)
	)
	private TossPaymentResponse getPayment(String path, String id) {
		return tossPaymentsRestClient.get()
			.uri(path, id)
			.retrieve()
			.onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
				throw new ResourceAccessException(TOSS_API_SERVER_ERROR + response.getStatusCode());
			})
			.onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
				String errorCode = readErrorCode(response);
				throw new TossPaymentException(PaymentInquiryErrorCode.fromErrorCode(errorCode));
			})
			.body(TossPaymentResponse.class);
	}

	private String readErrorCode(ClientHttpResponse response) {
		try {
			return parseErrorCode(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new TossPaymentResponseParsingException(e, ErrorCode.TOSS_PAYMENT_RESPONSE_PARSING_ERROR);
		}
	}

	private String parseErrorCode(String errorBody) {
		try {
			JsonNode root = objectMapper.readTree(errorBody);
			String topLevelCode = root.path("code").asText(null);
			if (topLevelCode != null) {
				return topLevelCode;
			}

			return root.path("failure").path("code").asText(UNKNOWN_ERROR);
		} catch (JsonProcessingException e) {
			log.error("토스페이먼츠 에러 응답 파싱 실패", e);
			throw new TossPaymentResponseParsingException(e, ErrorCode.TOSS_PAYMENT_RESPONSE_PARSING_ERROR);
		}
	}

	private void throwIfIdempotentRequestIsProcessing(String errorCode) {
		if (IDEMPOTENT_REQUEST_PROCESSING.equals(errorCode)) {
			throw new ResourceAccessException(TOSS_API_SERVER_ERROR + errorCode);
		}
	}
}
