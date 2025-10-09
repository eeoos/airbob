package kr.kro.airbob.kafka.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.service.PaymentService;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.SlackNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DlqConsumer {

	private static final String ALERT_MESSAGE = """
		🚨 *[DLQ-FATAL]* 🚨
		DLQ 메시지 자동 보상 처리 중 최종 실패! *수동 개입*이 필요합니다.
		                
		• *EventType*: `%s`
		• *Exception*: `%s`
		• *Error Message*: `%s`
		• *Original Message*: ```%s```
		""";

	private final PaymentService paymentService;
	private final DebeziumEventParser debeziumEventParser;
	private final SlackNotificationService slackNotificationService;

	@KafkaListener(topics = "${spring.kafka.consumer.properties.spring.kafka.dead-letter-publishing.topic-name}", groupId = "dlq-group")
	public void consumeDlqEvents(@Payload String message) {
		log.warn("[DLQ-CONSUME] DLQ 메시지 수신: {}", message);
		DebeziumEventParser.ParsedEvent parsedEvent = null;

		try {
			parsedEvent = debeziumEventParser.parse(message);
			String eventType = parsedEvent.eventType();
			String payloadJson = parsedEvent.payload();

			if (EventType.PAYMENT_SUCCEEDED.name().equals(eventType)) {
				PaymentEvent.PaymentSucceededEvent event = debeziumEventParser.deserialize(payloadJson,
					PaymentEvent.PaymentSucceededEvent.class);

				log.warn("[DLQ-COMPENSATION] 예약 확정 실패에 대한 결제 보상 트랜잭션 시작. ReservationUID: {}", event.reservationUid());

				paymentService.compensatePaymentByReservationUid(event.reservationUid());

			} else {
				log.warn("[DLQ-IGNORE] 처리 로직이 존재하지 않는 DLQ 메시지. EventType: {}", eventType);
			}
		} catch (Exception e) {
			log.error("[DLQ-FATAL] DLQ 메시지 자동 처리 중 심각한 오류 발생. 수동 개입 필요. Message: {}", message, e);

			String alertMessage = String.format(ALERT_MESSAGE,
				(parsedEvent != null ? parsedEvent.eventType() : "Unknown"),
				e.getClass().getSimpleName(),
				e.getMessage(),
				message);
			slackNotificationService.sendAlert(alertMessage);
		}
	}
}
