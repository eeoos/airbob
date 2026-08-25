package kr.kro.airbob.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class PaymentOperationResolutionTest {

	private static final Instant RECORDED_AT = Instant.parse("2026-08-17T01:02:03Z");

	@Test
	void keepsTheAuditVocabularyClosed() {
		assertThat(PaymentOperationResolutionActorType.values())
			.extracting(Enum::name)
			.containsExactly("SYSTEM", "ADMIN");
		assertThat(PaymentOperationResolutionAction.values())
			.extracting(Enum::name)
			.containsExactly(
				"RECONCILIATION_REQUESTED",
				"RECONCILIATION_APPLIED",
				"RECONCILIATION_DECLINED",
				"RECONCILIATION_RETURNED_TO_REVIEW",
				"MARKED_NOT_PAID"
			);
	}

	@Test
	void recordsSystemAuditWithoutPretendingAnAdminActed() {
		PaymentOperation operation = PaymentOperation.builder().dispatchGeneration(7).build();

		PaymentOperationResolution resolution = PaymentOperationResolution.recordSystem(
			operation,
			PaymentOperationResolutionAction.RECONCILIATION_RETURNED_TO_REVIEW,
			"Provider inquiry remained inconclusive",
			"provider-code=UNKNOWN",
			PaymentOperationStatus.EXECUTING,
			PaymentOperationStatus.MANUAL_REVIEW,
			RECORDED_AT
		);

		assertThat(resolution.getPaymentOperation()).isSameAs(operation);
		assertThat(resolution.getDispatchGeneration()).isEqualTo(7);
		assertThat(resolution.getActorType()).isEqualTo(PaymentOperationResolutionActorType.SYSTEM);
		assertThat(resolution.getActorMemberId()).isNull();
		assertThat(resolution.getResolutionAction())
			.isEqualTo(PaymentOperationResolutionAction.RECONCILIATION_RETURNED_TO_REVIEW);
		assertThat(resolution.getReason()).isEqualTo("Provider inquiry remained inconclusive");
		assertThat(resolution.getEvidenceReference()).isEqualTo("provider-code=UNKNOWN");
		assertThat(resolution.getPreviousStatus()).isEqualTo(PaymentOperationStatus.EXECUTING);
		assertThat(resolution.getResultStatus()).isEqualTo(PaymentOperationStatus.MANUAL_REVIEW);
		assertThat(resolution.getCreatedAt()).isEqualTo(RECORDED_AT);
	}

	@Test
	void recordsAdminAuditWithTheResponsibleMember() {
		PaymentOperationResolution resolution = PaymentOperationResolution.recordAdmin(
			PaymentOperation.builder().dispatchGeneration(7).build(),
			42L,
			PaymentOperationResolutionAction.RECONCILIATION_REQUESTED,
			"Reconcile against the provider dashboard",
			null,
			PaymentOperationStatus.MANUAL_REVIEW,
			PaymentOperationStatus.QUEUED,
			RECORDED_AT
		);

		assertThat(resolution.getActorType()).isEqualTo(PaymentOperationResolutionActorType.ADMIN);
		assertThat(resolution.getActorMemberId()).isEqualTo(42L);
	}

	@Test
	void rejectsInvalidActorAndUnboundedAuditText() {
		PaymentOperation operation = PaymentOperation.builder().dispatchGeneration(7).build();

		assertThatThrownBy(() -> PaymentOperationResolution.recordAdmin(
			operation,
			null,
			PaymentOperationResolutionAction.MARKED_NOT_PAID,
			"Verified not paid",
			null,
			PaymentOperationStatus.MANUAL_REVIEW,
			PaymentOperationStatus.DECLINED,
			RECORDED_AT
		)).isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> PaymentOperationResolution.recordSystem(
			operation,
			PaymentOperationResolutionAction.RECONCILIATION_RETURNED_TO_REVIEW,
			"x".repeat(513),
			null,
			PaymentOperationStatus.EXECUTING,
			PaymentOperationStatus.MANUAL_REVIEW,
			RECORDED_AT
		)).isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> PaymentOperationResolution.recordSystem(
			operation,
			PaymentOperationResolutionAction.RECONCILIATION_RETURNED_TO_REVIEW,
			"Still uncertain",
			"x".repeat(513),
			PaymentOperationStatus.EXECUTING,
			PaymentOperationStatus.MANUAL_REVIEW,
			RECORDED_AT
		)).isInstanceOf(IllegalArgumentException.class);
	}
}
