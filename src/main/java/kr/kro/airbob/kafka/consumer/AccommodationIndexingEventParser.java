package kr.kro.airbob.kafka.consumer;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.outbox.EventType;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AccommodationIndexingEventParser {

	private static final Set<EventType> SUPPORTED_EVENT_TYPES = EnumSet.of(
		EventType.ACCOMMODATION_UPDATED,
		EventType.ACCOMMODATION_DELETED,
		EventType.REVIEW_SUMMARY_CHANGED,
		EventType.RESERVATION_CHANGED
	);

	private final ObjectMapper objectMapper;

	AccommodationIndexingCommand parse(String message) {
		try {
			JsonNode root = objectMapper.readTree(message);
			EventType eventType = EventType.from(requiredText(root, "event_type"));
			if (!SUPPORTED_EVENT_TYPES.contains(eventType)) {
				throw new IllegalArgumentException();
			}
			UUID accommodationUid = UUID.fromString(
				requiredText(root.path("payload"), "accommodation_uid"));
			return new AccommodationIndexingCommand(eventType, accommodationUid);
		} catch (Exception ignored) {
			throw new AccommodationIndexingEventParsingException();
		}
	}

	Optional<AccommodationIndexingCommand> tryParse(String message) {
		try {
			return Optional.of(parse(message));
		} catch (AccommodationIndexingEventParsingException ignored) {
			return Optional.empty();
		}
	}

	private String requiredText(JsonNode node, String fieldName) {
		JsonNode value = node.path(fieldName);
		if (!value.isTextual() || value.textValue().isBlank()) {
			throw new IllegalArgumentException();
		}
		return value.textValue();
	}
}
