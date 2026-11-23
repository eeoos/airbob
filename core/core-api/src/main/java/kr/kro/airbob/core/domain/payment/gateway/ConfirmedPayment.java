package kr.kro.airbob.core.domain.payment.gateway;

import java.time.Instant;

import kr.kro.airbob.core.domain.payment.PaymentMethod;
import kr.kro.airbob.core.domain.payment.PaymentStatus;

public record ConfirmedPayment(
	String paymentKey,
	String orderId,
	long totalAmount,
	long balanceAmount,
	PaymentMethod method,
	PaymentStatus status,
	Instant approvedAt,
	VirtualAccountDetails virtualAccount
) {
	public record VirtualAccountDetails(
		String bankCode,
		String accountNumber,
		String customerName,
		Instant dueDate
	) {
	}
}
