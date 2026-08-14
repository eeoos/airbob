package kr.kro.airbob.config;

import static kr.kro.airbob.outbox.EventType.PAYMENT_EXECUTION_REQUESTED_V1;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.KafkaHeaders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class PaymentOperationKafkaPublisherConfig {

	private static final Set<String> APPROVED_ENVELOPE_FIELDS = Set.of(
		"event_id", "trace_id", "event_type", "event_version", "timestamp", "payload");
	private static final Set<String> APPROVED_PAYLOAD_FIELDS = Set.of(
		"operation_uid", "reservation_uid");
	private static final Set<String> SENSITIVE_FAILURE_HEADERS = Set.of(
		KafkaHeaders.EXCEPTION_MESSAGE,
		KafkaHeaders.EXCEPTION_STACKTRACE,
		KafkaHeaders.KEY_EXCEPTION_MESSAGE,
		KafkaHeaders.KEY_EXCEPTION_STACKTRACE,
		KafkaHeaders.DLT_EXCEPTION_MESSAGE,
		KafkaHeaders.DLT_EXCEPTION_STACKTRACE,
		KafkaHeaders.DLT_KEY_EXCEPTION_MESSAGE,
		KafkaHeaders.DLT_KEY_EXCEPTION_STACKTRACE);
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

		private SanitizingInterceptor(ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
		}

		@Override
		public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
			SanitizedPayload sanitized = sanitize(record.value());
			Headers sanitizedHeaders = new RecordHeaders(record.headers().toArray());
			SENSITIVE_FAILURE_HEADERS.forEach(sanitizedHeaders::remove);
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
				JsonNode root = objectMapper.readTree(value);
				Optional<UUID> operationUid = readOperationUid(root);
				if (operationUid.isEmpty()) {
					return SanitizedPayload.poison();
				}
				if (isApprovedIdentifierOnlyEnvelope(root)) {
					String reservationKey = readUuid(root.path("payload").path("reservation_uid"))
						.orElseThrow()
						.toString();
					return new SanitizedPayload(value, reservationKey);
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
				&& isInstant(root.path("timestamp"))
				&& readUuid(payload.path("operation_uid")).isPresent()
				&& readUuid(payload.path("reservation_uid")).isPresent();
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

		private boolean isInstant(JsonNode node) {
			if (!node.isTextual()) {
				return false;
			}
			try {
				Instant.parse(node.asText());
				return true;
			} catch (RuntimeException ignored) {
				return false;
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
