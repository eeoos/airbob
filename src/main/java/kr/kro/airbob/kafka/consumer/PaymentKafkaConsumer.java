package kr.kro.airbob.kafka.consumer;

import static kr.kro.airbob.outbox.EventType.*;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.service.PaymentService;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentKafkaConsumer {

	private final PaymentService paymentService;
	private final DebeziumEventParser debeziumEventParser;

	@KafkaListener(topics = "PAYMENT.events", groupId = "payment-group")
	@Transactional
	public void handlePaymentEvents(@Payload String message) throws Exception {
		DebeziumEventParser.ParsedEvent parsedEvent = debeziumEventParser.parse(message);
		String eventType = parsedEvent.eventType();
		String payloadJson = parsedEvent.payload();

		if (EventType.PAYMENT_CONFIRM_REQUESTED.name().equals(eventType)) {
			PaymentRequest.Confirm event =
				debeziumEventParser.deserialize(payloadJson, PaymentRequest.Confirm.class);
			paymentService.processPaymentConfirmation(event);
		}
	}

	@KafkaListener(topics = "RESERVATION.events", groupId = "payment-group")
	@Transactional
	public void handleReservationEvents(@Payload String message) throws Exception {
		log.info("[KAFKA-CONSUME] Reservation Event 수신: {}", message);

		DebeziumEventParser.ParsedEvent parsedEvent = debeziumEventParser.parse(message);
		String eventType = parsedEvent.eventType();
		String payloadJson = parsedEvent.payload();

		if (EventType.RESERVATION_CANCELLED.name().equals(eventType)) {
			ReservationEvent.ReservationCancelledEvent event =
				debeziumEventParser.deserialize(payloadJson, ReservationEvent.ReservationCancelledEvent.class);
			paymentService.processPaymentCancellation(event);
		}else if (EventType.RESERVATION_CONFIRMATION_FAILED.name().equals(eventType)) { // 추가된 부분
			ReservationEvent.ReservationConfirmationFailedEvent event =
				debeziumEventParser.deserialize(payloadJson, ReservationEvent.ReservationConfirmationFailedEvent.class);
			paymentService.compensatePayment(event);
		}
	}
}
