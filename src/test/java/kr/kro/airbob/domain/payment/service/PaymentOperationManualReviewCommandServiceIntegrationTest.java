package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionReason;
import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.exception.PaymentOperationConflictException;
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.messaging.alert.application.OperatorAlertOutboxAppender;
import kr.kro.airbob.messaging.alert.application.OperatorAlertOutboxPublisher;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.messaging.alert.infrastructure.outbox.MysqlOperatorAlertOutboxAppender;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.outbox.infrastructure.jpa.JpaOutboxWriter;
import kr.kro.airbob.search.messaging.outbox.OutboxAccommodationSearchRefreshPublisher;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	JpaAuditingConfig.class,
	QueryDslConfig.class,
	CouponTimeProvider.class,
	CouponUsageService.class,
	IntegrationEventCodec.class,
	JpaOutboxWriter.class,
	OutboxAccommodationSearchRefreshPublisher.class,
	MysqlOperatorAlertOutboxAppender.class,
	OperatorAlertOutboxPublisher.class,
	PaymentOperationManualResolutionRecorder.class,
	PaymentOperationManualReviewCommandService.class,
	PaymentOperationFinalizer.class,
	PaymentOperationManualReviewCommandServiceIntegrationTest.CommandTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentOperationManualReviewCommandServiceIntegrationTest {

	private static final UUID OPERATION_UID = UUID.fromString("0b333ce0-7672-447e-ac9e-c0963768dbce");
	private static final UUID RESERVATION_UID = UUID.fromString("979ff16e-37a3-44bc-af79-cbaec9797524");
	private static final UUID ACCOMMODATION_UID = UUID.fromString("66b32a04-70c1-4319-9ac8-0491024442c2");
	private static final Instant NOW = Instant.parse("2026-08-17T02:00:00Z");
	private static final long AMOUNT = 90_000L;

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_manual_payment_command");

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
	@Autowired private PaymentOperationManualReviewCommandService service;
	@Autowired private PaymentOperationFinalizer finalizer;
	@Autowired private HoldingManualCommandTransaction holdingCommandTransaction;
	@Autowired private ControllableAlertOutboxAppender alertAppender;

	private long adminMemberId;
	private long reservationId;
	private long operationId;
	private long memberCouponId;

	@BeforeEach
	void setUp() {
		alertAppender.fail(false);
		clearRows();
		insertManualReviewFixture(false);
	}

	@Test
	void reconciliationRequestCommitsStateAuditAlertAndExecutionDispatchTogether() {
		PaymentOperationManualReviewResult result = service.requestReconciliation(
			OPERATION_UID, adminMemberId, 0L);

		Map<String, Object> operation = operationRow();
		assertThat(operation)
			.containsEntry("status", "QUEUED")
			.containsEntry("next_action", "INQUIRE_CONFIRM")
			.containsEntry("dispatch_generation", 2L)
			.containsEntry("attempt_count", 0)
			.containsEntry("manual_reconciliation_pending", true)
			.containsEntry("not_paid_resolution_eligible", false)
			.containsEntry("version", 1L);
		assertThat(result.status()).isEqualTo(PaymentOperationStatus.QUEUED);
		assertThat(result.version()).isEqualTo(1L);
		assertThat(jdbc.queryForMap("""
			SELECT actor_type, actor_member_id, resolution_action, dispatch_generation,
			       previous_status, result_status
			FROM payment_operation_resolution
			"""))
			.containsEntry("actor_type", "ADMIN")
			.containsEntry("actor_member_id", adminMemberId)
			.containsEntry("resolution_action", "RECONCILIATION_REQUESTED")
			.containsEntry("dispatch_generation", 2L)
			.containsEntry("previous_status", "MANUAL_REVIEW")
			.containsEntry("result_status", "QUEUED");
		assertThat(jdbc.queryForList(
			"SELECT event_type FROM outbox ORDER BY id", String.class))
			.containsExactlyInAnyOrder(
				"PAYMENT_OPERATION_EXECUTION_REQUESTED",
				"OPERATOR_ALERT_REQUESTED");
	}

	@Test
	void reconciliationAlertFailureRollsBackStateAuditAndExecutionDispatch() {
		alertAppender.fail(true);

		assertThatThrownBy(() -> service.requestReconciliation(OPERATION_UID, adminMemberId, 0L))
			.isInstanceOf(DataAccessResourceFailureException.class);

		assertManualReviewState(false);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM payment_operation_resolution", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
	}

	@Test
	void markNotPaidCommitsFixedLedgerCouponHistorySearchAuditAndAlert() {
		jdbc.update("""
			UPDATE payment_operation SET not_paid_resolution_eligible = true WHERE id = ?
			""", operationId);
		String evidenceReference = "provider-case/NOT-PAID-42";

		PaymentOperationManualReviewResult result = service.markNotPaid(
			OPERATION_UID,
			adminMemberId,
			0L,
			PaymentOperationResolutionReason.PROVIDER_PAYMENT_NOT_FOUND,
			evidenceReference);

		assertThat(result.status()).isEqualTo(PaymentOperationStatus.DECLINED);
		assertThat(operationRow())
			.containsEntry("status", "DECLINED")
			.containsEntry("not_paid_resolution_eligible", false)
			.containsEntry("failure_code", "MANUAL_NOT_PAID_RESOLUTION")
			.containsEntry("failure_message", "Payment was verified as not paid.")
			.containsEntry("version", 1L);
		assertThat(jdbc.queryForObject(
			"SELECT status FROM reservation WHERE id = ?", String.class, reservationId))
			.isEqualTo("EXPIRED");
		assertThat(jdbc.queryForObject(
			"SELECT used FROM member_coupon WHERE id = ?", Boolean.class, memberCouponId)).isFalse();
		Map<String, Object> ledger = jdbc.queryForMap("""
			SELECT transaction_type, failure_code, failure_message
			FROM payment_transaction WHERE payment_operation_id = ?
			""", operationId);
		assertThat(ledger)
			.containsEntry("transaction_type", "FAIL")
			.containsEntry("failure_code", "MANUAL_NOT_PAID_RESOLUTION")
			.containsEntry("failure_message", "Payment was verified as not paid.");
		assertThat(ledger.values()).doesNotContain(evidenceReference);
		assertThat(jdbc.queryForObject(
			"SELECT change_reason FROM reservation_history", String.class))
			.isEqualTo("결제 미승인 수동 확정");
		assertThat(jdbc.queryForMap("""
			SELECT actor_type, resolution_action, reason, evidence_reference,
			       previous_status, result_status, dispatch_generation
			FROM payment_operation_resolution
			"""))
			.containsEntry("actor_type", "ADMIN")
			.containsEntry("resolution_action", "MARKED_NOT_PAID")
			.containsEntry("reason", "PROVIDER_PAYMENT_NOT_FOUND")
			.containsEntry("evidence_reference", evidenceReference)
			.containsEntry("previous_status", "MANUAL_REVIEW")
			.containsEntry("result_status", "DECLINED")
			.containsEntry("dispatch_generation", 1L);
		assertThat(jdbc.queryForList(
			"SELECT event_type FROM outbox ORDER BY id", String.class))
			.containsExactlyInAnyOrder(
				"ACCOMMODATION_SEARCH_REFRESH_REQUESTED",
				"OPERATOR_ALERT_REQUESTED");
	}

	@Test
	void markNotPaidAlertFailureRollsBackEveryLocalEffect() {
		jdbc.update("""
			UPDATE payment_operation SET not_paid_resolution_eligible = true WHERE id = ?
			""", operationId);
		alertAppender.fail(true);

		assertThatThrownBy(() -> service.markNotPaid(
			OPERATION_UID,
			adminMemberId,
			0L,
			PaymentOperationResolutionReason.PROVIDER_PAYMENT_NOT_FOUND,
			"provider-case/NOT-PAID-42"))
			.isInstanceOf(DataAccessResourceFailureException.class);

		assertManualReviewState(true);
		assertThat(jdbc.queryForObject(
			"SELECT status FROM reservation WHERE id = ?", String.class, reservationId))
			.isEqualTo("PAYMENT_PROCESSING");
		assertThat(jdbc.queryForObject(
			"SELECT used FROM member_coupon WHERE id = ?", Boolean.class, memberCouponId)).isTrue();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transaction", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_history", Integer.class)).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM payment_operation_resolution", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
	}

	@Test
	void secondAdminWithTheStaleVersionCannotStartAnotherCycle() {
		service.requestReconciliation(OPERATION_UID, adminMemberId, 0L);
		int resolutionCount = jdbc.queryForObject(
			"SELECT COUNT(*) FROM payment_operation_resolution", Integer.class);
		int outboxCount = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);

		assertThatThrownBy(() -> service.requestReconciliation(OPERATION_UID, adminMemberId, 0L))
			.isInstanceOf(PaymentOperationConflictException.class);

		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM payment_operation_resolution", Integer.class))
			.isEqualTo(resolutionCount);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class))
			.isEqualTo(outboxCount);
	}

	@Test
	void simultaneousAdminsSerializeAndOnlyOneCanStartTheVersionedCycle() throws Exception {
		CountDownLatch firstRequested = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			CompletableFuture<PaymentOperationManualReviewResult> first = CompletableFuture.supplyAsync(
				() -> holdingCommandTransaction.requestAndHold(
					OPERATION_UID, adminMemberId, 0L, firstRequested, releaseFirst), executor);
			assertThat(firstRequested.await(5, TimeUnit.SECONDS)).isTrue();

			CompletableFuture<PaymentOperationManualReviewResult> second = CompletableFuture.supplyAsync(() -> {
				secondStarted.countDown();
				return service.requestReconciliation(OPERATION_UID, adminMemberId, 0L);
			}, executor);
			assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);

			releaseFirst.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS).status())
				.isEqualTo(PaymentOperationStatus.QUEUED);
			assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class)
				.hasRootCauseInstanceOf(PaymentOperationConflictException.class);
			assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM payment_operation_resolution", Integer.class)).isOne();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isEqualTo(2);
		} finally {
			releaseFirst.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void adminReconciliationGenerationFencesAStaleProviderFinalizer() {
		service.requestReconciliation(OPERATION_UID, adminMemberId, 0L);
		int resolutionCount = jdbc.queryForObject(
			"SELECT COUNT(*) FROM payment_operation_resolution", Integer.class);
		int outboxCount = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
		PaymentExecution staleExecution = new PaymentExecution(
			OPERATION_UID,
			RESERVATION_UID,
			"manual-payment-key",
			RESERVATION_UID.toString(),
			AMOUNT,
			"manual-provider-key",
			null,
			"stale-worker",
			1,
			PaymentExecutionMode.CONFIRM,
			false);
		ConfirmedPayment staleApproval = new ConfirmedPayment(
			"manual-payment-key",
			RESERVATION_UID.toString(),
			AMOUNT,
			AMOUNT,
			PaymentMethod.CARD,
			PaymentStatus.DONE,
			NOW,
			null);

		finalizer.applyApproved(staleExecution, staleApproval);

		assertThat(operationRow())
			.containsEntry("status", "QUEUED")
			.containsEntry("dispatch_generation", 2L)
			.containsEntry("manual_reconciliation_pending", true);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transaction", Integer.class)).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM payment_operation_resolution", Integer.class))
			.isEqualTo(resolutionCount);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class))
			.isEqualTo(outboxCount);
	}

	private void assertManualReviewState(boolean notPaidEligible) {
		assertThat(operationRow())
			.containsEntry("status", "MANUAL_REVIEW")
			.containsEntry("dispatch_generation", 1L)
			.containsEntry("attempt_count", 5)
			.containsEntry("manual_reconciliation_pending", false)
			.containsEntry("not_paid_resolution_eligible", notPaidEligible)
			.containsEntry("version", 0L);
	}

	private Map<String, Object> operationRow() {
		return jdbc.queryForMap("""
			SELECT status, next_action, dispatch_generation, attempt_count,
			       manual_reconciliation_pending, not_paid_resolution_eligible,
			       failure_code, failure_message, version
			FROM payment_operation WHERE id = ?
			""", operationId);
	}

	private void clearRows() {
		jdbc.update("DELETE FROM payment_operation_resolution");
		jdbc.update("DELETE FROM payment_transaction");
		jdbc.update("DELETE FROM payment");
		jdbc.update("DELETE FROM outbox");
		jdbc.update("DELETE FROM payment_operation");
		jdbc.update("DELETE FROM reservation_history");
		jdbc.update("DELETE FROM member_coupon");
		jdbc.update("DELETE FROM reservation");
		jdbc.update("DELETE FROM coupon");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM member");
	}

	private void insertManualReviewFixture(boolean notPaidEligible) {
		jdbc.update("""
			INSERT INTO member (email, nickname, role, status, updated_at)
			VALUES ('manual-command@test.com', 'manual-command-admin', 'ADMIN', 'ACTIVE', NOW(6))
			""");
		adminMemberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
			INSERT INTO accommodation (
			  member_id, check_in_time, check_out_time, accommodation_uid, updated_at, status, time_zone_id
			) VALUES (?, '15:00:00', '11:00:00', UNHEX(REPLACE(?, '-', '')), NOW(6), 'DRAFT', 'UTC')
			""", adminMemberId, ACCOMMODATION_UID.toString());
		long accommodationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
			INSERT INTO reservation (
			  reservation_uid, accommodation_id, guest_id, check_in_date, check_out_date,
			  check_in_at, check_out_at, time_zone_id, guest_count, total_price, discount_amount,
			  status, reservation_code, created_at, expires_at, updated_at, currency
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, '2026-08-18', '2026-08-19',
			  '2026-08-18 15:00:00', '2026-08-19 11:00:00', 'UTC', 2, ?, 10000,
			  'PAYMENT_PROCESSING', 'MANUAL0001', NOW(6), '2026-08-17 01:00:00', NOW(6), 'KRW'
			)
			""", RESERVATION_UID.toString(), accommodationId, adminMemberId, AMOUNT);
		reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
			INSERT INTO coupon (
			  discount_value, is_active, min_payment_price, usable_from, usable_until,
			  issue_start_at, issue_end_at, name, discount_type, total_quantity,
			  issued_quantity, updated_at
			) VALUES (
			  10000, true, 0, '2026-01-01', '2027-01-01',
			  '2026-01-01', '2027-01-01', 'manual command coupon', 'FIXED_AMOUNT', 1,
			  1, NOW(6)
			)
			""");
		long couponId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
			INSERT INTO member_coupon (
			  member_id, coupon_id, used, used_at, reservation_id, updated_at
			) VALUES (?, ?, true, NOW(6), ?, NOW(6))
			""", adminMemberId, couponId, reservationId);
		memberCouponId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
			INSERT INTO payment_operation (
			  operation_uid, reservation_id, requester_member_id, operation_type, status, next_action,
			  payment_key, expected_amount, provider_idempotency_key, deduplication_key,
			  dispatch_generation, attempt_count, next_attempt_at, queued_at, review_required_at,
			  manual_reconciliation_pending, not_paid_resolution_eligible, manual_review_count,
			  failure_code, failure_message, version, created_at, updated_at
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, 'CONFIRM', 'MANUAL_REVIEW', 'INQUIRE_CONFIRM',
			  'manual-payment-key', ?, 'manual-provider-key', ?,
			  1, 5, NULL, '2026-08-17 01:00:00', '2026-08-17 01:30:00',
			  false, ?, 1, 'PROVIDER_RESULT_UNKNOWN', 'review required',
			  0, NOW(6), NOW(6)
			)
			""",
			OPERATION_UID.toString(),
			reservationId,
			adminMemberId,
			AMOUNT,
			"CONFIRM:" + RESERVATION_UID,
			notPaidEligible);
		operationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class CommandTestConfiguration {

		@Bean
		Clock manualCommandClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		ObjectMapper manualCommandObjectMapper() {
			return new ObjectMapper().findAndRegisterModules();
		}

		@Bean
		@Primary
		ControllableAlertOutboxAppender controllableAlertOutboxAppender(
			MysqlOperatorAlertOutboxAppender delegate
		) {
			return new ControllableAlertOutboxAppender(delegate);
		}

		@Bean
		HoldingManualCommandTransaction holdingManualCommandTransaction(
			PaymentOperationManualReviewCommandService service
		) {
			return new HoldingManualCommandTransaction(service);
		}
	}

	static class ControllableAlertOutboxAppender implements OperatorAlertOutboxAppender {
		private final OperatorAlertOutboxAppender delegate;
		private boolean fail;

		ControllableAlertOutboxAppender(OperatorAlertOutboxAppender delegate) {
			this.delegate = delegate;
		}

		void fail(boolean fail) {
			this.fail = fail;
		}

		@Override
		public boolean appendIfAbsent(OperatorAlertRequestedV1 event) {
			if (fail) {
				throw new DataAccessResourceFailureException("operator alert outbox unavailable");
			}
			return delegate.appendIfAbsent(event);
		}
	}

	static class HoldingManualCommandTransaction {
		private final PaymentOperationManualReviewCommandService service;

		HoldingManualCommandTransaction(PaymentOperationManualReviewCommandService service) {
			this.service = service;
		}

		@Transactional
		public PaymentOperationManualReviewResult requestAndHold(
			UUID operationUid,
			Long actorMemberId,
			long expectedVersion,
			CountDownLatch requested,
			CountDownLatch release
		) {
			PaymentOperationManualReviewResult result = service.requestReconciliation(
				operationUid, actorMemberId, expectedVersion);
			requested.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("timed out while holding manual command transaction");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("manual command transaction interrupted", exception);
			}
			return result;
		}
	}
}
