package kr.kro.airbob.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnore;

public interface IntegrationEvent {

	@JsonIgnore
	EventDescriptor descriptor();

	@JsonIgnore
	String aggregateId();

	@JsonIgnore
	default String partitionKey() {
		return aggregateId();
	}

	@JsonIgnore
	default String deduplicationKey() {
		return null;
	}
}
