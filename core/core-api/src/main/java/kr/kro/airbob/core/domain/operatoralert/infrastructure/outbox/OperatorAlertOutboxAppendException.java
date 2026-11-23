package kr.kro.airbob.core.domain.operatoralert.infrastructure.outbox;

public class OperatorAlertOutboxAppendException extends RuntimeException {

	public OperatorAlertOutboxAppendException() {
		super("Operator alert outbox append failed.");
	}
}
