package kr.kro.airbob.domain.payment.entity;

import java.time.Instant;
import java.util.Objects;

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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_operation_resolution")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentOperationResolution {

	public static final int AUDIT_TEXT_MAX_LENGTH = 512;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "payment_operation_id", nullable = false)
	private PaymentOperation paymentOperation;

	@Column(name = "dispatch_generation", nullable = false)
	private long dispatchGeneration;

	@Column(name = "actor_member_id")
	private Long actorMemberId;

	@Enumerated(EnumType.STRING)
	@Column(name = "actor_type", nullable = false, length = 30)
	private PaymentOperationResolutionActorType actorType;

	@Enumerated(EnumType.STRING)
	@Column(name = "resolution_action", nullable = false, length = 50)
	private PaymentOperationResolutionAction resolutionAction;

	@Column(nullable = false, length = AUDIT_TEXT_MAX_LENGTH)
	private String reason;

	@Column(name = "evidence_reference", length = AUDIT_TEXT_MAX_LENGTH)
	private String evidenceReference;

	@Enumerated(EnumType.STRING)
	@Column(name = "previous_status", nullable = false, length = 30)
	private PaymentOperationStatus previousStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "result_status", nullable = false, length = 30)
	private PaymentOperationStatus resultStatus;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private PaymentOperationResolution(
		PaymentOperation paymentOperation,
		Long actorMemberId,
		PaymentOperationResolutionActorType actorType,
		PaymentOperationResolutionAction resolutionAction,
		String reason,
		String evidenceReference,
		PaymentOperationStatus previousStatus,
		PaymentOperationStatus resultStatus,
		Instant createdAt
	) {
		this.paymentOperation = Objects.requireNonNull(paymentOperation, "paymentOperation must not be null");
		this.dispatchGeneration = paymentOperation.getDispatchGeneration();
		if (dispatchGeneration <= 0) {
			throw new IllegalArgumentException("payment operation dispatch generation must be positive");
		}
		this.actorMemberId = actorMemberId;
		this.actorType = Objects.requireNonNull(actorType, "actorType must not be null");
		this.resolutionAction = Objects.requireNonNull(resolutionAction, "resolutionAction must not be null");
		this.reason = requireAuditReason(reason);
		this.evidenceReference = requireBoundedText(evidenceReference, "evidenceReference");
		this.previousStatus = Objects.requireNonNull(previousStatus, "previousStatus must not be null");
		this.resultStatus = Objects.requireNonNull(resultStatus, "resultStatus must not be null");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
		validateActor();
	}

	public static PaymentOperationResolution recordSystem(
		PaymentOperation paymentOperation,
		PaymentOperationResolutionAction resolutionAction,
		String reason,
		String evidenceReference,
		PaymentOperationStatus previousStatus,
		PaymentOperationStatus resultStatus,
		Instant createdAt
	) {
		return new PaymentOperationResolution(
			paymentOperation,
			null,
			PaymentOperationResolutionActorType.SYSTEM,
			resolutionAction,
			reason,
			evidenceReference,
			previousStatus,
			resultStatus,
			createdAt
		);
	}

	public static PaymentOperationResolution recordAdmin(
		PaymentOperation paymentOperation,
		Long actorMemberId,
		PaymentOperationResolutionAction resolutionAction,
		String reason,
		String evidenceReference,
		PaymentOperationStatus previousStatus,
		PaymentOperationStatus resultStatus,
		Instant createdAt
	) {
		return new PaymentOperationResolution(
			paymentOperation,
			actorMemberId,
			PaymentOperationResolutionActorType.ADMIN,
			resolutionAction,
			reason,
			evidenceReference,
			previousStatus,
			resultStatus,
			createdAt
		);
	}

	private void validateActor() {
		if (actorType == PaymentOperationResolutionActorType.SYSTEM && actorMemberId != null) {
			throw new IllegalArgumentException("system audit must not have an actor member");
		}
		if (actorType == PaymentOperationResolutionActorType.ADMIN
			&& (actorMemberId == null || actorMemberId <= 0)) {
			throw new IllegalArgumentException("admin audit requires a positive actor member id");
		}
	}

	private static String requireAuditReason(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		return requireBoundedText(value, "reason");
	}

	private static String requireBoundedText(String value, String fieldName) {
		if (value != null && value.length() > AUDIT_TEXT_MAX_LENGTH) {
			throw new IllegalArgumentException(
				fieldName + " must not exceed " + AUDIT_TEXT_MAX_LENGTH + " characters");
		}
		return value;
	}
}
