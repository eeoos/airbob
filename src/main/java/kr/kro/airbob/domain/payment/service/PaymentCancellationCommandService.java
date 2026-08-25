package kr.kro.airbob.domain.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Cancellation;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationInvariantViolationException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationConflictException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ReservationCancellationDeadlinePassedException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.messaging.outbox.application.OutboxWriter;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@Service
public class PaymentCancellationCommandService {
	private static final String HISTORY_SOURCE = "PAYMENT_OPERATION";

	private final ReservationRepository reservationRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentOperationRepository operationRepository;
	private final ReservationHistoryRepository historyRepository;
	private final CouponUsageService couponUsageService;
	private final AccommodationSearchRefreshPublisher searchRefreshPublisher;
	private final OutboxWriter outboxWriter;
	private final Clock clock;

	public PaymentCancellationCommandService(
		ReservationRepository reservationRepository,
		PaymentRepository paymentRepository,
		PaymentOperationRepository operationRepository,
		ReservationHistoryRepository historyRepository,
		CouponUsageService couponUsageService,
		AccommodationSearchRefreshPublisher searchRefreshPublisher,
		OutboxWriter outboxWriter,
		Clock clock
	) {
		this.reservationRepository = reservationRepository;
		this.paymentRepository = paymentRepository;
		this.operationRepository = operationRepository;
		this.historyRepository = historyRepository;
		this.couponUsageService = couponUsageService;
		this.searchRefreshPublisher = searchRefreshPublisher;
		this.outboxWriter = outboxWriter;
		this.clock = clock;
	}

	@Transactional
	public Cancellation requestCancellation(
		String reservationUidValue,
		PaymentRequest.Cancel request,
		Long memberId
	) {
		UUID reservationUid = parseReservationUid(reservationUidValue);
		Reservation reservation = reservationRepository.findByReservationUidWithLock(reservationUid)
			.orElseThrow(ReservationNotFoundException::new);
		if (!reservation.belongsToGuest(memberId)) {
			throw new PaymentAccessDeniedException();
		}
		Instant now = clock.instant();

		if (!reservation.requiresPayment()) {
			if (reservation.getStatus() != ReservationStatus.CANCELLED) {
				validateCancellationDeadline(reservation, now);
			}
			return cancelComplimentary(reservation, request);
		}

		Optional<PaymentOperation> latest = operationRepository
			.findFirstByReservationIdAndOperationTypeOrderByIdDesc(
				reservation.getId(), PaymentOperationType.CANCEL);
		Cancellation replay = replayIfCancellationAlreadyInProgress(reservation, latest, request);
		if (replay != null) {
			return replay;
		}
		validateCancellationDeadline(reservation, now);

		Payment payment = paymentRepository.findByReservationIdWithLock(reservation.getId())
			.orElseThrow(PaymentNotFoundException::new);
		validateFullActiveBalance(payment, request.cancelAmount());

		reservation.requestCancellation();
		PaymentOperation operation = PaymentOperation.createCancellation(
			reservation,
			memberId,
			payment.getPaymentKey(),
			payment.getBalanceAmount(),
			request.cancelReason(),
			now
		);
		operationRepository.save(operation);
		historyRepository.save(ReservationHistory.ofSystem(
			reservation,
			ChangeType.STATUS_CHANGE,
			"결제 취소 처리 시작: " + request.cancelReason(),
			HISTORY_SOURCE
		));
		outboxWriter.append(new PaymentOperationExecutionRequestedV1(
			operation.getOperationUid(),
			reservationUid,
			operation.getDispatchGeneration()
		));
		return Cancellation.accepted(operation);
	}

	private void validateCancellationDeadline(Reservation reservation, Instant now) {
		if (!now.isBefore(reservation.getCheckInAt())) {
			throw new ReservationCancellationDeadlinePassedException();
		}
	}

	private Cancellation cancelComplimentary(
		Reservation reservation,
		PaymentRequest.Cancel request
	) {
		if (request.cancelAmount() != null) {
			throw new InvalidInputException("0원 예약에는 환불 금액을 지정할 수 없습니다.");
		}
		if (!reservation.cancelComplimentary()) {
			return Cancellation.completed();
		}
		couponUsageService.restore(reservation.getId());
		historyRepository.save(ReservationHistory.ofSystem(
			reservation, ChangeType.CANCEL, request.cancelReason(), "RESERVATION"));
		searchRefreshPublisher.requestRefresh(
			reservation.getAccommodation().getAccommodationUid());
		return Cancellation.completed();
	}

	private Cancellation replayIfCancellationAlreadyInProgress(
		Reservation reservation,
		Optional<PaymentOperation> latest,
		PaymentRequest.Cancel request
	) {
		if (reservation.getStatus() == ReservationStatus.CANCELLATION_PENDING) {
			PaymentOperation operation = latest
				.filter(this::isActiveCancellation)
				.orElseThrow(() -> new PaymentOperationInvariantViolationException(
					"cancellation-pending reservation has no active payment operation"));
			if (!operation.matchesCancellation(request.cancelReason(), request.cancelAmount())) {
				throw new PaymentOperationConflictException();
			}
			return Cancellation.accepted(operation);
		}
		if (reservation.getStatus() == ReservationStatus.CANCELLED) {
			PaymentOperation operation = latest
				.filter(candidate -> candidate.getStatus() == PaymentOperationStatus.APPLIED)
				.orElseThrow(() -> new PaymentOperationInvariantViolationException(
					"paid cancelled reservation has no applied payment operation"));
			if (!operation.matchesCancellation(request.cancelReason(), request.cancelAmount())) {
				throw new PaymentOperationConflictException();
			}
			return Cancellation.accepted(operation);
		}
		if (reservation.getStatus() == ReservationStatus.CANCELLATION_FAILED
			&& latest.map(PaymentOperation::getStatus)
				.filter(status -> status == PaymentOperationStatus.DECLINED)
				.isEmpty()) {
			throw new PaymentOperationInvariantViolationException(
				"cancellation-failed reservation has no declined payment operation");
		}
		return null;
	}

	private boolean isActiveCancellation(PaymentOperation operation) {
		return operation.getStatus() == PaymentOperationStatus.QUEUED
			|| operation.getStatus() == PaymentOperationStatus.EXECUTING
			|| operation.getStatus() == PaymentOperationStatus.WAITING_RETRY
			|| operation.getStatus() == PaymentOperationStatus.MANUAL_REVIEW;
	}

	private void validateFullActiveBalance(Payment payment, Long requestedAmount) {
		boolean fullyActive = payment.getStatus() == PaymentStatus.DONE
			&& payment.getAmount() != null
			&& payment.getBalanceAmount() != null
			&& payment.getAmount().equals(payment.getBalanceAmount())
			&& payment.getBalanceAmount() > 0L;
		if (!fullyActive) {
			throw new InvalidInputException("전액 결제가 유지 중인 예약만 취소할 수 있습니다.");
		}
		if (requestedAmount != null && !requestedAmount.equals(payment.getBalanceAmount())) {
			throw new InvalidInputException("예약 취소는 현재 결제 잔액 전액만 가능합니다.");
		}
	}

	private UUID parseReservationUid(String reservationUid) {
		try {
			return UUID.fromString(reservationUid);
		} catch (IllegalArgumentException exception) {
			throw new InvalidInputException("예약 번호가 UUID 형식이 아닙니다.");
		}
	}
}
