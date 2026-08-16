package kr.kro.airbob.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.READY);
		assertThat(operation.getAttemptCount()).isZero();
		assertThat(operation.getLastEnqueuedAt()).isEqualTo(NOW);
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
	void recordsItsLatestEnqueueTime() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		Instant requeuedAt = Instant.parse("2026-08-14T00:01:00Z");

		operation.recordEnqueued(requeuedAt);

		assertThat(operation.getLastEnqueuedAt()).isEqualTo(requeuedAt);
	}

	@Test
	void distinguishesTerminalOperationStates() {
		assertThat(PaymentOperationStatus.APPLIED.isTerminal()).isTrue();
		assertThat(PaymentOperationStatus.DECLINED.isTerminal()).isTrue();
		assertThat(PaymentOperationStatus.MANUAL_REVIEW.isTerminal()).isTrue();
		assertThat(PaymentOperationStatus.READY.isTerminal()).isFalse();
		assertThat(PaymentOperationStatus.EXECUTING.isTerminal()).isFalse();
		assertThat(PaymentOperationStatus.RETRY_WAIT.isTerminal()).isFalse();
		assertThat(PaymentOperationStatus.OUTCOME_UNKNOWN.isTerminal()).isFalse();
	}

	@Test
	void readyOperationAcquiresOneConfirmationLease() {
		PaymentOperation operation = operation("pk-one", 100_000L);

		assertThat(operation.acquireLease("worker-one", NOW, Duration.ofSeconds(30)))
			.contains(PaymentExecutionMode.CONFIRM);
		assertThat(operation.acquireLease("worker-two", NOW, Duration.ofSeconds(30))).isEmpty();
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.EXECUTING);
		assertThat(operation.getAttemptCount()).isEqualTo(1);
		assertThat(operation.getLeaseOwner()).isEqualTo("worker-one");
		assertThat(operation.getLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
	}

	@Test
	void leaseIsRecoverableAtTheExactExpiryBoundaryInInquiryMode() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("old-worker", NOW.minusSeconds(30), Duration.ofSeconds(30));

		assertThat(operation.acquireLease("new-worker", NOW, Duration.ofSeconds(30)))
			.contains(PaymentExecutionMode.INQUIRE);
		assertThat(operation.getLeaseOwner()).isEqualTo("new-worker");
		assertThat(operation.getAttemptCount()).isEqualTo(2);
	}

	@Test
	void retryWaitCannotBeClaimedBeforeItsDueTimeButIsClaimableAtTheBoundary() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("worker-one", NOW, Duration.ofSeconds(30));
		operation.scheduleRetry("worker-one", NOW.plusSeconds(10), "TEMPORARY", "try later");

		assertThat(operation.acquireLease("worker-two", NOW.plusSeconds(9), Duration.ofSeconds(30))).isEmpty();
		assertThat(operation.acquireLease("worker-two", NOW.plusSeconds(10), Duration.ofSeconds(30)))
			.contains(PaymentExecutionMode.CONFIRM);
	}

	@Test
	void outcomeUnknownIsRecoveredInInquiryModeOnlyWhenDue() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("worker-one", NOW, Duration.ofSeconds(30));
		operation.markOutcomeUnknown("worker-one", NOW.plusSeconds(10), "TIMEOUT", "response unknown");

		assertThat(operation.acquireLease("worker-two", NOW.plusSeconds(9), Duration.ofSeconds(30))).isEmpty();
		assertThat(operation.acquireLease("worker-two", NOW.plusSeconds(10), Duration.ofSeconds(30)))
			.contains(PaymentExecutionMode.INQUIRE);
	}

	@Test
	void staleLeaseOwnerCannotChangeCurrentExecution() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("old-worker", NOW.minusSeconds(31), Duration.ofSeconds(30));
		operation.acquireLease("new-worker", NOW, Duration.ofSeconds(30));

		assertThat(operation.scheduleRetry("old-worker", NOW.plusSeconds(10), "TEMPORARY", "stale")).isFalse();
		assertThat(operation.markOutcomeUnknown("old-worker", NOW.plusSeconds(10), "TIMEOUT", "stale")).isFalse();
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.EXECUTING);
		assertThat(operation.getLeaseOwner()).isEqualTo("new-worker");
	}

	@Test
	void retryTransitionClearsLeaseAndBoundsDurableFailureDetails() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("worker-one", NOW, Duration.ofSeconds(30));

		assertThat(operation.scheduleRetry(
			"worker-one", NOW.plusSeconds(10), "C".repeat(101), "M".repeat(513))).isTrue();
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.RETRY_WAIT);
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

		assertThat(operation.acquireLease("worker", NOW, Duration.ofSeconds(30))).isEmpty();
		assertThat(operation.getAttemptCount()).isZero();
	}

	@Test
	void recordingEnqueueTimeDoesNotChangeRetryOrLeaseState() {
		PaymentOperation operation = operation("pk-one", 100_000L);
		operation.acquireLease("worker-one", NOW, Duration.ofSeconds(30));
		Instant requeuedAt = NOW.plusSeconds(5);

		operation.recordEnqueued(requeuedAt);

		assertThat(operation.getLastEnqueuedAt()).isEqualTo(requeuedAt);
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.EXECUTING);
		assertThat(operation.getLeaseOwner()).isEqualTo("worker-one");
		assertThat(operation.getLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
	}

	private PaymentOperation operation(String paymentKey, long amount) {
		return PaymentOperation.createConfirmation(reservation, 7L, paymentKey, amount, NOW);
	}
}
