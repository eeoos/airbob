package kr.kro.airbob.outbox;

import java.io.IOException;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.outbox.exception.DebeziumEventParsingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class DebeziumEventParser {

	private final ObjectMapper objectMapper;

	public <T extends EventPayload> EventEnvelope<T> parse(String eventEnvelopeJson, Class<T> payloadType) {
		try {
			JavaType type = objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
			return objectMapper.readValue(eventEnvelopeJson, type);
		} catch (IOException e) {
			log.error("EventEnvelope 파싱 실패 JSON: {}", eventEnvelopeJson, e);
			throw new DebeziumEventParsingException(e);
		}
	}

	public String getEventType(String eventEnvelopeJson) {
		try {
			JsonNode envelopeNode = objectMapper.readTree(eventEnvelopeJson);
			if (envelopeNode.hasNonNull("event_type")) {
				return envelopeNode.path("event_type").asText();
			} else {
				throw new IOException("event_type 필드가 NULL이거나 존재하지 않음. EventEnvelope JSON: " + eventEnvelopeJson);
			}
		} catch (IOException e) {
			log.error("EventEnvelope에서 eventType 획득 실패 JSON: {}", eventEnvelopeJson, e);
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
