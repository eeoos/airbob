package kr.kro.airbob.kafka.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventEnvelope;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.exception.DebeziumEventParsingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventsConsumer {

	private final ReservationService reservationService;
	private final DebeziumEventParser debeziumEventParser;

	@KafkaListener(topics = "RESERVATION.events", groupId = "reservation-group")
	public void handleReservationEvents(@Payload String message, Acknowledgment ack) {
		try {
			String eventType = debeziumEventParser.getEventType(message);

			switch (EventType.from(eventType)) {
				case RESERVATION_CANCELLATION_REVERT_REQUESTED -> {
					EventEnvelope<ReservationEvent.ReservationCancellationRevertRequestedEvent> envelope =
						debeziumEventParser.parse(message, ReservationEvent.ReservationCancellationRevertRequestedEvent.class);
					ReservationEvent.ReservationCancellationRevertRequestedEvent event = envelope.payload();
					reservationService.revertCancellation(event);
				}
				case RESERVATION_CANCELLATION_COMPLETE_REQUESTED -> {
					EventEnvelope<ReservationEvent.ReservationCancellationCompleteRequestedEvent> envelope =
						debeziumEventParser.parse(
							message, ReservationEvent.ReservationCancellationCompleteRequestedEvent.class);
					reservationService.completeCancellation(envelope.payload());
				}
				/*case RESERVATION_PENDING -> {
					// 추후 알림과 같은 기능 생기면 로직 추가
				}*/
				default -> log.warn("[KAFKA-SKIP] 알 수 없는 예약 이벤트 타입: {}", eventType);
			}
			ack.acknowledge();
		} catch (DebeziumEventParsingException e) {
			log.error("[KAFKA-POISON] 메시지 파싱 실패. 재시도 없이 ack 처리. message={}", message, e);
			ack.acknowledge();
		} catch (Exception e) {
			log.error("[KAFKA-NACK] Reservation 이벤트 처리 중 예외 발생. 재시도 예정.", e);
			throw e;
		}
	}
}
