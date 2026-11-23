package kr.kro.airbob.core.domain.payment;

public class PaymentOperationInvariantViolationException extends IllegalStateException {

	public PaymentOperationInvariantViolationException(String message) {
		super(message);
	}
}
