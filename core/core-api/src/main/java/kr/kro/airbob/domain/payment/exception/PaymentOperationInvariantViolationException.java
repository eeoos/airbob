package kr.kro.airbob.domain.payment.exception;

public class PaymentOperationInvariantViolationException extends IllegalStateException {

	public PaymentOperationInvariantViolationException(String message) {
		super(message);
	}
}
