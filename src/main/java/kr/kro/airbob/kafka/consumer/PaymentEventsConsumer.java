package kr.kro.airbob.kafka.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.service.PaymentService;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventsConsumer {

	private final PaymentService paymentService;
	private final ReservationService reservationService;
	private final DebeziumEventParser debeziumEventParser;

	@KafkaListener(topics = "PAYMENT.events", groupId = "payment-group")
	@Transactional
	public void handlePaymentEvents(@Payload String message) throws Exception {
		DebeziumEventParser.ParsedEvent parsedEvent = debeziumEventParser.parse(message);
		String eventType = parsedEvent.eventType();
		String payloadJson = parsedEvent.payload();

		log.info("[KAFKA-CONSUME] Payment Event 수신: type={}, message={}", eventType, payloadJson);

		switch (EventType.from(eventType)) {
			case PAYMENT_CONFIRM_REQUESTED -> {
				PaymentRequest.Confirm event =
					debeziumEventParser.deserialize(payloadJson, PaymentRequest.Confirm.class);
				paymentService.processPaymentConfirmation(event);
			}
			case PAYMENT_SUCCEEDED -> {
				PaymentEvent.PaymentSucceededEvent event =
					debeziumEventParser.deserialize(payloadJson, PaymentEvent.PaymentSucceededEvent.class);
				reservationService.handlePaymentSucceeded(event);
			}
			case PAYMENT_FAILED -> {
				PaymentEvent.PaymentFailedEvent event =
					debeziumEventParser.deserialize(payloadJson, PaymentEvent.PaymentFailedEvent.class);
				reservationService.handlePaymentFailed(event);
			}
			default -> log.warn("알 수 없는 결제 이벤트 타입입니다: {}", eventType);
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
		} else if (EventType.RESERVATION_CONFIRMATION_FAILED.name().equals(eventType)) {
			ReservationEvent.ReservationConfirmationFailedEvent event =
				debeziumEventParser.deserialize(payloadJson, ReservationEvent.ReservationConfirmationFailedEvent.class);
			paymentService.compensatePayment(event);
		}else {
			log.warn("알 수 없는 예약 이벤트 타입입니다: {}", eventType);
		}
	}
}
