package kr.kro.airbob.domain.payment.service;

import static kr.kro.airbob.domain.payment.entity.PaymentOperationNextAction.CONFIRM;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationNextAction.INQUIRE_CONFIRM;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.APPLIED;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.EXECUTING;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.QUEUED;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.WAITING_RETRY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.payment.config.PaymentOperationProperties;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationNextAction;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryService.RecoveryBatch;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.outbox.infrastructure.jpa.JpaOutboxWriter;
import kr.kro.airbob.messaging.outbox.infrastructure.jpa.OutboxMessage;
import kr.kro.airbob.messaging.outbox.infrastructure.jpa.OutboxMessageRepository;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	JpaAuditingConfig.class,
	QueryDslConfig.class,
	IntegrationEventCodec.class,
	JpaOutboxWriter.class,
	PaymentOperationRecoveryService.class,
	PaymentOperationRecoveryServiceIntegrationTest.RecoveryTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentOperationRecoveryServiceIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private static final UUID RESERVATION_UID =
		UUID.fromString("7cc76484-37bb-4310-b3b6-b86247553c3a");
	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("e2bf35c4-9ef4-4583-a3ae-b9f014177348");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_payment_recovery")
		.withCommand("--log-bin-trust-function-creators=1");

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
	@Autowired private PaymentOperationRecoveryService recoveryService;
	@Autowired private PaymentOperationRepository operationRepository;
	@Autowired private OutboxMessageRepository outboxRepository;
	@Autowired private IntegrationEventCodec codec;
	@Autowired private HoldingRecoveryTransaction holdingRecoveryTransaction;

	private long memberId;
	private long reservationId;

	@BeforeEach
	void setUp() {
		dropOutboxFailureTrigger();
		clearFixtureRows();
		insertReservationFixture();
	}

	@Test
	void recoversOnlyDueRetryAndExpiredLeaseWithoutRepublishingQueuedWork() {
		UUID queuedUid = uid("already-queued");
		UUID retryUid = uid("retry-due");
		UUID notDueUid = uid("retry-not-due");
		UUID expiredLeaseUid = uid("expired-lease");
		UUID validLeaseUid = uid("valid-lease");
		UUID terminalUid = uid("terminal-applied");

		insertOperation(queuedUid, QUEUED, CONFIRM, 4, 0, null, NOW.minusSeconds(30), null, null);
		insertOperation(retryUid, WAITING_RETRY, CONFIRM, 7, 2, NOW, NOW.minusSeconds(30), null, null);
		insertOperation(notDueUid, WAITING_RETRY, CONFIRM, 3, 2, NOW.plusMillis(1), NOW.minusSeconds(30), null, null);
		insertOperation(expiredLeaseUid, EXECUTING, CONFIRM, 9, 4, null, NOW.minusSeconds(30), "expired-owner", NOW);
		insertOperation(validLeaseUid, EXECUTING, CONFIRM, 5, 1, null, NOW.minusSeconds(30), "active-owner", NOW.plusMillis(1));
		insertOperation(terminalUid, APPLIED, CONFIRM, 2, 1, null, NOW.minusSeconds(30), null, null);

		RecoveryBatch batch = recoveryService.recoverDue();

		assertThat(batch.enqueued()).isEqualTo(2);
		assertThat(outboxEvents()).extracting(PaymentOperationExecutionRequestedV1::operationUid)
			.containsExactlyInAnyOrder(retryUid, expiredLeaseUid);
		assertThat(reload(retryUid).getStatus()).isEqualTo(QUEUED);
		assertThat(reload(retryUid).getNextAction()).isEqualTo(CONFIRM);
		assertThat(reload(retryUid).getDispatchGeneration()).isEqualTo(8);
		assertThat(reload(expiredLeaseUid).getStatus()).isEqualTo(QUEUED);
		assertThat(reload(expiredLeaseUid).getNextAction()).isEqualTo(INQUIRE_CONFIRM);
		assertThat(reload(expiredLeaseUid).getDispatchGeneration()).isEqualTo(10);
		assertThat(reload(expiredLeaseUid).getLeaseOwner()).isNull();
		assertThat(reload(expiredLeaseUid).getLeaseExpiresAt()).isNull();
		assertThat(reload(queuedUid).getDispatchGeneration()).isEqualTo(4);
		assertThat(reload(notDueUid).getStatus()).isEqualTo(WAITING_RETRY);
		assertThat(reload(validLeaseUid).getStatus()).isEqualTo(EXECUTING);

		assertThat(recoveryService.recoverDue().enqueued()).isZero();
		assertThat(outboxEvents()).hasSize(2);
	}

	@Test
	void twoConcurrentRecoveryTransactionsAppendExactlyOneGenerationForOneDueRow() throws Exception {
		UUID operationUid = uid("concurrent-due-row");
		insertOperation(operationUid, WAITING_RETRY, CONFIRM, 1, 2, NOW, NOW.minusSeconds(30), null, null);
		assertThat(AopUtils.isAopProxy(recoveryService)).isTrue();
		assertThat(AopUtils.isAopProxy(holdingRecoveryTransaction)).isTrue();
		CountDownLatch firstRecovered = new CountDownLatch(1);
		CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			CompletableFuture<RecoveryBatch> first = CompletableFuture.supplyAsync(
				() -> holdingRecoveryTransaction.recoverAndHold(firstRecovered, releaseFirstTransaction),
				executor);
			assertThat(firstRecovered.await(5, TimeUnit.SECONDS)).isTrue();

			RecoveryBatch second = CompletableFuture.supplyAsync(recoveryService::recoverDue, executor)
				.get(5, TimeUnit.SECONDS);
			releaseFirstTransaction.countDown();
			RecoveryBatch firstBatch = first.get(5, TimeUnit.SECONDS);

			assertThat(firstBatch.enqueued() + second.enqueued()).isOne();
			assertThat(outboxEvents()).singleElement().satisfies(event -> {
				assertThat(event.operationUid()).isEqualTo(operationUid);
				assertThat(event.dispatchGeneration()).isEqualTo(2);
			});
			assertThat(reload(operationUid).getDispatchGeneration()).isEqualTo(2);
		} finally {
			releaseFirstTransaction.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void recoveryLimitsEachLockedScanToOneHundredRows() {
		for (int index = 0; index < 101; index++) {
			insertOperation(uid("batch-" + index), WAITING_RETRY, CONFIRM, 1, 1,
				NOW, NOW.minusSeconds(30), null, null);
		}

		assertThat(recoveryService.recoverDue().enqueued()).isEqualTo(100);
		assertThat(recoveryService.recoverDue().enqueued()).isOne();
		assertThat(outboxEvents()).hasSize(101);
	}

	@Test
	void outboxFailureRollsBackExpiredLeaseRecoveryAndGenerationTogether() {
		UUID operationUid = uid("atomic-expired-lease");
		insertOperation(operationUid, EXECUTING, CONFIRM, 4, 2,
			null, NOW.minusSeconds(30), "expired-owner", NOW);
		createOutboxFailureTrigger();

		assertThatThrownBy(recoveryService::recoverDue)
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("injected outbox failure");

		PaymentOperation reloaded = reload(operationUid);
		assertThat(reloaded.getStatus()).isEqualTo(EXECUTING);
		assertThat(reloaded.getNextAction()).isEqualTo(CONFIRM);
		assertThat(reloaded.getDispatchGeneration()).isEqualTo(4);
		assertThat(reloaded.getLeaseOwner()).isEqualTo("expired-owner");
		assertThat(reloaded.getLeaseExpiresAt()).isEqualTo(NOW);
		assertThat(outboxRepository.count()).isZero();
		dropOutboxFailureTrigger();
	}

	private PaymentOperation reload(UUID operationUid) {
		return operationRepository.findByOperationUid(operationUid).orElseThrow();
	}

	private List<PaymentOperationExecutionRequestedV1> outboxEvents() {
		return outboxRepository.findAll().stream().map(this::decode).toList();
	}

	private PaymentOperationExecutionRequestedV1 decode(OutboxMessage message) {
		EventEnvelope<PaymentOperationExecutionRequestedV1> envelope = codec.decode(
			message.getPayload(),
			PaymentOperationExecutionRequestedV1.DESCRIPTOR,
			PaymentOperationExecutionRequestedV1.class
		);
		return envelope.payload();
	}

	private void insertReservationFixture() {
		jdbc.update("""
			INSERT INTO member (email, nickname, role, status, updated_at)
			VALUES ('payment-recovery@test.com', 'payment-recovery-member', 'MEMBER', 'ACTIVE', NOW(6))
			""");
		memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
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
			  '2026-08-15 15:00:00', '2026-08-16 11:00:00', 'UTC', 2, 100000, 0,
			  'PAYMENT_PROCESSING', 'RECOV0001', NOW(6), '2026-08-14 01:00:00', NOW(6), 'KRW'
			)
			""", RESERVATION_UID.toString(), accommodationId, memberId);
		reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void insertOperation(
		UUID operationUid,
		PaymentOperationStatus status,
		PaymentOperationNextAction nextAction,
		long dispatchGeneration,
		int attemptCount,
		Instant nextAttemptAt,
		Instant queuedAt,
		String leaseOwner,
		Instant leaseExpiresAt
	) {
		jdbc.update("""
			INSERT INTO payment_operation (
			  operation_uid, reservation_id, requester_member_id, operation_type, status, next_action,
			  payment_key, expected_amount, provider_idempotency_key, deduplication_key,
			  dispatch_generation, attempt_count, next_attempt_at, queued_at,
			  lease_owner, lease_expires_at, completed_at,
			  manual_reconciliation_pending, manual_review_count, version, created_at, updated_at
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, 'CONFIRM', ?, ?,
			  ?, 100000, ?, ?,
			  ?, ?, ?, ?,
			  ?, ?, ?,
			  false, 0, 0, ?, ?
			)
			""",
			operationUid.toString(), reservationId, memberId, status.name(), nextAction.name(),
			"payment-key-" + operationUid, "provider-" + operationUid, "CONFIRM:" + operationUid,
			dispatchGeneration, attemptCount, timestamp(nextAttemptAt), timestamp(queuedAt),
			leaseOwner, timestamp(leaseExpiresAt), status.isTerminal() ? timestamp(NOW.minusSeconds(1)) : null,
			timestamp(NOW.minusSeconds(60)), timestamp(NOW.minusSeconds(60)));
	}

	private Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	private UUID uid(String name) {
		return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
	}

	private void clearFixtureRows() {
		jdbc.update("DELETE FROM outbox");
		jdbc.update("DELETE FROM payment_transaction");
		jdbc.update("DELETE FROM payment_operation_resolution");
		jdbc.update("DELETE FROM payment_operation");
		jdbc.update("DELETE FROM reservation_history");
		jdbc.update("DELETE FROM member_coupon");
		jdbc.update("DELETE FROM reservation");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM member");
	}

	private void createOutboxFailureTrigger() {
		jdbc.execute("""
			CREATE TRIGGER payment_recovery_reject_outbox
			BEFORE INSERT ON outbox
			FOR EACH ROW
			SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected outbox failure'
			""");
	}

	private void dropOutboxFailureTrigger() {
		jdbc.execute("DROP TRIGGER IF EXISTS payment_recovery_reject_outbox");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RecoveryTestConfiguration {
		@Bean
		Clock paymentRecoveryTestClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		PaymentOperationProperties paymentOperationProperties() {
			return new PaymentOperationProperties(
				Duration.ofSeconds(30), Duration.ofSeconds(10), 100, 5,
				Duration.ofSeconds(10), Duration.ofMinutes(5));
		}

		@Bean
		ObjectMapper paymentRecoveryObjectMapper() {
			return new ObjectMapper().findAndRegisterModules();
		}

		@Bean
		HoldingRecoveryTransaction holdingRecoveryTransaction(PaymentOperationRecoveryService service) {
			return new HoldingRecoveryTransaction(service);
		}
	}

	static class HoldingRecoveryTransaction {
		private final PaymentOperationRecoveryService service;

		HoldingRecoveryTransaction(PaymentOperationRecoveryService service) {
			this.service = service;
		}

		@Transactional
		public RecoveryBatch recoverAndHold(CountDownLatch recovered, CountDownLatch release) {
			RecoveryBatch batch = service.recoverDue();
			recovered.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("timed out waiting to release recovery transaction");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while holding recovery transaction", exception);
			}
			return batch;
		}
	}
}
