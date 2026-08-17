package kr.kro.airbob.domain.payment.entity;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.payment.exception.PaymentOperationInvariantException;
import kr.kro.airbob.domain.payment.service.PaymentExecutionMode;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentOperation extends BaseEntity {
	private static final int PAYMENT_KEY_MAX_LENGTH = 200;
	private static final int PROVIDER_IDEMPOTENCY_KEY_MAX_LENGTH = 100;
	private static final int DEDUPLICATION_KEY_MAX_LENGTH = 100;
	private static final int LEASE_OWNER_MAX_LENGTH = 100;
	private static final int FAILURE_CODE_MAX_LENGTH = 100;
	private static final int FAILURE_MESSAGE_MAX_LENGTH = 512;
	public static final int CANCELLATION_REASON_MAX_LENGTH = 200;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@JdbcTypeCode(SqlTypes.BINARY)
	@Column(nullable = false, unique = true, columnDefinition = "BINARY(16)")
	private UUID operationUid;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reservation_id", nullable = false)
	private Reservation reservation;

	@Column(nullable = false)
	private Long requesterMemberId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PaymentOperationType operationType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PaymentOperationStatus status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PaymentOperationNextAction nextAction;

	@Column(nullable = false, length = PAYMENT_KEY_MAX_LENGTH)
	private String paymentKey;

	@Column(nullable = false)
	private long expectedAmount;

	@Column(nullable = false, unique = true, length = PROVIDER_IDEMPOTENCY_KEY_MAX_LENGTH)
	private String providerIdempotencyKey;

	@Column(nullable = false, unique = true, length = DEDUPLICATION_KEY_MAX_LENGTH)
	private String deduplicationKey;

	@Column(nullable = false)
	private long dispatchGeneration;

	@Column(nullable = false)
	private int attemptCount;

	private Instant nextAttemptAt;

	@Column(nullable = false)
	private Instant queuedAt;

	@Column(length = LEASE_OWNER_MAX_LENGTH)
	private String leaseOwner;

	private Instant leaseExpiresAt;

	private Instant reviewRequiredAt;

	@Column(length = CANCELLATION_REASON_MAX_LENGTH)
	private String cancellationReason;

	@Column(nullable = false)
	private boolean manualReconciliationPending;

	@Column(nullable = false)
	private boolean notPaidResolutionEligible;

	@Column(nullable = false)
	private int manualReviewCount;

	@Column(length = FAILURE_CODE_MAX_LENGTH)
	private String failureCode;

	@Column(length = FAILURE_MESSAGE_MAX_LENGTH)
	private String failureMessage;

	private Instant completedAt;

	@Version
	@Column(nullable = false)
	private long version;

	public static PaymentOperation createConfirmation(
		Reservation reservation, Long requesterMemberId, String paymentKey, long amount, Instant now
	) {
		requireMaxLength(paymentKey, PAYMENT_KEY_MAX_LENGTH, "paymentKey");
		UUID operationUid = UUID.randomUUID();
		return PaymentOperation.builder()
			.operationUid(operationUid)
			.reservation(reservation)
			.requesterMemberId(requesterMemberId)
			.operationType(PaymentOperationType.CONFIRM)
			.status(PaymentOperationStatus.QUEUED)
			.nextAction(PaymentOperationNextAction.CONFIRM)
			.paymentKey(paymentKey)
			.expectedAmount(amount)
			.providerIdempotencyKey("airbob-confirm-" + operationUid)
			.deduplicationKey("CONFIRM:" + reservation.getReservationUid())
			.dispatchGeneration(1)
			.attemptCount(0)
			.queuedAt(now)
			.manualReconciliationPending(false)
			.notPaidResolutionEligible(false)
			.manualReviewCount(0)
			.build();
	}

	public static PaymentOperation createCancellation(
		Reservation reservation,
		Long requesterMemberId,
		String paymentKey,
		long amount,
		String cancellationReason,
		Instant now
	) {
		requireMaxLength(paymentKey, PAYMENT_KEY_MAX_LENGTH, "paymentKey");
		requireMaxLength(cancellationReason, CANCELLATION_REASON_MAX_LENGTH, "cancellationReason");
		if (cancellationReason == null || cancellationReason.isBlank()) {
			throw new IllegalArgumentException("cancellationReason must not be blank");
		}
		UUID operationUid = UUID.randomUUID();
		return PaymentOperation.builder()
			.operationUid(operationUid)
			.reservation(reservation)
			.requesterMemberId(requesterMemberId)
			.operationType(PaymentOperationType.CANCEL)
			.status(PaymentOperationStatus.QUEUED)
			.nextAction(PaymentOperationNextAction.CANCEL)
			.paymentKey(paymentKey)
			.expectedAmount(amount)
			.providerIdempotencyKey("airbob-cancel-" + operationUid)
			.deduplicationKey("CANCEL:" + reservation.getReservationUid() + ":" + operationUid)
			.dispatchGeneration(1)
			.attemptCount(0)
			.queuedAt(now)
			.cancellationReason(cancellationReason)
			.manualReconciliationPending(false)
			.notPaidResolutionEligible(false)
			.manualReviewCount(0)
			.build();
	}

	public boolean matchesConfirmation(String paymentKey, long amount) {
		return Objects.equals(this.paymentKey, paymentKey) && this.expectedAmount == amount;
	}

	public boolean matchesCancellation(String reason, Long requestedAmount) {
		return Objects.equals(cancellationReason, reason)
			&& (requestedAmount == null || expectedAmount == requestedAmount);
	}

	public boolean isRequestedBy(Long memberId) {
		return Objects.equals(this.requesterMemberId, memberId);
	}

	public boolean isApplied() {
		return status == PaymentOperationStatus.APPLIED;
	}

	public boolean isDeclined() {
		return status == PaymentOperationStatus.DECLINED;
	}

	public boolean isCancellation() {
		return operationType == PaymentOperationType.CANCEL;
	}

	public void rejectOppositeTerminal(PaymentOperationStatus targetStatus) {
		if (status.isTerminal() && status != targetStatus) {
			throw new PaymentOperationInvariantException(
				"terminal operation cannot change from " + status + " to " + targetStatus);
		}
	}

	public boolean isOwnedBy(String owner, long expectedGeneration) {
		return status == PaymentOperationStatus.EXECUTING
			&& dispatchGeneration == expectedGeneration
			&& Objects.equals(leaseOwner, owner);
	}

	public void markApplied(Instant now) {
		complete(PaymentOperationStatus.APPLIED, now, null, null);
	}

	public void markDeclined(Instant now, String code, String message) {
		complete(PaymentOperationStatus.DECLINED, now, code, message);
	}

	public Optional<PaymentExecutionMode> acquireLease(
		String owner, long expectedGeneration, Instant now, Duration leaseDuration
	) {
		if (!isQueuedGeneration(expectedGeneration)) {
			return Optional.empty();
		}
		PaymentExecutionMode mode = executionMode();
		if (manualReconciliationPending && !mode.isInquiry()) {
			throw new PaymentOperationInvariantException(
				"manual reconciliation can only acquire a provider inquiry lease");
		}
		status = PaymentOperationStatus.EXECUTING;
		leaseOwner = owner;
		leaseExpiresAt = now.plus(leaseDuration);
		attemptCount++;
		return Optional.of(mode);
	}

	public boolean markManualReviewIfAttemptsExhausted(
		long expectedGeneration, int maxAttempts, Instant now
	) {
		if (attemptCount < maxAttempts || !isQueuedGeneration(expectedGeneration)) {
			return false;
		}
		moveToManualReview(now);
		return true;
	}

	public boolean prepareRecoveryDispatch(Instant now) {
		if (status == PaymentOperationStatus.WAITING_RETRY) {
			if (nextAttemptAt == null || nextAttemptAt.isAfter(now)) {
				return false;
			}
			queueNextGeneration(now);
			return true;
		}
		if (status != PaymentOperationStatus.EXECUTING
			|| leaseExpiresAt == null || leaseExpiresAt.isAfter(now)) {
			return false;
		}
		nextAction = inquiryActionFor(operationType);
		queueNextGeneration(now);
		return true;
	}

	public boolean redispatchQuarantinedGeneration(long expectedGeneration, Instant now) {
		if (!isQueuedGeneration(expectedGeneration)) {
			return false;
		}
		queueNextGeneration(now);
		return true;
	}

	public void requestManualReconciliation(Instant now) {
		Objects.requireNonNull(now, "now must not be null");
		if (status != PaymentOperationStatus.MANUAL_REVIEW || manualReconciliationPending) {
			throw new PaymentOperationInvariantException(
				"only a paused manual-review operation can request reconciliation");
		}
		nextAction = inquiryActionFor(operationType);
		attemptCount = 0;
		manualReconciliationPending = true;
		notPaidResolutionEligible = false;
		reviewRequiredAt = null;
		failureCode = null;
		failureMessage = null;
		queueNextGeneration(now);
	}

	private void moveToManualReview(Instant now) {
		moveToManualReview(now, false);
	}

	private void moveToManualReview(Instant now, boolean notPaidEligible) {
		if (notPaidEligible && operationType != PaymentOperationType.CONFIRM) {
			throw new PaymentOperationInvariantException(
				"only a confirmation can become eligible for not-paid resolution");
		}
		status = PaymentOperationStatus.MANUAL_REVIEW;
		leaseOwner = null;
		leaseExpiresAt = null;
		nextAttemptAt = null;
		reviewRequiredAt = now;
		manualReconciliationPending = false;
		notPaidResolutionEligible = notPaidEligible;
		manualReviewCount++;
		completedAt = null;
	}

	public boolean returnManualReconciliationToReview(
		String owner,
		long expectedGeneration,
		Instant now,
		String code,
		String message,
		boolean notPaidEligible
	) {
		if (!manualReconciliationPending || !isOwnedBy(owner, expectedGeneration)) {
			return false;
		}
		failureCode = limitLength(code, FAILURE_CODE_MAX_LENGTH);
		failureMessage = limitLength(message, FAILURE_MESSAGE_MAX_LENGTH);
		moveToManualReview(now, notPaidEligible);
		return true;
	}

	public void markNotPaid(Instant now, String code, String message) {
		if (operationType != PaymentOperationType.CONFIRM
			|| status != PaymentOperationStatus.MANUAL_REVIEW
			|| manualReconciliationPending
			|| !notPaidResolutionEligible) {
			throw new PaymentOperationInvariantException(
				"operation is not eligible for a not-paid resolution");
		}
		status = PaymentOperationStatus.DECLINED;
		leaseOwner = null;
		leaseExpiresAt = null;
		nextAttemptAt = null;
		reviewRequiredAt = null;
		notPaidResolutionEligible = false;
		failureCode = limitLength(code, FAILURE_CODE_MAX_LENGTH);
		failureMessage = limitLength(message, FAILURE_MESSAGE_MAX_LENGTH);
		completedAt = Objects.requireNonNull(now, "now must not be null");
	}

	public boolean scheduleRetry(
		String owner, long expectedGeneration, Instant retryAt, String code, String message
	) {
		return transitionFromExecution(
			owner,
			expectedGeneration,
			manualReconciliationPending
				? inquiryActionFor(operationType)
				: executionActionFor(operationType),
			retryAt,
			code,
			message);
	}

	public boolean markOutcomeUnknown(
		String owner, long expectedGeneration, Instant retryAt, String code, String message
	) {
		return transitionFromExecution(
			owner, expectedGeneration, inquiryActionFor(operationType),
			retryAt, code, message);
	}

	public boolean markManualReview(
		String owner, long expectedGeneration, Instant now, String code, String message
	) {
		if (!isOwnedBy(owner, expectedGeneration)) {
			return false;
		}
		failureCode = limitLength(code, FAILURE_CODE_MAX_LENGTH);
		failureMessage = limitLength(message, FAILURE_MESSAGE_MAX_LENGTH);
		moveToManualReview(now);
		return true;
	}

	private boolean transitionFromExecution(
		String owner,
		long expectedGeneration,
		PaymentOperationNextAction retryAction,
		Instant retryAt,
		String code,
		String message
	) {
		if (!isOwnedBy(owner, expectedGeneration)) {
			return false;
		}
		status = PaymentOperationStatus.WAITING_RETRY;
		nextAction = retryAction;
		leaseOwner = null;
		leaseExpiresAt = null;
		nextAttemptAt = retryAt;
		failureCode = limitLength(code, FAILURE_CODE_MAX_LENGTH);
		failureMessage = limitLength(message, FAILURE_MESSAGE_MAX_LENGTH);
		completedAt = null;
		return true;
	}

	private void complete(PaymentOperationStatus terminalStatus, Instant now, String code, String message) {
		if (status != PaymentOperationStatus.EXECUTING) {
			throw new PaymentOperationInvariantException(
				"only an executing payment operation can become " + terminalStatus);
		}
		status = terminalStatus;
		leaseOwner = null;
		leaseExpiresAt = null;
		nextAttemptAt = null;
		failureCode = limitLength(code, FAILURE_CODE_MAX_LENGTH);
		failureMessage = limitLength(message, FAILURE_MESSAGE_MAX_LENGTH);
		completedAt = now;
		reviewRequiredAt = null;
		manualReconciliationPending = false;
		notPaidResolutionEligible = false;
	}

	private boolean isQueuedGeneration(long expectedGeneration) {
		return status == PaymentOperationStatus.QUEUED
			&& dispatchGeneration == expectedGeneration;
	}

	private PaymentExecutionMode executionMode() {
		return switch (nextAction) {
			case CONFIRM -> PaymentExecutionMode.CONFIRM;
			case INQUIRE_CONFIRM -> PaymentExecutionMode.INQUIRE_CONFIRM;
			case CANCEL -> PaymentExecutionMode.CANCEL;
			case INQUIRE_CANCEL -> PaymentExecutionMode.INQUIRE_CANCEL;
		};
	}

	private void queueNextGeneration(Instant now) {
		try {
			dispatchGeneration = Math.addExact(dispatchGeneration, 1);
		} catch (ArithmeticException overflow) {
			throw new PaymentOperationInvariantException("payment operation dispatch generation overflow");
		}
		status = PaymentOperationStatus.QUEUED;
		queuedAt = now;
		nextAttemptAt = null;
		leaseOwner = null;
		leaseExpiresAt = null;
	}

	private PaymentOperationNextAction inquiryActionFor(PaymentOperationType type) {
		return switch (type) {
			case CONFIRM -> PaymentOperationNextAction.INQUIRE_CONFIRM;
			case CANCEL -> PaymentOperationNextAction.INQUIRE_CANCEL;
		};
	}

	private PaymentOperationNextAction executionActionFor(PaymentOperationType type) {
		return switch (type) {
			case CONFIRM -> PaymentOperationNextAction.CONFIRM;
			case CANCEL -> PaymentOperationNextAction.CANCEL;
		};
	}

	private static void requireMaxLength(String value, int maxLength, String fieldName) {
		if (value != null && value.length() > maxLength) {
			throw new IllegalArgumentException(fieldName + " must not exceed " + maxLength + " characters");
		}
	}

	private static String limitLength(String value, int maxLength) {
		return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
	}
}
