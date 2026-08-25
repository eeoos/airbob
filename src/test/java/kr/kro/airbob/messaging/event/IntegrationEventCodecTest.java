package kr.kro.airbob.messaging.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class IntegrationEventCodecTest {

	private static final EventDescriptor DESCRIPTOR = new EventDescriptor(
		"PAYMENT_OPERATION.events",
		"PAYMENT_OPERATION",
		"PAYMENT_EXECUTION_REQUESTED",
		"1"
	);
	private static final UUID EVENT_ID = UUID.fromString("710470d6-8bb8-4fd4-8249-5f2f52a1afcc");
	private static final UUID OPERATION_UID = UUID.fromString("7e19fa7d-a8dc-4096-8c75-e84f43e5b639");
	private static final UUID RESERVATION_UID = UUID.fromString("ac3921de-5f64-4d73-829d-a49c32321950");
	private static final Instant OCCURRED_AT = Instant.parse("2026-08-17T08:00:00.123456Z");
	private static final String SECRET = "provider-secret-must-not-escape";

	private final ObjectMapper objectMapper = JsonMapper.builder()
		.addModule(new JavaTimeModule())
		.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
		.build();
	private final IntegrationEventCodec codec = new IntegrationEventCodec(objectMapper);

	@Test
	@DisplayName("outbox envelope은 event 식별자와 UTC Z 시각만 canonical JSON으로 기록한다")
	void writesCanonicalEnvelope() {
		PaymentExecutionRequested event = event();

		String json = codec.encode(EventEnvelope.of(EVENT_ID, OCCURRED_AT, event));

		assertThat(json)
			.contains("\"event_id\":\"" + EVENT_ID + "\"")
			.contains("\"event_type\":\"PAYMENT_EXECUTION_REQUESTED\"")
			.contains("\"event_version\":\"1\"")
			.contains("\"occurred_at\":\"2026-08-17T08:00:00.123456Z\"")
			.contains("\"operation_uid\":\"" + OPERATION_UID + "\"")
			.contains("\"reservation_uid\":\"" + RESERVATION_UID + "\"")
			.doesNotContain("descriptor", "aggregate_id", "partition_key", "deduplication_key", "trace_id");
	}

	@Test
	@DisplayName("strict codec은 기대한 type/version의 identifier payload를 복원한다")
	void readsExpectedEvent() {
		String json = codec.encode(EventEnvelope.of(EVENT_ID, OCCURRED_AT, event()));

		EventEnvelope<PaymentExecutionRequested> decoded = codec.decode(
			json, DESCRIPTOR, PaymentExecutionRequested.class);

		assertThat(decoded.eventId()).isEqualTo(EVENT_ID);
		assertThat(decoded.occurredAt()).isEqualTo(OCCURRED_AT);
		assertThat(decoded.payload()).isEqualTo(event());
	}

	@Test
	@DisplayName("중복 JSON key와 뒤에 붙은 JSON은 poison message로 거부한다")
	void rejectsDuplicateKeysAndTrailingTokens() {
		String valid = codec.encode(EventEnvelope.of(EVENT_ID, OCCURRED_AT, event()));
		String duplicate = valid.replace(
			"\"operation_uid\":\"" + OPERATION_UID + "\"",
			"\"operation_uid\":\"" + SECRET + "\",\"operation_uid\":\"" + OPERATION_UID + "\"");

		assertSafeFailure(duplicate);
		assertSafeFailure(valid + "{\"provider_secret\":\"" + SECRET + "\"}");
	}

	@Test
	@DisplayName("envelope이나 payload의 허용되지 않은 필드는 원문 재발행 전에 거부한다")
	void rejectsUnknownEnvelopeAndPayloadFields() {
		String valid = codec.encode(EventEnvelope.of(EVENT_ID, OCCURRED_AT, event()));
		String envelopeSecret = valid.replace(
			"\"payload\":",
			"\"provider_secret\":\"" + SECRET + "\",\"payload\":");
		String payloadSecret = valid.replace(
			"\"reservation_uid\":\"" + RESERVATION_UID + "\"",
			"\"reservation_uid\":\"" + RESERVATION_UID + "\",\"provider_secret\":\"" + SECRET + "\"");

		assertSafeFailure(envelopeSecret);
		assertSafeFailure(payloadSecret);
	}

	@Test
	@DisplayName("다른 event type 또는 version은 같은 payload 형태여도 소비하지 않는다")
	void rejectsUnexpectedContract() {
		String valid = codec.encode(EventEnvelope.of(EVENT_ID, OCCURRED_AT, event()));

		assertSafeFailure(valid.replace(
			"\"event_type\":\"PAYMENT_EXECUTION_REQUESTED\"",
			"\"event_type\":\"OTHER_EVENT\""));
		assertSafeFailure(valid.replace("\"event_version\":\"1\"", "\"event_version\":\"2\""));
	}

	private PaymentExecutionRequested event() {
		return new PaymentExecutionRequested(OPERATION_UID, RESERVATION_UID, 3L);
	}

	private void assertSafeFailure(String rawMessage) {
		Throwable failure = catchThrowable(
			() -> codec.decode(rawMessage, DESCRIPTOR, PaymentExecutionRequested.class));

		assertThat(failure)
			.isInstanceOf(InvalidIntegrationEventException.class)
			.hasMessage("Invalid integration event.")
			.hasNoCause();
		assertThat(failure.toString()).doesNotContain(SECRET, rawMessage);
	}

	private record PaymentExecutionRequested(
		UUID operationUid,
		UUID reservationUid,
		long dispatchGeneration
	) implements IntegrationEvent {

		@Override
		public EventDescriptor descriptor() {
			return DESCRIPTOR;
		}

		@Override
		public String aggregateId() {
			return operationUid.toString();
		}

		@Override
		public String partitionKey() {
			return reservationUid.toString();
		}

		@Override
		public String deduplicationKey() {
			return "PAYMENT_EXECUTION:" + operationUid + ":" + dispatchGeneration;
		}
	}
}
