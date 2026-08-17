package kr.kro.airbob.domain.payment.service;

import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.APPLIED;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.DECLINED;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.EXECUTING;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.CONFIRMED;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.CANCELLATION_FAILED;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.CANCELLATION_PENDING;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.CANCELLED;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.EXPIRED;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.PAYMENT_PROCESSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.coupon.service.CouponTimeProvider;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.entity.PaymentTransactionType;
import kr.kro.airbob.domain.payment.exception.PaymentOperationInvariantException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.payment.service.gateway.CancelledPayment;
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.messaging.outbox.OutboxMessageRepository;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	JpaAuditingConfig.class,
	QueryDslConfig.class,
	CouponTimeProvider.class,
	CouponUsageService.class,
	PaymentOperationFinalizer.class,
	PaymentOperationFinalizerIntegrationTest.FinalizerTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentOperationFinalizerIntegrationTest {

	private static final UUID OPERATION_UID = UUID.fromString("49f6394e-2812-4c38-949c-2c8b1ac87b8d");
	private static final UUID RESERVATION_UID = UUID.fromString("42a43f4e-d4a8-49ed-ac1a-7bbb2dc4bbc4");
	private static final UUID ACCOMMODATION_UID = UUID.fromString("32644a38-15d8-4199-a9af-2443f21c49bd");
	private static final String LEASE_OWNER = "worker-lease-owner";
	private static final String PAYMENT_KEY = "payment-key-approved";
	private static final long AMOUNT = 90_000L;
	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_payment_finalizer");

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private PaymentOperationFinalizer finalizer;
	@Autowired private PaymentOperationRepository operationRepository;
	@Autowired private ReservationRepository reservationRepository;
	@Autowired private PaymentRepository paymentRepository;
	@Autowired private PaymentTransactionRepository transactionRepository;
	@Autowired private ReservationHistoryRepository historyRepository;
	@Autowired private OutboxMessageRepository outboxRepository;
	@Autowired private HoldingFinalizerTransaction holdingFinalizerTransaction;
	@MockitoBean private AccommodationSearchRefreshPublisher searchRefreshPublisher;

	private long reservationId;
	private long operationId;
	private long memberCouponId;

	@BeforeEach
	void setUp() {
		clearFixtureRows();
		insertExecutingOperationFixture();
	}

	@AfterEach
	void resetSearchRefreshPublisherMock() {
		reset(searchRefreshPublisher);
	}

	@Test
	void approvalAtomicallyAppliesEveryLocalEffectAfterReservationDeadline() {
		finalizer.applyApproved(execution(LEASE_OWNER), confirmedPayment());

		PaymentOperation operation = reloadOperation();
		Reservation reservation = reloadReservation();
		Payment payment = paymentRepository.findByReservationId(reservationId).orElseThrow();
		PaymentTransaction ledger = transactionRepository.findAll().getFirst();

		assertThat(operation.getStatus()).isEqualTo(APPLIED);
		assertThat(operation.getCompletedAt()).isEqualTo(NOW);
		assertThat(operation.getLeaseOwner()).isNull();
		assertThat(reservation.getStatus()).isEqualTo(CONFIRMED);
		assertThat(payment)
			.extracting(Payment::getPaymentKey, Payment::getOrderId, Payment::getAmount,
				Payment::getBalanceAmount, Payment::getMethod, Payment::getStatus, Payment::getApprovedAt)
			.containsExactly(PAYMENT_KEY, RESERVATION_UID.toString(), AMOUNT, AMOUNT,
				PaymentMethod.CARD, PaymentStatus.DONE, NOW.plusSeconds(60));
		assertThat(ledger.getTransactionType()).isEqualTo(PaymentTransactionType.CONFIRM);
		assertThat(ledger.getPaymentOperationId()).isEqualTo(operationId);
		assertThat(ledger.getPaymentId()).isEqualTo(payment.getId());
		assertThat(ledger.getVirtualBankCode()).isNull();
		assertThat(ledger.getVirtualAccountNumber()).isNull();
		assertThat(ledger.getVirtualCustomerName()).isNull();
		assertThat(ledger.getVirtualDueDate()).isNull();
		assertThat(transactionRepository.countByPaymentOperationId(operationId)).isOne();
		assertThat(historyRepository.findAll()).extracting(ReservationHistory::getStatus)
			.containsExactly(CONFIRMED);
		assertThat(outboxRepository.count()).isZero();
		org.mockito.Mockito.verify(searchRefreshPublisher).requestRefresh(ACCOMMODATION_UID);
	}

	@Test
	void virtualAccountApprovalPersistsNormalizedConfirmationLedgerMetadata() {
		Instant dueDate = NOW.plusSeconds(3600);
		ConfirmedPayment confirmed = new ConfirmedPayment(
			PAYMENT_KEY,
			RESERVATION_UID.toString(),
			AMOUNT,
			AMOUNT,
			PaymentMethod.VIRTUAL_ACCOUNT,
			PaymentStatus.DONE,
			NOW.plusSeconds(60),
			new ConfirmedPayment.VirtualAccountDetails(
				"088", "sensitive-account", "sensitive-customer", dueDate)
		);

		finalizer.applyApproved(execution(LEASE_OWNER), confirmed);

		PaymentTransaction ledger = transactionRepository.findAll().getFirst();
		assertThat(ledger.getMethod()).isEqualTo(PaymentMethod.VIRTUAL_ACCOUNT);
		assertThat(ledger)
			.extracting(
				PaymentTransaction::getVirtualBankCode,
				PaymentTransaction::getVirtualAccountNumber,
				PaymentTransaction::getVirtualCustomerName,
				PaymentTransaction::getVirtualDueDate
			)
			.containsExactly("088", "sensitive-account", "sensitive-customer", dueDate);
	}

	@Test
	void duplicateApprovalDoesNotAppendAnySecondLocalEffect() {
		PaymentExecution execution = execution(LEASE_OWNER);
		ConfirmedPayment confirmed = confirmedPayment();
		finalizer.applyApproved(execution, confirmed);
		List<Long> countsAfterFirstApply = localEffectCounts();

		finalizer.applyApproved(execution, confirmed);

		assertThat(localEffectCounts()).isEqualTo(countsAfterFirstApply);
		assertThat(transactionRepository.countByPaymentOperationId(operationId)).isOne();
	}

	@Test
	void simultaneousApprovalsSerializeAndCommitExactlyOneApprovalEffect() throws Exception {
		assertThat(AopUtils.isAopProxy(finalizer)).isTrue();
		assertThat(AopUtils.isAopProxy(holdingFinalizerTransaction)).isTrue();
		CountDownLatch firstApplied = new CountDownLatch(1);
		CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			CompletableFuture<Void> first = CompletableFuture.runAsync(
				() -> holdingFinalizerTransaction.applyApprovedAndHold(
					execution(LEASE_OWNER), confirmedPayment(), firstApplied, releaseFirstTransaction),
				executor);
			assertThat(firstApplied.await(5, TimeUnit.SECONDS)).isTrue();

			CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
				secondStarted.countDown();
				finalizer.applyApproved(execution(LEASE_OWNER), confirmedPayment());
			}, executor);
			assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);

			releaseFirstTransaction.countDown();
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);

			assertSingleApprovedOutcome();
		} finally {
			releaseFirstTransaction.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void simultaneousApprovalAndDeclineCommitOnlyTheLockedApprovalOutcome() throws Exception {
		CountDownLatch firstApplied = new CountDownLatch(1);
		CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
		CountDownLatch declineStarted = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			CompletableFuture<Void> approval = CompletableFuture.runAsync(
				() -> holdingFinalizerTransaction.applyApprovedAndHold(
					execution(LEASE_OWNER), confirmedPayment(), firstApplied, releaseFirstTransaction),
				executor);
			assertThat(firstApplied.await(5, TimeUnit.SECONDS)).isTrue();

			CompletableFuture<Void> decline = CompletableFuture.runAsync(() -> {
				declineStarted.countDown();
				finalizer.applyDeclined(
					execution(LEASE_OWNER), "REJECT_CARD_PAYMENT", "card rejected");
			}, executor);
			assertThat(declineStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> decline.get(500, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);

			releaseFirstTransaction.countDown();
			approval.get(5, TimeUnit.SECONDS);
			assertThatThrownBy(() -> decline.get(5, TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class)
				.hasRootCauseInstanceOf(PaymentOperationInvariantException.class);

			assertSingleApprovedOutcome();
		} finally {
			releaseFirstTransaction.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void matchingExistingPaymentIsReusedWithoutCreatingAnotherDurablePayment() {
		long existingPaymentId = insertExistingPayment(PAYMENT_KEY);

		finalizer.applyApproved(execution(LEASE_OWNER), confirmedPayment());

		Payment payment = paymentRepository.findByReservationId(reservationId).orElseThrow();
		PaymentTransaction ledger = transactionRepository.findAll().getFirst();
		assertThat(paymentRepository.count()).isOne();
		assertThat(payment.getId()).isEqualTo(existingPaymentId);
		assertThat(payment.getPaymentKey()).isEqualTo(PAYMENT_KEY);
		assertThat(ledger.getPaymentId()).isEqualTo(existingPaymentId);
		assertThat(ledger.getPaymentOperationId()).isEqualTo(operationId);
		assertSingleApprovedOutcome();
	}

	@Test
	void conflictingExistingPaymentRejectsApprovalWithoutAnyNewLocalEffect() {
		long existingPaymentId = insertExistingPayment("conflicting-payment-key");

		assertThatThrownBy(() -> finalizer.applyApproved(execution(LEASE_OWNER), confirmedPayment()))
			.isInstanceOf(PaymentOperationInvariantException.class);

		Payment payment = paymentRepository.findByReservationId(reservationId).orElseThrow();
		assertThat(paymentRepository.count()).isOne();
		assertThat(payment.getId()).isEqualTo(existingPaymentId);
		assertThat(payment.getPaymentKey()).isEqualTo("conflicting-payment-key");
		assertThat(reloadOperation().getStatus()).isEqualTo(EXECUTING);
		assertThat(reloadOperation().getLeaseOwner()).isEqualTo(LEASE_OWNER);
		assertThat(reloadReservation().getStatus()).isEqualTo(PAYMENT_PROCESSING);
		assertThat(transactionRepository.countByPaymentOperationId(operationId)).isZero();
		assertThat(isMemberCouponUsed()).isTrue();
		assertThat(historyRepository.count()).isZero();
		assertThat(outboxRepository.count()).isZero();
	}

	@Test
	void oppositeTerminalReplayIsRejectedWithoutChangingApprovedEffects() {
		PaymentExecution execution = execution(LEASE_OWNER);
		finalizer.applyApproved(execution, confirmedPayment());
		List<Long> countsAfterApproval = localEffectCounts();

		assertThatThrownBy(() -> finalizer.applyDeclined(
			execution, "REJECT_CARD_PAYMENT", "card rejected"))
			.isInstanceOf(PaymentOperationInvariantException.class);

		assertThat(reloadOperation().getStatus()).isEqualTo(APPLIED);
		assertThat(reloadReservation().getStatus()).isEqualTo(CONFIRMED);
		assertThat(localEffectCounts()).isEqualTo(countsAfterApproval);
	}

	@Test
	void staleLeaseOwnerCannotApplyEitherProviderOutcome() {
		PaymentExecution stale = execution("stale-worker");

		finalizer.applyApproved(stale, confirmedPayment());
		finalizer.applyDeclined(stale, "REJECT_CARD_PAYMENT", "card rejected");

		assertPreFinalizationState();
	}

	@Test
	void mismatchedApprovalIsRejectedAndRollsBackWithoutPartialEffects() {
		ConfirmedPayment mismatched = new ConfirmedPayment(
			PAYMENT_KEY, RESERVATION_UID.toString(), AMOUNT - 1, AMOUNT - 1,
			PaymentMethod.CARD, PaymentStatus.DONE, NOW.plusSeconds(60), null);

		assertThatThrownBy(() -> finalizer.applyApproved(execution(LEASE_OWNER), mismatched))
			.isInstanceOf(PaymentOperationInvariantException.class);

		assertPreFinalizationState();
	}

	@Test
	void approvalRefreshPublicationFailureRollsBackPaymentLedgerReservationHistoryAndOperation() {
		doThrow(new IllegalStateException("injected index outbox failure"))
			.when(searchRefreshPublisher).requestRefresh(ACCOMMODATION_UID);

		assertThatThrownBy(() -> finalizer.applyApproved(execution(LEASE_OWNER), confirmedPayment()))
			.isInstanceOf(RuntimeException.class);

		assertPreFinalizationState();
	}

	@Test
	void finalDeclineAtomicallyExpiresRestoresCouponAndAppendsOneFailureFact() {
		finalizer.applyDeclined(execution(LEASE_OWNER), "REJECT_CARD_PAYMENT", "card rejected");

		PaymentOperation operation = reloadOperation();
		PaymentTransaction ledger = transactionRepository.findAll().getFirst();
		assertThat(operation.getStatus()).isEqualTo(DECLINED);
		assertThat(operation.getFailureCode()).isEqualTo("REJECT_CARD_PAYMENT");
		assertThat(operation.getFailureMessage()).isEqualTo("card rejected");
		assertThat(operation.getCompletedAt()).isEqualTo(NOW);
		assertThat(operation.getLeaseOwner()).isNull();
		assertThat(reloadReservation().getStatus()).isEqualTo(EXPIRED);
		assertThat(ledger.getTransactionType()).isEqualTo(PaymentTransactionType.FAIL);
		assertThat(ledger.getPaymentOperationId()).isEqualTo(operationId);
		assertThat(ledger.getPaymentId()).isNull();
		assertThat(ledger.getFailureCode()).isEqualTo("REJECT_CARD_PAYMENT");
		assertThat(ledger.getFailureMessage()).isEqualTo("card rejected");
		assertThat(transactionRepository.countByPaymentOperationId(operationId)).isOne();
		assertThat(paymentRepository.findByReservationId(reservationId)).isEmpty();
		assertThat(isMemberCouponUsed()).isFalse();
		assertThat(historyRepository.findAll()).singleElement().satisfies(history -> {
			assertThat(history.getStatus()).isEqualTo(EXPIRED);
			assertThat(history.getChangeReason()).isEqualTo("결제 최종 거절: REJECT_CARD_PAYMENT");
			assertThat(history.getSourceSystem()).isEqualTo("PAYMENT_OPERATION");
		});
		assertThat(outboxRepository.count()).isZero();
		org.mockito.Mockito.verify(searchRefreshPublisher).requestRefresh(ACCOMMODATION_UID);
	}

	@Test
	void duplicateFinalDeclineDoesNotAppendAnySecondLocalEffect() {
		PaymentExecution execution = execution(LEASE_OWNER);
		finalizer.applyDeclined(execution, "REJECT_CARD_PAYMENT", "card rejected");
		List<Long> countsAfterFirstApply = localEffectCounts();

		finalizer.applyDeclined(execution, "REJECT_CARD_PAYMENT", "card rejected");

		assertThat(localEffectCounts()).isEqualTo(countsAfterFirstApply);
		assertThat(transactionRepository.countByPaymentOperationId(operationId)).isOne();
		assertThat(isMemberCouponUsed()).isFalse();
	}

	@Test
	void declineRefreshPublicationFailureRollsBackLedgerReservationCouponHistoryAndOperation() {
		doThrow(new IllegalStateException("injected expiration outbox failure"))
			.when(searchRefreshPublisher).requestRefresh(ACCOMMODATION_UID);

		assertThatThrownBy(() -> finalizer.applyDeclined(
			execution(LEASE_OWNER), "REJECT_CARD_PAYMENT", "card rejected"))
			.isInstanceOf(RuntimeException.class);

		assertPreFinalizationState();
	}

	@Test
	void fullCancellationAtomicallyPersistsProviderEvidenceAndReleasesReservation() {
		prepareCancellationFixture();

		finalizer.applyCancelled(cancellationExecution(LEASE_OWNER), cancelledPayment());

		PaymentOperation operation = reloadOperation();
		Reservation reservation = reloadReservation();
		Payment payment = paymentRepository.findByReservationId(reservationId).orElseThrow();
		PaymentTransaction ledger = transactionRepository.findAll().getFirst();
		assertThat(operation.getStatus()).isEqualTo(APPLIED);
		assertThat(reservation.getStatus()).isEqualTo(CANCELLED);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(payment.getBalanceAmount()).isZero();
		assertThat(ledger.getTransactionType()).isEqualTo(PaymentTransactionType.CANCEL);
		assertThat(ledger.getPaymentOperationId()).isEqualTo(operationId);
		assertThat(ledger.getCancelAmount()).isEqualTo(AMOUNT);
		assertThat(ledger.getCancelReason()).isEqualTo("사용자 요청");
		assertThat(ledger.getTransactionKey()).isEqualTo("cancel-transaction-key");
		assertThat(ledger.getCanceledAt()).isEqualTo(NOW.plusSeconds(90));
		assertThat(isMemberCouponUsed()).isFalse();
		assertThat(historyRepository.findAll()).singleElement().satisfies(history -> {
			assertThat(history.getStatus()).isEqualTo(CANCELLED);
			assertThat(history.getSourceSystem()).isEqualTo("PAYMENT_OPERATION");
		});
		org.mockito.Mockito.verify(searchRefreshPublisher).requestRefresh(ACCOMMODATION_UID);
	}

	@Test
	void duplicateCancellationFinalizationAppendsExactlyOneLedgerAndCouponRestore() {
		prepareCancellationFixture();
		PaymentExecution execution = cancellationExecution(LEASE_OWNER);

		finalizer.applyCancelled(execution, cancelledPayment());
		List<Long> countsAfterFirstApply = localEffectCounts();
		finalizer.applyCancelled(execution, cancelledPayment());

		assertThat(localEffectCounts()).isEqualTo(countsAfterFirstApply);
		assertThat(transactionRepository.countByPaymentOperationId(operationId)).isOne();
		assertThat(isMemberCouponUsed()).isFalse();
	}

	@Test
	void cancellationDeclineKeepsPaymentAndCouponWhileRecordingFailureFact() {
		prepareCancellationFixture();

		finalizer.applyDeclined(
			cancellationExecution(LEASE_OWNER), "NOT_CANCELABLE_PAYMENT", "declined");

		Payment payment = paymentRepository.findByReservationId(reservationId).orElseThrow();
		PaymentTransaction ledger = transactionRepository.findAll().getFirst();
		assertThat(reloadOperation().getStatus()).isEqualTo(DECLINED);
		assertThat(reloadReservation().getStatus()).isEqualTo(CANCELLATION_FAILED);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
		assertThat(payment.getBalanceAmount()).isEqualTo(AMOUNT);
		assertThat(ledger.getTransactionType()).isEqualTo(PaymentTransactionType.CANCEL_FAIL);
		assertThat(ledger.getPaymentOperationId()).isEqualTo(operationId);
		assertThat(ledger.getFailureCode()).isEqualTo("NOT_CANCELABLE_PAYMENT");
		assertThat(ledger.getCancelReason()).isEqualTo("사용자 요청");
		assertThat(isMemberCouponUsed()).isTrue();
	}

	@Test
	void databaseFailureAfterProviderCancellationLeavesExecutionForLeaseInquiryRecovery() {
		prepareCancellationFixture();
		doThrow(new IllegalStateException("injected index outbox failure"))
			.when(searchRefreshPublisher).requestRefresh(ACCOMMODATION_UID);

		assertThatThrownBy(() -> finalizer.applyCancelled(
			cancellationExecution(LEASE_OWNER), cancelledPayment()))
			.isInstanceOf(RuntimeException.class);

		PaymentOperation operation = reloadOperation();
		Payment payment = paymentRepository.findByReservationId(reservationId).orElseThrow();
		assertThat(operation.getStatus()).isEqualTo(EXECUTING);
		assertThat(operation.getLeaseOwner()).isEqualTo(LEASE_OWNER);
		assertThat(reloadReservation().getStatus()).isEqualTo(CANCELLATION_PENDING);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
		assertThat(payment.getBalanceAmount()).isEqualTo(AMOUNT);
		assertThat(transactionRepository.countByPaymentOperationId(operationId)).isZero();
		assertThat(isMemberCouponUsed()).isTrue();
	}

	@Test
	void mismatchedCancellationEvidenceIsRejectedBeforeAnyLocalEffect() {
		prepareCancellationFixture();
		CancelledPayment mismatched = new CancelledPayment(
			PAYMENT_KEY,
			RESERVATION_UID.toString(),
			AMOUNT,
			0L,
			PaymentStatus.CANCELED,
			AMOUNT,
			"다른 사유",
			"cancel-transaction-key",
			NOW.plusSeconds(90)
		);

		assertThatThrownBy(() -> finalizer.applyCancelled(
			cancellationExecution(LEASE_OWNER), mismatched))
			.isInstanceOf(PaymentOperationInvariantException.class);

		assertThat(reloadOperation().getStatus()).isEqualTo(EXECUTING);
		assertThat(reloadReservation().getStatus()).isEqualTo(CANCELLATION_PENDING);
		assertThat(paymentRepository.findByReservationId(reservationId).orElseThrow().getStatus())
			.isEqualTo(PaymentStatus.DONE);
		assertThat(transactionRepository.countByPaymentOperationId(operationId)).isZero();
		assertThat(isMemberCouponUsed()).isTrue();
	}

	@Test
	void simultaneousCancellationFinalizersCommitOneCancellationFact() throws Exception {
		prepareCancellationFixture();
		CountDownLatch firstApplied = new CountDownLatch(1);
		CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			CompletableFuture<Void> first = CompletableFuture.runAsync(
				() -> holdingFinalizerTransaction.applyCancelledAndHold(
					cancellationExecution(LEASE_OWNER), cancelledPayment(),
					firstApplied, releaseFirstTransaction),
				executor);
			assertThat(firstApplied.await(5, TimeUnit.SECONDS)).isTrue();
			CompletableFuture<Void> second = CompletableFuture.runAsync(
				() -> finalizer.applyCancelled(
					cancellationExecution(LEASE_OWNER), cancelledPayment()),
				executor);
			assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);

			releaseFirstTransaction.countDown();
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);

			assertThat(reloadOperation().getStatus()).isEqualTo(APPLIED);
			assertThat(reloadReservation().getStatus()).isEqualTo(CANCELLED);
			assertThat(transactionRepository.countByPaymentOperationId(operationId)).isOne();
		} finally {
			releaseFirstTransaction.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private void assertPreFinalizationState() {
		PaymentOperation operation = reloadOperation();
		assertThat(operation.getStatus()).isEqualTo(EXECUTING);
		assertThat(operation.getCompletedAt()).isNull();
		assertThat(operation.getFailureCode()).isNull();
		assertThat(operation.getFailureMessage()).isNull();
		assertThat(operation.getNextAttemptAt()).isNull();
		assertThat(operation.getLeaseOwner()).isEqualTo(LEASE_OWNER);
		assertThat(operation.getLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(60));
		assertThat(operation.getAttemptCount()).isOne();
		assertThat(reloadReservation().getStatus()).isEqualTo(PAYMENT_PROCESSING);
		assertThat(paymentRepository.findByReservationId(reservationId)).isEmpty();
		assertThat(transactionRepository.countByPaymentOperationId(operationId)).isZero();
		assertThat(isMemberCouponUsed()).isTrue();
		assertThat(historyRepository.count()).isZero();
		assertThat(outboxRepository.count()).isZero();
	}

	private void assertSingleApprovedOutcome() {
		PaymentOperation operation = reloadOperation();
		PaymentTransaction ledger = transactionRepository.findAll().getFirst();
		assertThat(operation.getStatus()).isEqualTo(APPLIED);
		assertThat(reloadReservation().getStatus()).isEqualTo(CONFIRMED);
		assertThat(paymentRepository.count()).isOne();
		assertThat(transactionRepository.countByPaymentOperationId(operationId)).isOne();
		assertThat(ledger.getTransactionType()).isEqualTo(PaymentTransactionType.CONFIRM);
		assertThat(historyRepository.count()).isOne();
		assertThat(isMemberCouponUsed()).isTrue();
		assertThat(outboxRepository.count()).isZero();
	}

	private List<Long> localEffectCounts() {
		return List.of(
			paymentRepository.count(),
			transactionRepository.count(),
			historyRepository.count(),
			outboxRepository.count()
		);
	}

	private boolean isMemberCouponUsed() {
		return Boolean.TRUE.equals(jdbc.queryForObject(
			"SELECT used FROM member_coupon WHERE id = ?", Boolean.class, memberCouponId));
	}

	private PaymentOperation reloadOperation() {
		return operationRepository.findByOperationUid(OPERATION_UID).orElseThrow();
	}

	private Reservation reloadReservation() {
		return reservationRepository.findById(reservationId).orElseThrow();
	}

	private PaymentExecution execution(String leaseOwner) {
		return new PaymentExecution(
			OPERATION_UID,
			RESERVATION_UID,
			PAYMENT_KEY,
			RESERVATION_UID.toString(),
			AMOUNT,
			"provider-key",
			null,
			leaseOwner,
			1,
			PaymentExecutionMode.CONFIRM
		);
	}

	private ConfirmedPayment confirmedPayment() {
		return new ConfirmedPayment(
			PAYMENT_KEY,
			RESERVATION_UID.toString(),
			AMOUNT,
			AMOUNT,
			PaymentMethod.CARD,
			PaymentStatus.DONE,
			NOW.plusSeconds(60),
			null
		);
	}

	private PaymentExecution cancellationExecution(String leaseOwner) {
		return new PaymentExecution(
			OPERATION_UID,
			RESERVATION_UID,
			PAYMENT_KEY,
			RESERVATION_UID.toString(),
			AMOUNT,
			"airbob-cancel-" + OPERATION_UID,
			"사용자 요청",
			leaseOwner,
			1,
			PaymentExecutionMode.CANCEL
		);
	}

	private CancelledPayment cancelledPayment() {
		return new CancelledPayment(
			PAYMENT_KEY,
			RESERVATION_UID.toString(),
			AMOUNT,
			0L,
			PaymentStatus.CANCELED,
			AMOUNT,
			"사용자 요청",
			"cancel-transaction-key",
			NOW.plusSeconds(90)
		);
	}

	private void prepareCancellationFixture() {
		jdbc.update("""
			UPDATE reservation SET status = 'CANCELLATION_PENDING' WHERE id = ?
			""", reservationId);
		jdbc.update("""
			UPDATE payment_operation
			SET operation_type = 'CANCEL', next_action = 'CANCEL',
			    provider_idempotency_key = ?, deduplication_key = ?, cancellation_reason = ?
			WHERE id = ?
			""",
			"airbob-cancel-" + OPERATION_UID,
			"CANCEL:" + RESERVATION_UID + ":" + OPERATION_UID,
			"사용자 요청",
			operationId);
		insertExistingPayment(PAYMENT_KEY);
	}

	private long insertExistingPayment(String paymentKey) {
		jdbc.update("""
			INSERT INTO payment (
			  payment_uid, payment_key, order_id, amount, method, approved_at, created_at,
			  reservation_id, status, balance_amount, updated_at
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, ?, 'CARD', '2026-08-14 00:01:00', NOW(6),
			  ?, 'DONE', ?, NOW(6)
			)
			""", UUID.randomUUID().toString(), paymentKey, RESERVATION_UID.toString(), AMOUNT,
			reservationId, AMOUNT);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void clearFixtureRows() {
		jdbc.update("DELETE FROM payment_transaction");
		jdbc.update("DELETE FROM payment");
		jdbc.update("DELETE FROM payment_operation");
		jdbc.update("DELETE FROM reservation_history");
		jdbc.update("DELETE FROM outbox");
		jdbc.update("DELETE FROM member_coupon");
		jdbc.update("DELETE FROM reservation");
		jdbc.update("DELETE FROM coupon");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM member");
	}

	private void insertExecutingOperationFixture() {
		jdbc.update("""
			INSERT INTO member (email, nickname, role, status, updated_at)
			VALUES ('payment-finalizer@test.com', 'payment-finalizer-member', 'MEMBER', 'ACTIVE', NOW(6))
			""");
		long memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
			INSERT INTO accommodation (
			  member_id, check_in_time, check_out_time, accommodation_uid, updated_at, status, time_zone_id
			) VALUES (?, '15:00:00', '11:00:00', UNHEX(REPLACE(?, '-', '')), NOW(6), 'DRAFT', 'UTC')
			""", memberId, ACCOMMODATION_UID.toString());
		long accommodationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
			INSERT INTO reservation (
			  reservation_uid, accommodation_id, guest_id, check_in_date, check_out_date,
			  check_in_at, check_out_at, time_zone_id, guest_count, total_price, discount_amount,
			  status, reservation_code, created_at, expires_at, updated_at, currency
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, '2026-08-15', '2026-08-16',
			  '2026-08-15 15:00:00', '2026-08-16 11:00:00', 'UTC', 2, ?, 10000,
			  'PAYMENT_PROCESSING', 'FINAL0001', NOW(6), '2026-08-13 23:59:59', NOW(6), 'KRW'
			)
			""", RESERVATION_UID.toString(), accommodationId, memberId, AMOUNT);
		reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
			INSERT INTO coupon (
			  discount_value, is_active, min_payment_price, usable_from, usable_until,
			  issue_start_at, issue_end_at, name, discount_type, total_quantity,
			  issued_quantity, updated_at
			) VALUES (
			  10000, true, 0, '2026-01-01', '2027-01-01',
			  '2026-01-01', '2027-01-01', 'finalizer coupon', 'FIXED_AMOUNT', 1,
			  1, NOW(6)
			)
			""");
		long couponId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
			INSERT INTO member_coupon (
			  member_id, coupon_id, used, used_at, reservation_id, updated_at
			) VALUES (?, ?, true, NOW(6), ?, NOW(6))
			""", memberId, couponId, reservationId);
		memberCouponId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
			INSERT INTO payment_operation (
			  operation_uid, reservation_id, requester_member_id, operation_type, status, next_action,
			  payment_key, expected_amount, provider_idempotency_key, deduplication_key,
			  dispatch_generation, attempt_count, next_attempt_at, queued_at,
			  lease_owner, lease_expires_at, manual_reconciliation_pending, manual_review_count,
			  version, created_at, updated_at
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, 'CONFIRM', 'EXECUTING', 'CONFIRM',
			  ?, ?, 'provider-key', ?,
			  1, 1, NULL, '2026-08-14 00:00:00', ?, '2026-08-14 00:01:00', false, 0,
			  0, NOW(6), NOW(6)
			)
			""", OPERATION_UID.toString(), reservationId, memberId, PAYMENT_KEY, AMOUNT,
			"CONFIRM:" + RESERVATION_UID, LEASE_OWNER);
		operationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FinalizerTestConfiguration {
		@Bean
		Clock finalizerTestClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		ObjectMapper finalizerTestObjectMapper() {
			return new ObjectMapper().findAndRegisterModules();
		}

		@Bean
		HoldingFinalizerTransaction holdingFinalizerTransaction(PaymentOperationFinalizer finalizer) {
			return new HoldingFinalizerTransaction(finalizer);
		}
	}

	static class HoldingFinalizerTransaction {
		private final PaymentOperationFinalizer finalizer;

		HoldingFinalizerTransaction(PaymentOperationFinalizer finalizer) {
			this.finalizer = finalizer;
		}

		@Transactional
		public void applyApprovedAndHold(
			PaymentExecution execution,
			ConfirmedPayment confirmed,
			CountDownLatch applied,
			CountDownLatch release
		) {
			finalizer.applyApproved(execution, confirmed);
			applied.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("timed out holding the approved finalization transaction");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while holding finalization transaction", exception);
			}
		}

		@Transactional
		public void applyCancelledAndHold(
			PaymentExecution execution,
			CancelledPayment cancelled,
			CountDownLatch applied,
			CountDownLatch release
		) {
			finalizer.applyCancelled(execution, cancelled);
			applied.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException(
						"timed out holding the cancellation finalization transaction");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(
					"interrupted while holding cancellation transaction", exception);
			}
		}
	}
}
