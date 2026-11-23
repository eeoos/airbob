package kr.kro.airbob.core.domain.operatoralert.infrastructure.slack;

public class OperatorAlertDeliveryNotConfiguredException extends RuntimeException {

	public OperatorAlertDeliveryNotConfiguredException() {
		super("Operator alert delivery is not configured.");
	}
}
