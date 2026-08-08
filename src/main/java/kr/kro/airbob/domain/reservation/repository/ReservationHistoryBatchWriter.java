package kr.kro.airbob.domain.reservation.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationContext;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationContextHolder;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;

@Repository
public class ReservationHistoryBatchWriter {

	private static final String INSERT_SQL = """
		INSERT INTO reservation_history (
			reservation_id, reservation_uid, reservation_code, accommodation_id, guest_id,
			check_in, check_out, guest_count, total_price, currency, status, message,
			expires_at, created_at, created_by, history_created_at, history_created_by,
			change_type, change_reason, source_system, client_ip
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		""";

	private final JdbcTemplate jdbcTemplate;
	private final int batchSize;

	public ReservationHistoryBatchWriter(
		JdbcTemplate jdbcTemplate,
		@Value("${reservation.expiration.history-batch-size:100}") int batchSize
	) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("history batch size must be positive");
		}
		this.jdbcTemplate = jdbcTemplate;
		this.batchSize = batchSize;
	}

	public void writeAll(List<ReservationHistory> histories, LocalDateTime historyCreatedAt) {
		Objects.requireNonNull(histories, "histories must not be null");
		Objects.requireNonNull(historyCreatedAt, "historyCreatedAt must not be null");

		for (int start = 0; start < histories.size(); start += batchSize) {
			List<ReservationHistory> chunk = histories.subList(start, Math.min(start + batchSize, histories.size()));
			int[] updateCounts = jdbcTemplate.batchUpdate(
				INSERT_SQL,
				batchSetter(chunk, historyCreatedAt)
			);
			recordSuccessfulBatch(chunk.size(), affectedRows(updateCounts, chunk.size()));
		}
	}

	private BatchPreparedStatementSetter batchSetter(
		List<ReservationHistory> histories,
		LocalDateTime historyCreatedAt
	) {
		return new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement statement, int index) throws SQLException {
				bind(statement, histories.get(index), historyCreatedAt);
			}

			@Override
			public int getBatchSize() {
				return histories.size();
			}
		};
	}

	private void bind(
		PreparedStatement statement,
		ReservationHistory history,
		LocalDateTime historyCreatedAt
	) throws SQLException {
		statement.setLong(1, history.getReservationId());
		statement.setString(2, history.getReservationUid());
		statement.setString(3, history.getReservationCode());
		setNullableLong(statement, 4, history.getAccommodationId());
		setNullableLong(statement, 5, history.getGuestId());
		setNullableTimestamp(statement, 6, history.getCheckIn());
		setNullableTimestamp(statement, 7, history.getCheckOut());
		setNullableInteger(statement, 8, history.getGuestCount());
		setNullableLong(statement, 9, history.getTotalPrice());
		statement.setString(10, history.getCurrency());
		statement.setString(11, history.getStatus() == null ? null : history.getStatus().name());
		statement.setString(12, history.getMessage());
		setNullableTimestamp(statement, 13, history.getExpiresAt());
		setNullableTimestamp(statement, 14, history.getCreatedAt());
		setNullableLong(statement, 15, history.getCreatedBy());
		statement.setTimestamp(16, Timestamp.valueOf(historyCreatedAt));
		statement.setNull(17, Types.BIGINT);
		statement.setString(18, history.getChangeType().name());
		statement.setString(19, history.getChangeReason());
		statement.setString(20, history.getSourceSystem());
		statement.setString(21, history.getClientIp());
	}

	private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
		if (value == null) {
			statement.setNull(index, Types.BIGINT);
		} else {
			statement.setLong(index, value);
		}
	}

	private void setNullableInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
		if (value == null) {
			statement.setNull(index, Types.INTEGER);
		} else {
			statement.setInt(index, value);
		}
	}

	private void setNullableTimestamp(PreparedStatement statement, int index, LocalDateTime value) throws SQLException {
		if (value == null) {
			statement.setNull(index, Types.TIMESTAMP);
		} else {
			statement.setTimestamp(index, Timestamp.valueOf(value));
		}
	}

	private Long affectedRows(int[] updateCounts, int submittedRows) {
		if (updateCounts.length != submittedRows) {
			throw new DataIntegrityViolationException("JDBC batch result count does not match submitted rows");
		}
		boolean unknown = false;
		long affectedRows = 0;
		for (int updateCount : updateCounts) {
			if (updateCount == Statement.EXECUTE_FAILED) {
				throw new DataIntegrityViolationException("reservation history batch failed");
			}
			if (updateCount == Statement.SUCCESS_NO_INFO) {
				unknown = true;
			} else if (updateCount < 0) {
				throw new DataIntegrityViolationException("unknown JDBC batch result: " + updateCount);
			} else {
				affectedRows = Math.addExact(affectedRows, updateCount);
			}
		}
		return unknown ? null : affectedRows;
	}

	private void recordSuccessfulBatch(int submittedRows, Long affectedRows) {
		BulkOperationContext context = BulkOperationContextHolder.getContext();
		if (context != null) {
			context.recordJdbcBatch(submittedRows, batchSize, affectedRows);
		}
	}
}
