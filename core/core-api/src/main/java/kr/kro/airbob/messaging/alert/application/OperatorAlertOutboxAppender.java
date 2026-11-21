package kr.kro.airbob.messaging.alert.application;

import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;

public interface OperatorAlertOutboxAppender {

	boolean appendIfAbsent(OperatorAlertRequestedV1 event);
}
