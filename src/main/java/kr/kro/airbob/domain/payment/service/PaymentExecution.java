package kr.kro.airbob.domain.payment.service;

import java.util.UUID;

import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationCommand;

public record PaymentExecution(
	UUID operationUid,
	UUID reservationUid,
	String paymentKey,
	String orderId,
	long amount,
	String providerIdempotencyKey,
	String leaseOwner,
	PaymentExecutionMode mode
) {
	public static PaymentExecution from(
		PaymentOperation operation, String leaseOwner, PaymentExecutionMode mode
	) {
		UUID reservationUid = operation.getReservation().getReservationUid();
		return new PaymentExecution(
			operation.getOperationUid(), reservationUid, operation.getPaymentKey(),
			reservationUid.toString(), operation.getExpectedAmount(),
			operation.getProviderIdempotencyKey(), leaseOwner, mode);
	}

	public PaymentConfirmationCommand gatewayCommand() {
		return new PaymentConfirmationCommand(
			operationUid, paymentKey, orderId, amount, providerIdempotencyKey);
	}
}
