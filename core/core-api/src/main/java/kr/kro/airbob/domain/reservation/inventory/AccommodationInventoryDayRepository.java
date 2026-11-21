package kr.kro.airbob.domain.reservation.inventory;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import kr.kro.airbob.domain.reservation.exception.ReservationInventoryBusyException;

@Repository
public class AccommodationInventoryDayRepository {

	public static final int MAX_SEED_INSERT_ROWS = 1_000;
	public static final int MAX_SEED_TARGETS = 1_000;

	private static final String SELECT_COLUMNS = """
		SELECT accommodation_id, stay_date, state, reservation_id, hold_expires_at
		FROM accommodation_inventory_day FORCE INDEX (PRIMARY)
		WHERE accommodation_id = ?
		  AND stay_date >= ?
		  AND stay_date < ?
		ORDER BY stay_date
		""";
	private static final String INSERT_MISSING_FREE_DAYS = """
		INSERT INTO accommodation_inventory_day (
		  accommodation_id, stay_date, state, reservation_id, hold_expires_at
		) VALUES
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
		for (int offset = 0; offset < missingDays.size(); offset += MAX_SEED_INSERT_ROWS) {
			seedMissingDayBatch(missingDays.subList(
					offset, Math.min(offset + MAX_SEED_INSERT_ROWS, missingDays.size()))
				.stream().map(date -> new SeedDay(accommodationId, date)).toList());
		}
	}

	/** One bounded statement, independent of the JDBC driver's batch-rewrite setting. */
	public void seedMissingDayBatch(List<SeedDay> missingDays) {
		if (missingDays.isEmpty()) {
			return;
		}
		if (missingDays.size() > MAX_SEED_INSERT_ROWS) {
			throw new IllegalArgumentException("inventory insert batch exceeds the row limit");
		}
		List<Object> arguments = new ArrayList<>(missingDays.size() * 2);
		for (SeedDay day : missingDays) {
			arguments.add(day.accommodationId());
			arguments.add(Date.valueOf(day.stayDate()));
		}
		jdbcTemplate.update(
			INSERT_MISSING_FREE_DAYS
				+ String.join(",", Collections.nCopies(missingDays.size(), "(?, ?, 'FREE', NULL, NULL)"))
				+ " ON DUPLICATE KEY UPDATE stay_date = accommodation_inventory_day.stay_date",
			arguments.toArray()
		);
	}

	/**
	 * A primary-key count inside an exact DATE range proves every date exists: each date can
	 * occur at most once. No owner/state rows need to cross the network for complete calendars.
	 */
	public Map<Long, Integer> countSeedDays(
		List<Long> accommodationIds, LocalDate startInclusive, LocalDate endExclusive
	) {
		if (accommodationIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, Integer> counts = new LinkedHashMap<>();
		jdbcTemplate.query(
			"SELECT accommodation_id, COUNT(*) AS day_count "
				+ seedRangeSql(accommodationIds)
				+ " GROUP BY accommodation_id",
			resultSet -> {
				counts.put(resultSet.getLong("accommodation_id"), resultSet.getInt("day_count"));
			},
			seedRangeArguments(accommodationIds, startInclusive, endExclusive)
		);
		return counts;
	}

	public List<SeedDay> findSeedDays(
		List<Long> accommodationIds, LocalDate startInclusive, LocalDate endExclusive
	) {
		if (accommodationIds.isEmpty()) {
			return List.of();
		}
		return jdbcTemplate.query(
			"SELECT accommodation_id, stay_date "
				+ seedRangeSql(accommodationIds)
				+ " ORDER BY accommodation_id, stay_date",
			(resultSet, rowNumber) -> new SeedDay(
				resultSet.getLong("accommodation_id"), resultSet.getObject("stay_date", LocalDate.class)),
			seedRangeArguments(accommodationIds, startInclusive, endExclusive)
		);
	}

	private String seedRangeSql(List<Long> accommodationIds) {
		if (accommodationIds.size() > MAX_SEED_TARGETS) {
			throw new IllegalArgumentException("inventory seed target batch exceeds the limit");
		}
		return "FROM accommodation_inventory_day FORCE INDEX (PRIMARY) WHERE accommodation_id IN ("
			+ String.join(",", Collections.nCopies(accommodationIds.size(), "?"))
			+ ") AND stay_date >= ? AND stay_date < ?";
	}

	private Object[] seedRangeArguments(
		List<Long> accommodationIds, LocalDate startInclusive, LocalDate endExclusive
	) {
		List<Object> arguments = new ArrayList<>(accommodationIds);
		arguments.add(Date.valueOf(startInclusive));
		arguments.add(Date.valueOf(endExclusive));
		return arguments.toArray();
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

	public record SeedDay(Long accommodationId, LocalDate stayDate) {
		public SeedDay {
			if (accommodationId == null || accommodationId <= 0) {
				throw new IllegalArgumentException("inventory accommodationId must be positive");
			}
			Objects.requireNonNull(stayDate, "inventory stayDate must not be null");
		}
	}
}
