package kr.kro.airbob.domain.payment.service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
import kr.kro.airbob.domain.payment.exception.code.VirtualAccountIssueErrorCode;
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.payment.service.gateway.CancelledPayment;
import kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationFailureClassifier;
import kr.kro.airbob.domain.payment.service.gateway.PaymentGatewayResult;
import kr.kro.airbob.domain.payment.service.gateway.PaymentProviderCommand;
import kr.kro.airbob.domain.payment.service.gateway.PaymentProviderGateway;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class TossPaymentsAdapter implements PaymentProviderGateway {

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
	public static final String GET_PATH_BY_PAYMENT_KEY = "/v1/payments/{paymentKey}";
	public static final String VALID_HOURS = "validHours";
	public static final String VIRTUAL_ACCOUNTS_PATH = "/v1/virtual-accounts";
	public static final String TOSS_API_SERVER_ERROR = "토스 페이먼츠 API 서버 에러: ";
	private static final String SAFE_UNKNOWN_MESSAGE = "결제 결과를 확인하고 있습니다.";
	private static final String SAFE_RETRYABLE_MESSAGE = "결제 서비스에 연결할 수 없어 다시 시도합니다.";
	private static final String SAFE_NOT_FOUND_MESSAGE = "결제 정보를 찾을 수 없습니다.";
	private static final String SAFE_TERMINAL_MESSAGE = "결제가 완료되지 않았습니다.";
	private static final String SAFE_CANCELLATION_DECLINED_MESSAGE = "결제 취소가 거절되었습니다.";
	private static final String SAFE_CANCELLATION_REVIEW_MESSAGE =
		"결제 취소 상태를 운영자가 확인해야 합니다.";
	private static final int TRANSACTION_KEY_MAX_LENGTH = 64;
	private static final Set<String> CANCELLATION_INQUIRY_REQUIRED_CODES = Set.of(
		IDEMPOTENT_REQUEST_PROCESSING,
		"ALREADY_CANCELED_PAYMENT",
		"ALREADY_REFUND_PAYMENT",
		"ALREADY_REFUNDING_PAYMENT",
		"NOT_MATCHES_REFUNDABLE_AMOUNT"
	);
	private static final Set<String> CANCELLATION_RETRYABLE_CODES = Set.of(
		"PROVIDER_ERROR",
		"FORBIDDEN_CONSECUTIVE_REQUEST",
		"NOT_AVAILABLE_BANK"
	);
	private static final Set<String> CANCELLATION_TERMINAL_DECLINE_CODES = Set.of(
		"INVALID_REFUND_ACCOUNT_INFO",
		"EXCEED_CANCEL_AMOUNT_DISCOUNT_AMOUNT",
		"INVALID_REQUEST",
		"INVALID_REFUND_ACCOUNT_NUMBER",
		"INVALID_BANK",
		"REFUND_REJECTED",
		"FORBIDDEN_BANK_REFUND_REQUEST",
		"UNAUTHORIZED_KEY",
		"INCORRECT_BASIC_AUTH_FORMAT",
		"NOT_CANCELABLE_AMOUNT",
		"FORBIDDEN_REQUEST",
		"NOT_CANCELABLE_PAYMENT",
		"EXCEED_MAX_REFUND_DUE",
		"NOT_ALLOWED_PARTIAL_REFUND_WAITING_DEPOSIT",
		"NOT_ALLOWED_PARTIAL_REFUND"
	);

	private final RestClient tossPaymentsRestClient;
	private final ObjectMapper objectMapper;
	private final PaymentConfirmationFailureClassifier confirmationFailureClassifier;
	private final boolean enabled;

	public TossPaymentsAdapter(
		@Qualifier("tossPaymentRestClient") RestClient tossPaymentsRestClient,
		ObjectMapper objectMapper,
		PaymentConfirmationFailureClassifier confirmationFailureClassifier,
		@Value("${payment.toss.enabled:true}") boolean enabled
	) {
		this.tossPaymentsRestClient = tossPaymentsRestClient;
		this.objectMapper = objectMapper;
		this.confirmationFailureClassifier = confirmationFailureClassifier;
		this.enabled = enabled;
	}

	@Override
	public PaymentGatewayResult confirm(PaymentProviderCommand command) {
		requireEnabled();

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
	public PaymentGatewayResult inquireConfirmation(PaymentProviderCommand command) {
		requireEnabled();

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
		PaymentProviderCommand command,
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

	private boolean isCorrelated(PaymentProviderCommand command, TossPaymentResponse response) {
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

	@Override
	public PaymentGatewayResult cancel(PaymentProviderCommand command) {
		requireEnabled();

		Map<String, Object> payload = new HashMap<>();
		payload.put(CANCEL_REASON, command.cancellationReason());
		payload.put(CANCEL_AMOUNT, command.amount());

		try {
			TossPaymentResponse response = tossPaymentsRestClient.post()
				.uri(CANCEL_PATH, command.paymentKey())
				.header(IDEMPOTENCY_KEY_HEADER, command.providerIdempotencyKey())
				.body(payload)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
					throw providerHttpException(httpResponse);
				})
				.body(TossPaymentResponse.class);
			return cancelledOrReview(command, response, false);
		} catch (ProviderHttpException exception) {
			return classifyCancellationFailure(exception);
		} catch (TossPaymentResponseParsingException | RestClientException exception) {
			return classifyTransportFailure(exception);
		}
	}

	private PaymentGatewayResult classifyCancellationFailure(ProviderHttpException exception) {
		String code = exception.code();
		if (exception.statusCode() >= 500
			|| CANCELLATION_INQUIRY_REQUIRED_CODES.contains(code)) {
			return outcomeUnknown(code);
		}
		if (CANCELLATION_RETRYABLE_CODES.contains(code)) {
			return new PaymentGatewayResult.RetryableFailure(code, SAFE_RETRYABLE_MESSAGE);
		}
		if (CANCELLATION_TERMINAL_DECLINE_CODES.contains(code)) {
			return new PaymentGatewayResult.Declined(
				code, SAFE_CANCELLATION_DECLINED_MESSAGE);
		}
		return outcomeUnknown(code);
	}

	@Override
	public PaymentGatewayResult inquireCancellation(PaymentProviderCommand command) {
		requireEnabled();

		try {
			TossPaymentResponse response = tossPaymentsRestClient.get()
				.uri(GET_PATH_BY_PAYMENT_KEY, command.paymentKey())
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
					throw providerHttpException(httpResponse);
				})
				.body(TossPaymentResponse.class);
			return cancelledOrReview(command, response, true);
		} catch (ProviderHttpException exception) {
			if (exception.statusCode() == 404) {
				return new PaymentGatewayResult.NotFound(
					exception.code(), SAFE_NOT_FOUND_MESSAGE);
			}
			return outcomeUnknown(exception.code());
		} catch (TossPaymentResponseParsingException | RestClientException exception) {
			return classifyTransportFailure(exception);
		}
	}

	private PaymentGatewayResult cancelledOrReview(
		PaymentProviderCommand command,
		TossPaymentResponse response,
		boolean inquiry
	) {
		if (!isCorrelated(command, response)
			|| response.getBalanceAmount() == null
			|| knownStatus(response) == null) {
			return cancellationReview("PAYMENT_RESPONSE_MISMATCH");
		}

		PaymentStatus status = knownStatus(response);
		if (status == PaymentStatus.CANCELED && response.getBalanceAmount() == 0L) {
			TossPaymentResponse.Cancel cancellation = fullCancellationEvidence(
				response, command.amount(), command.cancellationReason());
			if (cancellation == null) {
				return cancellationReview("PARTIAL_CANCELLATION_RESPONSE");
			}
			return new PaymentGatewayResult.Cancelled(new CancelledPayment(
				response.getPaymentKey(),
				response.getOrderId(),
				response.getTotalAmount(),
				response.getBalanceAmount(),
				status,
				command.amount(),
				cancellation.getCancelReason(),
				cancellation.getTransactionKey(),
				cancellation.getCanceledAt().toInstant()
			));
		}

		if (inquiry
			&& status == PaymentStatus.DONE
			&& response.getBalanceAmount() == command.amount()) {
			return new PaymentGatewayResult.PaymentActive(
				"PAYMENT_ACTIVE", "Payment remains fully active.");
		}
		return cancellationReview("INCONSISTENT_CANCELLATION_STATE");
	}

	private TossPaymentResponse.Cancel fullCancellationEvidence(
		TossPaymentResponse response,
		long expectedAmount,
		String expectedReason
	) {
		if (response.getCancels() == null || response.getCancels().size() != 1) {
			return null;
		}
		TossPaymentResponse.Cancel cancellation = response.getCancels().getFirst();
		boolean completeEvidence = cancellation.getCancelAmount() != null
			&& cancellation.getCancelAmount() == expectedAmount
			&& Objects.equals(cancellation.getCancelReason(), expectedReason)
			&& cancellation.getTransactionKey() != null
			&& !cancellation.getTransactionKey().isBlank()
			&& cancellation.getTransactionKey().length() <= TRANSACTION_KEY_MAX_LENGTH
			&& cancellation.getCanceledAt() != null;
		return completeEvidence ? cancellation : null;
	}

	private PaymentGatewayResult.ManualReviewRequired cancellationReview(String code) {
		return new PaymentGatewayResult.ManualReviewRequired(
			code, SAFE_CANCELLATION_REVIEW_MESSAGE);
	}

	// TODO: 도메인 재발급 후 웹훅 구현 필요
	@Retryable(
		retryFor = { ResourceAccessException.class },
		maxAttempts = 3,
		backoff = @Backoff(delay = 2000)
	)
	public TossPaymentResponse issueVirtualAccount(Reservation reservation,String bankCode, String customerName) {
		requireEnabled();

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

	private String readErrorCode(ClientHttpResponse response) {
		try {
			return parseErrorCode(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			log.error("토스페이먼츠 에러 응답 읽기 실패: type={}", e.getClass().getSimpleName());
			throw new TossPaymentResponseParsingException(ErrorCode.TOSS_PAYMENT_RESPONSE_PARSING_ERROR);
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
			log.error("토스페이먼츠 에러 응답 파싱 실패: type={}", e.getClass().getSimpleName());
			throw new TossPaymentResponseParsingException(ErrorCode.TOSS_PAYMENT_RESPONSE_PARSING_ERROR);
		}
	}

	private void requireEnabled() {
		if (!enabled) {
			throw new IllegalStateException("Toss Payments is disabled in this runtime profile");
		}
	}

}
