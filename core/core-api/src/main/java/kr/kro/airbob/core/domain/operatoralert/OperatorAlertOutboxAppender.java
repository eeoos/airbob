package kr.kro.airbob.core.domain.operatoralert;

import kr.kro.airbob.core.domain.operatoralert.event.OperatorAlertRequestedV1;

public interface OperatorAlertOutboxAppender {

	boolean appendIfAbsent(OperatorAlertRequestedV1 event);
}
