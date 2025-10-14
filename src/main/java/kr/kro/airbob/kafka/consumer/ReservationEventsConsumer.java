package kr.kro.airbob.kafka.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.service.ReservationHoldService;
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
	private final ReservationHoldService reservationHoldService;
	private final DebeziumEventParser debeziumEventParser;

	@KafkaListener(topics = "RESERVATION.events", groupId = "reservation-group")
	public void handleReservationEvents(@Payload String message, Acknowledgment ack) {
		try {
			String eventType = debeziumEventParser.getEventType(message);

			switch (EventType.from(eventType)) {
				case RESERVATION_CONFIRM_REQUESTED -> {
					EventEnvelope<PaymentEvent.PaymentCompletedEvent> envelope =
						debeziumEventParser.parse(message, PaymentEvent.PaymentCompletedEvent.class);
					reservationService.confirmReservation(envelope.payload());
				}
				case RESERVATION_CONFIRMED -> {
					EventEnvelope<ReservationEvent.ReservationConfirmedEvent> envelope =
						debeziumEventParser.parse(message, ReservationEvent.ReservationConfirmedEvent.class);
					ReservationEvent.ReservationConfirmedEvent event = envelope.payload();
					reservationHoldService.removeHold(event.accommodationId(), event.checkInDate(), event.checkOutDate());
					log.info("[KAFKA] 예약 확정 완료. Redis 홀드 제거. Accommodation ID={}", event.accommodationId());
				}
				case RESERVATION_EXPIRE_REQUESTED -> {
					EventEnvelope<PaymentEvent.PaymentFailedEvent> envelope =
						debeziumEventParser.parse(message, PaymentEvent.PaymentFailedEvent.class);
					reservationService.expireReservation(envelope.payload());
				}
				case RESERVATION_EXPIRED -> {
					EventEnvelope<ReservationEvent.ReservationExpiredEvent> envelope =
						debeziumEventParser.parse(message, ReservationEvent.ReservationExpiredEvent.class);
					ReservationEvent.ReservationExpiredEvent event = envelope.payload();
					reservationHoldService.removeHold(event.accommodationId(), event.checkInDate(), event.checkOutDate());
					log.info("[KAFKA] 예약 만료 완료. Redis 홀드 제거. Accommodation ID={}", event.accommodationId());
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
