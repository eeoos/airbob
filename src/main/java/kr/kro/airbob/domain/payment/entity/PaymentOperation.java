package kr.kro.airbob.domain.payment.entity;

import java.time.Instant;
import java.util.Objects;
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
		UUID operationUid = UUID.randomUUID();
		return PaymentOperation.builder()
			.operationUid(operationUid)
			.reservation(reservation)
			.requesterMemberId(requesterMemberId)
			.operationType(PaymentOperationType.CONFIRM)
			.status(PaymentOperationStatus.READY)
			.paymentKey(limitLength(paymentKey, PAYMENT_KEY_MAX_LENGTH))
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

	public void recordEnqueued(Instant now) {
		this.lastEnqueuedAt = now;
	}

	private static String limitLength(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}
}
