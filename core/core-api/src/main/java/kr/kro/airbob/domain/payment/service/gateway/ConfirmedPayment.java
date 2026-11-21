package kr.kro.airbob.domain.payment.service.gateway;

import java.time.Instant;

import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;

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
