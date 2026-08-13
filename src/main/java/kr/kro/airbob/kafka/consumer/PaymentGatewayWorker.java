package kr.kro.airbob.kafka.consumer;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.exception.code.PaymentInquiryErrorCode;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.service.PaymentCompensationService;
import kr.kro.airbob.domain.payment.service.TossPaymentsAdapter;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
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
	private final ReservationRepository reservationRepository;
	private final PaymentCompensationService paymentCompensationService;
	private final Clock clock;

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
		processConfirmRequest(envelope.payload());
	}

	void processConfirmRequest(PaymentRequest.Confirm request) {
		UUID reservationUid = UUID.fromString(request.orderId());
		Reservation reservation = reservationRepository.findByReservationUid(reservationUid)
			.orElseThrow(ReservationNotFoundException::new);
		validateConfirmRequest(request, reservation);

		String orderId = request.orderId();
		if (reservation.getStatus().hasConfirmedPayment()) {
			log.info("[PG-WORKER-SKIP] 이미 결제가 확정된 예약의 중복 승인 요청입니다. Order ID: {}", orderId);
			return;
		}
		if (reservation.getStatus() == ReservationStatus.EXPIRED) {
			recoverOrExpireDelayedConfirmRequest(request, orderId, true);
			return;
		}
		if (reservation.getStatus() != ReservationStatus.PAYMENT_PROCESSING) {
			throw new IllegalStateException("결제 처리 상태가 아닌 예약은 PG 승인을 요청할 수 없습니다.");
		}
		if (reservation.isExpiredAt(clock.instant())) {
			recoverOrExpireDelayedConfirmRequest(request, orderId, false);
			return;
		}

		log.info("[PG-WORKER] Toss API 승인 호출 시작. Order ID: {}", orderId);

		TossPaymentResponse response;
		try {
			response = tossPaymentsAdapter.confirmPayment(
				request.paymentKey(),
				orderId,
				request.amount()
			);
		} catch (TossPaymentException e) {
			outboxEventPublisher.save(
				EventType.PG_CALL_FAILED,
				new PaymentEvent.PgCallFailedEvent(request, orderId, e.getErrorCode().name(), e.getMessage())
			);
			log.error("[PG-WORKER] Toss API 승인 호출 실패. Order ID: {}, Code: {}", orderId, e.getErrorCode().name(), e);
			return;
		}
		validateConfirmResponse(request, response);

		outboxEventPublisher.save(
			EventType.PG_CALL_SUCCEEDED,
			new PaymentEvent.PgCallSucceededEvent(response, orderId)
		);
		log.info("[PG-WORKER] Toss API 승인 호출 성공. Order ID: {}", orderId);
	}

	private void recoverOrExpireDelayedConfirmRequest(
		PaymentRequest.Confirm request,
		String orderId,
		boolean reservationAlreadyExpired
	) {
		try {
			TossPaymentResponse existingPayment = tossPaymentsAdapter.getPaymentByPaymentKey(request.paymentKey());
			if (isCorrelatedFullyCanceledPayment(request, existingPayment)) {
				if (!reservationAlreadyExpired) {
					publishExpiredFailure(request, orderId);
				}
				log.info("[PG-WORKER-SKIP] 이미 전액 환불된 결제의 중복 승인 요청입니다. Order ID: {}", orderId);
				return;
			}
			if (isCorrelatedPartiallyCanceledPayment(request, existingPayment)) {
				paymentCompensationService.compensate(orderId);
				log.warn("[PG-WORKER-COMPENSATE] 부분 환불 잔액의 보상 취소를 완료했습니다. Order ID: {}", orderId);
				return;
			}
			validateConfirmResponse(request, existingPayment);
			outboxEventPublisher.save(
				EventType.PG_CALL_SUCCEEDED,
				new PaymentEvent.PgCallSucceededEvent(existingPayment, orderId)
			);
			log.warn("[PG-WORKER-RECOVER] 만료 후 재처리에서 기존 승인 결제를 조회해 복구합니다. Order ID: {}", orderId);
		} catch (TossPaymentException e) {
			if (e.getErrorCode() != PaymentInquiryErrorCode.NOT_FOUND_PAYMENT
				&& e.getErrorCode() != PaymentInquiryErrorCode.NOT_FOUND) {
				throw e;
			}
			publishExpiredFailure(request, orderId);
			log.warn("[PG-WORKER-SKIP] 예약 만료 후 도착했고 승인된 결제도 없습니다. Order ID: {}", orderId);
		}
	}

	private boolean isCorrelatedFullyCanceledPayment(
		PaymentRequest.Confirm request,
		TossPaymentResponse response
	) {
		return response != null
			&& Objects.equals(request.paymentKey(), response.getPaymentKey())
			&& Objects.equals(request.orderId(), response.getOrderId())
			&& Objects.equals(request.amount().longValue(), response.getTotalAmount())
			&& PaymentStatus.CANCELED.name().equalsIgnoreCase(response.getStatus())
			&& Objects.equals(0L, response.getBalanceAmount());
	}

	private boolean isCorrelatedPartiallyCanceledPayment(
		PaymentRequest.Confirm request,
		TossPaymentResponse response
	) {
		return response != null
			&& Objects.equals(request.paymentKey(), response.getPaymentKey())
			&& Objects.equals(request.orderId(), response.getOrderId())
			&& Objects.equals(request.amount().longValue(), response.getTotalAmount())
			&& PaymentStatus.PARTIAL_CANCELED.name().equalsIgnoreCase(response.getStatus())
			&& response.getBalanceAmount() != null
			&& response.getBalanceAmount() > 0L;
	}

	private void publishExpiredFailure(PaymentRequest.Confirm request, String orderId) {
		outboxEventPublisher.save(
			EventType.PG_CALL_FAILED,
			new PaymentEvent.PgCallFailedEvent(
				request,
				orderId,
				"RESERVATION_EXPIRED",
				"결제 승인 요청 처리 전에 예약 유효 시간이 만료되었습니다."
			)
		);
	}

	private void validateConfirmRequest(PaymentRequest.Confirm request, Reservation reservation) {
		if (!reservation.matchesPaymentRequest(request.orderId(), request.amount().longValue())) {
			throw new IllegalStateException("결제 승인 요청이 예약 정보와 일치하지 않습니다.");
		}
	}

	private void validateConfirmResponse(
		PaymentRequest.Confirm request,
		TossPaymentResponse response
	) {
		boolean correlated = response != null
			&& Objects.equals(request.paymentKey(), response.getPaymentKey())
			&& Objects.equals(request.orderId(), response.getOrderId())
			&& Objects.equals(request.amount().longValue(), response.getTotalAmount())
			&& PaymentStatus.DONE == PaymentStatus.from(response.getStatus())
			&& response.getApprovedAt() != null
			&& response.getMethod() != null
			&& !response.getMethod().isBlank();
		if (!correlated) {
			throw new IllegalStateException("PG 승인 응답이 원 결제 요청과 일치하지 않습니다.");
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
				new PaymentEvent.PgCancelCallFailedEvent(request, reservationUid, e.getErrorCode().name(), e.getMessage())
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
