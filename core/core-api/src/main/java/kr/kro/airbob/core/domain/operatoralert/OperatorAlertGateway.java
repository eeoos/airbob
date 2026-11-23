package kr.kro.airbob.core.domain.operatoralert;

import kr.kro.airbob.core.domain.operatoralert.event.OperatorAlertRequestedV1;

public interface OperatorAlertGateway {

	void deliver(OperatorAlertRequestedV1 alert);
}
