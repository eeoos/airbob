package kr.kro.airbob.kafka.router;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventRouter {

	private final DebeziumEventParser parser;
	private final KafkaTemplate<String, String> kafkaTemplate;

	// 1. Debezium이 발행하는 원본 토픽을 구독합니다.

	@KafkaListener(topics = "outbox.event.mysql-server-1.airbob.outbox", groupId = "event-router-group")
	public void route(String message) {
		try {
			DebeziumEventParser.ParsedEvent parsedEvent = parser.parse(message);
			String payload = parsedEvent.payload();

			// 1. 문자열 eventType을 Enum으로 변환
			EventType eventType = EventType.from(parsedEvent.eventType());

			// 2. Enum에 정의된 토픽으로 메시지 전송
			if (eventType != EventType.UNKNOWN && eventType.getTopic() != null) {
				kafkaTemplate.send(eventType.getTopic(), payload);
				log.info("라우팅 완료: {} -> '{}' 토픽", eventType.name(), eventType.getTopic());
			} else {
				log.warn("처리할 수 없는 이벤트 타입이거나 토픽이 지정되지 않았습니다: {}", parsedEvent.eventType());
			}

		} catch (Exception e) {
			log.error("이벤트 라우팅 중 오류 발생: message={}", message, e);
			// TODO: 라우팅 실패 메시지는 DLQ로 보내야 합니다.
		}
	}

}
