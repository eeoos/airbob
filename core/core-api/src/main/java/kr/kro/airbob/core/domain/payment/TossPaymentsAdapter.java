package kr.kro.airbob.core.domain.payment;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import kr.kro.airbob.client.toss.TossPaymentResponse;
import kr.kro.airbob.client.toss.TossPaymentsApiException;
import kr.kro.airbob.client.toss.TossPaymentsClient;
import kr.kro.airbob.client.toss.TossPaymentsResponseParsingException;
import kr.kro.airbob.core.domain.payment.gateway.CancelledPayment;
import kr.kro.airbob.core.domain.payment.gateway.ConfirmedPayment;
import kr.kro.airbob.core.domain.payment.gateway.PaymentConfirmationFailureClassifier;
import kr.kro.airbob.core.domain.payment.gateway.PaymentGatewayResult;
import kr.kro.airbob.core.domain.payment.gateway.PaymentProviderCommand;
import kr.kro.airbob.core.domain.payment.gateway.PaymentProviderGateway;

/**
 * Translates Toss Payments outcomes into {@link PaymentGatewayResult}s.
 * HTTP transport lives in {@link TossPaymentsClient} (clients:client-toss-payments); this adapter owns
 * the classification of provider codes, response correlation, and normalization to domain values.
 */
@Component
public class TossPaymentsAdapter implements PaymentProviderGateway {

	public static final String IDEMPOTENT_REQUEST_PROCESSING = "IDEMPOTENT_REQUEST_PROCESSING";
	private static final String SAFE_UNKNOWN_MESSAGE = "결제 결과를 확인하고 있습니다.";
	private static final String SAFE_RETRYABLE_MESSAGE = "결제 서비스에 연결할 수 없어 다시 시도합니다.";
	private static final String SAFE_NOT_FOUND_MESSAGE = "결제 정보를 찾을 수 없습니다.";
	private static final String SAFE_TERMINAL_MESSAGE = "결제가 완료되지 않았습니다.";
	private static final String SAFE_CANCELLATION_DECLINED_MESSAGE = "결제 취소가 거절되었습니다.";
	private static final String SAFE_CANCELLATION_REVIEW_MESSAGE = "결제 취소 상태를 운영자가 확인해야 합니다.";
	private static final int TRANSACTION_KEY_MAX_LENGTH = 64;
	private static final Set<String> CANCELLATION_INQUIRY_REQUIRED_CODES = Set.of(
		IDEMPOTENT_REQUEST_PROCESSING,
		"ALREADY_CANCELED_PAYMENT",
		"ALREADY_REFUND_PAYMENT",
		"ALREADY_REFUNDING_PAYMENT",
		"NOT_MATCHES_REFUNDABLE_AMOUNT");
	private static final Set<String> CANCELLATION_RETRYABLE_CODES = Set.of(
		"PROVIDER_ERROR",
		"FORBIDDEN_CONSECUTIVE_REQUEST",
		"NOT_AVAILABLE_BANK");
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
		"NOT_ALLOWED_PARTIAL_REFUND");

	private final TossPaymentsClient tossPaymentsClient;
	private final PaymentConfirmationFailureClassifier confirmationFailureClassifier;
	private final boolean enabled;

	public TossPaymentsAdapter(
		TossPaymentsClient tossPaymentsClient,
		PaymentConfirmationFailureClassifier confirmationFailureClassifier,
		@Value("${payment.toss.enabled:true}")
		boolean enabled) {
		this.tossPaymentsClient = tossPaymentsClient;
		this.confirmationFailureClassifier = confirmationFailureClassifier;
		this.enabled = enabled;
	}

	@Override
	public PaymentGatewayResult confirm(PaymentProviderCommand command) {
		requireEnabled();

		try {
			TossPaymentResponse response = tossPaymentsClient.confirm(
				command.paymentKey(),
				command.orderId(),
				command.amount(),
				command.providerIdempotencyKey());

			return approvedOrUnknown(command, response);
		} catch (TossPaymentsApiException exception) {
			if (exception.statusCode() >= 500) {
				return outcomeUnknown("PROVIDER_ERROR");
			}
			return confirmationFailureClassifier.classify(exception.code(), null);
		} catch (TossPaymentsResponseParsingException | RestClientException exception) {
			return classifyTransportFailure(exception);
		}
	}

	@Override
	public PaymentGatewayResult inquireConfirmation(PaymentProviderCommand command) {
		requireEnabled();

		try {
			TossPaymentResponse response = tossPaymentsClient.getPayment(command.paymentKey());

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
		} catch (TossPaymentsApiException exception) {
			if (exception.statusCode() == 404) {
				return new PaymentGatewayResult.NotFound(exception.code(), SAFE_NOT_FOUND_MESSAGE);
			}
			return outcomeUnknown(exception.code());
		} catch (TossPaymentsResponseParsingException | RestClientException exception) {
			return classifyTransportFailure(exception);
		}
	}

	private PaymentGatewayResult approvedOrUnknown(
		PaymentProviderCommand command,
		TossPaymentResponse response) {
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
			normalize(response.getVirtualAccount()));
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
			dueDate);
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

	@Override
	public PaymentGatewayResult cancel(PaymentProviderCommand command) {
		requireEnabled();

		try {
			TossPaymentResponse response = tossPaymentsClient.cancel(
				command.paymentKey(),
				command.cancellationReason(),
				command.amount(),
				command.providerIdempotencyKey());
			return cancelledOrReview(command, response, false);
		} catch (TossPaymentsApiException exception) {
			return classifyCancellationFailure(exception);
		} catch (TossPaymentsResponseParsingException | RestClientException exception) {
			return classifyTransportFailure(exception);
		}
	}

	private PaymentGatewayResult classifyCancellationFailure(TossPaymentsApiException exception) {
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
			TossPaymentResponse response = tossPaymentsClient.getPayment(command.paymentKey());
			return cancelledOrReview(command, response, true);
		} catch (TossPaymentsApiException exception) {
			if (exception.statusCode() == 404) {
				return new PaymentGatewayResult.NotFound(
					exception.code(), SAFE_NOT_FOUND_MESSAGE);
			}
			return outcomeUnknown(exception.code());
		} catch (TossPaymentsResponseParsingException | RestClientException exception) {
			return classifyTransportFailure(exception);
		}
	}

	private PaymentGatewayResult cancelledOrReview(
		PaymentProviderCommand command,
		TossPaymentResponse response,
		boolean inquiry) {
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
				cancellation.getCanceledAt().toInstant()));
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
		String expectedReason) {
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

	private void requireEnabled() {
		if (!enabled) {
			throw new IllegalStateException("Toss Payments is disabled in this runtime profile");
		}
	}

}
