package kr.kro.airbob.messaging.alert.infrastructure.outbox;

public class OperatorAlertOutboxAppendException extends RuntimeException {

	public OperatorAlertOutboxAppendException() {
		super("Operator alert outbox append failed.");
	}
}
