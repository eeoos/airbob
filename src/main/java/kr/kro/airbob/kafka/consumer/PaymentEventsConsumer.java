package kr.kro.airbob.kafka.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.service.PaymentCancellationService;
import kr.kro.airbob.domain.payment.service.PaymentConfirmationService;
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
public class PaymentEventsConsumer {

	private final PaymentCancellationService paymentCancellationService;
	private final PaymentConfirmationService paymentConfirmationService;
	private final DebeziumEventParser debeziumEventParser;
	private final OutboxEventPublisher outboxEventPublisher;

	@KafkaListener(topics = "PAYMENT.events", groupId = "payment-group")
	public void handlePaymentEvents(@Payload String message, Acknowledgment ack) {
		try {
			String eventType = debeziumEventParser.getEventType(message);

			switch (EventType.from(eventType)) {
				case PAYMENT_CONFIRM_REQUESTED -> {
					EventEnvelope<PaymentRequest.Confirm> envelope =
						debeziumEventParser.parse(message, PaymentRequest.Confirm.class);
					paymentConfirmationService.processPaymentConfirmation(envelope.payload());
				}
				case PAYMENT_COMPLETED -> {
					EventEnvelope<PaymentEvent.PaymentCompletedEvent> envelope =
						debeziumEventParser.parse(message, PaymentEvent.PaymentCompletedEvent.class);
					outboxEventPublisher.save(
						EventType.RESERVATION_CONFIRM_REQUESTED,
						envelope.payload()
					);
					log.info("[KAFKA] 결제 완료. 예약 확정 요청 이벤트 발행. UID={}", envelope.payload().reservationUid());
				}
				case PAYMENT_FAILED -> {
					EventEnvelope<PaymentEvent.PaymentFailedEvent> envelope =
						debeziumEventParser.parse(message, PaymentEvent.PaymentFailedEvent.class);
					outboxEventPublisher.save(
						EventType.RESERVATION_EXPIRE_REQUESTED,
						envelope.payload()
					);
					log.info("[KAFKA] 결제 실패. 예약 만료 요청 이벤트 발행. UID={}", envelope.payload().reservationUid());
				}
				case PAYMENT_CANCELLATION_REQUESTED -> {
					EventEnvelope<PaymentEvent.PaymentCancellationRequestedEvent> envelope =
						debeziumEventParser.parse(message, PaymentEvent.PaymentCancellationRequestedEvent.class);
					paymentCancellationService.process(envelope.payload());
				}
				default -> log.warn("[KAFKA-SKIP] 알 수 없는 결제 이벤트 타입: {}", eventType);
			}
			ack.acknowledge();
		} catch (DebeziumEventParsingException e) {
			log.error("[KAFKA-POISON] 메시지 파싱 실패. 재시도 없이 ack 처리. message={}", message, e);
			ack.acknowledge();
		} catch (Exception e) {
			log.error("[KAFKA-NACK] Payment 이벤트 처리 중 예외 발생. 재시도 예정.", e);
			throw e;
		}
	}
}
