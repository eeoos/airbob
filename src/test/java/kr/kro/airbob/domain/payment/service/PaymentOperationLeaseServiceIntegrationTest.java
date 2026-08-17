package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
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
import kr.kro.airbob.domain.payment.config.PaymentOperationProperties;
import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.messaging.alert.application.OperatorAlertOutboxAppender;
import kr.kro.airbob.messaging.alert.application.OperatorAlertOutboxPublisher;
import kr.kro.airbob.messaging.alert.application.OperatorAlertRequest;
import kr.kro.airbob.messaging.alert.event.OperatorAlertKind;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.messaging.alert.infrastructure.outbox.MysqlOperatorAlertOutboxAppender;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.outbox.JpaOutboxWriter;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	JpaAuditingConfig.class,
		QueryDslConfig.class,
		PaymentOperationLeaseService.class,
		PaymentOperationDltIncidentService.class,
		JpaOutboxWriter.class,
		MysqlOperatorAlertOutboxAppender.class,
		OperatorAlertOutboxPublisher.class,
		PaymentOperationLeaseServiceIntegrationTest.LeaseTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentOperationLeaseServiceIntegrationTest {

	private static final UUID OPERATION_UID = UUID.fromString("183e6e9c-a9ad-4e64-bb73-4fb6e359aa51");
	private static final UUID RESERVATION_UID = UUID.fromString("65fb488c-a7b5-4d9c-b099-d818eea0b8ee");
	private static final UUID ACCOMMODATION_UID = UUID.fromString("38679b45-6e79-4088-b2cb-4dd3835c195b");
	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_payment_lease");

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
	@Autowired private PaymentOperationLeaseService service;
	@Autowired private HoldingClaimTransaction holdingClaimTransaction;
	@Autowired private ControllableAlertOutboxAppender alertOutboxAppender;
	@Autowired private IntegrationEventCodec codec;
	@Autowired private PaymentOperationDltIncidentService dltIncidentService;

	@BeforeEach
	void insertReadyOperation() {
		alertOutboxAppender.fail(false);
		jdbc.update("DELETE FROM outbox");
		jdbc.update("DELETE FROM payment_operation");
		jdbc.update("DELETE FROM reservation");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM member");
		jdbc.update("INSERT INTO member (email, nickname, role, status, updated_at) VALUES (?, ?, 'MEMBER', 'ACTIVE', NOW(6))",
			"payment-lease@test.com", "payment-lease-member");
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
			  '2026-08-15 15:00:00', '2026-08-16 11:00:00', 'UTC', 2, 100000, 0,
			  'PAYMENT_PROCESSING', 'LEASE0001', NOW(6), '2026-08-14 01:00:00', NOW(6), 'KRW'
			)
			""", RESERVATION_UID.toString(), accommodationId, memberId);
		long reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbc.update("""
			INSERT INTO payment_operation (
			  operation_uid, reservation_id, requester_member_id, operation_type, status, next_action,
			  payment_key, expected_amount, provider_idempotency_key, deduplication_key,
			  dispatch_generation, attempt_count, next_attempt_at, queued_at,
			  manual_reconciliation_pending, manual_review_count, version, created_at, updated_at
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, 'CONFIRM', 'QUEUED', 'CONFIRM',
			  'payment-key', 100000, 'provider-key', ?,
			  1, 0, NULL, '2026-08-14 00:00:00',
			  false, 0, 0, NOW(6), NOW(6)
			)
			""", OPERATION_UID.toString(), reservationId, memberId, "CONFIRM:" + RESERVATION_UID);
	}

	@Test
	void manualReviewTransitionAndAlertCommitTogetherAndDuplicateClaimEmitsNothing() {
		jdbc.update("""
			UPDATE payment_operation
			SET attempt_count = 5
			WHERE operation_uid = UNHEX(REPLACE(?, '-', ''))
			""", OPERATION_UID.toString());

		assertThat(service.claim(OPERATION_UID, 1)).isEmpty();
		assertThat(service.claim(OPERATION_UID, 1)).isEmpty();

		Map<String, Object> operation = jdbc.queryForMap("""
			SELECT status, manual_review_count
			FROM payment_operation
			WHERE operation_uid = UNHEX(REPLACE(?, '-', ''))
			""", OPERATION_UID.toString());
		assertThat(operation)
			.containsEntry("status", "MANUAL_REVIEW")
			.containsEntry("manual_review_count", 1);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM outbox", Integer.class)).isOne();

		String payload = jdbc.queryForObject("SELECT payload FROM outbox", String.class);
		EventEnvelope<OperatorAlertRequestedV1> envelope = codec.decode(
			payload, OperatorAlertRequestedV1.DESCRIPTOR, OperatorAlertRequestedV1.class);
		assertThat(envelope.payload().kind()).isEqualTo(OperatorAlertKind.PAYMENT_MANUAL_REVIEW);
		assertThat(envelope.payload().subjectUid()).isEqualTo(OPERATION_UID);
		assertThat(envelope.payload().sourcePosition().present()).isFalse();
	}

	@Test
	void alertAppendFailureRollsBackManualReviewTransition() {
		jdbc.update("""
			UPDATE payment_operation
			SET attempt_count = 5
			WHERE operation_uid = UNHEX(REPLACE(?, '-', ''))
			""", OPERATION_UID.toString());
		alertOutboxAppender.fail(true);

		assertThatThrownBy(() -> service.claim(OPERATION_UID, 1))
			.isInstanceOf(DataAccessResourceFailureException.class);

		Map<String, Object> operation = jdbc.queryForMap("""
			SELECT status, manual_review_count
			FROM payment_operation
			WHERE operation_uid = UNHEX(REPLACE(?, '-', ''))
			""", OPERATION_UID.toString());
		assertThat(operation)
			.containsEntry("status", "QUEUED")
			.containsEntry("manual_review_count", 0);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
	}

	@Test
	void queuedDltIncidentAdvancesOneGenerationAndAtomicallyAppendsExecutionAndAlert() {
		var source = new kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition(
			PaymentOperationExecutionRequestedV1.TOPIC, 2, 41L);
		String message = executionMessage(1);

		dltIncidentService.record(message, source);
		dltIncidentService.record(message, source);

		Map<String, Object> operation = jdbc.queryForMap("""
			SELECT status, dispatch_generation, attempt_count
			FROM payment_operation
			WHERE operation_uid = UNHEX(REPLACE(?, '-', ''))
			""", OPERATION_UID.toString());
		assertThat(operation)
			.containsEntry("status", "QUEUED")
			.containsEntry("dispatch_generation", 2L)
			.containsEntry("attempt_count", 0);
		assertThat(jdbc.queryForList(
			"SELECT event_type FROM outbox ORDER BY id", String.class))
			.containsExactlyInAnyOrder(
				"PAYMENT_OPERATION_EXECUTION_REQUESTED",
				"OPERATOR_ALERT_REQUESTED");

		String executionPayload = jdbc.queryForObject("""
			SELECT payload FROM outbox
			WHERE event_type = 'PAYMENT_OPERATION_EXECUTION_REQUESTED'
			""", String.class);
		EventEnvelope<PaymentOperationExecutionRequestedV1> execution = codec.decode(
			executionPayload,
			PaymentOperationExecutionRequestedV1.DESCRIPTOR,
			PaymentOperationExecutionRequestedV1.class);
		assertThat(execution.payload().operationUid()).isEqualTo(OPERATION_UID);
		assertThat(execution.payload().reservationUid()).isEqualTo(RESERVATION_UID);
		assertThat(execution.payload().dispatchGeneration()).isEqualTo(2);
	}

	@Test
	void paymentDltAlertFailureRollsBackQueuedRedispatchAndBothOutboxRows() {
		alertOutboxAppender.fail(true);

		assertThatThrownBy(() -> dltIncidentService.record(
			executionMessage(1),
			new kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition(
				PaymentOperationExecutionRequestedV1.TOPIC, 1, 19L)))
			.isInstanceOf(DataAccessResourceFailureException.class);

		Map<String, Object> operation = jdbc.queryForMap("""
			SELECT status, dispatch_generation
			FROM payment_operation
			WHERE operation_uid = UNHEX(REPLACE(?, '-', ''))
			""", OPERATION_UID.toString());
		assertThat(operation)
			.containsEntry("status", "QUEUED")
			.containsEntry("dispatch_generation", 1L);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
	}

	@Test
	void mismatchedReservationUidDoesNotRedispatchButStillCommitsOneQuarantineAlert() {
		UUID mismatchedReservationUid =
			UUID.fromString("4052fe21-1bbf-48b9-b76d-25d373ff94f8");
		String mismatched = codec.encode(EventEnvelope.of(
			UUID.fromString("908ed7b0-f1a2-4cd8-b2b8-83c96c676ea2"),
			NOW,
			new PaymentOperationExecutionRequestedV1(
				OPERATION_UID, mismatchedReservationUid, 1)
		));

		dltIncidentService.record(
			mismatched,
			new kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition(
				PaymentOperationExecutionRequestedV1.TOPIC, 3, 88L));

		assertThat(jdbc.queryForMap("""
			SELECT status, dispatch_generation
			FROM payment_operation
			WHERE operation_uid = UNHEX(REPLACE(?, '-', ''))
			""", OPERATION_UID.toString()))
			.containsEntry("status", "QUEUED")
			.containsEntry("dispatch_generation", 1L);
		assertThat(jdbc.queryForList(
			"SELECT event_type FROM outbox", String.class))
			.containsExactly("OPERATOR_ALERT_REQUESTED");
	}

	@ParameterizedTest
	@ValueSource(strings = {"EXECUTING", "APPLIED"})
	void nonQueuedOperationIsNeverRedispatchedButStillCommitsOneQuarantineAlert(String status) {
		jdbc.update("""
			UPDATE payment_operation
			SET status = ?
			WHERE operation_uid = UNHEX(REPLACE(?, '-', ''))
			""", status, OPERATION_UID.toString());

		dltIncidentService.record(
			executionMessage(1),
			new kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition(
				PaymentOperationExecutionRequestedV1.TOPIC, 4, 101L));

		assertThat(jdbc.queryForMap("""
			SELECT status, dispatch_generation
			FROM payment_operation
			WHERE operation_uid = UNHEX(REPLACE(?, '-', ''))
			""", OPERATION_UID.toString()))
			.containsEntry("status", status)
			.containsEntry("dispatch_generation", 1L);
		assertThat(jdbc.queryForList(
			"SELECT event_type FROM outbox", String.class))
			.containsExactly("OPERATOR_ALERT_REQUESTED");
	}

	@Test
	void poisonDltCreatesOnlyDeterministicCoordinateAlertWithoutRetainingRawPayload() {
		var source = new kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition(
			PaymentOperationExecutionRequestedV1.TOPIC, 0, 7L);
		String poison = "not-json paymentKey=secret-provider-value";

		dltIncidentService.record(poison, source);
		dltIncidentService.record(poison, source);

		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM outbox", Integer.class)).isOne();
		String payload = jdbc.queryForObject("SELECT payload FROM outbox", String.class);
		assertThat(payload).doesNotContain(poison, "paymentKey", "secret-provider-value");
		EventEnvelope<OperatorAlertRequestedV1> envelope = codec.decode(
			payload, OperatorAlertRequestedV1.DESCRIPTOR, OperatorAlertRequestedV1.class);
		assertThat(envelope.payload().kind())
			.isEqualTo(OperatorAlertKind.PAYMENT_OPERATION_QUARANTINED);
		assertThat(envelope.payload().subjectUid()).isEqualTo(
			OperatorAlertRequest.paymentOperationQuarantined(null, source).subjectUid());
		assertThat(envelope.payload().sourcePosition()).isEqualTo(source);
	}

	private String executionMessage(long generation) {
		return codec.encode(EventEnvelope.of(
			UUID.fromString("908ed7b0-f1a2-4cd8-b2b8-83c96c676ea1"),
			NOW,
			new PaymentOperationExecutionRequestedV1(
				OPERATION_UID, RESERVATION_UID, generation)
		));
	}

	@Test
	void pessimisticLockSerializesTwoProxiedClaimsAndCommitsOneLease() throws Exception {
		assertThat(AopUtils.isAopProxy(service)).isTrue();
		assertThat(AopUtils.isAopProxy(holdingClaimTransaction)).isTrue();
		CountDownLatch firstClaimed = new CountDownLatch(1);
		CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			CompletableFuture<Optional<PaymentExecution>> first = CompletableFuture.supplyAsync(
				() -> holdingClaimTransaction.claimAndHold(
					OPERATION_UID, firstClaimed, releaseFirstTransaction), executor);
			assertThat(firstClaimed.await(5, TimeUnit.SECONDS)).isTrue();

			CompletableFuture<Optional<PaymentExecution>> second = CompletableFuture.supplyAsync(() -> {
				secondStarted.countDown();
				return service.claim(OPERATION_UID, 1);
			}, executor);
			assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);

			releaseFirstTransaction.countDown();
			PaymentExecution claimed = first.get(5, TimeUnit.SECONDS).orElseThrow();
			Optional<PaymentExecution> secondResult = second.get(5, TimeUnit.SECONDS);
			assertThat(secondResult).isEmpty();

			Map<String, Object> row = jdbc.queryForMap("""
				SELECT status, attempt_count, lease_owner
				FROM payment_operation
				WHERE operation_uid = UNHEX(REPLACE(?, '-', ''))
				""", OPERATION_UID.toString());
			assertThat(row)
				.containsEntry("status", "EXECUTING")
				.containsEntry("attempt_count", 1)
				.containsEntry("lease_owner", claimed.leaseOwner());
		} finally {
			releaseFirstTransaction.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class LeaseTestConfiguration {
		@Bean
		Clock paymentLeaseTestClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		PaymentOperationProperties paymentOperationProperties() {
			return new PaymentOperationProperties(
				Duration.ofSeconds(30), Duration.ofSeconds(10), 100, 5,
				Duration.ofSeconds(10), Duration.ofMinutes(5));
		}

		@Bean
		PaymentRetryBackoff paymentRetryBackoff(PaymentOperationProperties properties) {
			return new PaymentRetryBackoff(properties.retryInitialDelay(), properties.retryMaxDelay());
		}

		@Bean
		IntegrationEventCodec integrationEventCodec() {
			return new IntegrationEventCodec(new ObjectMapper().findAndRegisterModules());
		}

		@Bean
		@Primary
		ControllableAlertOutboxAppender controllableAlertOutboxAppender(
			MysqlOperatorAlertOutboxAppender delegate
		) {
			return new ControllableAlertOutboxAppender(delegate);
		}

		@Bean
		HoldingClaimTransaction holdingClaimTransaction(PaymentOperationLeaseService service) {
			return new HoldingClaimTransaction(service);
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

	static class HoldingClaimTransaction {
		private final PaymentOperationLeaseService service;

		HoldingClaimTransaction(PaymentOperationLeaseService service) {
			this.service = service;
		}

		@Transactional
		public Optional<PaymentExecution> claimAndHold(
			UUID operationUid,
			CountDownLatch claimed,
			CountDownLatch release
		) {
			Optional<PaymentExecution> result = service.claim(operationUid, 1);
			claimed.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("timed out waiting to release the first claim transaction");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while holding the first claim transaction", exception);
			}
			return result;
		}
	}
}
