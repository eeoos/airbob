package kr.kro.airbob.core.domain.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.core.domain.reservation.ExpiredReservationConfirmationException;
import kr.kro.airbob.core.domain.reservation.Reservation;
import kr.kro.airbob.core.domain.reservation.ReservationHistoryWriter;
import kr.kro.airbob.core.domain.reservation.ReservationReader;
import kr.kro.airbob.core.domain.reservation.inventory.ReservationInventoryManager;
import kr.kro.airbob.core.support.error.InvalidInputException;
import kr.kro.airbob.core.support.persistence.history.ChangeType;
import kr.kro.airbob.messaging.outbox.application.OutboxWriter;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentOperationCommandService {

	private final ReservationReader reservationReader;
	private final ReservationInventoryManager inventoryManager;
	private final PaymentOperationReader operationReader;
	private final PaymentOperationWriter operationWriter;
	private final ReservationHistoryWriter reservationHistoryWriter;
	private final OutboxWriter outboxWriter;
	private final Clock clock;

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PaymentOperationReceipt requestConfirmation(PaymentConfirmation request, Long memberId) {
		UUID reservationUid = parseReservationUid(request.orderId());
		Reservation reservation = reservationReader.readByUidForUpdate(reservationUid);
		if (!reservation.belongsToGuest(memberId)) {
			throw new PaymentAccessDeniedException();
		}
		if (!reservation.matchesPaymentRequest(request.orderId(), request.amount().longValue())) {
			throw new InvalidInputException("결제 승인 요청이 예약 정보와 일치하지 않습니다.");
		}
		reservation.validatePaymentAttempt(request.paymentAttemptId());

		String deduplicationKey = "CONFIRM:" + reservationUid;
		Optional<PaymentOperation> existing = operationReader.findByDeduplicationKey(deduplicationKey);
		if (existing.isPresent()) {
			return replayOrConflict(existing.get(), request);
		}

		Instant now = clock.instant();
		if (!reservation.startPayment(now)) {
			throw new ExpiredReservationConfirmationException();
		}
		reservation.consumePaymentAttempt(request.paymentAttemptId(), now);
		inventoryManager.transitionHeldToOccupied(
			reservation.getAccommodation().getId(),
			reservation.getCheckInDate(),
			reservation.getCheckOutDate(),
			reservation.getId(),
			now
		);
		PaymentOperation operation = PaymentOperation.createConfirmation(
			reservation, memberId, request.paymentKey(), request.amount(), now);
		operationWriter.create(operation);
		reservationHistoryWriter.recordSystem(
			reservation, ChangeType.STATUS_CHANGE, "결제 승인 처리 시작", "PAYMENT_OPERATION");
		outboxWriter.append(new PaymentOperationExecutionRequestedV1(
			operation.getOperationUid(),
			reservationUid,
			operation.getDispatchGeneration()
		));
		return PaymentOperationReceipt.accepted(operation);
	}

	private PaymentOperationReceipt replayOrConflict(PaymentOperation operation, PaymentConfirmation request) {
		if (!operation.matchesConfirmation(request.paymentKey(), request.amount().longValue())) {
			throw new PaymentOperationConflictException();
		}
		return PaymentOperationReceipt.accepted(operation);
	}

	private UUID parseReservationUid(String orderId) {
		try {
			return UUID.fromString(orderId);
		} catch (IllegalArgumentException e) {
			throw new InvalidInputException("결제 승인 주문 번호가 UUID 형식이 아닙니다.");
		}
	}
}
