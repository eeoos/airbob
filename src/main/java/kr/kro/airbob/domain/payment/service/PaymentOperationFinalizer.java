package kr.kro.airbob.domain.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionAction;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.exception.PaymentOperationInvariantViolationException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationNotFoundException;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.payment.service.gateway.CancelledPayment;
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

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
	private final AccommodationSearchRefreshPublisher searchRefreshPublisher;
	private final PaymentOperationManualResolutionRecorder resolutionRecorder;
	private final Clock clock;

	public PaymentOperationFinalizer(
		PaymentOperationRepository operationRepository,
		ReservationRepository reservationRepository,
		PaymentRepository paymentRepository,
		PaymentTransactionRepository paymentTransactionRepository,
		CouponUsageService couponUsageService,
		ReservationHistoryRepository historyRepository,
		AccommodationSearchRefreshPublisher searchRefreshPublisher,
		PaymentOperationManualResolutionRecorder resolutionRecorder,
		Clock clock
	) {
		this.operationRepository = operationRepository;
		this.reservationRepository = reservationRepository;
		this.paymentRepository = paymentRepository;
		this.paymentTransactionRepository = paymentTransactionRepository;
		this.couponUsageService = couponUsageService;
		this.historyRepository = historyRepository;
		this.searchRefreshPublisher = searchRefreshPublisher;
		this.resolutionRecorder = resolutionRecorder;
		this.clock = clock;
	}

	@Transactional
	public void applyApproved(PaymentExecution execution, ConfirmedPayment confirmed) {
		PaymentOperation operation = lockOperation(execution);
		if (operation.isApplied()) {
			return;
		}
		operation.rejectOppositeTerminal(PaymentOperationStatus.APPLIED);
		if (!operation.isOwnedBy(execution.leaseOwner(), execution.dispatchGeneration())) {
			return;
		}
		boolean manualReconciliation = isCurrentManualReconciliation(operation, execution);

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
		var completedAt = clock.instant();
		operation.markApplied(completedAt);
		requestAccommodationSearchRefresh(reservation);
		recordManualResolutionResult(
			operation,
			manualReconciliation,
			PaymentOperationResolutionAction.RECONCILIATION_APPLIED,
			"PROVIDER_PAYMENT_CONFIRMED",
			PaymentOperationStatus.APPLIED,
			completedAt);
	}

	@Transactional
	public void applyDeclined(PaymentExecution execution, String code, String message) {
		PaymentOperation operation = lockOperation(execution);
		if (operation.isDeclined()) {
			return;
		}
		operation.rejectOppositeTerminal(PaymentOperationStatus.DECLINED);
		if (!operation.isOwnedBy(execution.leaseOwner(), execution.dispatchGeneration())) {
			return;
		}
		boolean manualReconciliation = isCurrentManualReconciliation(operation, execution);

		Reservation reservation = lockReservation(operation);
		validateExecutionCorrelation(execution, operation, reservation);
		String normalizedCode = normalizeFailureCode(code);
		if (operation.isCancellation()) {
			applyCancellationDecline(operation, reservation, normalizedCode, message);
		} else {
			applyConfirmationDecline(operation, reservation, normalizedCode, message);
		}
		var completedAt = clock.instant();
		operation.markDeclined(completedAt, normalizedCode, message);
		requestAccommodationSearchRefresh(reservation);
		recordManualResolutionResult(
			operation,
			manualReconciliation,
			PaymentOperationResolutionAction.RECONCILIATION_DECLINED,
			"PROVIDER_PAYMENT_DECLINED",
			PaymentOperationStatus.DECLINED,
			completedAt);
	}

	@Transactional
	public void applyCancelled(PaymentExecution execution, CancelledPayment cancelled) {
		PaymentOperation operation = lockOperation(execution);
		if (operation.isApplied()) {
			return;
		}
		operation.rejectOppositeTerminal(PaymentOperationStatus.APPLIED);
		if (!operation.isOwnedBy(execution.leaseOwner(), execution.dispatchGeneration())) {
			return;
		}
		boolean manualReconciliation = isCurrentManualReconciliation(operation, execution);

		Reservation reservation = lockReservation(operation);
		validateExecutionCorrelation(execution, operation, reservation);
		Payment payment = paymentRepository.findByReservationIdWithLock(reservation.getId())
			.orElseThrow(PaymentNotFoundException::new);
		validateCancellationCorrelation(cancelled, operation, reservation, payment);

		payment.applyFullCancellation(cancelled);
		if (!paymentTransactionRepository.existsByPaymentOperationId(operation.getId())) {
			paymentTransactionRepository.save(PaymentTransaction.cancel(
				cancelled, reservation, payment, operation.getId()));
		}

		reservation.completeCancellation();
		couponUsageService.restore(reservation.getId());
		historyRepository.save(ReservationHistory.ofSystem(
			reservation, ChangeType.CANCEL, "PG 결제 전액 취소 성공", HISTORY_SOURCE));
		var completedAt = clock.instant();
		operation.markApplied(completedAt);
		requestAccommodationSearchRefresh(reservation);
		recordManualResolutionResult(
			operation,
			manualReconciliation,
			PaymentOperationResolutionAction.RECONCILIATION_APPLIED,
			"PROVIDER_CANCELLATION_CONFIRMED",
			PaymentOperationStatus.APPLIED,
			completedAt);
	}

	private boolean isCurrentManualReconciliation(
		PaymentOperation operation,
		PaymentExecution execution
	) {
		return execution.manualReconciliation() && operation.isManualReconciliationPending();
	}

	private void recordManualResolutionResult(
		PaymentOperation operation,
		boolean manualReconciliation,
		PaymentOperationResolutionAction action,
		String reason,
		PaymentOperationStatus resultStatus,
		Instant recordedAt
	) {
		if (!manualReconciliation) {
			return;
		}
		resolutionRecorder.recordSystem(
			operation,
			action,
			reason,
			PaymentOperationStatus.EXECUTING,
			resultStatus,
			recordedAt);
	}

	private void applyConfirmationDecline(
		PaymentOperation operation,
		Reservation reservation,
		String normalizedCode,
		String message
	) {
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
	}

	private void applyCancellationDecline(
		PaymentOperation operation,
		Reservation reservation,
		String normalizedCode,
		String message
	) {
		Payment payment = paymentRepository.findByReservationIdWithLock(reservation.getId())
			.orElseThrow(PaymentNotFoundException::new);
		validateActivePaymentForCancellation(operation, reservation, payment);
		if (!paymentTransactionRepository.existsByPaymentOperationId(operation.getId())) {
			paymentTransactionRepository.save(PaymentTransaction.cancellationFailed(
				operation, reservation, payment, normalizedCode, message));
		}
		reservation.failCancellation();
		historyRepository.save(ReservationHistory.ofSystem(
			reservation,
			ChangeType.STATUS_CHANGE,
			"결제 취소 최종 거절: " + normalizedCode,
			HISTORY_SOURCE));
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
		boolean operationModeMatches = switch (operation.getOperationType()) {
			case CONFIRM -> execution.mode() == PaymentExecutionMode.CONFIRM
				|| execution.mode() == PaymentExecutionMode.INQUIRE_CONFIRM;
			case CANCEL -> execution.mode() == PaymentExecutionMode.CANCEL
				|| execution.mode() == PaymentExecutionMode.INQUIRE_CANCEL;
		};
		boolean manualReconciliationMatches =
			execution.manualReconciliation() == operation.isManualReconciliationPending()
				&& (!execution.manualReconciliation() || execution.mode().isInquiry());
		boolean matches = operationModeMatches
			&& manualReconciliationMatches
			&& Objects.equals(execution.operationUid(), operation.getOperationUid())
			&& Objects.equals(execution.reservationUid(), reservation.getReservationUid())
			&& Objects.equals(execution.paymentKey(), operation.getPaymentKey())
			&& Objects.equals(execution.orderId(), reservation.getReservationUid().toString())
			&& execution.amount() == operation.getExpectedAmount()
			&& execution.amount() == reservation.getTotalPrice()
			&& Objects.equals(execution.providerIdempotencyKey(), operation.getProviderIdempotencyKey());
		if (!matches) {
			throw new PaymentOperationInvariantViolationException(
				"payment execution does not match its persisted operation and reservation");
		}
	}

	private void validateCancellationCorrelation(
		CancelledPayment cancelled,
		PaymentOperation operation,
		Reservation reservation,
		Payment payment
	) {
		validateActivePaymentForCancellation(operation, reservation, payment);
		boolean matches = cancelled != null
			&& operation.getOperationType() == PaymentOperationType.CANCEL
			&& Objects.equals(cancelled.paymentKey(), payment.getPaymentKey())
			&& Objects.equals(cancelled.orderId(), payment.getOrderId())
			&& Objects.equals(cancelled.orderId(), reservation.getReservationUid().toString())
			&& cancelled.totalAmount() == payment.getAmount()
			&& cancelled.cancelAmount() == operation.getExpectedAmount()
			&& cancelled.balanceAmount() == 0L
			&& cancelled.status() == PaymentStatus.CANCELED
			&& Objects.equals(cancelled.cancelReason(), operation.getCancellationReason())
			&& cancelled.transactionKey() != null
			&& !cancelled.transactionKey().isBlank()
			&& cancelled.transactionKey().length() <= 64
			&& cancelled.cancelledAt() != null;
		if (!matches) {
			throw new PaymentOperationInvariantViolationException(
				"cancelled payment does not match its operation, reservation, and payment");
		}
	}

	private void validateActivePaymentForCancellation(
		PaymentOperation operation,
		Reservation reservation,
		Payment payment
	) {
		boolean matches = operation.getOperationType() == PaymentOperationType.CANCEL
			&& reservation.getStatus() == ReservationStatus.CANCELLATION_PENDING
			&& Objects.equals(payment.getReservation().getId(), reservation.getId())
			&& Objects.equals(payment.getPaymentKey(), operation.getPaymentKey())
			&& Objects.equals(payment.getOrderId(), reservation.getReservationUid().toString())
			&& payment.getStatus() == PaymentStatus.DONE
			&& payment.getAmount() != null
			&& payment.getBalanceAmount() != null
			&& payment.getAmount().equals(payment.getBalanceAmount())
			&& payment.getBalanceAmount() == operation.getExpectedAmount();
		if (!matches) {
			throw new PaymentOperationInvariantViolationException(
				"active payment does not match its cancellation operation and reservation");
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
			throw new PaymentOperationInvariantViolationException(
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
			throw new PaymentOperationInvariantViolationException(
				"reservation already has a different approved payment");
		}
	}

	private void requestAccommodationSearchRefresh(Reservation reservation) {
		searchRefreshPublisher.requestRefresh(
			reservation.getAccommodation().getAccommodationUid());
	}

	private String normalizeFailureCode(String code) {
		if (code == null || code.isBlank()) {
			return "UNKNOWN";
		}
		return code.length() <= FAILURE_CODE_MAX_LENGTH
			? code : code.substring(0, FAILURE_CODE_MAX_LENGTH);
	}
}
