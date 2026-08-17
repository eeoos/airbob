package kr.kro.airbob.domain.payment.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.exception.PaymentOperationInvariantException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import kr.kro.airbob.search.event.AccommodationIndexingEvents;

@Service
public class PaymentOperationFinalizer {
	private static final int FAILURE_CODE_MAX_LENGTH = 100;
	private static final String HISTORY_SOURCE = "PAYMENT_OPERATION";

	private final PaymentOperationRepository operationRepository;
	private final ReservationRepository reservationRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentTransactionRepository paymentTransactionRepository;
	private final CouponUsageService couponUsageService;
	private final ReservationHistoryRepository historyRepository;
	private final OutboxEventPublisher outboxEventPublisher;
	private final Clock clock;

	public PaymentOperationFinalizer(
		PaymentOperationRepository operationRepository,
		ReservationRepository reservationRepository,
		PaymentRepository paymentRepository,
		PaymentTransactionRepository paymentTransactionRepository,
		CouponUsageService couponUsageService,
		ReservationHistoryRepository historyRepository,
		OutboxEventPublisher outboxEventPublisher,
		Clock clock
	) {
		this.operationRepository = operationRepository;
		this.reservationRepository = reservationRepository;
		this.paymentRepository = paymentRepository;
		this.paymentTransactionRepository = paymentTransactionRepository;
		this.couponUsageService = couponUsageService;
		this.historyRepository = historyRepository;
		this.outboxEventPublisher = outboxEventPublisher;
		this.clock = clock;
	}

	@Transactional
	public void applyApproved(PaymentExecution execution, ConfirmedPayment confirmed) {
		PaymentOperation operation = lockOperation(execution);
		if (operation.isApplied()) {
			return;
		}
		operation.rejectOppositeTerminal(PaymentOperationStatus.APPLIED);
		if (!operation.isOwnedBy(execution.leaseOwner())) {
			return;
		}

		Reservation reservation = lockReservation(operation);
		validateExecutionCorrelation(execution, operation, reservation);
		validateApprovalCorrelation(confirmed, operation, reservation);

		Payment payment = paymentRepository.findByReservationIdWithLock(reservation.getId())
			.orElseGet(() -> paymentRepository.save(Payment.create(confirmed, reservation)));
		validateExistingPayment(payment, confirmed, reservation);

		if (!paymentTransactionRepository.existsByPaymentOperationId(operation.getId())) {
			paymentTransactionRepository.save(
				PaymentTransaction.confirm(confirmed, reservation, payment, operation.getId()));
		}

		reservation.confirm();
		historyRepository.save(ReservationHistory.ofSystem(
			reservation, ChangeType.STATUS_CHANGE, "결제 성공", HISTORY_SOURCE));
		operation.markApplied(clock.instant());
		publishReservationConfirmedAndIndexChanged(reservation);
	}

	@Transactional
	public void applyDeclined(PaymentExecution execution, String code, String message) {
		PaymentOperation operation = lockOperation(execution);
		if (operation.isDeclined()) {
			return;
		}
		operation.rejectOppositeTerminal(PaymentOperationStatus.DECLINED);
		if (!operation.isOwnedBy(execution.leaseOwner())) {
			return;
		}

		Reservation reservation = lockReservation(operation);
		validateExecutionCorrelation(execution, operation, reservation);
		String normalizedCode = normalizeFailureCode(code);
		if (!paymentTransactionRepository.existsByPaymentOperationId(operation.getId())) {
			paymentTransactionRepository.save(
				PaymentTransaction.fail(operation, reservation, normalizedCode, message));
		}

		reservation.expireAfterFinalPaymentDecline();
		couponUsageService.restore(reservation.getId());
		historyRepository.save(ReservationHistory.ofSystem(
			reservation,
			ChangeType.STATUS_CHANGE,
			"결제 최종 거절: " + normalizedCode,
			HISTORY_SOURCE));
		operation.markDeclined(clock.instant(), normalizedCode, message);
		outboxEventPublisher.save(
			EventType.RESERVATION_EXPIRED,
			new ReservationEvent.ReservationExpiredEvent(
				reservation.getAccommodation().getId(),
				reservation.getCheckInDate(),
				reservation.getCheckOutDate()));
	}

	private PaymentOperation lockOperation(PaymentExecution execution) {
		return operationRepository.findByOperationUidWithLock(execution.operationUid())
			.orElseThrow(PaymentOperationNotFoundException::new);
	}

	private Reservation lockReservation(PaymentOperation operation) {
		return reservationRepository.findByIdWithLock(operation.getReservation().getId())
			.orElseThrow(ReservationNotFoundException::new);
	}

	private void validateExecutionCorrelation(
		PaymentExecution execution,
		PaymentOperation operation,
		Reservation reservation
	) {
		boolean matches = operation.getOperationType() == PaymentOperationType.CONFIRM
			&& Objects.equals(execution.operationUid(), operation.getOperationUid())
			&& Objects.equals(execution.reservationUid(), reservation.getReservationUid())
			&& Objects.equals(execution.paymentKey(), operation.getPaymentKey())
			&& Objects.equals(execution.orderId(), reservation.getReservationUid().toString())
			&& execution.amount() == operation.getExpectedAmount()
			&& execution.amount() == reservation.getTotalPrice()
			&& Objects.equals(execution.providerIdempotencyKey(), operation.getProviderIdempotencyKey());
		if (!matches) {
			throw new PaymentOperationInvariantException(
				"payment execution does not match its persisted operation and reservation");
		}
	}

	private void validateApprovalCorrelation(
		ConfirmedPayment confirmed,
		PaymentOperation operation,
		Reservation reservation
	) {
		boolean matches = confirmed != null
			&& Objects.equals(confirmed.paymentKey(), operation.getPaymentKey())
			&& Objects.equals(confirmed.orderId(), reservation.getReservationUid().toString())
			&& confirmed.totalAmount() == operation.getExpectedAmount()
			&& confirmed.balanceAmount() == confirmed.totalAmount()
			&& confirmed.method() != null
			&& confirmed.status() == PaymentStatus.DONE
			&& confirmed.approvedAt() != null;
		if (!matches) {
			throw new PaymentOperationInvariantException(
				"approved payment does not match its operation and reservation");
		}
	}

	private void validateExistingPayment(
		Payment payment,
		ConfirmedPayment confirmed,
		Reservation reservation
	) {
		boolean matches = Objects.equals(payment.getReservation().getId(), reservation.getId())
			&& Objects.equals(payment.getPaymentKey(), confirmed.paymentKey())
			&& Objects.equals(payment.getOrderId(), confirmed.orderId())
			&& Objects.equals(payment.getAmount(), confirmed.totalAmount())
			&& Objects.equals(payment.getBalanceAmount(), confirmed.balanceAmount())
			&& payment.getMethod() == confirmed.method()
			&& payment.getStatus() == confirmed.status()
			&& payment.getApprovedAt() != null
			&& payment.getApprovedAt().truncatedTo(ChronoUnit.MICROS)
				.equals(confirmed.approvedAt().truncatedTo(ChronoUnit.MICROS));
		if (!matches) {
			throw new PaymentOperationInvariantException(
				"reservation already has a different approved payment");
		}
	}

	private void publishReservationConfirmedAndIndexChanged(Reservation reservation) {
		outboxEventPublisher.save(
			EventType.RESERVATION_CONFIRMED,
			new ReservationEvent.ReservationConfirmedEvent(
				reservation.getAccommodation().getId(),
				reservation.getCheckInDate(),
				reservation.getCheckOutDate()));
		outboxEventPublisher.save(
			EventType.RESERVATION_CHANGED,
			new AccommodationIndexingEvents.ReservationChangedEvent(
				reservation.getAccommodation().getAccommodationUid().toString()));
	}

	private String normalizeFailureCode(String code) {
		if (code == null || code.isBlank()) {
			return "UNKNOWN";
		}
		return code.length() <= FAILURE_CODE_MAX_LENGTH
			? code : code.substring(0, FAILURE_CODE_MAX_LENGTH);
	}
}
