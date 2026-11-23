package kr.kro.airbob.core.domain.operatoralert.infrastructure.slack;

public class OperatorAlertDeliveryException extends RuntimeException {

	public OperatorAlertDeliveryException() {
		super("Operator alert delivery failed.");
	}
}
