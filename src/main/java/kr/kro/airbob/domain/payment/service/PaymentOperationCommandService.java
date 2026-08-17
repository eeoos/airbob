package kr.kro.airbob.domain.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Accepted;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationConflictException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.exception.ExpiredReservationConfirmationException;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.messaging.outbox.application.OutboxWriter;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentOperationCommandService {

	private final AccommodationRepository accommodationRepository;
	private final ReservationRepository reservationRepository;
	private final PaymentOperationRepository paymentOperationRepository;
	private final ReservationHistoryRepository historyRepository;
	private final OutboxWriter outboxWriter;
	private final Clock clock;

	// The initial lookup establishes lock order; replay lookup must see a commit made while waiting.
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Accepted requestConfirmation(PaymentRequest.Confirm request, Long memberId) {
		UUID reservationUid = parseReservationUid(request.orderId());
		Long accommodationId = reservationRepository.findAccommodationIdByReservationUid(reservationUid)
			.orElseThrow(ReservationNotFoundException::new);
		accommodationRepository.findByIdForUpdate(accommodationId)
			.orElseThrow(AccommodationNotFoundException::new);
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
		if (reservationRepository.existsConflictingReservationExcluding(
			accommodationId,
			reservation.getId(),
			reservation.getCheckInDate(),
			reservation.getCheckOutDate(),
			now
		)) {
			throw new ReservationConflictException();
		}
		PaymentOperation operation = PaymentOperation.createConfirmation(
			reservation, memberId, request.paymentKey(), request.amount(), now);
		paymentOperationRepository.save(operation);
		historyRepository.save(ReservationHistory.ofSystem(
			reservation, ChangeType.STATUS_CHANGE, "결제 승인 처리 시작", "PAYMENT_OPERATION"));
		outboxWriter.append(new PaymentOperationExecutionRequestedV1(
			operation.getOperationUid(),
			reservationUid,
			operation.getDispatchGeneration()
		));
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
