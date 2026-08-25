package kr.kro.airbob.messaging.outbox.infrastructure.jpa;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.kro.airbob.common.domain.BaseEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessage extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", nullable = false, updatable = false, length = 36)
	private String eventId;

	@Column(nullable = false, updatable = false)
	private String destination;

	@Column(name = "partition_key", nullable = false, updatable = false)
	private String partitionKey;

	@Column(name = "aggregate_type", nullable = false, updatable = false)
	private String aggregateType;

	@Column(name = "aggregate_id", nullable = false, updatable = false)
	private String aggregateId;

	@Column(name = "event_type", nullable = false, updatable = false)
	private String eventType;

	@Column(name = "event_version", nullable = false, updatable = false, length = 30)
	private String eventVersion;

	@Column(columnDefinition = "TEXT", nullable = false, updatable = false)
	private String payload;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@Column(name = "deduplication_key", unique = true, updatable = false)
	private String deduplicationKey;

	private OutboxMessage(
		String eventId,
		String destination,
		String partitionKey,
		String aggregateType,
		String aggregateId,
		String eventType,
		String eventVersion,
		String payload,
		Instant occurredAt,
		String deduplicationKey
	) {
		this.eventId = eventId;
		this.destination = destination;
		this.partitionKey = partitionKey;
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.eventVersion = eventVersion;
		this.payload = payload;
		this.occurredAt = occurredAt;
		this.deduplicationKey = deduplicationKey;
	}

	public static OutboxMessage create(
		String eventId,
		String destination,
		String partitionKey,
		String aggregateType,
		String aggregateId,
		String eventType,
		String eventVersion,
		String payload,
		Instant occurredAt,
		String deduplicationKey
	) {
		return new OutboxMessage(
			eventId,
			destination,
			partitionKey,
			aggregateType,
			aggregateId,
			eventType,
			eventVersion,
			payload,
			occurredAt,
			deduplicationKey
		);
	}
}
