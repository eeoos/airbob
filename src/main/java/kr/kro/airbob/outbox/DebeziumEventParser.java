package kr.kro.airbob.outbox;

import java.io.IOException;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.outbox.exception.DebeziumEventParsingException;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DebeziumEventParser {

	private final ObjectMapper objectMapper;

	public <T extends EventPayload> EventEnvelope<T> parse(String debeziumMessage, Class<T> payloadType) {
		try {
			String envelopeJson = extractEnvelopeJson(debeziumMessage);
			JavaType type = objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
			return objectMapper.readValue(envelopeJson, type);
		} catch (IOException e) {
			throw new DebeziumEventParsingException(e);
		}
	}

	public String getEventType(String debeziumMessage) {
		try {
			String envelopeJson = extractEnvelopeJson(debeziumMessage);
			JsonNode envelopeNode = objectMapper.readTree(envelopeJson);
			return envelopeNode.path("event_type").asText();
		} catch (IOException e) {
			throw new DebeziumEventParsingException(e);
		}
	}

	private String extractEnvelopeJson(String debeziumMessage) throws IOException {
		JsonNode rootNode = objectMapper.readTree(debeziumMessage);
		JsonNode payloadNode = rootNode.path("payload");
		if (payloadNode.isMissingNode() || !payloadNode.isTextual()) {
			throw new IOException("Debezium 메시지에서 'payload' 필드를 찾을 수 없거나 텍스트 형식이 아닙니다.");
		}
		return payloadNode.asText();
	}
}
