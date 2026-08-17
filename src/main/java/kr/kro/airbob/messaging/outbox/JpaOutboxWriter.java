package kr.kro.airbob.messaging.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.messaging.event.EventDescriptor;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEvent;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;

@Component
public class JpaOutboxWriter implements OutboxWriter {

	private final OutboxMessageRepository repository;
	private final IntegrationEventCodec codec;
	private final Clock clock;

	public JpaOutboxWriter(
		OutboxMessageRepository repository,
		IntegrationEventCodec codec,
		Clock clock
	) {
		this.repository = repository;
		this.codec = codec;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void append(IntegrationEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		EventDescriptor descriptor = Objects.requireNonNull(
			event.descriptor(), "event descriptor must not be null");
		String aggregateId = requireText(event.aggregateId(), "aggregateId");
		String partitionKey = requireText(event.partitionKey(), "partitionKey");
		UUID eventId = UUID.randomUUID();
		Instant occurredAt = clock.instant();
		String payload = codec.encode(EventEnvelope.of(eventId, occurredAt, event));

		repository.save(OutboxMessage.create(
			eventId.toString(),
			descriptor.destination(),
			partitionKey,
			descriptor.aggregateType(),
			aggregateId,
			descriptor.eventType(),
			descriptor.eventVersion(),
			payload,
			occurredAt,
			nullIfBlank(event.deduplicationKey())
		));
	}

	private String requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return value;
	}

	private String nullIfBlank(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
