package kr.kro.airbob.core.domain.payment.gateway;

import java.time.Instant;

import kr.kro.airbob.core.domain.payment.PaymentStatus;

public record CancelledPayment(
	String paymentKey,
	String orderId,
	long totalAmount,
	long balanceAmount,
	PaymentStatus status,
	long cancelAmount,
	String cancelReason,
	String transactionKey,
	Instant cancelledAt
) {
}
