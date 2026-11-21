package kr.kro.airbob.messaging.outbox.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MysqlOutboxCleanupRepositoryIntegrationTest {

	private static final Instant CUTOFF = Instant.parse("2026-08-01T00:00:00Z");
	private static final Instant MARKER_UPDATED_AT = Instant.parse("2025-01-01T00:00:00Z");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbob_outbox_cleanup")
		.withUsername("test")
		.withPassword("test")
		.withUrlParam("connectionTimeZone", "UTC")
		.withUrlParam("forceConnectionTimeZoneToSession", "true");

	private JdbcTemplate jdbcTemplate;
	private MysqlOutboxCleanupRepository repository;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS outbox (
			  id bigint NOT NULL AUTO_INCREMENT,
			  event_id varchar(36) NOT NULL,
			  occurred_at datetime(6) NOT NULL,
			  updated_at datetime(6) NOT NULL,
			  payload text NOT NULL,
			  PRIMARY KEY (id),
			  UNIQUE KEY uk_outbox_event_id (event_id),
			  KEY idx_outbox_cleanup (occurred_at, id)
			) ENGINE=InnoDB
			""");
		jdbcTemplate.update("DELETE FROM outbox");
		repository = new MysqlOutboxCleanupRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("보관 기한보다 오래된 행을 occurred_at, id 순서로 한 배치만 삭제한다")
	void deletesOneOrderedBatchAndPreservesNewMessages() {
		long oldestId = insert(CUTOFF.minusSeconds(100));
		long sameTimestampFirstId = insert(CUTOFF.minusSeconds(50));
		long sameTimestampSecondId = insert(CUTOFF.minusSeconds(50));
		long stillOldButOutsideBatchId = insert(CUTOFF.minusSeconds(10));
		long atCutoffId = insert(CUTOFF);
		long newId = insert(CUTOFF.plusSeconds(1));

		int deleted = repository.deleteOldestBefore(CUTOFF, 3);

		assertThat(deleted).isEqualTo(3);
		assertThat(remainingIds()).containsExactly(stillOldButOutsideBatchId, atCutoffId, newId);
		assertThat(remainingIds()).doesNotContain(oldestId, sameTimestampFirstId, sameTimestampSecondId);
	}

	@Test
	@DisplayName("삭제할 행이 없으면 0을 반환하고 반복 실행해도 결과가 같다")
	void emptyAndRepeatedCleanupAreIdempotent() {
		long oldId = insert(CUTOFF.minusSeconds(1));

		assertThat(repository.deleteOldestBefore(CUTOFF, 10)).isEqualTo(1);
		assertThat(repository.deleteOldestBefore(CUTOFF, 10)).isZero();
		assertThat(repository.deleteOldestBefore(CUTOFF, 10)).isZero();
		assertThat(remainingIds()).doesNotContain(oldId);
	}

	@Test
	@DisplayName("cleanup은 보존되는 outbox 행을 갱신하지 않는다")
	void neverUpdatesRetainedRows() {
		long retainedId = insert(CUTOFF.plusSeconds(1));

		repository.deleteOldestBefore(CUTOFF, 10);

		Timestamp updatedAt = jdbcTemplate.queryForObject(
			"SELECT updated_at FROM outbox WHERE id = ?", Timestamp.class, retainedId);
		assertThat(updatedAt.toInstant()).isEqualTo(MARKER_UPDATED_AT);
	}

	private long insert(Instant occurredAt) {
		String eventId = UUID.randomUUID().toString();
		jdbcTemplate.update("""
			INSERT INTO outbox (event_id, occurred_at, updated_at, payload)
			VALUES (?, ?, ?, '{}')
			""", eventId, Timestamp.from(occurredAt), Timestamp.from(MARKER_UPDATED_AT));
		return jdbcTemplate.queryForObject(
			"SELECT id FROM outbox WHERE event_id = ?", Long.class, eventId);
	}

	private List<Long> remainingIds() {
		return jdbcTemplate.queryForList("SELECT id FROM outbox ORDER BY id", Long.class);
	}
}
