package kr.kro.airbob.domain.payment.service;

import static kr.kro.airbob.domain.payment.service.PaymentExecutionMode.CONFIRM;
import static kr.kro.airbob.domain.payment.service.PaymentExecutionMode.INQUIRE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.payment.config.PaymentOperationConfiguration;
import kr.kro.airbob.domain.payment.config.PaymentOperationProperties;
import kr.kro.airbob.domain.payment.config.TossPaymentClientProperties;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.exception.PaymentOperationNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationCommand;
import kr.kro.airbob.domain.reservation.entity.Reservation;

@ExtendWith(MockitoExtension.class)
class PaymentOperationLeaseServiceTest {

	private static final UUID OPERATION_UID = UUID.fromString("45d633d0-20ac-4f46-b757-d851fba3f7df");
	private static final UUID RESERVATION_UID = UUID.fromString("018290b8-d807-7a2e-8f41-114c7d0a4a21");
	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

	@Mock private PaymentOperationRepository repository;

	private PaymentOperation operation;
	private PaymentOperationLeaseService service;

	@BeforeEach
	void setUp() {
		operation = operation(PaymentOperationStatus.READY);
		PaymentOperationProperties properties = properties();
		service = new PaymentOperationLeaseService(
			repository,
			properties,
			new PaymentRetryBackoff(properties.retryInitialDelay(), properties.retryMaxDelay()),
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	void readyClaimGetsConfirmModeAndFencesConcurrentWorker() {
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));

		Optional<PaymentExecution> first = service.claim(OPERATION_UID);
		Optional<PaymentExecution> second = service.claim(OPERATION_UID);

		assertThat(first).get().extracting(PaymentExecution::mode).isEqualTo(CONFIRM);
		assertThat(second).isEmpty();
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.EXECUTING);
		assertThat(operation.getAttemptCount()).isEqualTo(1);
		assertThat(operation.getLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
	}

	@Test
	void expiredExecutingClaimUsesInquiryAndReplacesLeaseOwner() {
		operation.acquireLease("old-worker", NOW.minusSeconds(31), Duration.ofSeconds(30));
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));

		Optional<PaymentExecution> recovered = service.claim(OPERATION_UID);

		assertThat(recovered).get().extracting(PaymentExecution::mode).isEqualTo(INQUIRE);
		assertThat(recovered.orElseThrow().leaseOwner()).isNotEqualTo("old-worker");
		assertThat(operation.getAttemptCount()).isEqualTo(2);
	}

	@Test
	void staleWorkerCannotOverwriteCurrentRecoveryState() {
		operation.acquireLease("old-worker", NOW.minusSeconds(31), Duration.ofSeconds(30));
		PaymentExecution stale = PaymentExecution.from(operation, "old-worker", CONFIRM);
		operation.acquireLease("new-worker", NOW, Duration.ofSeconds(30));
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));

		assertThat(service.markOutcomeUnknown(stale, "TIMEOUT", "lost response")).isFalse();
		assertThat(service.scheduleRetry(stale, "TEMPORARY", "try later")).isFalse();
		assertThat(operation.getLeaseOwner()).isEqualTo("new-worker");
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.EXECUTING);
	}

	@Test
	void retryUsesAttemptNumberForCappedDeterministicBackoff() {
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));
		PaymentExecution execution = service.claim(OPERATION_UID).orElseThrow();

		assertThat(service.scheduleRetry(execution, "TEMPORARY", "try later")).isTrue();

		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.RETRY_WAIT);
		assertThat(operation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
	}

	@Test
	void unknownOutcomeSchedulesInquiryUsingTheSameBackoffPolicy() {
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));
		PaymentExecution execution = service.claim(OPERATION_UID).orElseThrow();

		assertThat(service.markOutcomeUnknown(execution, "TIMEOUT", "lost response")).isTrue();

		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.OUTCOME_UNKNOWN);
		assertThat(operation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
		assertThat(operation.getLeaseOwner()).isNull();
		assertThat(operation.getLeaseExpiresAt()).isNull();
	}

	@Test
	void fifthFailedExecutionStopsAutomaticAttemptsInManualReview() {
		operation = operation(PaymentOperationStatus.READY, 4);
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));
		PaymentExecution fifth = service.claim(OPERATION_UID).orElseThrow();

		assertThat(service.scheduleRetry(fifth, "TEMPORARY", "still unavailable")).isTrue();

		assertThat(operation.getAttemptCount()).isEqualTo(5);
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.MANUAL_REVIEW);
		assertThat(operation.getNextAttemptAt()).isNull();
		assertThat(service.claim(OPERATION_UID)).isEmpty();
	}

	@Test
	void executionCreatesTypedGatewayCommandWithoutCallingTheGateway() {
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));

		PaymentExecution execution = service.claim(OPERATION_UID).orElseThrow();

		assertThat(execution.reservationUid()).isEqualTo(RESERVATION_UID);
		assertThat(execution.gatewayCommand()).isEqualTo(new PaymentConfirmationCommand(
			OPERATION_UID, "payment-key", RESERVATION_UID.toString(), 100_000L, "provider-key"));
	}

	@Test
	void missingOperationFailsInsteadOfClaimingAnImaginaryExecution() {
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.claim(OPERATION_UID))
			.isInstanceOf(PaymentOperationNotFoundException.class);
	}

	@Test
	void exponentialBackoffCapsAtFiveMinutesAndCannotOverflow() {
		PaymentRetryBackoff backoff = new PaymentRetryBackoff(Duration.ofSeconds(10), Duration.ofMinutes(5));

		assertThat(backoff.forAttempt(1)).isEqualTo(Duration.ofSeconds(10));
		assertThat(backoff.forAttempt(2)).isEqualTo(Duration.ofSeconds(20));
		assertThat(backoff.forAttempt(5)).isEqualTo(Duration.ofSeconds(160));
		assertThat(backoff.forAttempt(6)).isEqualTo(Duration.ofMinutes(5));
		assertThat(backoff.forAttempt(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
		assertThat(new PaymentRetryBackoff(Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE))
			.forAttempt(21)).isEqualTo(Duration.ofSeconds(Long.MAX_VALUE));
	}

	@Test
	void operationPolicyRejectsNonpositiveValuesAndInvertedRetryRange() {
		assertThatThrownBy(() -> new PaymentOperationProperties(
			Duration.ZERO, Duration.ofSeconds(10), 100, 5,
			Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofSeconds(10)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PaymentOperationProperties(
			Duration.ofSeconds(30), Duration.ofSeconds(10), 0, 5,
			Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofSeconds(10)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PaymentOperationProperties(
			Duration.ofSeconds(30), Duration.ofSeconds(10), 100, 5,
			Duration.ofMinutes(6), Duration.ofMinutes(5), Duration.ofSeconds(10)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void tossTimeoutMustBeStrictlyShorterThanLease() {
		PaymentOperationConfiguration configuration = new PaymentOperationConfiguration();
		TossPaymentClientProperties toss = new TossPaymentClientProperties(
			"secret", "https://example.com", Duration.ofSeconds(2), Duration.ofSeconds(30));

		assertThatThrownBy(() -> configuration.paymentOperationTimeoutGuard(properties(), toss).afterPropertiesSet())
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Toss 타임아웃은 payment-operation lease보다 짧아야 합니다.");
	}

	private PaymentOperationProperties properties() {
		return new PaymentOperationProperties(
			Duration.ofSeconds(30), Duration.ofSeconds(10), 100, 5,
			Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofSeconds(10));
	}

	private PaymentOperation operation(PaymentOperationStatus status) {
		return operation(status, 0);
	}

	private PaymentOperation operation(PaymentOperationStatus status, int attemptCount) {
		Reservation reservation = Reservation.builder().id(1L).reservationUid(RESERVATION_UID).build();
		return PaymentOperation.builder()
			.id(2L)
			.operationUid(OPERATION_UID)
			.reservation(reservation)
			.requesterMemberId(7L)
			.status(status)
			.paymentKey("payment-key")
			.expectedAmount(100_000L)
			.providerIdempotencyKey("provider-key")
			.deduplicationKey("CONFIRM:" + RESERVATION_UID)
			.attemptCount(attemptCount)
			.nextAttemptAt(NOW)
			.lastEnqueuedAt(NOW)
			.build();
	}
}
