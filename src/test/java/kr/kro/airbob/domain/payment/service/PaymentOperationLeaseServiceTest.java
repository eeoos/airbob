package kr.kro.airbob.domain.payment.service;

import static kr.kro.airbob.domain.payment.service.PaymentExecutionMode.CONFIRM;
import static kr.kro.airbob.domain.payment.service.PaymentExecutionMode.INQUIRE_CONFIRM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

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
import kr.kro.airbob.domain.payment.entity.PaymentOperationNextAction;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionAction;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.exception.PaymentOperationNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.service.gateway.PaymentProviderCommand;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.messaging.alert.application.OperatorAlertOutboxPublisher;
import kr.kro.airbob.messaging.alert.application.OperatorAlertRequest;

@ExtendWith(MockitoExtension.class)
class PaymentOperationLeaseServiceTest {

	private static final UUID OPERATION_UID = UUID.fromString("45d633d0-20ac-4f46-b757-d851fba3f7df");
	private static final UUID RESERVATION_UID = UUID.fromString("018290b8-d807-7a2e-8f41-114c7d0a4a21");
	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

	@Mock private PaymentOperationRepository repository;
	@Mock private OperatorAlertOutboxPublisher alertPublisher;
	@Mock private PaymentOperationManualResolutionRecorder resolutionRecorder;

	private PaymentOperation operation;
	private PaymentOperationLeaseService service;

	@BeforeEach
	void setUp() {
		operation = operation(PaymentOperationStatus.QUEUED);
		PaymentOperationProperties properties = properties();
		service = new PaymentOperationLeaseService(
			repository,
			properties,
			new PaymentRetryBackoff(properties.retryInitialDelay(), properties.retryMaxDelay()),
			Clock.fixed(NOW, ZoneOffset.UTC),
			alertPublisher,
			resolutionRecorder
		);
	}

	@Test
	void readyClaimGetsConfirmModeAndFencesConcurrentWorker() {
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));

		Optional<PaymentExecution> first = service.claim(OPERATION_UID, 1);
		Optional<PaymentExecution> second = service.claim(OPERATION_UID, 1);

		assertThat(first).get().extracting(PaymentExecution::mode).isEqualTo(CONFIRM);
		assertThat(second).isEmpty();
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.EXECUTING);
		assertThat(operation.getAttemptCount()).isEqualTo(1);
		assertThat(operation.getLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
		then(alertPublisher).shouldHaveNoInteractions();
	}

	@Test
	void expiredExecutingClaimUsesInquiryAndReplacesLeaseOwner() {
		operation.acquireLease("old-worker", 1, NOW.minusSeconds(31), Duration.ofSeconds(30));
		operation.prepareRecoveryDispatch(NOW);
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));

		Optional<PaymentExecution> recovered = service.claim(OPERATION_UID, 2);

		assertThat(recovered).get().extracting(PaymentExecution::mode).isEqualTo(INQUIRE_CONFIRM);
		assertThat(recovered.orElseThrow().leaseOwner()).isNotEqualTo("old-worker");
		assertThat(operation.getAttemptCount()).isEqualTo(2);
	}

	@Test
	void manualReconciliationClaimCarriesImmutableInquiryFence() {
		operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30));
		operation.markManualReview("worker-one", 1, NOW, "UNKNOWN", "review");
		operation.requestManualReconciliation(NOW);
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));

		PaymentExecution execution = service.claim(OPERATION_UID, 2).orElseThrow();

		assertThat(execution.mode()).isEqualTo(INQUIRE_CONFIRM);
		assertThat(execution.manualReconciliation()).isTrue();
	}

	@Test
	void staleWorkerCannotOverwriteCurrentRecoveryState() {
		operation.acquireLease("old-worker", 1, NOW.minusSeconds(31), Duration.ofSeconds(30));
		PaymentExecution stale = PaymentExecution.from(operation, "old-worker", CONFIRM);
		operation.prepareRecoveryDispatch(NOW);
		operation.acquireLease("new-worker", 2, NOW, Duration.ofSeconds(30));
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));

		service.markOutcomeUnknown(stale, "TIMEOUT", "lost response");
		service.scheduleRetry(stale, "TEMPORARY", "try later");
		assertThat(operation.getLeaseOwner()).isEqualTo("new-worker");
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.EXECUTING);
		then(alertPublisher).shouldHaveNoInteractions();
	}

	@Test
	void retryUsesAttemptNumberForCappedDeterministicBackoff() {
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));
		PaymentExecution execution = service.claim(OPERATION_UID, 1).orElseThrow();

		service.scheduleRetry(execution, "TEMPORARY", "try later");

		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.WAITING_RETRY);
		assertThat(operation.getNextAction()).isEqualTo(PaymentOperationNextAction.CONFIRM);
		assertThat(operation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
	}

	@Test
	void unknownOutcomeSchedulesInquiryUsingTheSameBackoffPolicy() {
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));
		PaymentExecution execution = service.claim(OPERATION_UID, 1).orElseThrow();

		service.markOutcomeUnknown(execution, "TIMEOUT", "lost response");

		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.WAITING_RETRY);
		assertThat(operation.getNextAction()).isEqualTo(PaymentOperationNextAction.INQUIRE_CONFIRM);
		assertThat(operation.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
		assertThat(operation.getLeaseOwner()).isNull();
		assertThat(operation.getLeaseExpiresAt()).isNull();
	}

	@Test
	void fifthFailedExecutionStopsAutomaticAttemptsInManualReview() {
		operation = operation(PaymentOperationStatus.QUEUED, 4);
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));
		PaymentExecution fifth = service.claim(OPERATION_UID, 1).orElseThrow();

		service.scheduleRetry(fifth, "TEMPORARY", "still unavailable");

		assertThat(operation.getAttemptCount()).isEqualTo(5);
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.MANUAL_REVIEW);
		assertThat(operation.getNextAttemptAt()).isNull();
		then(alertPublisher).should().append(
			OperatorAlertRequest.paymentManualReview(OPERATION_UID, 1));
		assertThat(service.claim(OPERATION_UID, 1)).isEmpty();
		then(alertPublisher).shouldHaveNoMoreInteractions();
	}

	@Test
	void expiredFifthExecutionMovesToManualReviewWithoutIssuingSixthLease() {
		operation = operation(PaymentOperationStatus.QUEUED, 4);
		operation.acquireLease("crashed-fifth-worker", 1, NOW.minusSeconds(31), Duration.ofSeconds(30));
		operation.prepareRecoveryDispatch(NOW);
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));

		Optional<PaymentExecution> recovered = service.claim(OPERATION_UID, 2);

		assertThat(recovered).isEmpty();
		assertThat(operation.getAttemptCount()).isEqualTo(5);
		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.MANUAL_REVIEW);
		assertThat(operation.getLeaseOwner()).isNull();
		assertThat(operation.getLeaseExpiresAt()).isNull();
		assertThat(operation.getCompletedAt()).isNull();
		assertThat(operation.getReviewRequiredAt()).isEqualTo(NOW);
		then(alertPublisher).should().append(
			OperatorAlertRequest.paymentManualReview(OPERATION_UID, 1));
	}

	@Test
	void exhaustedManualReconciliationRecordsSystemAuditInsteadOfGenericReviewAlert() {
		operation = manualReconciliationOperation(5);
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));

		assertThat(service.claim(OPERATION_UID, 2)).isEmpty();

		then(resolutionRecorder).should().recordSystem(
			eq(operation),
			eq(PaymentOperationResolutionAction.RECONCILIATION_RETURNED_TO_REVIEW),
			eq("RECONCILIATION_ATTEMPTS_EXHAUSTED"),
			eq(PaymentOperationStatus.QUEUED),
			eq(PaymentOperationStatus.MANUAL_REVIEW),
			eq(NOW));
		then(alertPublisher).shouldHaveNoInteractions();
	}

	@Test
	void confirmationNotFoundReturnEnablesMarkNotPaidAndRecordsTheCycle() {
		operation.acquireLease("worker-one", 1, NOW, Duration.ofSeconds(30));
		operation.markManualReview("worker-one", 1, NOW, "UNKNOWN", "review");
		operation.requestManualReconciliation(NOW);
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));
		PaymentExecution execution = service.claim(OPERATION_UID, 2).orElseThrow();

		service.returnManualReconciliationToReview(
			execution, "NOT_FOUND_PAYMENT", "not found", true);

		assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.MANUAL_REVIEW);
		assertThat(operation.isNotPaidResolutionEligible()).isTrue();
		then(resolutionRecorder).should().recordSystem(
			eq(operation),
			eq(PaymentOperationResolutionAction.RECONCILIATION_RETURNED_TO_REVIEW),
			eq("PROVIDER_PAYMENT_NOT_FOUND"),
			eq(PaymentOperationStatus.EXECUTING),
			eq(PaymentOperationStatus.MANUAL_REVIEW),
			eq(NOW));
	}

	@Test
	void executionCreatesTypedGatewayCommandWithoutCallingTheGateway() {
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.of(operation));

		PaymentExecution execution = service.claim(OPERATION_UID, 1).orElseThrow();

		assertThat(execution.reservationUid()).isEqualTo(RESERVATION_UID);
		assertThat(execution.gatewayCommand()).isEqualTo(new PaymentProviderCommand(
			OPERATION_UID,
			"payment-key",
			RESERVATION_UID.toString(),
			100_000L,
			"provider-key",
			null
		));
	}

	@Test
	void missingOperationFailsInsteadOfClaimingAnImaginaryExecution() {
		given(repository.findByOperationUidWithLock(OPERATION_UID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.claim(OPERATION_UID, 1))
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
			Duration.ofSeconds(10), Duration.ofMinutes(5)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PaymentOperationProperties(
			Duration.ofSeconds(30), Duration.ofSeconds(10), 0, 5,
			Duration.ofSeconds(10), Duration.ofMinutes(5)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PaymentOperationProperties(
			Duration.ofSeconds(30), Duration.ofSeconds(10), 100, 5,
			Duration.ofMinutes(6), Duration.ofMinutes(5)))
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
			Duration.ofSeconds(10), Duration.ofMinutes(5));
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
			.operationType(PaymentOperationType.CONFIRM)
			.status(status)
			.nextAction(PaymentOperationNextAction.CONFIRM)
			.paymentKey("payment-key")
			.expectedAmount(100_000L)
			.providerIdempotencyKey("provider-key")
			.deduplicationKey("CONFIRM:" + RESERVATION_UID)
			.dispatchGeneration(1)
			.attemptCount(attemptCount)
			.nextAttemptAt(NOW)
			.queuedAt(NOW)
			.build();
	}

	private PaymentOperation manualReconciliationOperation(int attemptCount) {
		Reservation reservation = Reservation.builder().id(1L).reservationUid(RESERVATION_UID).build();
		return PaymentOperation.builder()
			.id(2L)
			.operationUid(OPERATION_UID)
			.reservation(reservation)
			.requesterMemberId(7L)
			.operationType(PaymentOperationType.CONFIRM)
			.status(PaymentOperationStatus.QUEUED)
			.nextAction(PaymentOperationNextAction.INQUIRE_CONFIRM)
			.paymentKey("payment-key")
			.expectedAmount(100_000L)
			.providerIdempotencyKey("provider-key")
			.deduplicationKey("CONFIRM:" + RESERVATION_UID)
			.dispatchGeneration(2)
			.attemptCount(attemptCount)
			.queuedAt(NOW)
			.manualReconciliationPending(true)
			.build();
	}
}
