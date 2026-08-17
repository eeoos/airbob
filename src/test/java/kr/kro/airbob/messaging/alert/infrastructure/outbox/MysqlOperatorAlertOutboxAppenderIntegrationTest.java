package kr.kro.airbob.messaging.alert.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.messaging.alert.application.OperatorAlertEnqueueService;
import kr.kro.airbob.messaging.alert.application.OperatorAlertOutboxPublisher;
import kr.kro.airbob.messaging.alert.application.OperatorAlertPublication;
import kr.kro.airbob.messaging.alert.application.OperatorAlertRequest;
import kr.kro.airbob.messaging.alert.event.OperatorAlertKind;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSummaryCode;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;

@Testcontainers
@SpringJUnitConfig(MysqlOperatorAlertOutboxAppenderIntegrationTest.TestConfiguration.class)
@DisplayName("operator alert atomic outbox append")
class MysqlOperatorAlertOutboxAppenderIntegrationTest {

	private static final UUID SUBJECT_UID =
		UUID.fromString("4929fed6-2ff1-4867-b099-59311ef194a1");
	private static final UUID OCCURRENCE_UID =
		UUID.fromString("55eacfc3-cce9-470b-88f1-4df081320322");

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbob_operator_alert")
		.withUsername("airbob")
		.withPassword("airbob")
		.withUrlParam("connectionTimeZone", "UTC")
		.withUrlParam("forceConnectionTimeZoneToSession", "true");

	private final JdbcTemplate jdbcTemplate;
	private final OperatorAlertEnqueueService enqueueService;
	private final OperatorAlertOutboxPublisher publisher;
	private final PlatformTransactionManager transactionManager;
	private final IntegrationEventCodec codec;
	private final Clock clock;

	@Autowired
	MysqlOperatorAlertOutboxAppenderIntegrationTest(
		JdbcTemplate jdbcTemplate,
		OperatorAlertEnqueueService enqueueService,
		OperatorAlertOutboxPublisher publisher,
		PlatformTransactionManager transactionManager,
		IntegrationEventCodec codec,
		Clock clock
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.enqueueService = enqueueService;
		this.publisher = publisher;
		this.transactionManager = transactionManager;
		this.codec = codec;
		this.clock = clock;
	}

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS outbox (
			  id bigint NOT NULL AUTO_INCREMENT,
			  event_id varchar(36) NOT NULL,
			  destination varchar(255) NOT NULL,
			  partition_key varchar(255) NOT NULL,
			  aggregate_type varchar(255) NOT NULL,
			  aggregate_id varchar(255) NOT NULL,
			  event_type varchar(255) NOT NULL,
			  event_version varchar(30) NOT NULL,
			  payload text NOT NULL,
			  occurred_at datetime(6) NOT NULL,
			  deduplication_key varchar(255) DEFAULT NULL,
			  created_at datetime(6) NOT NULL,
			  updated_at datetime(6) NOT NULL,
			  PRIMARY KEY (id),
			  UNIQUE KEY uk_outbox_event_id (event_id),
			  UNIQUE KEY uk_outbox_deduplication_key (deduplication_key)
			) ENGINE=InnoDB
			""");
		jdbcTemplate.update("DELETE FROM outbox");
	}

	@Test
	void concurrentSameOccurrenceCallsBothCompleteAndConvergeToOneRow() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			List<Future<OperatorAlertPublication>> calls = List.of(
				executor.submit(() -> enqueueTogether(ready, start)),
				executor.submit(() -> enqueueTogether(ready, start))
			);
			ready.await();
			start.countDown();

			List<OperatorAlertPublication> results = calls.stream().map(this::get).toList();

			assertThat(results).extracting(OperatorAlertPublication::alertUid)
				.containsOnly(results.getFirst().alertUid());
			assertThat(results).extracting(OperatorAlertPublication::appended)
				.containsExactlyInAnyOrder(true, false);
		}
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM outbox", Integer.class)).isEqualTo(1);
	}

	@Test
	void surroundingTransactionRollbackRemovesTheAlertRow() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		transaction.executeWithoutResult(status -> {
			publisher.append(request());
			status.setRollbackOnly();
		});

		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
	}

	@Test
	void envelopeAndDatetimeSixStoreTheSameMicrosecondTimestamp() {
		enqueueService.enqueue(request());

		Timestamp databaseTimestamp = jdbcTemplate.queryForObject(
			"SELECT occurred_at FROM outbox", Timestamp.class);
		String payload = jdbcTemplate.queryForObject(
			"SELECT payload FROM outbox", String.class);
		EventEnvelope<OperatorAlertRequestedV1> envelope = codec.decode(
			payload, OperatorAlertRequestedV1.DESCRIPTOR, OperatorAlertRequestedV1.class);
		Instant expected = Instant.parse("2026-08-17T00:00:00.123456Z");

		assertThat(databaseTimestamp.toInstant()).isEqualTo(expected);
		assertThat(envelope.occurredAt()).isEqualTo(expected);
	}

	@Test
	void eventIdCollisionWithDifferentDedupeIdentityIsNotSilentlyIgnored() {
		UUID fixedEventId = UUID.fromString("de4b6371-a2ee-4fe5-9f54-ad0190cd74af");
		MysqlOperatorAlertOutboxAppender fixedIdAppender =
			new MysqlOperatorAlertOutboxAppender(
				jdbcTemplate, codec, clock, () -> fixedEventId);
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		OperatorAlertRequestedV1 first = event(OCCURRENCE_UID);
		OperatorAlertRequestedV1 differentDedupe = event(UUID.randomUUID());

		transaction.executeWithoutResult(status ->
			assertThat(fixedIdAppender.appendIfAbsent(first)).isTrue());

		assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
			fixedIdAppender.appendIfAbsent(differentDedupe)))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM outbox", Integer.class)).isEqualTo(1);
	}

	private OperatorAlertPublication enqueueTogether(
		CountDownLatch ready,
		CountDownLatch start
	) throws InterruptedException {
		ready.countDown();
		start.await();
		return enqueueService.enqueue(request());
	}

	private OperatorAlertPublication get(Future<OperatorAlertPublication> call) {
		try {
			return call.get();
		} catch (Exception exception) {
			throw new AssertionError("concurrent enqueue failed", exception);
		}
	}

	private OperatorAlertRequest request() {
		return new OperatorAlertRequest(
			OperatorAlertKind.PAYMENT_OPERATION_QUARANTINED,
			SUBJECT_UID,
			OperatorAlertSummaryCode.MESSAGE_PROCESSING_FAILED,
			new OperatorAlertSourcePosition("PAYMENT_OPERATION.events", 0, 11L),
			OCCURRENCE_UID
		);
	}

	private OperatorAlertRequestedV1 event(UUID occurrenceUid) {
		return OperatorAlertRequestedV1.create(
			OperatorAlertKind.PAYMENT_OPERATION_QUARANTINED,
			SUBJECT_UID,
			OperatorAlertSummaryCode.MESSAGE_PROCESSING_FAILED,
			OperatorAlertSourcePosition.none(),
			occurrenceUid
		);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement(proxyTargetClass = true)
	static class TestConfiguration {
		@Bean
		DataSource dataSource() {
			return new DriverManagerDataSource(
				MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
		}

		@Bean
		JdbcTemplate jdbcTemplate(DataSource dataSource) {
			return new JdbcTemplate(dataSource);
		}

		@Bean
		PlatformTransactionManager transactionManager(DataSource dataSource) {
			return new DataSourceTransactionManager(dataSource);
		}

		@Bean
		Clock clock() {
			return Clock.fixed(
				Instant.parse("2026-08-17T00:00:00.123456789Z"), ZoneOffset.UTC);
		}

		@Bean
		IntegrationEventCodec codec() {
			return new IntegrationEventCodec(new ObjectMapper().findAndRegisterModules());
		}

		@Bean
		MysqlOperatorAlertOutboxAppender appender(
			JdbcTemplate jdbcTemplate,
			IntegrationEventCodec codec,
			Clock clock
		) {
			return new MysqlOperatorAlertOutboxAppender(jdbcTemplate, codec, clock);
		}

		@Bean
		OperatorAlertOutboxPublisher publisher(MysqlOperatorAlertOutboxAppender appender) {
			return new OperatorAlertOutboxPublisher(appender);
		}

		@Bean
		OperatorAlertEnqueueService enqueueService(OperatorAlertOutboxPublisher publisher) {
			return new OperatorAlertEnqueueService(publisher);
		}
	}
}
