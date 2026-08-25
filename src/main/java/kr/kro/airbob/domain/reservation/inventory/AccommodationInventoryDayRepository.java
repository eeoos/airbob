package kr.kro.airbob.domain.reservation.inventory;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import kr.kro.airbob.domain.reservation.exception.ReservationInventoryBusyException;

@Repository
public class AccommodationInventoryDayRepository {

	private static final String SELECT_COLUMNS = """
		SELECT accommodation_id, stay_date, state, reservation_id, hold_expires_at
		FROM accommodation_inventory_day FORCE INDEX (PRIMARY)
		WHERE accommodation_id = ?
		  AND stay_date >= ?
		  AND stay_date < ?
		ORDER BY stay_date
		""";
	private static final String UPSERT_MISSING_FREE_DAY = """
		INSERT INTO accommodation_inventory_day (
		  accommodation_id, stay_date, state, reservation_id, hold_expires_at
		) VALUES (?, ?, 'FREE', NULL, NULL)
		ON DUPLICATE KEY UPDATE stay_date = VALUES(stay_date)
		""";
	private static final String DELETE_PAST_FREE_DAYS = """
		DELETE FROM accommodation_inventory_day
		WHERE state = 'FREE'
		  AND stay_date < ?
		ORDER BY stay_date, accommodation_id
		LIMIT ?
		""";
	private static final String CLAIM_AVAILABLE_RANGE = """
		UPDATE accommodation_inventory_day FORCE INDEX (PRIMARY)
		SET state = ?, reservation_id = ?, hold_expires_at = ?
		WHERE accommodation_id = ?
		  AND stay_date >= ?
		  AND stay_date < ?
		  AND (
		    state = 'FREE'
		    OR (state = 'HOLD' AND hold_expires_at <= ?)
		  )
		""";
	private static final String TRANSITION_EXACT_OWNER = """
		UPDATE accommodation_inventory_day FORCE INDEX (PRIMARY)
		SET state = ?, hold_expires_at = NULL
		WHERE accommodation_id = ?
		  AND stay_date >= ?
		  AND stay_date < ?
		  AND reservation_id = ?
		  AND state = ?
		""";
	private static final String RELEASE_EXACT_OWNER = """
		UPDATE accommodation_inventory_day FORCE INDEX (PRIMARY)
		SET state = 'FREE', reservation_id = NULL, hold_expires_at = NULL
		WHERE accommodation_id = ?
		  AND stay_date >= ?
		  AND stay_date < ?
		  AND reservation_id = ?
		  AND state = ?
		""";

	private final JdbcTemplate jdbcTemplate;

	public AccommodationInventoryDayRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void seedMissingDays(Long accommodationId, List<LocalDate> missingDays) {
		List<Object[]> arguments = missingDays.stream()
			.map(date -> new Object[] {accommodationId, Date.valueOf(date)})
			.toList();
		if (arguments.isEmpty()) {
			return;
		}
		jdbcTemplate.batchUpdate(UPSERT_MISSING_FREE_DAY, arguments);
	}

	public int deletePastFreeDays(LocalDate cutoffExclusive, int limit) {
		return jdbcTemplate.update(
			DELETE_PAST_FREE_DAYS,
			Date.valueOf(cutoffExclusive),
			limit
		);
	}

	public List<AccommodationInventoryDay> findSnapshot(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive
	) {
		return queryRange(SELECT_COLUMNS, accommodationId, startInclusive, endExclusive);
	}

	public List<AccommodationInventoryDay> lockRangeNowait(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive
	) {
		try {
			return queryRange(
				SELECT_COLUMNS + " FOR UPDATE NOWAIT",
				accommodationId,
				startInclusive,
				endExclusive
			);
		} catch (DataAccessException exception) {
			if (MysqlNowaitFailureClassifier.isNowait(exception)) {
				throw new ReservationInventoryBusyException(exception);
			}
			throw exception;
		}
	}

	public List<AccommodationInventoryDay> lockRange(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive
	) {
		return queryRange(
			SELECT_COLUMNS + " FOR UPDATE",
			accommodationId,
			startInclusive,
			endExclusive
		);
	}

	public int claimAvailableRange(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive,
		Instant decisionAt,
		Long reservationId,
		AccommodationInventoryState targetState,
		Instant holdExpiresAt
	) {
		return jdbcTemplate.update(
			CLAIM_AVAILABLE_RANGE,
			targetState.name(),
			reservationId,
			timestamp(holdExpiresAt),
			accommodationId,
			Date.valueOf(startInclusive),
			Date.valueOf(endExclusive),
			Timestamp.from(decisionAt)
		);
	}

	public int transitionExactOwner(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive,
		Long reservationId,
		AccommodationInventoryState expectedState,
		AccommodationInventoryState targetState
	) {
		return jdbcTemplate.update(
			TRANSITION_EXACT_OWNER,
			targetState.name(),
			accommodationId,
			Date.valueOf(startInclusive),
			Date.valueOf(endExclusive),
			reservationId,
			expectedState.name()
		);
	}

	public int releaseExactOwner(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive,
		Long reservationId,
		AccommodationInventoryState expectedState
	) {
		return jdbcTemplate.update(
			RELEASE_EXACT_OWNER,
			accommodationId,
			Date.valueOf(startInclusive),
			Date.valueOf(endExclusive),
			reservationId,
			expectedState.name()
		);
	}

	private List<AccommodationInventoryDay> queryRange(
		String sql,
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive
	) {
		return jdbcTemplate.query(
			sql,
			(resultSet, rowNumber) -> new AccommodationInventoryDay(
				resultSet.getLong("accommodation_id"),
				resultSet.getObject("stay_date", LocalDate.class),
				AccommodationInventoryState.valueOf(resultSet.getString("state")),
				resultSet.getObject("reservation_id", Long.class),
				instant(resultSet.getTimestamp("hold_expires_at"))
			),
			accommodationId,
			Date.valueOf(startInclusive),
			Date.valueOf(endExclusive)
		);
	}

	private static Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
