package kr.kro.airbob.kafka.consumer;

import java.io.IOException;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventEnvelope;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import kr.kro.airbob.outbox.exception.DebeziumEventParsingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventTranslator {

	private final DebeziumEventParser debeziumEventParser;
	private final OutboxEventPublisher outboxEventPublisher;

	@KafkaListener(topics = "RESERVATION.events", groupId = "translator-group")
	public void translateReservationEvents(@Payload String message, Acknowledgment ack) {
		try {
			String eventType = debeziumEventParser.getEventType(message);

			if (EventType.RESERVATION_CANCELLED.name().equals(eventType)) {
				EventEnvelope<ReservationEvent.ReservationCancelledEvent> envelope =
					debeziumEventParser.parse(message, ReservationEvent.ReservationCancelledEvent.class);
				ReservationEvent.ReservationCancelledEvent payload = envelope.payload();

				outboxEventPublisher.save(
					EventType.PAYMENT_CANCELLATION_REQUESTED,
					new PaymentEvent.PaymentCancellationRequestedEvent(
						payload.reservationUid(),
						payload.cancelReason(),
						payload.cancelAmount()
					)
				);
				log.info("[TRANSLATOR] RESERVATION_CANCELLED -> PAYMENT_CANCELLATION_REQUESTED 발행. UID: {}", payload.reservationUid());
			}
			ack.acknowledge();
		} catch (DebeziumEventParsingException e) {
			log.error("[KAFKA-POISON] 메시지 파싱 실패. 재시도 없이 ack 처리. message={}", message, e);
			ack.acknowledge();
		} catch (Exception e) {
			log.error("[TRANSLATOR-NACK] 예약 이벤트 번역 중 오류 발생. 재시도 예정.", e);
			throw e; // Kafka ErrorHandler 재시도 및 DLQ 전송
		}
	}

}
