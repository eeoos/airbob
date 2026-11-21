package kr.kro.airbob.domain.payment.service.gateway;

import java.time.Instant;

import kr.kro.airbob.domain.payment.entity.PaymentStatus;

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
