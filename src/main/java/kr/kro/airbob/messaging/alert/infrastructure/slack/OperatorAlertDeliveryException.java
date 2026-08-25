package kr.kro.airbob.messaging.alert.infrastructure.slack;

public class OperatorAlertDeliveryException extends RuntimeException {

	public OperatorAlertDeliveryException() {
		super("Operator alert delivery failed.");
	}
}
