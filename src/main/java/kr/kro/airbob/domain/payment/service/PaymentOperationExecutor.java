package kr.kro.airbob.domain.payment.service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationGateway;
import kr.kro.airbob.domain.payment.service.gateway.PaymentGatewayResult;

@Service
public class PaymentOperationExecutor {
	private static final int FAILURE_CODE_MAX_LENGTH = 100;
	private static final int FAILURE_MESSAGE_MAX_LENGTH = 512;
	private static final String UNKNOWN_CODE = "UNKNOWN_GATEWAY_RESULT";
	private static final String UNEXPECTED_GATEWAY_MESSAGE = "Unexpected payment gateway failure.";
	private static final String RESPONSE_MISMATCH_MESSAGE =
		"Provider approval did not match the payment operation.";

	private final PaymentOperationLeaseService leaseService;
	private final PaymentConfirmationGateway gateway;
	private final PaymentOperationFinalizer finalizer;

	public PaymentOperationExecutor(
		PaymentOperationLeaseService leaseService,
		PaymentConfirmationGateway gateway,
		PaymentOperationFinalizer finalizer
	) {
		this.leaseService = leaseService;
		this.gateway = gateway;
		this.finalizer = finalizer;
	}

	public void execute(UUID operationUid) {
		Optional<PaymentExecution> claimed = leaseService.claim(operationUid);
		if (claimed.isEmpty()) {
			return;
		}
		PaymentExecution execution = claimed.get();

		PaymentGatewayResult result;
		try {
			result = executeGateway(execution);
		} catch (RuntimeException unexpectedGatewayFailure) {
			leaseService.markOutcomeUnknown(
				execution,
				"UNCLASSIFIED_GATEWAY_FAILURE",
				UNEXPECTED_GATEWAY_MESSAGE
			);
			return;
		}

		dispatchDurableResult(execution, result);
	}

	private PaymentGatewayResult executeGateway(PaymentExecution execution) {
		return execution.mode() == PaymentExecutionMode.CONFIRM
			? gateway.confirm(execution.gatewayCommand())
			: gateway.inquire(execution.gatewayCommand());
	}

	private void dispatchDurableResult(PaymentExecution execution, PaymentGatewayResult result) {
		if (result instanceof PaymentGatewayResult.Approved approved) {
			applyApproved(execution, approved.payment());
			return;
		}
		if (result instanceof PaymentGatewayResult.Declined declined) {
			finalizer.applyDeclined(
				execution,
				sanitizeCode(execution, declined.code()),
				sanitizeMessage(execution, declined.message())
			);
			return;
		}
		if (result instanceof PaymentGatewayResult.RetryableFailure retryable) {
			applyRetryable(execution, retryable.code(), retryable.message());
			return;
		}
		if (result instanceof PaymentGatewayResult.OutcomeUnknown unknown) {
			markOutcomeUnknown(execution, unknown.code(), unknown.message());
			return;
		}
		if (result instanceof PaymentGatewayResult.NotFound notFound) {
			applyNotFound(execution, notFound.code(), notFound.message());
			return;
		}

		markOutcomeUnknown(execution, UNKNOWN_CODE, "Payment gateway returned no result.");
	}

	private void applyApproved(PaymentExecution execution, ConfirmedPayment confirmed) {
		if (!isCorrelatedApproval(execution, confirmed)) {
			leaseService.markOutcomeUnknown(
				execution,
				"PROVIDER_RESPONSE_MISMATCH",
				RESPONSE_MISMATCH_MESSAGE
			);
			return;
		}

		finalizer.applyApproved(execution, confirmed);
	}

	private void applyRetryable(PaymentExecution execution, String code, String message) {
		if (execution.mode() == PaymentExecutionMode.CONFIRM) {
			leaseService.scheduleRetry(
				execution, sanitizeCode(execution, code), sanitizeMessage(execution, message));
			return;
		}

		markOutcomeUnknown(execution, code, message);
	}

	private void applyNotFound(PaymentExecution execution, String code, String message) {
		if (execution.mode() == PaymentExecutionMode.INQUIRE) {
			leaseService.scheduleRetry(
				execution, sanitizeCode(execution, code), sanitizeMessage(execution, message));
			return;
		}

		markOutcomeUnknown(execution, code, message);
	}

	private void markOutcomeUnknown(PaymentExecution execution, String code, String message) {
		leaseService.markOutcomeUnknown(
			execution, sanitizeCode(execution, code), sanitizeMessage(execution, message));
	}

	private boolean isCorrelatedApproval(PaymentExecution execution, ConfirmedPayment confirmed) {
		return confirmed != null
			&& Objects.equals(execution.paymentKey(), confirmed.paymentKey())
			&& Objects.equals(execution.orderId(), confirmed.orderId())
			&& execution.amount() == confirmed.totalAmount()
			&& confirmed.balanceAmount() == confirmed.totalAmount()
			&& confirmed.method() != null
			&& confirmed.status() == PaymentStatus.DONE
			&& confirmed.approvedAt() != null;
	}

	private String sanitizeCode(PaymentExecution execution, String code) {
		String redacted = code == null ? "" : code;
		redacted = redact(redacted, execution.paymentKey());
		redacted = redact(redacted, execution.orderId());
		redacted = redact(redacted, execution.providerIdempotencyKey());
		String sanitized = sanitizeText(redacted, FAILURE_CODE_MAX_LENGTH);
		return sanitized.isBlank() ? UNKNOWN_CODE : sanitized;
	}

	private String sanitizeMessage(PaymentExecution execution, String message) {
		String redacted = message == null ? "" : message;
		redacted = redact(redacted, execution.paymentKey());
		redacted = redact(redacted, execution.orderId());
		redacted = redact(redacted, execution.providerIdempotencyKey());
		return sanitizeText(redacted, FAILURE_MESSAGE_MAX_LENGTH);
	}

	private String sanitizeText(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		StringBuilder sanitized = new StringBuilder(Math.min(value.length(), maxLength));
		for (int index = 0; index < value.length() && sanitized.length() < maxLength; index++) {
			char character = value.charAt(index);
			sanitized.append(Character.isISOControl(character) ? ' ' : character);
		}
		return sanitized.toString().trim();
	}

	private String redact(String value, String sensitiveValue) {
		if (sensitiveValue == null || sensitiveValue.isBlank()) {
			return value;
		}
		return value.replace(sensitiveValue, "[REDACTED]");
	}
}
