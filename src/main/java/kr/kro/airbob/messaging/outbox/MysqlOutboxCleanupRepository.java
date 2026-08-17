package kr.kro.airbob.messaging.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

public class MysqlOutboxCleanupRepository implements OutboxCleanupRepository {

	static final String DELETE_OLDEST_BATCH_SQL = """
		DELETE FROM outbox
		WHERE occurred_at < ?
		ORDER BY occurred_at ASC, id ASC
		LIMIT ?
		""";

	private static final String READ_BACKLOG_SQL = """
		SELECT COUNT(*) AS message_count, MIN(occurred_at) AS oldest_occurred_at
		FROM outbox
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

	@Override
	public OutboxBacklogSnapshot readBacklogSnapshot() {
		return jdbcTemplate.queryForObject(READ_BACKLOG_SQL, (resultSet, rowNumber) -> {
			long messageCount = resultSet.getLong("message_count");
			Timestamp oldestOccurredAt = resultSet.getTimestamp("oldest_occurred_at");
			return new OutboxBacklogSnapshot(
				messageCount,
				Optional.ofNullable(oldestOccurredAt).map(Timestamp::toInstant)
			);
		});
	}
}
