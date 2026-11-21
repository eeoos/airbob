package kr.kro.airbob.domain.payment.service.gateway;

import java.util.UUID;

import kr.kro.airbob.domain.payment.entity.PaymentOperation;

public record PaymentProviderCommand(
	UUID operationUid,
	String paymentKey,
	String orderId,
	long amount,
	String providerIdempotencyKey,
	String cancellationReason
) {
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
