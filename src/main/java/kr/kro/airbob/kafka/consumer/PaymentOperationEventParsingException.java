package kr.kro.airbob.kafka.consumer;

public final class PaymentOperationEventParsingException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public PaymentOperationEventParsingException() {
		super("Invalid payment-operation event.");
	}
}
