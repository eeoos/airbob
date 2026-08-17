package kr.kro.airbob.config;

import static kr.kro.airbob.outbox.EventType.PAYMENT_EXECUTION_REQUESTED_V1;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;

import kr.kro.airbob.kafka.PaymentOperationKafkaHeaders;

@Configuration(proxyBeanMethods = false)
public class PaymentOperationKafkaPublisherConfig {

	private static final Set<String> APPROVED_ENVELOPE_FIELDS = Set.of(
		"event_id", "trace_id", "event_type", "event_version", "timestamp", "payload");
	private static final Set<String> APPROVED_PAYLOAD_FIELDS = Set.of(
		"operation_uid", "reservation_uid");
	private static final String SANITIZED_POISON = "{\"event_type\":\"UNKNOWN\",\"payload\":{}}";

	@Bean
	public KafkaTemplate<String, String> paymentOperationRetryKafkaTemplate(
		@Qualifier("deadLetterProducerFactory") ProducerFactory<String, String> producerFactory,
		ObjectMapper objectMapper
	) {
		KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>(producerFactory);
		kafkaTemplate.setProducerInterceptor(new SanitizingInterceptor(objectMapper));
		return kafkaTemplate;
	}

	private static final class SanitizingInterceptor implements ProducerInterceptor<String, String> {
		private final ObjectMapper objectMapper;
		private final ObjectReader strictReader;

		private SanitizingInterceptor(ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
			this.strictReader = objectMapper.readerFor(JsonNode.class)
				.with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
				.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
		}

		@Override
		public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
			SanitizedPayload sanitized = sanitize(record.value());
			Headers sanitizedHeaders = PaymentOperationKafkaHeaders.copyValidatedFrameworkOwned(
				record.headers(), record.topic(), record.partition());
			return new ProducerRecord<>(
				record.topic(),
				record.partition(),
				record.timestamp(),
				sanitized.key(),
				sanitized.value(),
				sanitizedHeaders
			);
		}

		private SanitizedPayload sanitize(String value) {
			try {
				JsonNode root = strictReader.readTree(value);
				Optional<UUID> operationUid = readOperationUid(root);
				if (operationUid.isEmpty()) {
					return SanitizedPayload.poison();
				}
				if (isApprovedIdentifierOnlyEnvelope(root)) {
					return canonicalEnvelope(root);
				}
				return new SanitizedPayload(sanitizedExecutionRequest(operationUid.get()), null);
			} catch (Exception ignored) {
				return SanitizedPayload.poison();
			}
		}

		private Optional<UUID> readOperationUid(JsonNode root) {
			if (root == null
				|| !root.isObject()
				|| !PAYMENT_EXECUTION_REQUESTED_V1.name().equals(root.path("event_type").asText())) {
				return Optional.empty();
			}
			return readUuid(root.path("payload").path("operation_uid"));
		}

		private boolean isApprovedIdentifierOnlyEnvelope(JsonNode root) {
			JsonNode payload = root.path("payload");
			return hasOnlyFields(root, APPROVED_ENVELOPE_FIELDS)
				&& payload.isObject()
				&& hasOnlyFields(payload, APPROVED_PAYLOAD_FIELDS)
				&& readUuid(root.path("event_id")).isPresent()
				&& readUuid(root.path("trace_id")).isPresent()
				&& "1.0".equals(root.path("event_version").asText())
				&& isSupportedTimestamp(root.path("timestamp"))
				&& readUuid(payload.path("operation_uid")).isPresent()
				&& readUuid(payload.path("reservation_uid")).isPresent();
		}

		private SanitizedPayload canonicalEnvelope(JsonNode root) throws JsonProcessingException {
			UUID eventId = readUuid(root.path("event_id")).orElseThrow();
			UUID traceId = readUuid(root.path("trace_id")).orElseThrow();
			JsonNode payload = root.path("payload");
			UUID operationUid = readUuid(payload.path("operation_uid")).orElseThrow();
			UUID reservationUid = readUuid(payload.path("reservation_uid")).orElseThrow();

			ObjectNode canonical = objectMapper.createObjectNode();
			canonical.put("event_id", eventId.toString());
			canonical.put("trace_id", traceId.toString());
			canonical.put("event_type", PAYMENT_EXECUTION_REQUESTED_V1.name());
			canonical.put("event_version", "1.0");
			canonical.put("timestamp", root.path("timestamp").asText());
			ObjectNode canonicalPayload = canonical.putObject("payload");
			canonicalPayload.put("operation_uid", operationUid.toString());
			canonicalPayload.put("reservation_uid", reservationUid.toString());
			return new SanitizedPayload(
				objectMapper.writeValueAsString(canonical), reservationUid.toString());
		}

		private boolean hasOnlyFields(JsonNode node, Set<String> approvedFields) {
			return node.properties().stream().allMatch(entry -> approvedFields.contains(entry.getKey()));
		}

		private Optional<UUID> readUuid(JsonNode node) {
			if (!node.isTextual()) {
				return Optional.empty();
			}
			try {
				return Optional.of(UUID.fromString(node.asText()));
			} catch (IllegalArgumentException ignored) {
				return Optional.empty();
			}
		}

		private boolean isSupportedTimestamp(JsonNode node) {
			if (!node.isTextual()) {
				return false;
			}
			try {
				OffsetDateTime.parse(node.asText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
				return true;
			} catch (RuntimeException ignoredOffset) {
				try {
					LocalDateTime.parse(node.asText(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
					return true;
				} catch (RuntimeException ignoredLocal) {
					return false;
				}
			}
		}

		private String sanitizedExecutionRequest(UUID operationUid) {
			return "{\"event_type\":\"PAYMENT_EXECUTION_REQUESTED_V1\","
				+ "\"payload\":{\"operation_uid\":\"" + operationUid + "\"}}";
		}

		@Override
		public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
		}

		@Override
		public void close() {
		}

		@Override
		public void configure(Map<String, ?> configs) {
		}
	}

	private record SanitizedPayload(String value, String key) {
		private static SanitizedPayload poison() {
			return new SanitizedPayload(SANITIZED_POISON, null);
		}
	}
}
