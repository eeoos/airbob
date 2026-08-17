package kr.kro.airbob.messaging.alert.infrastructure.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.messaging.alert.application.OperatorAlertOutboxAppender;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.messaging.event.EventDescriptor;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;

@Repository
public class MysqlOperatorAlertOutboxAppender implements OperatorAlertOutboxAppender {

	private static final String INSERT = """
		INSERT INTO outbox (
		  event_id, destination, partition_key, aggregate_type, aggregate_id,
		  event_type, event_version, payload, occurred_at, deduplication_key,
		  created_at, updated_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON DUPLICATE KEY UPDATE destination = CASE
		  WHEN deduplication_key = VALUES(deduplication_key) THEN destination
		  ELSE NULL
		END
		""";
	private static final String EVENT_ID_EXISTS =
		"SELECT EXISTS(SELECT 1 FROM outbox WHERE event_id = ?)";

	private final JdbcTemplate jdbcTemplate;
	private final IntegrationEventCodec codec;
	private final Clock clock;
	private final Supplier<UUID> eventIdSupplier;

	@Autowired
	public MysqlOperatorAlertOutboxAppender(
		JdbcTemplate jdbcTemplate,
		IntegrationEventCodec codec,
		Clock clock
	) {
		this(jdbcTemplate, codec, clock, UUID::randomUUID);
	}

	MysqlOperatorAlertOutboxAppender(
		JdbcTemplate jdbcTemplate,
		IntegrationEventCodec codec,
		Clock clock,
		Supplier<UUID> eventIdSupplier
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.codec = codec;
		this.clock = clock;
		this.eventIdSupplier = eventIdSupplier;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean appendIfAbsent(OperatorAlertRequestedV1 event) {
		UUID eventId = eventIdSupplier.get();
		Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
		EventDescriptor descriptor = event.descriptor();
		String payload = codec.encode(EventEnvelope.of(eventId, occurredAt, event));
		int affected = jdbcTemplate.update(
			INSERT,
			eventId.toString(),
			descriptor.destination(),
			event.partitionKey(),
			descriptor.aggregateType(),
			event.aggregateId(),
			descriptor.eventType(),
			descriptor.eventVersion(),
			payload,
			Timestamp.from(occurredAt),
			event.deduplicationKey(),
			Timestamp.from(occurredAt),
			Timestamp.from(occurredAt)
		);
		if (affected == 0) {
			return false;
		}
		if (affected == 1) {
			// Connector/J may report a no-op duplicate as one matched row unless
			// useAffectedRows is enabled. The mutation is still the single atomic INSERT;
			// this identifier-only read makes the result portable across both modes.
			Boolean inserted = jdbcTemplate.queryForObject(
				EVENT_ID_EXISTS, Boolean.class, eventId.toString());
			return Boolean.TRUE.equals(inserted);
		}
		throw new OperatorAlertOutboxAppendException();
	}
}
