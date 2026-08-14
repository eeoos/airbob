package kr.kro.airbob.domain.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Accepted;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.event.PaymentOperationEvent.PaymentExecutionRequestedV1;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationConflictException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.exception.ExpiredReservationConfirmationException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentOperationCommandService {

	private final ReservationRepository reservationRepository;
	private final PaymentOperationRepository paymentOperationRepository;
	private final ReservationHistoryRepository historyRepository;
	private final OutboxEventPublisher outboxEventPublisher;
	private final Clock clock;

	@Transactional
	public Accepted requestConfirmation(PaymentRequest.Confirm request, Long memberId) {
		UUID reservationUid = parseReservationUid(request.orderId());
		Reservation reservation = reservationRepository.findByReservationUidWithLock(reservationUid)
			.orElseThrow(ReservationNotFoundException::new);
		if (!reservation.belongsToGuest(memberId)) {
			throw new PaymentAccessDeniedException();
		}
		if (!reservation.matchesPaymentRequest(request.orderId(), request.amount().longValue())) {
			throw new InvalidInputException("결제 승인 요청이 예약 정보와 일치하지 않습니다.");
		}

		String deduplicationKey = "CONFIRM:" + reservationUid;
		Optional<PaymentOperation> existing = paymentOperationRepository.findByDeduplicationKey(deduplicationKey);
		if (existing.isPresent()) {
			return replayOrConflict(existing.get(), request);
		}

		Instant now = clock.instant();
		if (!reservation.startPayment(now)) {
			throw new ExpiredReservationConfirmationException();
		}
		PaymentOperation operation = PaymentOperation.createConfirmation(
			reservation, memberId, request.paymentKey(), request.amount(), now);
		paymentOperationRepository.save(operation);
		historyRepository.save(ReservationHistory.ofSystem(
			reservation, ChangeType.STATUS_CHANGE, "결제 승인 처리 시작", "PAYMENT_OPERATION"));
		outboxEventPublisher.save(EventType.PAYMENT_EXECUTION_REQUESTED_V1,
			new PaymentExecutionRequestedV1(operation.getOperationUid(), reservationUid));
		return Accepted.from(operation);
	}

	private Accepted replayOrConflict(PaymentOperation operation, PaymentRequest.Confirm request) {
		if (!operation.matchesConfirmation(request.paymentKey(), request.amount().longValue())) {
			throw new PaymentOperationConflictException();
		}
		return Accepted.from(operation);
	}

	private UUID parseReservationUid(String orderId) {
		try {
			return UUID.fromString(orderId);
		} catch (IllegalArgumentException e) {
			throw new InvalidInputException("결제 승인 주문 번호가 UUID 형식이 아닙니다.");
		}
	}
}
