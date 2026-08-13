package kr.kro.airbob.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.payment.event.PaymentEvent;

@JsonTest
@DisplayName("이벤트 봉투 시간 테스트")
class EventEnvelopeTest {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("이벤트 발생 시각은 UTC 절대 시각으로 보존한다")
	void timestampIsAnInstant() {
		Instant occurredAt = Instant.parse("2026-08-12T05:30:00.123456Z");
		PaymentEvent.PaymentCompletedEvent payload =
			new PaymentEvent.PaymentCompletedEvent("reservation-uid");

		EventEnvelope<PaymentEvent.PaymentCompletedEvent> envelope = EventEnvelope.of(
			EventType.PAYMENT_COMPLETED,
			payload,
			occurredAt
		);

		assertThat(envelope.timestamp()).isEqualTo(occurredAt);
	}

	@Test
	@DisplayName("호환 기간에는 UTC 시각을 오프셋 없는 기존 형식으로 직렬화한다")
	void serializesTimestampAsLegacyBareUtc() throws JsonProcessingException {
		EventEnvelope<PaymentEvent.PaymentCompletedEvent> envelope = envelopeAt(
			Instant.parse("2026-08-12T05:30:00.123456Z")
		);

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(envelope));

		assertThat(json.path("timestamp").asText()).isEqualTo("2026-08-12T05:30:00.123456");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("compatibleTimestamps")
	@DisplayName("기존 UTC 문자열과 Z·오프셋 문자열을 동일한 Instant로 역직렬화한다")
	void deserializesLegacyAndOffsetTimestamps(String timestamp, Instant expected) throws JsonProcessingException {
		EventEnvelope<PaymentEvent.PaymentCompletedEvent> envelope = objectMapper.readValue(
			envelopeJson(timestamp),
			envelopeType()
		);

		assertThat(envelope.timestamp()).isEqualTo(expected);
	}

	@ParameterizedTest(name = "timestamp={0}")
	@MethodSource("invalidTimestamps")
	@DisplayName("null과 잘못된 시각은 EventEnvelope로 역직렬화할 수 없다")
	void rejectsNullAndInvalidTimestamps(String timestampJson) {
		assertThatThrownBy(() -> objectMapper.readValue(envelopeJsonValue(timestampJson), envelopeType()))
			.isInstanceOf(JsonProcessingException.class)
			.hasMessageContaining("timestamp");
	}

	private EventEnvelope<PaymentEvent.PaymentCompletedEvent> envelopeAt(Instant timestamp) {
		return EventEnvelope.of(
			EventType.PAYMENT_COMPLETED,
			new PaymentEvent.PaymentCompletedEvent("reservation-uid"),
			timestamp
		);
	}

	private JavaType envelopeType() {
		return objectMapper.getTypeFactory().constructParametricType(
			EventEnvelope.class,
			PaymentEvent.PaymentCompletedEvent.class
		);
	}

	private String envelopeJson(String timestamp) {
		return envelopeJsonValue('"' + timestamp + '"');
	}

	private String envelopeJsonValue(String timestampJson) {
		return """
			{
			  "event_id": "12f4c680-6c60-4b60-a1c8-8ef1ea285207",
			  "trace_id": "reservation-uid",
			  "event_type": "PAYMENT_COMPLETED",
			  "event_version": "1.0",
			  "timestamp": %s,
			  "payload": {"reservation_uid": "reservation-uid"}
			}
			""".formatted(timestampJson);
	}

	private static java.util.stream.Stream<Arguments> compatibleTimestamps() {
		Instant expected = Instant.parse("2026-08-12T05:30:00.123456Z");
		return java.util.stream.Stream.of(
			Arguments.of("2026-08-12T05:30:00.123456", expected),
			Arguments.of("2026-08-12T05:30:00.123456Z", expected),
			Arguments.of("2026-08-12T14:30:00.123456+09:00", expected)
		);
	}

	private static java.util.stream.Stream<Arguments> invalidTimestamps() {
		return java.util.stream.Stream.of(
			Arguments.of("null"),
			Arguments.of("\"not-a-timestamp\"")
		);
	}
}
