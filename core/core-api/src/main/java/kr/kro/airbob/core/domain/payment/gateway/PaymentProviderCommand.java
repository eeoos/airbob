package kr.kro.airbob.core.domain.payment.gateway;

import java.util.UUID;

import kr.kro.airbob.core.domain.payment.PaymentOperation;

public record PaymentProviderCommand(
	UUID operationUid,
	String paymentKey,
	String orderId,
	long amount,
	String providerIdempotencyKey,
	String cancellationReason) {
	public PaymentProviderCommand {
		if (cancellationReason != null
			&& (cancellationReason.isBlank()
				|| cancellationReason.length() > PaymentOperation.CANCELLATION_REASON_MAX_LENGTH)) {
			throw new IllegalArgumentException(
				"cancellationReason must be non-blank and not exceed "
					+ PaymentOperation.CANCELLATION_REASON_MAX_LENGTH + " characters");
		}
	}
}
