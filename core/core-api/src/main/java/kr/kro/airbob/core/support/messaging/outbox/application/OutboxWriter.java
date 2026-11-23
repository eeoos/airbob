package kr.kro.airbob.core.support.messaging.outbox.application;

import kr.kro.airbob.core.support.messaging.event.IntegrationEvent;

public interface OutboxWriter {

	void append(IntegrationEvent event);
}
