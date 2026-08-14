package kr.kro.airbob.domain.payment.service;

import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.APPLIED;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.EXECUTING;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.MANUAL_REVIEW;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.OUTCOME_UNKNOWN;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.READY;
import static kr.kro.airbob.domain.payment.entity.PaymentOperationStatus.RETRY_WAIT;
import static kr.kro.airbob.outbox.EventType.PAYMENT_EXECUTION_REQUESTED_V1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.payment.config.PaymentOperationProperties;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.event.PaymentOperationEvent.PaymentExecutionRequestedV1;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryService.RecoveryBatch;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import kr.kro.airbob.outbox.entity.Outbox;
import kr.kro.airbob.outbox.repository.OutboxRepository;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	JpaAuditingConfig.class,
	QueryDslConfig.class,
	OutboxEventPublisher.class,
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
		.withDatabaseName("airbobdb_payment_recovery");

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
	@Autowired private ObjectMapper objectMapper;
	@Autowired private PaymentOperationRecoveryService recoveryService;
	@Autowired private PaymentOperationRepository operationRepository;
	@Autowired private OutboxRepository outboxRepository;
	@Autowired private HoldingRecoveryTransaction holdingRecoveryTransaction;
	@MockitoSpyBean private OutboxEventPublisher outboxEventPublisher;

	private long memberId;
	private long reservationId;

	@BeforeEach
	void setUp() {
		clearFixtureRows();
		insertReservationFixture();
	}

	@AfterEach
	void resetOutboxPublisherSpy() {
		reset(outboxEventPublisher);
	}

	@Test
	void recoversOnlyDueDatabaseWorkAtInclusiveBoundariesAndQuarantinesExhaustedAttempts() {
		UUID staleReadyUid = uid("stale-ready");
		UUID freshReadyUid = uid("fresh-ready");
		UUID retryUid = uid("retry-due");
		UUID notDueUid = uid("retry-not-due");
		UUID unknownUid = uid("unknown-due");
		UUID expiredLeaseUid = uid("expired-lease");
		UUID validLeaseUid = uid("valid-lease");
		UUID exhaustedUid = uid("unknown-exhausted");
		UUID terminalUid = uid("terminal-applied");

		insertOperation(staleReadyUid, READY, 0, NOW, NOW.minusSeconds(10), null, null);
		insertOperation(freshReadyUid, READY, 0, NOW, NOW.minusSeconds(9), null, null);
		insertOperation(retryUid, RETRY_WAIT, 2, NOW, NOW.minusSeconds(10), null, null);
		insertOperation(notDueUid, RETRY_WAIT, 2, NOW.plusMillis(1), NOW.minusSeconds(30), null, null);
		insertOperation(unknownUid, OUTCOME_UNKNOWN, 3, NOW, NOW.minusSeconds(30), null, null);
		insertOperation(
			expiredLeaseUid, EXECUTING, 4, NOW.minusSeconds(30), NOW.minusSeconds(30), "expired-owner", NOW);
		insertOperation(
			validLeaseUid, EXECUTING, 1, NOW.minusSeconds(30), NOW.minusSeconds(30), "active-owner", NOW.plusMillis(1));
		insertOperation(exhaustedUid, OUTCOME_UNKNOWN, 5, NOW, NOW.minusSeconds(30), null, null);
		insertOperation(terminalUid, APPLIED, 5, null, NOW.minusSeconds(30), null, null);

		RecoveryBatch batch = recoveryService.recoverDue();

		assertThat(batch.enqueued()).isEqualTo(4);
		assertThat(batch.manualReviews()).extracting(PaymentOperationManualReviewNotice::operationUid)
			.containsExactly(exhaustedUid);
		assertThat(outboxOperationUids()).containsExactlyInAnyOrder(
			staleReadyUid, retryUid, unknownUid, expiredLeaseUid);
		assertThat(outboxOperationUids()).doesNotContain(
			freshReadyUid, notDueUid, validLeaseUid, exhaustedUid, terminalUid);
		assertThat(reload(expiredLeaseUid).getStatus()).isEqualTo(OUTCOME_UNKNOWN);
		assertThat(reload(expiredLeaseUid).getLeaseOwner()).isNull();
		assertThat(reload(expiredLeaseUid).getLeaseExpiresAt()).isNull();
		assertThat(reload(expiredLeaseUid).getNextAttemptAt()).isEqualTo(NOW);
		assertThat(reload(exhaustedUid).getStatus()).isEqualTo(MANUAL_REVIEW);
		assertThat(reload(exhaustedUid).getCompletedAt()).isEqualTo(NOW);
		assertThat(reload(notDueUid).getStatus()).isEqualTo(RETRY_WAIT);
		assertThat(reload(validLeaseUid).getStatus()).isEqualTo(EXECUTING);
		assertThat(List.of(staleReadyUid, retryUid, unknownUid, expiredLeaseUid))
			.allSatisfy(uid -> assertThat(reload(uid).getLastEnqueuedAt()).isEqualTo(NOW));

		RecoveryBatch immediateRepeat = recoveryService.recoverDue();

		assertThat(immediateRepeat.enqueued()).isZero();
		assertThat(immediateRepeat.manualReviews()).isEmpty();
		assertThat(outboxOperationUids()).hasSize(4);
	}

	@Test
	void twoConcurrentRecoveryTransactionsAppendExactlyOneCommandForOneDueRow() throws Exception {
		UUID operationUid = uid("concurrent-due-row");
		insertOperation(operationUid, READY, 0, NOW, NOW.minusSeconds(30), null, null);
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

			CompletableFuture<RecoveryBatch> second = CompletableFuture.supplyAsync(
				recoveryService::recoverDue, executor);
			RecoveryBatch secondBatch = second.get(5, TimeUnit.SECONDS);

			releaseFirstTransaction.countDown();
			RecoveryBatch firstBatch = first.get(5, TimeUnit.SECONDS);

			assertThat(firstBatch.enqueued() + secondBatch.enqueued()).isOne();
			assertThat(outboxOperationUids()).containsExactly(operationUid);
			assertThat(reload(operationUid).getLastEnqueuedAt()).isEqualTo(NOW);
		} finally {
			releaseFirstTransaction.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void recoveryLimitsEachLockedScanToOneHundredRows() {
		for (int index = 0; index < 101; index++) {
			insertOperation(uid("batch-" + index), READY, 0, NOW, NOW.minusSeconds(30), null, null);
		}

		RecoveryBatch firstBatch = recoveryService.recoverDue();
		RecoveryBatch secondBatch = recoveryService.recoverDue();

		assertThat(firstBatch.enqueued()).isEqualTo(100);
		assertThat(secondBatch.enqueued()).isOne();
		assertThat(outboxOperationUids()).hasSize(101);
	}

	@Test
	void outboxFailureRollsBackExpiredLeaseRecoveryAndEnqueueTimestampTogether() {
		UUID operationUid = uid("atomic-expired-lease");
		Instant previousEnqueue = NOW.minusSeconds(30);
		insertOperation(
			operationUid, EXECUTING, 2, NOW.minusSeconds(30), previousEnqueue, "expired-owner", NOW);
		doThrow(new IllegalStateException("injected outbox failure"))
			.when(outboxEventPublisher)
			.save(eq(PAYMENT_EXECUTION_REQUESTED_V1), any(PaymentExecutionRequestedV1.class));

		assertThatThrownBy(recoveryService::recoverDue)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("injected outbox failure");

		PaymentOperation reloaded = reload(operationUid);
		assertThat(reloaded.getStatus()).isEqualTo(EXECUTING);
		assertThat(reloaded.getLeaseOwner()).isEqualTo("expired-owner");
		assertThat(reloaded.getLeaseExpiresAt()).isEqualTo(NOW);
		assertThat(reloaded.getLastEnqueuedAt()).isEqualTo(previousEnqueue);
		assertThat(outboxRepository.count()).isZero();
	}

	private PaymentOperation reload(UUID operationUid) {
		return operationRepository.findByOperationUid(operationUid).orElseThrow();
	}

	private List<UUID> outboxOperationUids() {
		return outboxRepository.findAll().stream()
			.filter(outbox -> PAYMENT_EXECUTION_REQUESTED_V1.name().equals(outbox.getEventType()))
			.map(this::operationUidFrom)
			.toList();
	}

	private UUID operationUidFrom(Outbox outbox) {
		try {
			JsonNode root = objectMapper.readTree(outbox.getPayload());
			return UUID.fromString(root.path("payload").path("operation_uid").asText());
		} catch (Exception exception) {
			throw new AssertionError("payment operation outbox payload should be readable", exception);
		}
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
		int attemptCount,
		Instant nextAttemptAt,
		Instant lastEnqueuedAt,
		String leaseOwner,
		Instant leaseExpiresAt
	) {
		jdbc.update("""
			INSERT INTO payment_operation (
			  operation_uid, reservation_id, requester_member_id, operation_type, status,
			  payment_key, expected_amount, provider_idempotency_key, deduplication_key,
			  attempt_count, next_attempt_at, last_enqueued_at, lease_owner, lease_expires_at,
			  completed_at, version, created_at, updated_at
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, 'CONFIRM', ?,
			  ?, 100000, ?, ?,
			  ?, ?, ?, ?, ?,
			  ?, 0, ?, ?
			)
			""",
			operationUid.toString(), reservationId, memberId, status.name(),
			"payment-key-" + operationUid, "provider-" + operationUid, "CONFIRM:" + operationUid,
			attemptCount, timestamp(nextAttemptAt), timestamp(lastEnqueuedAt), leaseOwner,
			timestamp(leaseExpiresAt), status.isTerminal() ? timestamp(NOW.minusSeconds(1)) : null,
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
		jdbc.update("DELETE FROM payment_operation");
		jdbc.update("DELETE FROM reservation_history");
		jdbc.update("DELETE FROM member_coupon");
		jdbc.update("DELETE FROM reservation");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM member");
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
				Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofSeconds(10));
		}

		@Bean
		ObjectMapper paymentRecoveryObjectMapper() {
			return new ObjectMapper()
				.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
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
