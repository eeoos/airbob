package kr.kro.airbob.kafka.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.exception.DebeziumEventParsingException;
import kr.kro.airbob.search.event.AccommodationIndexingEvents.AccommodationCreatedEvent;
import kr.kro.airbob.search.event.AccommodationIndexingEvents.AccommodationDeletedEvent;
import kr.kro.airbob.search.event.AccommodationIndexingEvents.AccommodationUpdatedEvent;
import kr.kro.airbob.search.event.AccommodationIndexingEvents.ReservationChangedEvent;
import kr.kro.airbob.search.event.AccommodationIndexingEvents.ReviewSummaryChangedEvent;
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
	public void handleAccommodationEvents(@Payload String payloadJson, @Header("eventType") String eventType, Acknowledgment ack) {

		eventType = eventType.replace("\"", "");
		log.info("[KAFKA-CONSUME] Accommodation Indexing Event 수신: type={}, message={}", eventType, payloadJson);

		try {
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

			// 모든 작업이 성공적으로 끝나면 Kafka에 메시지 처리 완료 알림
			ack.acknowledge();
			log.info("[KAFKA-ACK] ES 색인 작업 성공. Offset 커밋. EventType: {}", eventType);

		} catch (DebeziumEventParsingException e) {
			log.error("[INDEX-POISON][DEBEZIUM] 메시지 파싱 실패 - 재시도 불필요: {}", payloadJson, e);
			ack.acknowledge();
		} catch (IllegalArgumentException e) {
			log.error("[INDEX-POISON][FIELD] 필드 형식 불일치(UUID/enum 변환 실패 등) - 재시도 불필요: {}", payloadJson, e);
			ack.acknowledge();
		} catch (Exception e) {
			log.error("[INDEX-NACK] 인덱싱 처리 중 예외 발생 - 재시도 예정", e);
			throw e;
		}
	}
}
