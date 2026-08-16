package kr.kro.airbob.kafka.consumer;

import java.util.UUID;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

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
public class PaymentCancellationGatewayWorker {

	private final DebeziumEventParser debeziumEventParser;
	private final TossPaymentsAdapter tossPaymentsAdapter;
	private final OutboxEventPublisher outboxEventPublisher;
	private final PaymentRepository paymentRepository;

	@KafkaListener(topics = "PAYMENT.events", groupId = "payment-gateway-worker-group")
	public void handlePgCallRequest(@Payload String message, Acknowledgment ack) {
		String eventType = debeziumEventParser.getEventType(message);

		try {
			if (EventType.from(eventType) != EventType.PG_CANCEL_CALL_REQUESTED) {
				ack.acknowledge();
				return;
			}
			handleCancelRequest(message);
			ack.acknowledge();
		} catch (DebeziumEventParsingException e) {
			log.error("[KAFKA-POISON] 파싱 실패: {}", message, e);
			ack.acknowledge();
		} catch (Exception e) {
			log.error("[KAFKA-NACK] PG 취소 워커 처리 실패. 재시도 예정.", e);
			throw e;
		}
	}

	private void handleCancelRequest(String message) {
		EventEnvelope<PaymentEvent.PaymentCancellationRequestedEvent> envelope =
			debeziumEventParser.parse(message, PaymentEvent.PaymentCancellationRequestedEvent.class);
		processCancelRequest(envelope.payload());
	}

	void processCancelRequest(PaymentEvent.PaymentCancellationRequestedEvent request) {
		String reservationUid = request.reservationUid();

		log.info("[PG-WORKER] Toss API 취소 호출 시작. Reservation UID: {}", reservationUid);

		Payment payment = paymentRepository.findByReservationReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(PaymentNotFoundException::new);
		TossPaymentResponse response;
		try {
			response = tossPaymentsAdapter.cancelPayment(
				payment.getPaymentKey(),
				request.cancelReason(),
				request.cancelAmount()
			);
		} catch (TossPaymentException e) {
			outboxEventPublisher.save(
				EventType.PG_CANCEL_CALL_FAILED,
				new PaymentEvent.PgCancelCallFailedEvent(
					request, reservationUid, e.getErrorCode().name(), e.getMessage())
			);
			log.error("[PG-WORKER] Toss API 취소 호출 실패. Reservation UID: {}, Code: {}", reservationUid, e.getErrorCode().name(), e);
			return;
		}

		outboxEventPublisher.save(
			EventType.PG_CANCEL_CALL_SUCCEEDED,
			new PaymentEvent.PgCancelCallSucceededEvent(response, reservationUid)
		);
		log.info("[PG-WORKER] Toss API 취소 호출 성공. Reservation UID: {}", reservationUid);
	}
}
