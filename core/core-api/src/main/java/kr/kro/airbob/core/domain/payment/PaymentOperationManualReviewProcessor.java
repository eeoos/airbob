package kr.kro.airbob.core.domain.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.domain.accommodation.port.AccommodationSearchRefreshRequester;
import kr.kro.airbob.core.domain.coupon.CouponUsageManager;
import kr.kro.airbob.core.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.core.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.core.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.core.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.core.domain.reservation.Reservation;
import kr.kro.airbob.core.domain.reservation.ReservationHistoryWriter;
import kr.kro.airbob.core.domain.reservation.ReservationReader;
import kr.kro.airbob.core.domain.reservation.ReservationStatus;
import kr.kro.airbob.core.domain.reservation.inventory.ReservationInventoryManager;
import kr.kro.airbob.core.support.persistence.history.ChangeType;
import kr.kro.airbob.messaging.outbox.application.OutboxWriter;

@Component
public class PaymentOperationManualReviewProcessor {

	private static final String HISTORY_SOURCE = "PAYMENT_MANUAL_RESOLUTION";
	private static final String NOT_PAID_FAILURE_CODE = "MANUAL_NOT_PAID_RESOLUTION";
	private static final String NOT_PAID_FAILURE_MESSAGE = "Payment was verified as not paid.";
	private static final String NOT_PAID_HISTORY_REASON = "결제 미승인 수동 확정";
	private static final int EVIDENCE_REFERENCE_MAX_LENGTH = 256;
	private static final Pattern INTERNAL_EVIDENCE_REFERENCE =
		Pattern.compile("^[A-Za-z0-9_\\-/:.,]+$");

	private final PaymentOperationRepository operationRepository;
	private final ReservationReader reservationReader;
	private final ReservationInventoryManager inventoryManager;
	private final PaymentRepository paymentRepository;
	private final PaymentTransactionRepository transactionRepository;
	private final CouponUsageManager couponUsageManager;
	private final ReservationHistoryWriter reservationHistoryWriter;
	private final AccommodationSearchRefreshRequester searchRefreshPublisher;
	private final OutboxWriter outboxWriter;
	private final PaymentOperationManualResolutionRecorder resolutionRecorder;
	private final Clock clock;

	public PaymentOperationManualReviewProcessor(
		PaymentOperationRepository operationRepository,
		ReservationReader reservationReader,
		ReservationInventoryManager inventoryManager,
		PaymentRepository paymentRepository,
		PaymentTransactionRepository transactionRepository,
		CouponUsageManager couponUsageManager,
		ReservationHistoryWriter reservationHistoryWriter,
		AccommodationSearchRefreshRequester searchRefreshPublisher,
		OutboxWriter outboxWriter,
		PaymentOperationManualResolutionRecorder resolutionRecorder,
		Clock clock
	) {
		this.operationRepository = operationRepository;
		this.reservationReader = reservationReader;
		this.inventoryManager = inventoryManager;
		this.paymentRepository = paymentRepository;
		this.transactionRepository = transactionRepository;
		this.couponUsageManager = couponUsageManager;
		this.reservationHistoryWriter = reservationHistoryWriter;
		this.searchRefreshPublisher = searchRefreshPublisher;
		this.outboxWriter = outboxWriter;
		this.resolutionRecorder = resolutionRecorder;
		this.clock = clock;
	}

	@Transactional
	public PaymentOperationManualReviewResult requestReconciliation(
		UUID operationUid,
		Long actorMemberId,
		long expectedVersion
	) {
		validateActorMemberId(actorMemberId);
		PaymentOperation operation = lockAtVersion(operationUid, expectedVersion);
		validateReconciliationRequest(operation);
		PaymentOperationStatus previousStatus = operation.getStatus();
		Instant now = clock.instant();
		operation.requestManualReconciliation(now);
		resolutionRecorder.recordAdmin(
			operation,
			actorMemberId,
			PaymentOperationResolutionAction.RECONCILIATION_REQUESTED,
			"ADMIN_RECONCILIATION_REQUESTED",
			null,
			previousStatus,
			operation.getStatus(),
			now
		);
		outboxWriter.append(new PaymentOperationExecutionRequestedV1(
			operation.getOperationUid(),
			operation.getReservation().getReservationUid(),
			operation.getDispatchGeneration()
		));
		operationRepository.flush();
		return PaymentOperationManualReviewResult.from(operation);
	}

	@Transactional
	public PaymentOperationManualReviewResult markNotPaid(
		UUID operationUid,
		Long actorMemberId,
		long expectedVersion,
		PaymentOperationResolutionReason reasonCode,
		String evidenceReference
	) {
		validateActorMemberId(actorMemberId);
		if (reasonCode == null) {
			throw new PaymentOperationConflictException();
		}
		validateEvidenceReference(evidenceReference);
		PaymentOperation operation = lockAtVersion(operationUid, expectedVersion);
		validateNotPaidOperation(operation);
		Reservation reservation = reservationReader.readForUpdate(operation.getReservation().getId());
		validateNotPaidReservation(operation, reservation);
		if (paymentRepository.findByReservationIdWithLock(reservation.getId()).isPresent()) {
			throw new PaymentOperationInvariantViolationException(
				"an approved payment exists for the not-paid resolution");
		}
		if (transactionRepository.existsByPaymentOperationId(operation.getId())) {
			throw new PaymentOperationInvariantViolationException(
				"the operation already has a terminal payment ledger entry");
		}
		inventoryManager.releaseOccupied(
			reservation.getAccommodation().getId(),
			reservation.getCheckInDate(),
			reservation.getCheckOutDate(),
			reservation.getId()
		);

		PaymentOperationStatus previousStatus = operation.getStatus();
		Instant now = clock.instant();
		operation.markNotPaid(now, NOT_PAID_FAILURE_CODE, NOT_PAID_FAILURE_MESSAGE);
		transactionRepository.save(PaymentTransaction.fail(
			operation, reservation, NOT_PAID_FAILURE_CODE, NOT_PAID_FAILURE_MESSAGE));
		reservation.expireAfterFinalPaymentDecline();
		couponUsageManager.restore(reservation.getId());
		reservationHistoryWriter.recordSystem(
			reservation, ChangeType.STATUS_CHANGE, NOT_PAID_HISTORY_REASON, HISTORY_SOURCE);
		searchRefreshPublisher.requestRefresh(
			reservation.getAccommodation().getAccommodationUid());
		resolutionRecorder.recordAdmin(
			operation,
			actorMemberId,
			PaymentOperationResolutionAction.MARKED_NOT_PAID,
			reasonCode.name(),
			evidenceReference,
			previousStatus,
			operation.getStatus(),
			now
		);
		operationRepository.flush();
		return PaymentOperationManualReviewResult.from(operation);
	}

	private PaymentOperation lockAtVersion(UUID operationUid, long expectedVersion) {
		if (operationUid == null || expectedVersion < 0) {
			throw new PaymentOperationConflictException();
		}
		PaymentOperation operation = operationRepository.findByOperationUidWithLock(operationUid)
			.orElseThrow(PaymentOperationNotFoundException::new);
		if (operation.getVersion() != expectedVersion) {
			throw new PaymentOperationConflictException();
		}
		return operation;
	}

	private void validateNotPaidOperation(PaymentOperation operation) {
		if (operation.getOperationType() != PaymentOperationType.CONFIRM
			|| operation.getStatus() != PaymentOperationStatus.MANUAL_REVIEW
			|| operation.isManualReconciliationPending()
			|| !operation.isNotPaidResolutionEligible()) {
			throw new PaymentOperationConflictException();
		}
	}

	private void validateReconciliationRequest(PaymentOperation operation) {
		if (operation.getStatus() != PaymentOperationStatus.MANUAL_REVIEW
			|| operation.isManualReconciliationPending()) {
			throw new PaymentOperationConflictException();
		}
	}

	private void validateNotPaidReservation(PaymentOperation operation, Reservation reservation) {
		if (reservation.getStatus() != ReservationStatus.PAYMENT_PROCESSING
			|| !Objects.equals(reservation.getTotalPrice(), operation.getExpectedAmount())) {
			throw new PaymentOperationInvariantViolationException(
				"reservation does not match the not-paid resolution operation");
		}
	}

	private void validateEvidenceReference(String evidenceReference) {
		if (evidenceReference == null || evidenceReference.isBlank()
			|| evidenceReference.length() > EVIDENCE_REFERENCE_MAX_LENGTH
			|| !INTERNAL_EVIDENCE_REFERENCE.matcher(evidenceReference).matches()) {
			throw new PaymentOperationConflictException();
		}
	}

	private void validateActorMemberId(Long actorMemberId) {
		if (actorMemberId == null || actorMemberId <= 0) {
			throw new PaymentOperationConflictException();
		}
	}
}
