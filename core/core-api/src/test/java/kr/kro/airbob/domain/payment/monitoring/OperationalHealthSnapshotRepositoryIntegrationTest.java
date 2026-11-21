package kr.kro.airbob.domain.payment.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionAction;
import kr.kro.airbob.messaging.outbox.monitoring.JdbcOutboxHealthSnapshotRepository;
import kr.kro.airbob.messaging.outbox.monitoring.OutboxHealthSnapshot;

@Testcontainers
class OperationalHealthSnapshotRepositoryIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbob_operational_health")
		.withUsername("test")
		.withPassword("test")
		.withUrlParam("connectionTimeZone", "UTC")
		.withUrlParam("forceConnectionTimeZoneToSession", "true");

	private JdbcTemplate jdbc;
	private JdbcPaymentOperationHealthSnapshotRepository paymentRepository;
	private JdbcOutboxHealthSnapshotRepository outboxRepository;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		createTables();
		jdbc.update("DELETE FROM payment_operation_resolution");
		jdbc.update("DELETE FROM payment_operation");
		jdbc.update("DELETE FROM outbox");
		paymentRepository = new JdbcPaymentOperationHealthSnapshotRepository(jdbc);
		outboxRepository = new JdbcOutboxHealthSnapshotRepository(jdbc);
	}

	@Test
	void readsPaymentBacklogsAndClosedResolutionCountsWithTwoAggregateQueries() {
		long firstManual = insertOperation("MANUAL_REVIEW", NOW.minusSeconds(200), null,
			NOW.minusSeconds(120), false);
		insertOperation("MANUAL_REVIEW", NOW.minusSeconds(100), null,
			NOW.minusSeconds(60), false);
		long pending = insertOperation("WAITING_RETRY", NOW.minusSeconds(5), null,
			null, true);
		insertOperation("QUEUED", NOW.minusSeconds(31), null, null, false);
		insertOperation("QUEUED", NOW.minusSeconds(29), null, null, false);
		insertOperation("EXECUTING", NOW.minusSeconds(20), NOW.minusSeconds(1), null, false);
		insertOperation("EXECUTING", NOW.minusSeconds(20), NOW, null, false);
		insertOperation("EXECUTING", NOW.minusSeconds(20), NOW.plusSeconds(1), null, false);
		insertResolution(firstManual, PaymentOperationResolutionAction.MARKED_NOT_PAID,
			NOW.minusSeconds(110));
		insertResolution(pending, PaymentOperationResolutionAction.RECONCILIATION_REQUESTED,
			NOW.minusSeconds(100));
		insertResolution(pending, PaymentOperationResolutionAction.RECONCILIATION_REQUESTED,
			NOW.minusSeconds(90));

		PaymentOperationHealthSnapshot snapshot = paymentRepository.readSnapshot(
			NOW, Duration.ofSeconds(30));

		assertThat(snapshot.manualReviewCount()).isEqualTo(2);
		assertThat(snapshot.oldestManualReviewAt()).contains(NOW.minusSeconds(120));
		assertThat(snapshot.reconciliationPendingCount()).isEqualTo(1);
		assertThat(snapshot.oldestReconciliationPendingAt()).contains(NOW.minusSeconds(90));
		assertThat(snapshot.staleQueuedCount()).isEqualTo(1);
		assertThat(snapshot.oldestStaleQueuedAt()).contains(NOW.minusSeconds(31));
		assertThat(snapshot.expiredExecutingLeaseCount()).isEqualTo(2);
		assertThat(snapshot.resolutionCounts())
			.containsEntry(PaymentOperationResolutionAction.RECONCILIATION_REQUESTED, 2L)
			.containsEntry(PaymentOperationResolutionAction.MARKED_NOT_PAID, 1L);
	}

	@Test
	void readsOutboxCountAndOldestTimestampWithoutPayloadOrKeys() {
		assertThat(outboxRepository.readSnapshot(NOW).oldestOccurredAt()).isEmpty();
		insertOutbox(NOW.minusSeconds(45));
		insertOutbox(NOW.minusSeconds(5));

		OutboxHealthSnapshot snapshot = outboxRepository.readSnapshot(NOW);

		assertThat(snapshot.retainedRowCount()).isEqualTo(2);
		assertThat(snapshot.oldestOccurredAt()).contains(NOW.minusSeconds(45));
	}

	private void createTables() {
		jdbc.execute("""
			CREATE TABLE IF NOT EXISTS payment_operation (
			  id bigint NOT NULL AUTO_INCREMENT,
			  operation_ref varchar(36) NOT NULL,
			  status varchar(30) NOT NULL,
			  queued_at datetime(6) NOT NULL,
			  lease_expires_at datetime(6) DEFAULT NULL,
			  review_required_at datetime(6) DEFAULT NULL,
			  manual_reconciliation_pending boolean NOT NULL DEFAULT false,
			  PRIMARY KEY (id),
			  UNIQUE KEY uk_payment_operation_ref (operation_ref)
			) ENGINE=InnoDB
			""");
		jdbc.execute("""
			CREATE TABLE IF NOT EXISTS payment_operation_resolution (
			  id bigint NOT NULL AUTO_INCREMENT,
			  payment_operation_id bigint NOT NULL,
			  resolution_action varchar(50) NOT NULL,
			  created_at datetime(6) NOT NULL,
			  PRIMARY KEY (id),
			  KEY idx_resolution_operation_created (payment_operation_id, created_at)
			) ENGINE=InnoDB
			""");
		jdbc.execute("""
			CREATE TABLE IF NOT EXISTS outbox (
			  id bigint NOT NULL AUTO_INCREMENT,
			  event_id varchar(36) NOT NULL,
			  occurred_at datetime(6) NOT NULL,
			  payload text NOT NULL,
			  PRIMARY KEY (id)
			) ENGINE=InnoDB
			""");
	}

	private long insertOperation(
		String status,
		Instant queuedAt,
		Instant leaseExpiresAt,
		Instant reviewRequiredAt,
		boolean reconciliationPending
	) {
		String operationRef = UUID.randomUUID().toString();
		jdbc.update("""
			INSERT INTO payment_operation (
			  operation_ref, status, queued_at, lease_expires_at, review_required_at,
			  manual_reconciliation_pending
			) VALUES (?, ?, ?, ?, ?, ?)
			""",
			operationRef,
			status,
			Timestamp.from(queuedAt),
			leaseExpiresAt == null ? null : Timestamp.from(leaseExpiresAt),
			reviewRequiredAt == null ? null : Timestamp.from(reviewRequiredAt),
			reconciliationPending
		);
		return jdbc.queryForObject(
			"SELECT id FROM payment_operation WHERE operation_ref = ?",
			Long.class,
			operationRef
		);
	}

	private void insertResolution(
		long operationId,
		PaymentOperationResolutionAction action,
		Instant createdAt
	) {
		jdbc.update("""
			INSERT INTO payment_operation_resolution (
			  payment_operation_id, resolution_action, created_at
			) VALUES (?, ?, ?)
			""", operationId, action.name(), Timestamp.from(createdAt));
	}

	private void insertOutbox(Instant occurredAt) {
		jdbc.update("""
			INSERT INTO outbox (event_id, occurred_at, payload)
			VALUES (?, ?, '{}')
			""", UUID.randomUUID().toString(), Timestamp.from(occurredAt));
	}
}
