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
import org.slf4j.LoggerFactory;
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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationGateway;
import kr.kro.airbob.domain.payment.service.gateway.PaymentGatewayResult;

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
	private PaymentConfirmationGateway gateway;

	@Autowired
	private PaymentOperationFinalizer finalizer;

	@Autowired
	private PaymentOperationAlertService alertService;

	@BeforeEach
	void resetDoubles() {
		reset(leaseService, gateway, finalizer, alertService);
	}

	@Test
	void terminalOperationIsNoOpWhenClaimReturnsEmpty() {
		given(leaseService.claim(OPERATION_UID)).willReturn(PaymentOperationClaimResult.noAction());

		executor.execute(OPERATION_UID);

		then(gateway).shouldHaveNoInteractions();
		then(finalizer).shouldHaveNoInteractions();
		then(leaseService).should(never()).scheduleRetry(any(), any(), any());
		then(leaseService).should(never()).markOutcomeUnknown(any(), any(), any());
		then(alertService).shouldHaveNoInteractions();
	}

	@Test
	void operationWithAnotherActiveLeaseIsNoOpWhenClaimReturnsEmpty() {
		given(leaseService.claim(OPERATION_UID)).willReturn(PaymentOperationClaimResult.noAction());

		executor.execute(OPERATION_UID);

		then(gateway).shouldHaveNoInteractions();
		then(finalizer).shouldHaveNoInteractions();
		then(alertService).shouldHaveNoInteractions();
	}

	@Test
	void exhaustedClaimAlertsExactlyOnceWithoutInvokingTheGateway() {
		PaymentOperationManualReviewNotice notice =
			new PaymentOperationManualReviewNotice(OPERATION_UID);
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.manualReview(notice));

		executor.execute(OPERATION_UID);

		InOrder order = inOrder(leaseService, alertService);
		order.verify(leaseService).claim(OPERATION_UID);
		order.verify(alertService).alertManualReview(notice);
		then(alertService).shouldHaveNoMoreInteractions();
		then(gateway).shouldHaveNoInteractions();
		then(finalizer).shouldHaveNoInteractions();
	}

	@Test
	void approvedConfirmationFinalizesAfterClaimReturns() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		ConfirmedPayment confirmed = confirmedPayment();
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.Approved(confirmed));

		executor.execute(OPERATION_UID);

		InOrder order = inOrder(leaseService, gateway, finalizer);
		order.verify(leaseService).claim(OPERATION_UID);
		order.verify(gateway).confirm(execution.gatewayCommand());
		order.verify(finalizer).applyApproved(execution, confirmed);
		then(leaseService).should(never()).markOutcomeUnknown(any(), any(), any());
	}

	@Test
	void approvedInquiryUsesInquiryAndFinalizes() {
		PaymentExecution execution = execution(PaymentExecutionMode.INQUIRE);
		ConfirmedPayment confirmed = confirmedPayment();
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.inquire(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.Approved(confirmed));

		executor.execute(OPERATION_UID);

		then(gateway).should(never()).confirm(any());
		then(finalizer).should().applyApproved(execution, confirmed);
	}

	@Test
	void finalDeclineExpiresReservationThroughFinalizer() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.Declined("REJECT_CARD_PAYMENT", "card rejected"));

		executor.execute(OPERATION_UID);

		then(finalizer).should().applyDeclined(execution, "REJECT_CARD_PAYMENT", "card rejected");
		then(leaseService).should(never()).scheduleRetry(any(), any(), any());
		then(leaseService).should(never()).markOutcomeUnknown(any(), any(), any());
	}

	@Test
	void retryableConfirmationSchedulesSafeRetry() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.RetryableFailure("CONNECTION_FAILED", "try later"));

		executor.execute(OPERATION_UID);

		then(leaseService).should().scheduleRetry(execution, "CONNECTION_FAILED", "try later");
		then(finalizer).shouldHaveNoInteractions();
		then(alertService).shouldHaveNoInteractions();
	}

	@Test
	void fifthRetryableResultAlertsOnlyAfterTheManualReviewTransitionReturns() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		PaymentOperationManualReviewNotice notice =
			new PaymentOperationManualReviewNotice(OPERATION_UID);
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.RetryableFailure("CONNECTION_FAILED", "try later"));
		given(leaseService.scheduleRetry(execution, "CONNECTION_FAILED", "try later"))
			.willReturn(Optional.of(notice));
		willAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return null;
		}).given(alertService).alertManualReview(notice);

		executor.execute(OPERATION_UID);

		InOrder order = inOrder(leaseService, alertService);
		order.verify(leaseService).scheduleRetry(execution, "CONNECTION_FAILED", "try later");
		order.verify(alertService).alertManualReview(notice);
		then(alertService).shouldHaveNoMoreInteractions();
	}

	@Test
	void retryableInquiryRemainsUnknownSoItCannotReconfirm() {
		PaymentExecution execution = execution(PaymentExecutionMode.INQUIRE);
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.inquire(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.RetryableFailure("CONNECTION_FAILED", "try later"));

		executor.execute(OPERATION_UID);

		then(leaseService).should().markOutcomeUnknown(execution, "CONNECTION_FAILED", "try later");
		then(leaseService).should(never()).scheduleRetry(any(), any(), any());
		then(finalizer).shouldHaveNoInteractions();
		then(alertService).shouldHaveNoInteractions();
	}

	@Test
	void readTimeoutBecomesDurableUnknownAndDoesNotExpireReservation() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.OutcomeUnknown("READ_TIMEOUT", "response lost"));

		executor.execute(OPERATION_UID);

		then(leaseService).should().markOutcomeUnknown(execution, "READ_TIMEOUT", "response lost");
		then(finalizer).shouldHaveNoInteractions();
	}

	@Test
	void fifthUnknownResultAlertsOnlyAfterTheManualReviewTransitionReturns() {
		PaymentExecution execution = execution(PaymentExecutionMode.INQUIRE);
		PaymentOperationManualReviewNotice notice =
			new PaymentOperationManualReviewNotice(OPERATION_UID);
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.inquire(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.OutcomeUnknown("READ_TIMEOUT", "response lost"));
		given(leaseService.markOutcomeUnknown(execution, "READ_TIMEOUT", "response lost"))
			.willReturn(Optional.of(notice));

		executor.execute(OPERATION_UID);

		InOrder order = inOrder(leaseService, alertService);
		order.verify(leaseService).markOutcomeUnknown(execution, "READ_TIMEOUT", "response lost");
		order.verify(alertService).alertManualReview(notice);
		then(alertService).shouldHaveNoMoreInteractions();
	}

	@Test
	void unknownInquiryNotFoundReturnsToSafeConfirmRetry() {
		PaymentExecution execution = execution(PaymentExecutionMode.INQUIRE);
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.inquire(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.NotFound("NOT_FOUND_PAYMENT", "not found"));

		executor.execute(OPERATION_UID);

		then(leaseService).should().scheduleRetry(execution, "NOT_FOUND_PAYMENT", "not found");
		then(leaseService).should(never()).markOutcomeUnknown(any(), any(), any());
	}

	@Test
	void unexpectedNotFoundDuringConfirmationBecomesDurableUnknown() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.NotFound("NOT_FOUND_PAYMENT", "not found"));

		executor.execute(OPERATION_UID);

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
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.Approved(mismatched));

		executor.execute(OPERATION_UID);

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
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willThrow(new IllegalStateException("Authorization: secret-token paymentKey=" + PAYMENT_KEY));

		executor.execute(OPERATION_UID);

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
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.OutcomeUnknown("READ_TIMEOUT", message));
		ArgumentCaptor<String> persistedMessage = ArgumentCaptor.forClass(String.class);

		executor.execute(OPERATION_UID);

		then(leaseService).should().markOutcomeUnknown(
			any(), any(), persistedMessage.capture());
		assertThat(persistedMessage.getValue())
			.hasSizeLessThanOrEqualTo(512)
			.doesNotContain("pk");
	}

	@Test
	void gatewayReasonCodeDoesNotPersistSensitiveCorrelationData() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.OutcomeUnknown(PAYMENT_KEY, "response lost"));
		ArgumentCaptor<String> persistedCode = ArgumentCaptor.forClass(String.class);

		executor.execute(OPERATION_UID);

		then(leaseService).should().markOutcomeUnknown(
			any(), persistedCode.capture(), any());
		assertThat(persistedCode.getValue())
			.hasSizeLessThanOrEqualTo(100)
			.doesNotContain(PAYMENT_KEY);
	}

	@Test
	void staleWorkerRetryTransitionIsAQuietNoOp() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.RetryableFailure("CONNECTION_FAILED", "try later"));
		given(leaseService.scheduleRetry(execution, "CONNECTION_FAILED", "try later"))
			.willReturn(Optional.empty());

		assertThatCode(() -> executor.execute(OPERATION_UID)).doesNotThrowAnyException();

		then(finalizer).shouldHaveNoInteractions();
		then(alertService).shouldHaveNoInteractions();
	}

	@Test
	void alertFailureCannotUndoOrRetryACommittedManualReviewTransition() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		PaymentOperationManualReviewNotice notice =
			new PaymentOperationManualReviewNotice(OPERATION_UID);
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.RetryableFailure("CONNECTION_FAILED", "try later"));
		given(leaseService.scheduleRetry(execution, "CONNECTION_FAILED", "try later"))
			.willReturn(Optional.of(notice));
		willThrow(new IllegalStateException("slack failed with paymentKey=" + PAYMENT_KEY))
			.given(alertService).alertManualReview(notice);
		Logger logger = (Logger)LoggerFactory.getLogger(PaymentOperationExecutor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			assertThatCode(() -> executor.execute(OPERATION_UID)).doesNotThrowAnyException();
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		then(leaseService).should().scheduleRetry(execution, "CONNECTION_FAILED", "try later");
		then(leaseService).should(never()).markOutcomeUnknown(any(), any(), any());
		then(alertService).should().alertManualReview(notice);
		assertThat(appender.list).singleElement().satisfies(event -> {
			assertThat(event.getFormattedMessage())
				.contains(OPERATION_UID.toString())
				.doesNotContain(PAYMENT_KEY, "paymentKey", "slack failed");
			assertThat(event.getThrowableProxy()).isNull();
		});
	}

	@Test
	void finalizerDatabaseFailurePropagatesForCallerRetry() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		ConfirmedPayment confirmed = confirmedPayment();
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		given(gateway.confirm(execution.gatewayCommand()))
			.willReturn(new PaymentGatewayResult.Approved(confirmed));
		willThrow(new DataAccessResourceFailureException("db unavailable"))
			.given(finalizer).applyApproved(execution, confirmed);

		assertThatThrownBy(() -> executor.execute(OPERATION_UID))
			.isInstanceOf(DataAccessException.class);

		then(leaseService).should(never()).markOutcomeUnknown(any(), any(), any());
	}

	@Test
	void gatewayInvocationHasNoActiveSpringTransaction() {
		PaymentExecution execution = execution(PaymentExecutionMode.CONFIRM);
		AtomicBoolean gatewayWasInvoked = new AtomicBoolean();
		given(leaseService.claim(OPERATION_UID))
			.willReturn(PaymentOperationClaimResult.claimed(execution));
		willAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			gatewayWasInvoked.set(true);
			return new PaymentGatewayResult.Approved(confirmedPayment());
		}).given(gateway).confirm(execution.gatewayCommand());

		executor.execute(OPERATION_UID);

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
			LEASE_OWNER,
			mode
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
		PaymentConfirmationGateway gateway() {
			return mock(PaymentConfirmationGateway.class);
		}

		@Bean
		PaymentOperationFinalizer finalizer() {
			return mock(PaymentOperationFinalizer.class);
		}

		@Bean
		PaymentOperationAlertService alertService() {
			return mock(PaymentOperationAlertService.class);
		}

		@Bean
		PaymentOperationExecutor executor(
			PaymentOperationLeaseService leaseService,
			PaymentConfirmationGateway gateway,
			PaymentOperationFinalizer finalizer,
			PaymentOperationAlertService alertService
		) {
			return new PaymentOperationExecutor(leaseService, gateway, finalizer, alertService);
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
