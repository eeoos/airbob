package kr.kro.airbob.outbox;

import java.io.IOException;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.outbox.exception.DebeziumEventParsingException;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DebeziumEventParser {

	public static final String PAYLOAD = "payload";
	private final ObjectMapper objectMapper;

	public ParsedEvent parse(String message) {
		try {
			JsonNode rootNode = objectMapper.readTree(message);

			JsonNode payloadNode = rootNode.path(PAYLOAD);
			if (payloadNode.isMissingNode() || payloadNode.isNull()) {
				throw new IOException("Debezium 메시지에 payload 필드가 없습니다.");
			}

			String eventType = payloadNode.path("eventType").asText();
			String eventPayload = payloadNode.path("payload").asText();

			if (eventType.isEmpty() || eventPayload.isEmpty()) {
				throw new IOException("Outbox 이벤트에 eventType 또는 payload 필드가 비어있습니다.");
			}

			return new ParsedEvent(eventType, eventPayload);
		} catch (IOException e) {
			throw new DebeziumEventParsingException(e);
		}
	}

	public <T extends EventPayload> T deserialize(String payloadJson, Class<T> payloadType) {
		try {
			return objectMapper.readValue(payloadJson, payloadType);
		} catch (IOException e) {
			throw new DebeziumEventParsingException(e);
		}
	}

	public record ParsedEvent(String eventType, String payload) {
	}
}
