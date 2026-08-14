package kr.kro.airbob.domain.payment.service;

import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.APPLIED;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.DECLINED;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.EXECUTING;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.CONFIRMED;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.EXPIRED;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.PAYMENT_PROCESSING;
import static kr.kro.airbob.outbox.EventType.RESERVATION_CHANGED;
import static kr.kro.airbob.outbox.EventType.RESERVATION_CONFIRMED;
import static kr.kro.airbob.outbox.EventType.RESERVATION_EXPIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.outbox.EventPayload;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import kr.kro.airbob.outbox.repository.OutboxRepository;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	JpaAuditingConfig.class,
	QueryDslConfig.class,
	CouponTimeProvider.class,
	CouponUsageService.class,
	OutboxEventPublisher.class,
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
	@Autowired private OutboxRepository outboxRepository;
	@MockitoSpyBean private OutboxEventPublisher outboxEventPublisher;

	private long reservationId;
	private long operationId;
	private long memberCouponId;

	@BeforeEach
	void setUp() {
		clearFixtureRows();
		insertExecutingOperationFixture();
	}

	@AfterEach
	void resetOutboxPublisherSpy() {
		reset(outboxEventPublisher);
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
		assertThat(outboxEventTypes()).containsExactlyInAnyOrder(
			RESERVATION_CONFIRMED.name(), RESERVATION_CHANGED.name());
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
	void approvalOutboxFailureRollsBackPaymentLedgerReservationHistoryOperationAndOutbox() {
		doThrow(new IllegalStateException("injected index outbox failure"))
			.when(outboxEventPublisher).save(eq(RESERVATION_CHANGED), any(EventPayload.class));

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
		assertThat(outboxEventTypes()).containsExactly(RESERVATION_EXPIRED.name());
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
	void declineOutboxFailureRollsBackLedgerReservationCouponHistoryOperationAndOutbox() {
		doThrow(new IllegalStateException("injected expiration outbox failure"))
			.when(outboxEventPublisher).save(eq(RESERVATION_EXPIRED), any(EventPayload.class));

		assertThatThrownBy(() -> finalizer.applyDeclined(
			execution(LEASE_OWNER), "REJECT_CARD_PAYMENT", "card rejected"))
			.isInstanceOf(RuntimeException.class);

		assertPreFinalizationState();
	}

	private void assertPreFinalizationState() {
		assertThat(reloadOperation().getStatus()).isEqualTo(EXECUTING);
		assertThat(reloadOperation().getLeaseOwner()).isEqualTo(LEASE_OWNER);
		assertThat(reloadReservation().getStatus()).isEqualTo(PAYMENT_PROCESSING);
		assertThat(paymentRepository.findByReservationId(reservationId)).isEmpty();
		assertThat(transactionRepository.countByPaymentOperationId(operationId)).isZero();
		assertThat(isMemberCouponUsed()).isTrue();
		assertThat(historyRepository.count()).isZero();
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

	private List<String> outboxEventTypes() {
		return outboxRepository.findAll().stream().map(outbox -> outbox.getEventType()).toList();
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
			leaseOwner,
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
			new ConfirmedPayment.VirtualAccountDetails(
				"088", "sensitive-account", "sensitive-customer", NOW.plusSeconds(3600))
		);
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
			  operation_uid, reservation_id, requester_member_id, operation_type, status,
			  payment_key, expected_amount, provider_idempotency_key, deduplication_key,
			  attempt_count, next_attempt_at, last_enqueued_at, lease_owner, lease_expires_at,
			  version, created_at, updated_at
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, 'CONFIRM', 'EXECUTING',
			  ?, ?, 'provider-key', ?,
			  1, NULL, '2026-08-14 00:00:00', ?, '2026-08-14 00:01:00',
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
	}
}
