package kr.kro.airbob.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import kr.kro.airbob.messaging.event.EventDescriptor;
import kr.kro.airbob.messaging.event.IntegrationEvent;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;

@ExtendWith(MockitoExtension.class)
class JpaOutboxWriterTest {

	private static final Instant NOW = Instant.parse("2026-08-17T08:00:00Z");
	private static final UUID AGGREGATE_UID = UUID.fromString("7e19fa7d-a8dc-4096-8c75-e84f43e5b639");
	private static final UUID PARTITION_UID = UUID.fromString("ac3921de-5f64-4d73-829d-a49c32321950");
	private static final EventDescriptor DESCRIPTOR = new EventDescriptor(
		"PAYMENT_OPERATION.events", "PAYMENT_OPERATION", "PAYMENT_EXECUTION_REQUESTED", "1");

	@Mock
	private OutboxMessageRepository repository;

	@Test
	@DisplayName("event routing metadata와 envelope의 event ID를 하나의 immutable outbox row에 저장한다")
	void appendsCanonicalOutboxMessage() {
		JpaOutboxWriter writer = new JpaOutboxWriter(
			repository,
			new IntegrationEventCodec(JsonMapper.builder()
				.addModule(new JavaTimeModule())
				.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
				.build()),
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		TestEvent event = new TestEvent(AGGREGATE_UID, PARTITION_UID, 7L);

		writer.append(event);

		ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
		then(repository).should().save(captor.capture());
		OutboxMessage message = captor.getValue();
		assertThat(message.getEventId()).isNotNull();
		assertThat(message.getDestination()).isEqualTo("PAYMENT_OPERATION.events");
		assertThat(message.getAggregateType()).isEqualTo("PAYMENT_OPERATION");
		assertThat(message.getAggregateId()).isEqualTo(AGGREGATE_UID.toString());
		assertThat(message.getPartitionKey()).isEqualTo(PARTITION_UID.toString());
		assertThat(message.getEventType()).isEqualTo("PAYMENT_EXECUTION_REQUESTED");
		assertThat(message.getEventVersion()).isEqualTo("1");
		assertThat(message.getOccurredAt()).isEqualTo(NOW);
		assertThat(message.getDeduplicationKey())
			.isEqualTo("PAYMENT_EXECUTION:" + AGGREGATE_UID + ":7");
		assertThat(message.getPayload()).contains("\"event_id\":\"" + message.getEventId() + "\"");
	}

	@Test
	@DisplayName("outbox append는 독립 commit을 만들지 못하도록 기존 transaction을 강제한다")
	void requiresExistingTransaction() throws NoSuchMethodException {
		Transactional transactional = JpaOutboxWriter.class
			.getMethod("append", IntegrationEvent.class)
			.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
	}

	@Test
	@DisplayName("row와 envelope 시각은 MySQL DATETIME(6) 정밀도로 동일하게 기록한다")
	void normalizesOccurredAtToDatabasePrecision() {
		Instant nanosecondClock = Instant.parse("2026-08-17T08:00:00.123456789Z");
		JpaOutboxWriter writer = new JpaOutboxWriter(
			repository,
			new IntegrationEventCodec(JsonMapper.builder()
				.addModule(new JavaTimeModule())
				.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
				.build()),
			Clock.fixed(nanosecondClock, ZoneOffset.UTC)
		);

		writer.append(new TestEvent(AGGREGATE_UID, PARTITION_UID, 8L));

		ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
		then(repository).should().save(captor.capture());
		OutboxMessage message = captor.getValue();
		assertThat(message.getOccurredAt())
			.isEqualTo(Instant.parse("2026-08-17T08:00:00.123456Z"));
		assertThat(message.getPayload())
			.contains("\"occurred_at\":\"2026-08-17T08:00:00.123456Z\"")
			.doesNotContain("123456789");
	}

	private record TestEvent(UUID uid, UUID reservationUid, long generation) implements IntegrationEvent {
		@Override
		public EventDescriptor descriptor() {
			return DESCRIPTOR;
		}

		@Override
		public String aggregateId() {
			return uid.toString();
		}

		@Override
		public String partitionKey() {
			return reservationUid.toString();
		}

		@Override
		public String deduplicationKey() {
			return "PAYMENT_EXECUTION:" + uid + ":" + generation;
		}
	}
}
