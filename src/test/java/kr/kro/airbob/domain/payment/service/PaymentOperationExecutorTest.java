package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.service.gateway.CancelledPayment;
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.payment.service.gateway.PaymentGatewayResult;
import kr.kro.airbob.domain.payment.service.gateway.PaymentProviderGateway;

@SpringJUnitConfig(PaymentOperationExecutorTest.TestConfiguration.class)
class PaymentOperationExecutorTest {

	private static final UUID OPERATION_UID =
		UUID.fromString("4dc96ec8-d45f-4688-bb75-560c71b88d5d");
	private static final UUID RESERVATION_UID =
		UUID.fromString("5250ea1b-df85-46f4-a266-d1f34d4f2de9");
	private static final String PAYMENT_KEY = "pk_test";
	private static final long AMOUNT = 100_000L;
	private static final String LEASE_OWNER = "lease-owner";

	@Autowired
	private PaymentOperationExecutor executor;

	@Autowired
	private PaymentOperationLeaseService leaseService;

	@Autowired
	private PaymentProviderGateway gateway;

	@Autowired
	private PaymentOperationFinalizer finalizer;

	@BeforeEach
	void resetDoubles() {
		reset(leaseService, gateway, finalizer);
	}

	@Test
	void terminalOperationIsNoOpWhenClaimReturnsEmpty() {
		given(leaseService.claim(OPERATION_UID, 1)).willReturn(Optional.empty());

		executor.execute(OPERATION_UID, 1);

		then(gateway).shouldHaveNoInteractions();
		then(finalizer).shouldHaveNoInteractions();
		then(leaseService).should(never()).scheduleRetry(any(), any(), any());
		then(leaseService).should(never()).markOutcomeUnknown(any(), any(), any());
	}

	@Test
	void operationWithAnotherActiveLeaseIsNoOpWhenClaimReturnsEmpty() {
		given(leaseService.claim(OPERATION_UID, 1)).willReturn(Optional.empty());

		executor.execute(OPERATION_UID, 1);

		then(gateway).shouldHaveNoInteractions();
		then(finalizer).shouldHaveNoInteractions();
	}

	@Test
	void approvedConfirmationFinalizesAfterClaimReturns() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		ConfirmedPayment confirmed = confirmedPayment();
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.Approved(confirmed));

		executor.execute(OPERATION_UID, 1);

		InOrder order = inOrder(leaseService, gateway, finalizer);
		order.verify(leaseService).claim(OPERATION_UID, 1);
		order.verify(gateway).confirm(execution.gatewayCommand());
		order.verify(finalizer).applyApproved(execution, confirmed);
		then(leaseService).should(never()).markOutcomeUnknown(any(), any(), any());
	}

	@Test
	void approvedInquiryUsesInquiryAndFinalizes() {
		PaymentExecution execution = execution(PaymentExecutionMode.INQUIRE_CONFIRM);
		ConfirmedPayment confirmed = confirmedPayment();
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.inquireConfirmation(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.Approved(confirmed));

		executor.execute(OPERATION_UID, 1);

		then(gateway).should(never()).confirm(any());
		then(finalizer).should().applyApproved(execution, confirmed);
	}

	@Test
	void cancellationCallsProviderOnceAndFinalizesNormalizedEvidence() {
		PaymentExecution execution = cancellationExecution(PaymentExecutionMode.CANCEL);
		CancelledPayment cancelled = cancelledPayment();
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.cancel(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.Cancelled(cancelled));

		executor.execute(OPERATION_UID, 1);

		then(gateway).should().cancel(execution.gatewayCommand());
		then(gateway).should(never()).inquireCancellation(any());
		then(finalizer).should().applyCancelled(execution, cancelled);
	}

	@Test
	void cancellationTimeoutMovesDurablyToCancellationInquiry() {
		PaymentExecution execution = cancellationExecution(PaymentExecutionMode.CANCEL);
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.cancel(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.OutcomeUnknown("READ_TIMEOUT", "unknown"));

		executor.execute(OPERATION_UID, 1);

		then(leaseService).should().markOutcomeUnknown(execution, "READ_TIMEOUT", "unknown");
		then(finalizer).shouldHaveNoInteractions();
	}

	@Test
	void cancellationInquirySeeingAnActivePaymentSchedulesAnotherCancelGeneration() {
		PaymentExecution execution = cancellationExecution(PaymentExecutionMode.INQUIRE_CANCEL);
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.inquireCancellation(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.PaymentActive("PAYMENT_ACTIVE", "active"));

		executor.execute(OPERATION_UID, 1);

		then(leaseService).should().scheduleRetry(execution, "PAYMENT_ACTIVE", "active");
		then(gateway).should(never()).cancel(any());
		then(finalizer).shouldHaveNoInteractions();
	}

	@Test
	void inconsistentCancellationEvidenceStopsInManualReviewImmediately() {
		PaymentExecution execution = cancellationExecution(PaymentExecutionMode.INQUIRE_CANCEL);
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.inquireCancellation(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.ManualReviewRequired(
				"PARTIAL_CANCELLATION_RESPONSE", "review"));
		executor.execute(OPERATION_UID, 1);

		then(leaseService).should().markManualReview(
			execution, "PARTIAL_CANCELLATION_RESPONSE", "review");
		then(finalizer).shouldHaveNoInteractions();
	}

	@Test
	void finalDeclineExpiresReservationThroughFinalizer() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.Declined("REJECT_CARD_PAYMENT", "card rejected"));

		executor.execute(OPERATION_UID, 1);

		then(finalizer).should().applyDeclined(execution, "REJECT_CARD_PAYMENT", "card rejected");
		then(leaseService).should(never()).scheduleRetry(any(), any(), any());
		then(leaseService).should(never()).markOutcomeUnknown(any(), any(), any());
	}

	@Test
	void retryableConfirmationSchedulesSafeRetry() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.RetryableFailure("CONNECTION_FAILED", "try later"));

		executor.execute(OPERATION_UID, 1);

		then(leaseService).should().scheduleRetry(execution, "CONNECTION_FAILED", "try later");
		then(finalizer).shouldHaveNoInteractions();
	}

	@Test
	void retryableInquiryRemainsUnknownSoItCannotReconfirm() {
		PaymentExecution execution = execution(PaymentExecutionMode.INQUIRE_CONFIRM);
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.inquireConfirmation(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.RetryableFailure("CONNECTION_FAILED", "try later"));

		executor.execute(OPERATION_UID, 1);

		then(leaseService).should().markOutcomeUnknown(execution, "CONNECTION_FAILED", "try later");
		then(leaseService).should(never()).scheduleRetry(any(), any(), any());
		then(finalizer).shouldHaveNoInteractions();
	}

	@Test
	void readTimeoutBecomesDurableUnknownAndDoesNotExpireReservation() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.OutcomeUnknown("READ_TIMEOUT", "response lost"));

		executor.execute(OPERATION_UID, 1);

		then(leaseService).should().markOutcomeUnknown(execution, "READ_TIMEOUT", "response lost");
		then(finalizer).shouldHaveNoInteractions();
	}

	@Test
	void unknownInquiryNotFoundReturnsToSafeConfirmRetry() {
		PaymentExecution execution = execution(PaymentExecutionMode.INQUIRE_CONFIRM);
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.inquireConfirmation(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.NotFound("NOT_FOUND_PAYMENT", "not found"));

		executor.execute(OPERATION_UID, 1);

		then(leaseService).should().scheduleRetry(execution, "NOT_FOUND_PAYMENT", "not found");
		then(leaseService).should(never()).markOutcomeUnknown(any(), any(), any());
	}

	@Test
	void unexpectedNotFoundDuringConfirmationBecomesDurableUnknown() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.NotFound("NOT_FOUND_PAYMENT", "not found"));

		executor.execute(OPERATION_UID, 1);

		then(leaseService).should().markOutcomeUnknown(execution, "NOT_FOUND_PAYMENT", "not found");
		then(leaseService).should(never()).scheduleRetry(any(), any(), any());
		then(finalizer).shouldHaveNoInteractions();
	}

	@ParameterizedTest(name = "approved response mismatch: {0}")
	@MethodSource("mismatchedApprovals")
	void approvedResponseMismatchBecomesDurableUnknown(
		String ignoredDescription,
		ConfirmedPayment mismatched
	) {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.Approved(mismatched));

		executor.execute(OPERATION_UID, 1);

		then(leaseService).should().markOutcomeUnknown(
			execution,
			"PROVIDER_RESPONSE_MISMATCH",
			"Provider approval did not match the payment operation."
		);
		then(finalizer).shouldHaveNoInteractions();
	}

	@Test
	void unexpectedGatewayFailureBecomesUnknownWithoutPersistingSensitiveMessage() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willThrow(new IllegalStateException("Authorization: secret-token paymentKey=" + PAYMENT_KEY));

		executor.execute(OPERATION_UID, 1);

		then(leaseService).should().markOutcomeUnknown(
			execution,
			"UNCLASSIFIED_GATEWAY_FAILURE",
			"Unexpected payment gateway failure."
		);
		then(finalizer).shouldHaveNoInteractions();
	}

	@Test
	void boundedGatewayReasonDoesNotLeakATruncatedPaymentKey() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		String message = "x".repeat(510) + PAYMENT_KEY;
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.OutcomeUnknown("READ_TIMEOUT", message));
		ArgumentCaptor<String> persistedMessage = ArgumentCaptor.forClass(String.class);

		executor.execute(OPERATION_UID, 1);

		then(leaseService).should().markOutcomeUnknown(
			any(), any(), persistedMessage.capture());
		assertThat(persistedMessage.getValue())
			.hasSizeLessThanOrEqualTo(512)
			.doesNotContain("pk");
	}

	@Test
	void gatewayReasonCodeDoesNotPersistSensitiveCorrelationData() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.OutcomeUnknown(PAYMENT_KEY, "response lost"));
		ArgumentCaptor<String> persistedCode = ArgumentCaptor.forClass(String.class);

		executor.execute(OPERATION_UID, 1);

		then(leaseService).should().markOutcomeUnknown(
			any(), persistedCode.capture(), any());
		assertThat(persistedCode.getValue())
			.hasSizeLessThanOrEqualTo(100)
			.doesNotContain(PAYMENT_KEY);
	}

	@Test
	void staleWorkerRetryTransitionIsAQuietNoOp() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.RetryableFailure("CONNECTION_FAILED", "try later"));
		assertThatCode(() -> executor.execute(OPERATION_UID, 1)).doesNotThrowAnyException();

		then(finalizer).shouldHaveNoInteractions();
	}

	@Test
	void finalizerDatabaseFailurePropagatesForCallerRetry() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		ConfirmedPayment confirmed = confirmedPayment();
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.Approved(confirmed));
		willThrow(new DataAccessResourceFailureException("db unavailable"))
			.given(finalizer).applyApproved(execution, confirmed);

		assertThatThrownBy(() -> executor.execute(OPERATION_UID, 1))
			.isInstanceOf(DataAccessException.class);

		then(leaseService).should(never()).markOutcomeUnknown(any(), any(), any());
	}

	@Test
	void gatewayInvocationHasNoActiveSpringTransaction() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		AtomicBoolean gatewayWasInvoked = new AtomicBoolean();
		given(leaseService.claim(OPERATION_UID, 1))
			.willReturn(Optional.of(execution));
		willAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			gatewayWasInvoked.set(true);
			return new PaymentGatewayResult.Approved(confirmedPayment());
		}).given(gateway).confirm(execution.gatewayCommand());

		executor.execute(OPERATION_UID, 1);

		assertThat(gatewayWasInvoked).isTrue();
	}

	private static Stream<Arguments> mismatchedApprovals() {
		ConfirmedPayment valid = confirmedPayment();
		return Stream.of(
			Arguments.of("payment key", new ConfirmedPayment(
				"pk_other", valid.orderId(), valid.totalAmount(), valid.balanceAmount(),
				valid.method(), valid.status(), valid.approvedAt(), valid.virtualAccount())),
			Arguments.of("order ID", new ConfirmedPayment(
				valid.paymentKey(), UUID.randomUUID().toString(), valid.totalAmount(), valid.balanceAmount(),
				valid.method(), valid.status(), valid.approvedAt(), valid.virtualAccount())),
			Arguments.of("amount", new ConfirmedPayment(
				valid.paymentKey(), valid.orderId(), valid.totalAmount() + 1, valid.balanceAmount(),
				valid.method(), valid.status(), valid.approvedAt(), valid.virtualAccount())),
			Arguments.of("balance amount", new ConfirmedPayment(
				valid.paymentKey(), valid.orderId(), valid.totalAmount(), valid.balanceAmount() - 1,
				valid.method(), valid.status(), valid.approvedAt(), valid.virtualAccount())),
			Arguments.of("method", new ConfirmedPayment(
				valid.paymentKey(), valid.orderId(), valid.totalAmount(), valid.balanceAmount(),
				null, valid.status(), valid.approvedAt(), valid.virtualAccount())),
			Arguments.of("status", new ConfirmedPayment(
				valid.paymentKey(), valid.orderId(), valid.totalAmount(), valid.balanceAmount(),
				valid.method(), PaymentStatus.ABORTED, valid.approvedAt(), valid.virtualAccount())),
			Arguments.of("approved timestamp", new ConfirmedPayment(
				valid.paymentKey(), valid.orderId(), valid.totalAmount(), valid.balanceAmount(),
				valid.method(), valid.status(), null, valid.virtualAccount()))
		);
	}

	private static PaymentExecution execution(PaymentExecutionMode mode) {
		return new PaymentExecution(
			OPERATION_UID,
			RESERVATION_UID,
			PAYMENT_KEY,
			RESERVATION_UID.toString(),
			AMOUNT,
			"airbob-confirm-" + OPERATION_UID,
			null,
			LEASE_OWNER,
			1,
			mode
		);
	}

	private static PaymentExecution cancellationExecution(PaymentExecutionMode mode) {
		return new PaymentExecution(
			OPERATION_UID,
			RESERVATION_UID,
			PAYMENT_KEY,
			RESERVATION_UID.toString(),
			AMOUNT,
			"airbob-cancel-" + OPERATION_UID,
			"사용자 요청",
			LEASE_OWNER,
			1,
			mode
		);
	}

	private static CancelledPayment cancelledPayment() {
		return new CancelledPayment(
			PAYMENT_KEY,
			RESERVATION_UID.toString(),
			AMOUNT,
			0L,
			PaymentStatus.CANCELED,
			AMOUNT,
			"사용자 요청",
			"cancel-transaction-key",
			Instant.parse("2026-08-17T01:02:03Z")
		);
	}

	private static ConfirmedPayment confirmedPayment() {
		return new ConfirmedPayment(
			PAYMENT_KEY,
			RESERVATION_UID.toString(),
			AMOUNT,
			AMOUNT,
			PaymentMethod.CARD,
			PaymentStatus.DONE,
			Instant.parse("2026-08-14T03:34:56Z"),
			null
		);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	static class TestConfiguration {

		@Bean
		PaymentOperationLeaseService leaseService() {
			return mock(PaymentOperationLeaseService.class);
		}

		@Bean
		PaymentProviderGateway gateway() {
			return mock(PaymentProviderGateway.class);
		}

		@Bean
		PaymentOperationFinalizer finalizer() {
			return mock(PaymentOperationFinalizer.class);
		}

		@Bean
		PaymentOperationExecutor executor(
			PaymentOperationLeaseService leaseService,
			PaymentProviderGateway gateway,
			PaymentOperationFinalizer finalizer
		) {
			return new PaymentOperationExecutor(leaseService, gateway, finalizer);
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return new RecordingTransactionManager();
		}
	}

	private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
		}
	}
}
