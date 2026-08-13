package kr.kro.airbob.outbox;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

public record EventEnvelope<T extends EventPayload>(
	UUID eventId, // 이벤트 고유 ID
	String traceId, // 전체 요청 추적 ID
	String eventType,
	String eventVersion, // 이벤트 스키마 버전
	@JsonSerialize(using = EventTimestampSerializer.class)
	@JsonDeserialize(using = EventTimestampDeserializer.class)
	Instant timestamp,
	T payload
) {
	public static <T extends EventPayload> EventEnvelope<T> of(
		EventType eventType,
		T payload,
		Instant timestamp
	) {
		return new EventEnvelope<>(
			UUID.randomUUID(),
			payload.getId(), // 임시로 Aggregate ID를 Trace ID로 사용
			eventType.name(),
			"1.0",
			timestamp,
			payload
		);
	}
}
