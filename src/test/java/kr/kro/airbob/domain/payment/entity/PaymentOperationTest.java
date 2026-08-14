package kr.kro.airbob.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.reservation.entity.Reservation;

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

	private PaymentOperation operation(String paymentKey, long amount) {
		return PaymentOperation.createConfirmation(reservation, 7L, paymentKey, amount, NOW);
	}
}
