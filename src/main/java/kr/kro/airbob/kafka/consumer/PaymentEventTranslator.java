package kr.kro.airbob.kafka.consumer;

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
public class PaymentEventTranslator {

	private final DebeziumEventParser debeziumEventParser;
	private final OutboxEventPublisher outboxEventPublisher;

	@KafkaListener(topics = "PAYMENT.events", groupId = "payment-translator-group")
	public void translatePaymentEvents(@Payload String message, Acknowledgment ack) {
		try {
			String eventType = debeziumEventParser.getEventType(message);
			EventType type = EventType.from(eventType);

			if (type == EventType.PAYMENT_COMPLETED) {
				EventEnvelope<PaymentEvent.PaymentCompletedEvent> envelope =
					debeziumEventParser.parse(message, PaymentEvent.PaymentCompletedEvent.class);

				outboxEventPublisher.save(
					EventType.RESERVATION_CONFIRM_REQUESTED,
					envelope.payload()
				);
				log.info("[TRANSLATOR] PAYMENT_COMPLETED -> RESERVATION_CONFIRM_REQUESTED 발행. UID: {}", envelope.payload().reservationUid());

			} else if (type == EventType.PAYMENT_FAILED) {
				EventEnvelope<PaymentEvent.PaymentFailedEvent> envelope =
					debeziumEventParser.parse(message, PaymentEvent.PaymentFailedEvent.class);

				outboxEventPublisher.save(
					EventType.RESERVATION_EXPIRE_REQUESTED,
					envelope.payload()
				);
				log.info("[TRANSLATOR] PAYMENT_FAILED -> RESERVATION_EXPIRE_REQUESTED 발행. UID: {}", envelope.payload().reservationUid());
			}else if (type == EventType.PAYMENT_CANCELLATION_FAILED) {
				EventEnvelope<PaymentEvent.PaymentCancellationFailedEvent> envelope =
					debeziumEventParser.parse(message, PaymentEvent.PaymentCancellationFailedEvent.class);
				PaymentEvent.PaymentCancellationFailedEvent payload = envelope.payload();

				outboxEventPublisher.save(
					EventType.RESERVATION_CANCELLATION_REVERT_REQUESTED,
					new ReservationEvent.ReservationCancellationRevertRequestedEvent(
						payload.reservationUid(),
						payload.reason()
					)
				);
				log.info("[TRANSLATOR] PAYMENT_CANCELLATION_FAILED -> RESERVATION_CANCELLATION_REVERT_REQUESTED 발행. UID: {}", payload.reservationUid());
			}

			ack.acknowledge();
		} catch (DebeziumEventParsingException e) {
			log.error("[KAFKA-POISON] 메시지 파싱 실패 (Translator). 재시도 없이 ack 처리. message={}", message, e);
			ack.acknowledge();
		} catch (Exception e) {
			log.error("[TRANSLATOR-NACK] 결제 이벤트 번역 중 오류 발생. 재시도 예정.", e);
			throw e; // Kafka ErrorHandler 재시도 및 DLQ 전송
		}
	}
}
