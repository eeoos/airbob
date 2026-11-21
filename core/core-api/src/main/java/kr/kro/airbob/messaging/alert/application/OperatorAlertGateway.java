package kr.kro.airbob.messaging.alert.application;

import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;

public interface OperatorAlertGateway {

	void deliver(OperatorAlertRequestedV1 alert);
}
