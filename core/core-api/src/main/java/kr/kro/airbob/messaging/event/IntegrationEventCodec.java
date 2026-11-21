package kr.kro.airbob.messaging.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

@Component
public class IntegrationEventCodec {

	private final ObjectMapper objectMapper;

	public IntegrationEventCodec(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper.copy()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
	}

	public String encode(EventEnvelope<? extends IntegrationEvent> envelope) {
		try {
			return objectMapper.writeValueAsString(envelope);
		} catch (Exception exception) {
			throw new IntegrationEventEncodingException();
		}
	}

	public <T extends IntegrationEvent> EventEnvelope<T> decode(
		String rawMessage,
		EventDescriptor expectedDescriptor,
		Class<T> payloadType
	) {
		try {
			Objects.requireNonNull(expectedDescriptor);
			Objects.requireNonNull(payloadType);
			RawEventEnvelope rawEnvelope = objectMapper.readerFor(RawEventEnvelope.class)
				.readValue(rawMessage);
			validateEnvelope(rawEnvelope, expectedDescriptor);

			UUID eventId = UUID.fromString(rawEnvelope.eventId());
			Instant occurredAt = parseCanonicalInstant(rawEnvelope.occurredAt());
			T payload = objectMapper.readerFor(payloadType)
				.readValue(rawEnvelope.payload().traverse(objectMapper));
			validatePayload(payload, expectedDescriptor);

			return new EventEnvelope<>(
				eventId,
				rawEnvelope.eventType(),
				rawEnvelope.eventVersion(),
				occurredAt,
				payload
			);
		} catch (Exception exception) {
			throw new InvalidIntegrationEventException();
		}
	}

	private void validateEnvelope(RawEventEnvelope envelope, EventDescriptor expectedDescriptor) {
		Objects.requireNonNull(envelope);
		if (!expectedDescriptor.eventType().equals(envelope.eventType())
			|| !expectedDescriptor.eventVersion().equals(envelope.eventVersion())
			|| envelope.eventId() == null
			|| envelope.occurredAt() == null
			|| envelope.payload() == null
			|| !envelope.payload().isObject()) {
			throw new IllegalArgumentException("unexpected integration event contract");
		}
	}

	private Instant parseCanonicalInstant(String value) {
		if (!value.endsWith("Z")) {
			throw new IllegalArgumentException("occurredAt must use UTC Z notation");
		}
		return Instant.parse(value);
	}

	private void validatePayload(IntegrationEvent payload, EventDescriptor expectedDescriptor) {
		Objects.requireNonNull(payload);
		if (!expectedDescriptor.equals(payload.descriptor())) {
			throw new IllegalArgumentException("payload descriptor does not match envelope");
		}
		requireText(payload.aggregateId());
		requireText(payload.partitionKey());
	}

	private void requireText(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("event routing key must not be blank");
		}
	}

	private record RawEventEnvelope(
		String eventId,
		String eventType,
		String eventVersion,
		String occurredAt,
		JsonNode payload
	) {
	}
}
