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

	@Column(nullable = false, length = PAYMENT_KEY_MAX_LENGTH)
	private String paymentKey;

	@Column(nullable = false)
	private long expectedAmount;

	@Column(nullable = false, unique = true, length = PROVIDER_IDEMPOTENCY_KEY_MAX_LENGTH)
	private String providerIdempotencyKey;

	@Column(nullable = false, unique = true, length = DEDUPLICATION_KEY_MAX_LENGTH)
	private String deduplicationKey;

	@Column(nullable = false)
	private int attemptCount;

	private Instant nextAttemptAt;

	@Column(nullable = false)
	private Instant lastEnqueuedAt;

	@Column(length = LEASE_OWNER_MAX_LENGTH)
	private String leaseOwner;

	private Instant leaseExpiresAt;

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
			.status(PaymentOperationStatus.READY)
			.paymentKey(paymentKey)
			.expectedAmount(amount)
			.providerIdempotencyKey("airbob-confirm-" + operationUid)
			.deduplicationKey("CONFIRM:" + reservation.getReservationUid())
			.attemptCount(0)
			.nextAttemptAt(now)
			.lastEnqueuedAt(now)
			.build();
	}

	public boolean matchesConfirmation(String paymentKey, long amount) {
		return Objects.equals(this.paymentKey, paymentKey) && this.expectedAmount == amount;
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

	public void rejectOppositeTerminal(PaymentOperationStatus targetStatus) {
		if (status.isTerminal() && status != targetStatus) {
			throw new PaymentOperationInvariantException(
				"terminal operation cannot change from " + status + " to " + targetStatus);
		}
	}

	public boolean isOwnedBy(String owner) {
		return status == PaymentOperationStatus.EXECUTING && Objects.equals(leaseOwner, owner);
	}

	public void markApplied(Instant now) {
		complete(PaymentOperationStatus.APPLIED, now, null, null);
	}

	public void markDeclined(Instant now, String code, String message) {
		complete(PaymentOperationStatus.DECLINED, now, code, message);
	}

	public void recordEnqueued(Instant now) {
		this.lastEnqueuedAt = now;
	}

	public Optional<PaymentExecutionMode> acquireLease(
		String owner, Instant now, Duration leaseDuration
	) {
		if (!isEligibleForLeaseAt(now)) {
			return Optional.empty();
		}
		PaymentExecutionMode mode = status == PaymentOperationStatus.OUTCOME_UNKNOWN
			|| status == PaymentOperationStatus.EXECUTING
			? PaymentExecutionMode.INQUIRE : PaymentExecutionMode.CONFIRM;
		status = PaymentOperationStatus.EXECUTING;
		leaseOwner = owner;
		leaseExpiresAt = now.plus(leaseDuration);
		attemptCount++;
		return Optional.of(mode);
	}

	public boolean markManualReviewIfAttemptsExhausted(int maxAttempts, Instant now) {
		if (attemptCount < maxAttempts || !isEligibleForLeaseAt(now)) {
			return false;
		}
		status = PaymentOperationStatus.MANUAL_REVIEW;
		leaseOwner = null;
		leaseExpiresAt = null;
		nextAttemptAt = null;
		completedAt = now;
		return true;
	}

	public boolean scheduleRetry(String owner, Instant retryAt, String code, String message) {
		return transitionFromExecution(
			owner, PaymentOperationStatus.RETRY_WAIT, retryAt, code, message, null);
	}

	public boolean markOutcomeUnknown(String owner, Instant retryAt, String code, String message) {
		return transitionFromExecution(
			owner, PaymentOperationStatus.OUTCOME_UNKNOWN, retryAt, code, message, null);
	}

	public boolean markManualReview(String owner, Instant now, String code, String message) {
		return transitionFromExecution(
			owner, PaymentOperationStatus.MANUAL_REVIEW, null, code, message, now);
	}

	private boolean transitionFromExecution(
		String owner,
		PaymentOperationStatus nextStatus,
		Instant retryAt,
		String code,
		String message,
		Instant terminalAt
	) {
		if (status != PaymentOperationStatus.EXECUTING || !Objects.equals(leaseOwner, owner)) {
			return false;
		}
		status = nextStatus;
		leaseOwner = null;
		leaseExpiresAt = null;
		nextAttemptAt = retryAt;
		failureCode = limitLength(code, FAILURE_CODE_MAX_LENGTH);
		failureMessage = limitLength(message, FAILURE_MESSAGE_MAX_LENGTH);
		completedAt = terminalAt;
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
	}

	private boolean isEligibleForLeaseAt(Instant now) {
		if (status.isTerminal()) {
			return false;
		}
		if (status == PaymentOperationStatus.EXECUTING
			&& leaseExpiresAt != null && leaseExpiresAt.isAfter(now)) {
			return false;
		}
		return (status != PaymentOperationStatus.RETRY_WAIT && status != PaymentOperationStatus.OUTCOME_UNKNOWN)
			|| nextAttemptAt == null || !nextAttemptAt.isAfter(now);
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
