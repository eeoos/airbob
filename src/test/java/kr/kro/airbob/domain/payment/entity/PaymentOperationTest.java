package kr.kro.airbob.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.payment.exception.PaymentOperationInvariantException;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.payment.service.PaymentExecutionMode;

class PaymentOperationTest {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

	private Reservation reservation;

	@BeforeEach
	void setUp() {
		reservation = Reservation.builder()
			.id(1L)
			.reservationUid(UUID.fromString("018290b8-d807-7a2e-8f41-114c7d0a4a21"))
			.build();
	}

	@Test
	void createsStableOperationIdentityFromReservation() {
		PaymentOperation operation = PaymentOperation.createConfirmation(
			reservation, 7L, "secret-payment-key", 100_000L, NOW);

		assertThat(operation.getOperationUid()).isNotNull();
		assertThat(operation.getProviderIdempotencyKey())
			.isEqualTo("airbob-confirm-" + operation.getOperationUid());
		assertThat(operation.getDeduplicationKey())
			.isEqualTo("CONFIRM:018290b8-d807-7a2e-8f41-114c7d0a4a21");
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.QUEUED);
		assertThat(operation.getNextAction()).isEqualTo(PaymentOperationNextAction.CONFIRM);
		assertThat(operation.getDispatchGeneration()).isOne();
		assertThat(operation.getQueuedAt()).isEqualTo(NOW);
		assertThat(operation.getAttemptCount()).isZero();
	}

	@Test
	void createsCancellationWithItsOwnStableProviderKeyAndFirstDispatch() {
		PaymentOperation operation = PaymentOperation.createCancellation(
			reservation, 7L, "secret-payment-key", 100_000L, "게스트 요청", NOW);

		assertThat(operation.getOperationType()).isEqualTo(PaymentOperationType.CANCEL);
		assertThat(operation.getProviderIdempotencyKey())
			.isEqualTo("airbob-cancel-" + operation.getOperationUid());
		assertThat(operation.getDeduplicationKey())
			.isEqualTo("CANCEL:018290b8-d807-7a2e-8f41-114c7d0a4a21:" + operation.getOperationUid());
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.QUEUED);
		assertThat(operation.getNextAction()).isEqualTo(PaymentOperationNextAction.CANCEL);
		assertThat(operation.getCancellationReason()).isEqualTo("게스트 요청");
		assertThat(operation.getDispatchGeneration()).isOne();
		assertThat(operation.getQueuedAt()).isEqualTo(NOW);
	}

	@Test
	void cancellationReasonUsesTheSameTwoHundredCharacterBoundaryAsTheLedger() {
		String maximumReason = "사".repeat(200);

		PaymentOperation operation = PaymentOperation.createCancellation(
			reservation, 7L, "payment-key", 100_000L, maximumReason, NOW);

		assertThat(operation.getCancellationReason()).hasSize(200);
		assertThatThrownBy(() -> PaymentOperation.createCancellation(
			reservation, 7L, "payment-key", 100_000L, maximumReason + "유", NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("cancellationReason must not exceed 200 characters");
	}

	@Test
	void distinguishesIdenticalAndConflictingConfirmationReplays() {
		PaymentOperation operation = operation("pk-one", 100_000L);

		assertThat(operation.matchesConfirmation("pk-one", 100_000L)).isTrue();
		assertThat(operation.matchesConfirmation("pk-two", 100_000L)).isFalse();
		assertThat(operation.matchesConfirmation("pk-one", 90_000L)).isFalse();
	}

	@Test
	void rejectsAnOversizedPaymentKeyBeforeCreatingTheDurableOperation() {
		String oversizedPaymentKey = "p".repeat(201);

		assertThatThrownBy(() -> operation(oversizedPaymentKey, 100_000L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("paymentKey must not exceed 200 characters");
	}

	@Test
	void preservesAnExactMaximumLengthPaymentKeyForConfirmationReplay() {
		String paymentKey = "p".repeat(200);

		PaymentOperation operation = operation(paymentKey, 100_000L);

		assertThat(operation.getPaymentKey()).isEqualTo(paymentKey);
		assertThat(operation.matchesConfirmation(paymentKey, 100_000L)).isTrue();
	}

	@Test
	void identifiesOnlyTheMemberThatRequestedIt() {
		PaymentOperation operation = operation("pk-one", 100_000L);

		assertThat(operation.isRequestedBy(7L)).isTrue();
		assertThat(operation.isRequestedBy(8L)).isFalse();
	}

	@Test
	void distinguishesCompletedOperationStatesFromPausedManualReview() {
		assertThat(PaymentOperationStatus.APPLIED.isTerminal()).isTrue();
		assertThat(PaymentOperationStatus.DECLINED.isTerminal()).isTrue();
		assertThat(PaymentOperationStatus.MANUAL_REVIEW.isTerminal()).isFalse();
		assertThat(PaymentOperationStatus.QUEUED.isTerminal()).isFalse();
		assertThat(PaymentOperationStatus.EXECUTING.isTerminal()).isFalse();
		assertThat(PaymentOperationStatus.WAITING_RETRY.isTerminal()).isFalse();
	}

	@Test
	void queuedOperationAcquiresOnlyItsMatchingGeneration() {
		PaymentOperation operation = operation("pk-one", 100_000L);

		assertThat(operation.acquireLease("worker-stale", 0, NOW, Duration.ofSeconds(30))).isEmpty();
		assertThat(operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30)))
			.contains(PaymentExecutionMode.CONFIRM);
		assertThat(operation.acquireLease("worker-two", 1, NOW, Duration.ofSeconds(30))).isEmpty();
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.EXECUTING);
		assertThat(operation.getAttemptCount()).isEqualTo(1);
		assertThat(operation.getLeaseOwner()).isEqualTo("worker-one");
		assertThat(operation.getLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
	}

	@Test
	void expiredLeaseMustBeRedispatchedBeforeItCanBeClaimedInInquiryMode() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("old-worker", 1, NOW.minusSeconds(30), Duration.ofSeconds(30));

		assertThat(operation.prepareRecoveryDispatch(NOW)).isTrue();
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.QUEUED);
		assertThat(operation.getNextAction()).isEqualTo(PaymentOperationNextAction.INQUIRE_CONFIRM);
		assertThat(operation.getDispatchGeneration()).isEqualTo(2);
		assertThat(operation.acquireLease("stale-worker", 1, NOW, Duration.ofSeconds(30))).isEmpty();
		assertThat(operation.acquireLease("new-worker", 2, NOW, Duration.ofSeconds(30)))
			.contains(PaymentExecutionMode.INQUIRE_CONFIRM);
		assertThat(operation.getLeaseOwner()).isEqualTo("new-worker");
		assertThat(operation.getAttemptCount()).isEqualTo(2);
	}

	@Test
	void retryWaitMustBeDueAndRedispatchedBeforeItCanBeClaimed() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30));
		operation.scheduleRetry("worker-one", 1, NOW.plusSeconds(10), "TEMPORARY", "try later");

		assertThat(operation.prepareRecoveryDispatch(NOW.plusSeconds(9))).isFalse();
		assertThat(operation.prepareRecoveryDispatch(NOW.plusSeconds(10))).isTrue();
		assertThat(operation.acquireLease("worker-two", 2, NOW.plusSeconds(10), Duration.ofSeconds(30)))
			.contains(PaymentExecutionMode.CONFIRM);
	}

	@Test
	void unknownOutcomeWaitsForAnInquiryRedispatch() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30));
		operation.markOutcomeUnknown(
			"worker-one", 1, NOW.plusSeconds(10), "TIMEOUT", "response unknown");

		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.WAITING_RETRY);
		assertThat(operation.getNextAction()).isEqualTo(PaymentOperationNextAction.INQUIRE_CONFIRM);
		assertThat(operation.prepareRecoveryDispatch(NOW.plusSeconds(9))).isFalse();
		assertThat(operation.prepareRecoveryDispatch(NOW.plusSeconds(10))).isTrue();
		assertThat(operation.acquireLease("worker-two", 2, NOW.plusSeconds(10), Duration.ofSeconds(30)))
			.contains(PaymentExecutionMode.INQUIRE_CONFIRM);
	}

	@Test
	void cancellationUnknownOutcomeRedispatchesAsCancellationInquiry() {
		PaymentOperation operation = PaymentOperation.createCancellation(
			reservation, 7L, "pk-one", 100_000L, "게스트 요청", NOW);
		assertThat(operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30)))
			.contains(PaymentExecutionMode.CANCEL);

		operation.markOutcomeUnknown(
			"worker-one", 1, NOW.plusSeconds(10), "TIMEOUT", "response unknown");
		assertThat(operation.getNextAction()).isEqualTo(PaymentOperationNextAction.INQUIRE_CANCEL);

		assertThat(operation.prepareRecoveryDispatch(NOW.plusSeconds(10))).isTrue();
		assertThat(operation.acquireLease("worker-two", 2, NOW.plusSeconds(10), Duration.ofSeconds(30)))
			.contains(PaymentExecutionMode.INQUIRE_CANCEL);
	}

	@Test
	void cancellationInquiryCanRetryTheCancelCallWithoutReusingTheOldGeneration() {
		PaymentOperation operation = PaymentOperation.createCancellation(
			reservation, 7L, "pk-one", 100_000L, "게스트 요청", NOW);
		operation.acquireLease("worker-one", 1, NOW.minusSeconds(30), Duration.ofSeconds(30));
		operation.prepareRecoveryDispatch(NOW);
		operation.acquireLease("worker-two", 2, NOW, Duration.ofSeconds(30));

		operation.scheduleRetry(
			"worker-two", 2, NOW.plusSeconds(10), "PAYMENT_ACTIVE", "retry cancellation");

		assertThat(operation.getNextAction()).isEqualTo(PaymentOperationNextAction.CANCEL);
		assertThat(operation.prepareRecoveryDispatch(NOW.plusSeconds(10))).isTrue();
		assertThat(operation.getDispatchGeneration()).isEqualTo(3);
		assertThat(operation.acquireLease("worker-three", 3, NOW.plusSeconds(10), Duration.ofSeconds(30)))
			.contains(PaymentExecutionMode.CANCEL);
	}

	@Test
	void staleLeaseOwnerCannotChangeCurrentExecution() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("old-worker", 1, NOW.minusSeconds(31), Duration.ofSeconds(30));
		operation.prepareRecoveryDispatch(NOW);
		operation.acquireLease("new-worker", 2, NOW, Duration.ofSeconds(30));

		assertThat(operation.scheduleRetry(
			"old-worker", 1, NOW.plusSeconds(10), "TEMPORARY", "stale")).isFalse();
		assertThat(operation.markOutcomeUnknown(
			"old-worker", 1, NOW.plusSeconds(10), "TIMEOUT", "stale")).isFalse();
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.EXECUTING);
		assertThat(operation.getLeaseOwner()).isEqualTo("new-worker");
	}

	@Test
	void retryTransitionClearsLeaseAndBoundsDurableFailureDetails() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30));

		assertThat(operation.scheduleRetry(
			"worker-one", 1, NOW.plusSeconds(10), "C".repeat(101), "M".repeat(513))).isTrue();
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.WAITING_RETRY);
		assertThat(operation.getNextAction()).isEqualTo(PaymentOperationNextAction.CONFIRM);
		assertThat(operation.getLeaseOwner()).isNull();
		assertThat(operation.getLeaseExpiresAt()).isNull();
		assertThat(operation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
		assertThat(operation.getFailureCode()).hasSize(100);
		assertThat(operation.getFailureMessage()).hasSize(512);
	}

	@Test
	void terminalOperationNeverAcquiresAnotherLease() {
		PaymentOperation operation = PaymentOperation.builder()
			.operationUid(UUID.randomUUID())
			.reservation(reservation)
			.status(PaymentOperationStatus.MANUAL_REVIEW)
			.build();

		assertThat(operation.acquireLease("worker", 1, NOW, Duration.ofSeconds(30))).isEmpty();
		assertThat(operation.getAttemptCount()).isZero();
	}

	@Test
	void manualReviewPausesWithoutPretendingTheOperationCompleted() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30));

		assertThat(operation.markManualReview(
			"worker-one", 1, NOW, "UNKNOWN", "check provider")).isTrue();

		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.MANUAL_REVIEW);
		assertThat(operation.getReviewRequiredAt()).isEqualTo(NOW);
		assertThat(operation.getManualReviewCount()).isOne();
		assertThat(operation.getCompletedAt()).isNull();
	}

	@Test
	void adminReconciliationStartsANewInquiryOnlyCycle() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30));
		operation.markManualReview("worker-one", 1, NOW, "UNKNOWN", "check provider");

		operation.requestManualReconciliation(NOW.plusSeconds(1));

		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.QUEUED);
		assertThat(operation.getDispatchGeneration()).isEqualTo(2);
		assertThat(operation.getAttemptCount()).isZero();
		assertThat(operation.getNextAction()).isEqualTo(PaymentOperationNextAction.INQUIRE_CONFIRM);
		assertThat(operation.isManualReconciliationPending()).isTrue();
		assertThat(operation.isNotPaidResolutionEligible()).isFalse();
		assertThat(operation.getReviewRequiredAt()).isNull();
		assertThat(operation.acquireLease(
			"manual-worker", 2, NOW.plusSeconds(1), Duration.ofSeconds(30)))
			.contains(PaymentExecutionMode.INQUIRE_CONFIRM);
	}

	@Test
	void cancellationAdminReconciliationCanOnlyInquire() {
		PaymentOperation operation = PaymentOperation.createCancellation(
			reservation, 7L, "pk-one", 100_000L, "게스트 요청", NOW);
		operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30));
		operation.markManualReview("worker-one", 1, NOW, "UNKNOWN", "check provider");

		operation.requestManualReconciliation(NOW.plusSeconds(1));

		assertThat(operation.getNextAction()).isEqualTo(PaymentOperationNextAction.INQUIRE_CANCEL);
		assertThat(operation.acquireLease(
			"manual-worker", 2, NOW.plusSeconds(1), Duration.ofSeconds(30)))
			.contains(PaymentExecutionMode.INQUIRE_CANCEL);
	}

	@Test
	void manualReconciliationRetryPreservesInquiryInsteadOfRestoringMutation() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30));
		operation.markManualReview("worker-one", 1, NOW, "UNKNOWN", "check provider");
		operation.requestManualReconciliation(NOW.plusSeconds(1));
		operation.acquireLease("manual-worker", 2, NOW.plusSeconds(1), Duration.ofSeconds(30));

		operation.scheduleRetry(
			"manual-worker", 2, NOW.plusSeconds(10), "TEMPORARY", "try inquiry later");

		assertThat(operation.getNextAction()).isEqualTo(PaymentOperationNextAction.INQUIRE_CONFIRM);
		assertThat(operation.isManualReconciliationPending()).isTrue();
	}

	@Test
	void confirmationNotFoundReentersReviewAndEnablesExplicitNotPaidResolution() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30));
		operation.markManualReview("worker-one", 1, NOW, "UNKNOWN", "check provider");
		operation.requestManualReconciliation(NOW.plusSeconds(1));
		operation.acquireLease("manual-worker", 2, NOW.plusSeconds(1), Duration.ofSeconds(30));

		assertThat(operation.returnManualReconciliationToReview(
			"manual-worker", 2, NOW.plusSeconds(2), "NOT_FOUND", "not found", true)).isTrue();

		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.MANUAL_REVIEW);
		assertThat(operation.isManualReconciliationPending()).isFalse();
		assertThat(operation.isNotPaidResolutionEligible()).isTrue();
		assertThat(operation.getManualReviewCount()).isEqualTo(2);
	}

	@Test
	void cancellationReentryNeverEnablesMarkNotPaid() {
		PaymentOperation operation = PaymentOperation.createCancellation(
			reservation, 7L, "pk-one", 100_000L, "게스트 요청", NOW);
		operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30));
		operation.markManualReview("worker-one", 1, NOW, "UNKNOWN", "check provider");
		operation.requestManualReconciliation(NOW.plusSeconds(1));
		operation.acquireLease("manual-worker", 2, NOW.plusSeconds(1), Duration.ofSeconds(30));

		assertThat(operation.returnManualReconciliationToReview(
			"manual-worker", 2, NOW.plusSeconds(2), "ACTIVE", "still active", false)).isTrue();

		assertThat(operation.isNotPaidResolutionEligible()).isFalse();
		assertThatThrownBy(() -> operation.markNotPaid(
			NOW.plusSeconds(3), "MANUAL_NOT_PAID_RESOLUTION", "verified not paid"))
			.isInstanceOf(PaymentOperationInvariantException.class);
	}

	@Test
	void onlyEligibleConfirmationCanBeMarkedNotPaid() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30));
		operation.markManualReview("worker-one", 1, NOW, "UNKNOWN", "check provider");
		operation.requestManualReconciliation(NOW.plusSeconds(1));
		operation.acquireLease("manual-worker", 2, NOW.plusSeconds(1), Duration.ofSeconds(30));
		operation.returnManualReconciliationToReview(
			"manual-worker", 2, NOW.plusSeconds(2), "NOT_FOUND", "not found", true);

		operation.markNotPaid(
			NOW.plusSeconds(3), "MANUAL_NOT_PAID_RESOLUTION", "verified not paid");

		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.DECLINED);
		assertThat(operation.getCompletedAt()).isEqualTo(NOW.plusSeconds(3));
		assertThat(operation.isNotPaidResolutionEligible()).isFalse();
		assertThat(operation.getReviewRequiredAt()).isNull();
	}

	private PaymentOperation operation(String paymentKey, long amount) {
		return PaymentOperation.createConfirmation(reservation, 7L, paymentKey, amount, NOW);
	}
}
