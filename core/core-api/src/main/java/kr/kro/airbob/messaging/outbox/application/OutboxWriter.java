package kr.kro.airbob.messaging.outbox.application;

import kr.kro.airbob.messaging.event.IntegrationEvent;

public interface OutboxWriter {

	void append(IntegrationEvent event);
}
