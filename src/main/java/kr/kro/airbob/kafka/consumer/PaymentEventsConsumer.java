package kr.kro.airbob.kafka.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.exception.TossPaymentResponseParsingException;
import kr.kro.airbob.domain.payment.service.PaymentService;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.exception.DebeziumEventParsingException;
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
	public void handlePaymentEvents(@Payload String message, Acknowledgment ack) {
		try {
			DebeziumEventParser.ParsedEvent parsedEvent = debeziumEventParser.parse(message);
			String eventType = parsedEvent.eventType();
			String payloadJson = parsedEvent.payload();

			switch (EventType.from(eventType)) {
				case PAYMENT_CONFIRM_REQUESTED -> {
					PaymentRequest.Confirm event =
						debeziumEventParser.deserialize(payloadJson, PaymentRequest.Confirm.class);
					paymentService.processPaymentConfirmation(event, ack);
				}
				case PAYMENT_SUCCEEDED -> {
					PaymentEvent.PaymentSucceededEvent event =
						debeziumEventParser.deserialize(payloadJson, PaymentEvent.PaymentSucceededEvent.class);
					reservationService.handlePaymentSucceeded(event, ack);
				}
				case PAYMENT_FAILED -> {
					PaymentEvent.PaymentFailedEvent event =
						debeziumEventParser.deserialize(payloadJson, PaymentEvent.PaymentFailedEvent.class);
					reservationService.handlePaymentFailed(event, ack);
				}
				default -> {
					log.warn("[KAFKA-SKIP] 알 수 없는 결제 이벤트 타입: {}", eventType);
					ack.acknowledge();
				}
			}
		}catch (DebeziumEventParsingException e) {
			log.error("[KAFKA-POISON][DEBEZIUM] 메시지 파싱 실패 - 재시도 불필요. message={}", message, e);
			ack.acknowledge();
		} catch (TossPaymentResponseParsingException e) {
			log.error("[KAFKA-POISON][TOSS] Toss 응답 파싱 실패 - 재시도 불필요. message={}", message, e);
			ack.acknowledge();
		} catch (Exception e) {
			log.error("[KAFKA-NACK] Payment 이벤트 처리 중 예외 발생. 재시도 예정.", e);
			throw e; // 비즈니스/외부 자원 문제는 재시도
		}
	}

	@KafkaListener(topics = "RESERVATION.events", groupId = "payment-group")
	public void handleReservationEvents(@Payload String message, Acknowledgment ack){
		log.info("[KAFKA-CONSUME] Reservation Event 수신: {}", message);

		try {
			DebeziumEventParser.ParsedEvent parsedEvent = debeziumEventParser.parse(message);
			String eventType = parsedEvent.eventType();
			String payloadJson = parsedEvent.payload();

			if (EventType.RESERVATION_CANCELLED.name().equals(eventType)) {
				ReservationEvent.ReservationCancelledEvent event =
					debeziumEventParser.deserialize(payloadJson, ReservationEvent.ReservationCancelledEvent.class);

				paymentService.processPaymentCancellation(event, ack);
			} else {
				log.warn("알 수 없는 예약 이벤트 타입입니다: {}", eventType);
				ack.acknowledge(); // 처리 불필요 이벤트 즉시 커밋
			}

		} catch (DebeziumEventParsingException e) {
			log.error("[KAFKA-POISON][DEBEZIUM] 메시지 파싱 실패 - 재시도 불필요. message={}", message, e);
			ack.acknowledge();
		} catch (TossPaymentResponseParsingException e) {
			log.error("[KAFKA-POISON][TOSS] Toss 응답 파싱 실패 - 재시도 불필요. message={}", message, e);
			ack.acknowledge();
		} catch (Exception e) {
			log.error("[KAFKA-NACK] 예약 이벤트 처리 중 오류 발생. Offset 커밋 X → Kafka 재시도 예정.", e);
			throw e;
		}
	}
}
