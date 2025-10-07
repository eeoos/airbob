package kr.kro.airbob.kafka.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.search.event.AccommodationIndexingEvents.*;
import kr.kro.airbob.search.service.AccommodationIndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccommodationIndexingConsumer {

	private final DebeziumEventParser debeziumEventParser;
	private final AccommodationIndexingService indexingService;

	@KafkaListener(topics = "ACCOMMODATION.events", groupId = "indexing-group")
	@Transactional(readOnly = true)
	public void handleAccommodationEvents(
		@Payload String payloadJson,
		@Header("eventType") String eventType) throws Exception {

		eventType = eventType.replace("\"", "");

		log.info("[KAFKA-CONSUME] Accommodation Indexing Event 수신: type={}, message={}", eventType, payloadJson);

		// 이벤트 타입에 따른 해당 색인 서비스 호출
		switch (EventType.from(eventType)) {
			case ACCOMMODATION_CREATED -> {
				var event = debeziumEventParser.deserialize(payloadJson, AccommodationCreatedEvent.class);
				indexingService.indexNewAccommodation(event);
			}
			case ACCOMMODATION_UPDATED -> {
				var event = debeziumEventParser.deserialize(payloadJson, AccommodationUpdatedEvent.class);
				indexingService.updateAccommodationIndex(event);
			}
			case ACCOMMODATION_DELETED -> {
				var event = debeziumEventParser.deserialize(payloadJson, AccommodationDeletedEvent.class);
				indexingService.deleteAccommodationIndex(event);
			}
			case REVIEW_SUMMARY_CHANGED -> {
				var event = debeziumEventParser.deserialize(payloadJson, ReviewSummaryChangedEvent.class);
				indexingService.updateReviewSummaryInIndex(event);
			}
			case RESERVATION_CHANGED -> {
				var event = debeziumEventParser.deserialize(payloadJson, ReservationChangedEvent.class);
				indexingService.updateReservedDatesInIndex(event);
			}
			default -> log.warn("알 수 없는 색인 이벤트 타입입니다: {}", eventType);
		}
	}
}
