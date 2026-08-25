package kr.kro.airbob.messaging.alert.infrastructure.slack;

public class OperatorAlertDeliveryNotConfiguredException extends RuntimeException {

	public OperatorAlertDeliveryNotConfiguredException() {
		super("Operator alert delivery is not configured.");
	}
}
