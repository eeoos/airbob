package kr.kro.airbob.domain.payment.service.gateway;

import java.util.UUID;

public record PaymentConfirmationCommand(
	UUID operationUid,
	String paymentKey,
	String orderId,
	long amount,
	String providerIdempotencyKey
) {
}
