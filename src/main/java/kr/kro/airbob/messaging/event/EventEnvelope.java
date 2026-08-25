package kr.kro.airbob.messaging.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope<T extends IntegrationEvent>(
	UUID eventId,
	String eventType,
	String eventVersion,
	Instant occurredAt,
	T payload
) {
	public EventEnvelope {
		Objects.requireNonNull(eventId, "eventId must not be null");
		Objects.requireNonNull(eventType, "eventType must not be null");
		Objects.requireNonNull(eventVersion, "eventVersion must not be null");
		Objects.requireNonNull(occurredAt, "occurredAt must not be null");
		Objects.requireNonNull(payload, "payload must not be null");
	}

	public static <T extends IntegrationEvent> EventEnvelope<T> of(
		UUID eventId,
		Instant occurredAt,
		T event
	) {
		Objects.requireNonNull(event, "event must not be null");
		EventDescriptor descriptor = Objects.requireNonNull(
			event.descriptor(), "event descriptor must not be null");
		return new EventEnvelope<>(
			eventId,
			descriptor.eventType(),
			descriptor.eventVersion(),
			occurredAt,
			event
		);
	}
}
