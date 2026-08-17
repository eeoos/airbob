package kr.kro.airbob.messaging.outbox.infrastructure.jdbc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;

import kr.kro.airbob.messaging.outbox.application.OutboxCleanupRepository;

public class MysqlOutboxCleanupRepository implements OutboxCleanupRepository {

	static final String DELETE_OLDEST_BATCH_SQL = """
		DELETE FROM outbox
		WHERE occurred_at < ?
		ORDER BY occurred_at ASC, id ASC
		LIMIT ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public MysqlOutboxCleanupRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public int deleteOldestBefore(Instant cutoffExclusive, int batchSize) {
		Objects.requireNonNull(cutoffExclusive, "cutoffExclusive must not be null");
		if (batchSize <= 0) {
			throw new IllegalArgumentException("batchSize must be positive");
		}
		return jdbcTemplate.update(
			DELETE_OLDEST_BATCH_SQL,
			Timestamp.from(cutoffExclusive),
			batchSize
		);
	}
}
