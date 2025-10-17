package kr.kro.airbob.kafka.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.service.PaymentCancellationProcessor;
import kr.kro.airbob.domain.payment.service.PaymentConfirmationProcessor;
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

	private final DebeziumEventParser debeziumEventParser;
	private final OutboxEventPublisher outboxEventPublisher;
	private final PaymentConfirmationProcessor confirmationProcessor;
	private final PaymentCancellationProcessor cancellationProcessor;
	@KafkaListener(topics = "PAYMENT.events", groupId = "payment-group")
	public void handlePaymentEvents(@Payload String message, Acknowledgment ack) {
		try {
			String eventType = debeziumEventParser.getEventType(message);

			switch (EventType.from(eventType)) {
				case PAYMENT_CONFIRM_REQUESTED -> {
					EventEnvelope<PaymentRequest.Confirm> envelope =
						debeziumEventParser.parse(message, PaymentRequest.Confirm.class);

					outboxEventPublisher.save(
						EventType.PG_CALL_REQUESTED,
						envelope.payload()
					);
					log.info("[KAFKA] PG사 결제 승인 API 호출 요청 이벤트 발행. Order ID={}", envelope.payload().orderId());
				}
				case PG_CALL_SUCCEEDED -> {
					EventEnvelope<PaymentEvent.PgCallSucceededEvent> envelope =
						debeziumEventParser.parse(message, PaymentEvent.PgCallSucceededEvent.class);
					confirmationProcessor.processSuccess(envelope.payload());
				}
				case PG_CALL_FAILED -> {
					EventEnvelope<PaymentEvent.PgCallFailedEvent> envelope =
						debeziumEventParser.parse(message, PaymentEvent.PgCallFailedEvent.class);
					confirmationProcessor.processFailure(envelope.payload());
				}
				case PG_CANCEL_CALL_SUCCEEDED -> {
					EventEnvelope<PaymentEvent.PgCancelCallSucceededEvent> envelope =
						debeziumEventParser.parse(message, PaymentEvent.PgCancelCallSucceededEvent.class);
					cancellationProcessor.processSuccess(envelope.payload());
				}
				case PG_CANCEL_CALL_FAILED -> {
					EventEnvelope<PaymentEvent.PgCancelCallFailedEvent> envelope =
						debeziumEventParser.parse(message, PaymentEvent.PgCancelCallFailedEvent.class);
					cancellationProcessor.processFailure(envelope.payload());
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
