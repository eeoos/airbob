package kr.kro.airbob.kafka.consumer;

import static kr.kro.airbob.outbox.EventType.PAYMENT_EXECUTION_REQUESTED_V1;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

@Component
public final class PaymentOperationEventParser {
	private final ObjectReader strictReader;

	public PaymentOperationEventParser(ObjectMapper objectMapper) {
		this.strictReader = objectMapper.readerFor(JsonNode.class)
			.with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
			.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	}

	public UUID parseOperationUid(String message) {
		return tryReadOperationUid(message)
			.orElseThrow(PaymentOperationEventParsingException::new);
	}

	public Optional<UUID> tryReadOperationUid(String message) {
		try {
			JsonNode root = strictReader.readTree(message);
			if (root == null
				|| !root.isObject()
				|| !PAYMENT_EXECUTION_REQUESTED_V1.name().equals(root.path("event_type").asText())) {
				return Optional.empty();
			}
			JsonNode operationUid = root.path("payload").path("operation_uid");
			if (!operationUid.isTextual()) {
				return Optional.empty();
			}
			return Optional.of(UUID.fromString(operationUid.asText()));
		} catch (Exception ignored) {
			return Optional.empty();
		}
	}
}
