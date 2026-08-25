package kr.kro.airbob.messaging.outbox.monitoring;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOutboxHealthSnapshotRepository implements OutboxHealthSnapshotRepository {

	static final String READ_HEALTH_SQL = """
		SELECT COUNT(*) AS retained_row_count, MIN(occurred_at) AS oldest_occurred_at
		FROM outbox
		""";

	private final JdbcTemplate jdbcTemplate;

	public JdbcOutboxHealthSnapshotRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@Transactional(readOnly = true)
	public OutboxHealthSnapshot readSnapshot(Instant observedAt) {
		Objects.requireNonNull(observedAt, "observedAt must not be null");
		return jdbcTemplate.queryForObject(READ_HEALTH_SQL, (resultSet, rowNumber) -> {
			Timestamp oldest = resultSet.getTimestamp("oldest_occurred_at");
			return new OutboxHealthSnapshot(
				observedAt,
				resultSet.getLong("retained_row_count"),
				Optional.ofNullable(oldest).map(Timestamp::toInstant)
			);
		});
	}
}
