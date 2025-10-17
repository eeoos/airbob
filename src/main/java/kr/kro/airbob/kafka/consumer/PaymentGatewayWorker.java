package kr.kro.airbob.kafka.consumer;

import java.util.UUID;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.service.TossPaymentsAdapter;
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
public class PaymentGatewayWorker {

	private final DebeziumEventParser debeziumEventParser;
	private final TossPaymentsAdapter tossPaymentsAdapter;
	private final OutboxEventPublisher outboxEventPublisher;

	private final PaymentRepository paymentRepository;

	@KafkaListener(topics = "PAYMENT.events", groupId = "payment-gateway-worker-group")
	public void handlePgCallRequest(@Payload String message, Acknowledgment ack) {
		String eventType = debeziumEventParser.getEventType(message);

		try {
			switch (EventType.from(eventType)) {
				case PG_CALL_REQUESTED -> handleConfirmRequest(message);
				case PG_CANCEL_CALL_REQUESTED -> handleCancelRequest(message);
				default -> {
					ack.acknowledge();
					return;
				}
			}
			ack.acknowledge();
		} catch (DebeziumEventParsingException e) {
			log.error("[KAFKA-POISON] 파싱 실패: {}", message, e);
			ack.acknowledge();
		} catch (Exception e) {
			log.error("[KAFKA-NACK] PG 워커 처리 실패. 재시도 예정.", e);
			throw e;
		}
	}

	private void handleConfirmRequest(String message) {
		EventEnvelope<PaymentRequest.Confirm> envelope = debeziumEventParser.parse(message, PaymentRequest.Confirm.class);
		PaymentRequest.Confirm request = envelope.payload();
		String orderId = request.orderId();

		log.info("[PG-WORKER] Toss API 승인 호출 시작. Order ID: {}", orderId);

		try {
			TossPaymentResponse response = tossPaymentsAdapter.confirmPayment(
				request.paymentKey(),
				orderId,
				request.amount()
			);

			outboxEventPublisher.save(
				EventType.PG_CALL_SUCCEEDED,
				new PaymentEvent.PgCallSucceededEvent(response, orderId)
			);
			log.info("[PG-WORKER] Toss API 승인 호출 성공. Order ID: {}", orderId);
		} catch (TossPaymentException e) {
			outboxEventPublisher.save(
				EventType.PG_CALL_FAILED,
				new PaymentEvent.PgCallFailedEvent(request, orderId, e.getErrorCode().name(), e.getMessage())
			);
			log.error("[PG-WORKER] Toss API 승인 호출 실패. Order ID: {}, Code: {}", orderId, e.getErrorCode().name(), e);
		} catch (Exception e) {
			outboxEventPublisher.save(
				EventType.PG_CALL_FAILED,
				new PaymentEvent.PgCallFailedEvent(request, orderId, "UNKNOWN_WORKER_ERROR", e.getMessage())
			);
			log.error("[PG-WORKER] Toss API 승인 호출 중 알 수 없는 예외 발생. Order ID: {}", orderId, e);
		}
	}

	private void handleCancelRequest(String message) {
		EventEnvelope<PaymentEvent.PaymentCancellationRequestedEvent> envelope =
			debeziumEventParser.parse(message, PaymentEvent.PaymentCancellationRequestedEvent.class);
		PaymentEvent.PaymentCancellationRequestedEvent request = envelope.payload();
		String reservationUid = request.reservationUid();

		log.info("[PG-WORKER] Toss API 취소 호출 시작. Reservation UID: {}", reservationUid);

		try {
			Payment payment = paymentRepository.findByReservationReservationUid(UUID.fromString(reservationUid))
				.orElseThrow(PaymentNotFoundException::new);

			TossPaymentResponse response = tossPaymentsAdapter.cancelPayment(
				payment.getPaymentKey(),
				request.cancelReason(),
				request.cancelAmount()
			);

			outboxEventPublisher.save(
				EventType.PG_CANCEL_CALL_SUCCEEDED,
				new PaymentEvent.PgCancelCallSucceededEvent(response, reservationUid)
			);
			log.info("[PG-WORKER] Toss API 취소 호출 성공. Reservation UID: {}", reservationUid);

		} catch (TossPaymentException e) {
			outboxEventPublisher.save(
				EventType.PG_CANCEL_CALL_FAILED,
				new PaymentEvent.PgCancelCallFailedEvent(request, reservationUid, e.getErrorCode().name(), e.getMessage())
			);
			log.error("[PG-WORKER] Toss API 취소 호출 실패. Reservation UID: {}, Code: {}", reservationUid, e.getErrorCode().name(), e);
		} catch (Exception e) {
			outboxEventPublisher.save(
				EventType.PG_CANCEL_CALL_FAILED,
				new PaymentEvent.PgCancelCallFailedEvent(request, reservationUid, "UNKNOWN_WORKER_ERROR", e.getMessage())
			);
			log.error("[PG-WORKER] Toss API 취소 호출 중 알 수 없는 예외 발생. Reservation UID: {}", reservationUid, e);
		}
	}
}
