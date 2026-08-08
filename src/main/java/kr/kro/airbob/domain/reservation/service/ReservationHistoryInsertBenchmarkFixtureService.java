package kr.kro.airbob.domain.reservation.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkVerification;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkHoldService.HoldRemoval;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkHoldService.HoldRemovalSnapshot;

@Service
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class ReservationHistoryInsertBenchmarkFixtureService {

	private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);
	private static final LocalDateTime CHECK_IN = LocalDateTime.of(2030, 1, 1, 15, 0);
	private static final LocalDateTime CHECK_OUT = LocalDateTime.of(2030, 1, 3, 11, 0);
	private static final LocalDateTime EXPIRED_AT = LocalDateTime.of(2000, 1, 1, 0, 0);
	private static final LocalDateTime FUTURE_EXPIRES_AT = LocalDateTime.of(2099, 1, 1, 0, 0);

	private final JdbcTemplate jdbcTemplate;

	public ReservationHistoryInsertBenchmarkFixtureService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Fixture createFixture(int datasetSize) {
		validateDatasetSize(datasetSize);
		assertNoExistingEligibleReservation();

		long memberId = insertMember();
		long accommodationId = insertAccommodation(memberId);
		List<ReservationExpectation> targets = new ArrayList<>(datasetSize);
		for (int index = 0; index < datasetSize; index++) {
			LocalDateTime targetCheckIn = CHECK_IN.plusDays(index * 3L);
			LocalDateTime targetCheckOut = CHECK_OUT.plusDays(index * 3L);
			targets.add(insertReservation(
				accommodationId,
				memberId,
				targetCheckIn,
				targetCheckOut,
				"PAYMENT_PENDING",
				"benchmark-target-" + index,
				EXPIRED_AT
			));
		}

		ReservationExpectation futurePending = insertReservation(
			accommodationId,
			memberId,
			CHECK_IN.minusDays(6),
			CHECK_OUT.minusDays(6),
			"PAYMENT_PENDING",
			"benchmark-future-control",
			FUTURE_EXPIRES_AT
		);
		ReservationExpectation nonPendingExpired = insertReservation(
			accommodationId,
			memberId,
			CHECK_IN.minusDays(3),
			CHECK_OUT.minusDays(3),
			"CONFIRMED",
			"benchmark-status-control",
			EXPIRED_AT
		);

		Fixture fixture = new Fixture(
			datasetSize,
			memberId,
			accommodationId,
			targets,
			futurePending,
			nonPendingExpired
		);
		assertOnlyFixtureTargetsAreEligible(fixture);
		return fixture;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public ReservationHistoryInsertBenchmarkVerification verify(
		Fixture fixture,
		HoldRemovalSnapshot holdSnapshot
	) {
		Objects.requireNonNull(fixture, "fixture must not be null");
		Objects.requireNonNull(holdSnapshot, "holdSnapshot must not be null");

		Map<Long, ReservationRow> reservations = findReservations(fixture.allReservationIds());
		List<HistoryRow> histories = findHistories(fixture.allReservationIds());
		Map<Long, List<HistoryRow>> historiesByReservation = historiesByReservation(histories);

		boolean targetReservationsExpired = fixture.targets().stream()
			.allMatch(target -> hasStatus(reservations, target.id(), "EXPIRED"));
		boolean futurePendingPreserved = controlPreserved(
			reservations,
			fixture.futurePending(),
			"PAYMENT_PENDING"
		);
		boolean nonPendingExpiredPreserved = controlPreserved(
			reservations,
			fixture.nonPendingExpired(),
			"CONFIRMED"
		);

		long targetHistoryCount = fixture.targets().stream()
			.mapToLong(target -> historiesByReservation.getOrDefault(target.id(), List.of()).size())
			.sum();
		boolean controlsHaveNoHistory = historiesByReservation
			.getOrDefault(fixture.futurePending().id(), List.of()).isEmpty()
			&& historiesByReservation
			.getOrDefault(fixture.nonPendingExpired().id(), List.of()).isEmpty();
		boolean targetHistoriesInserted = targetHistoryCount == fixture.datasetSize()
			&& fixture.targets().stream()
			.allMatch(target -> historiesByReservation.getOrDefault(target.id(), List.of()).size() == 1)
			&& controlsHaveNoHistory;

		long verifiedRows = fixture.targets().stream()
			.filter(target -> matchingHistory(target, historiesByReservation.get(target.id())))
			.count();
		boolean historySnapshotsPreserved = verifiedRows == fixture.datasetSize();
		boolean historyAuditContextPreserved = fixture.targets().stream()
			.allMatch(target -> hasSchedulerAuditContext(historiesByReservation.get(target.id())));
		boolean holdRemovalsMatched = holdRemovalCounts(expectedHoldRemovals(fixture))
			.equals(holdRemovalCounts(holdSnapshot.removals()));

		boolean succeeded = targetReservationsExpired
			&& targetHistoriesInserted
			&& futurePendingPreserved
			&& nonPendingExpiredPreserved
			&& historySnapshotsPreserved
			&& historyAuditContextPreserved
			&& holdRemovalsMatched;

		return new ReservationHistoryInsertBenchmarkVerification(
			verifiedRows,
			succeeded,
			targetReservationsExpired,
			targetHistoriesInserted,
			futurePendingPreserved,
			nonPendingExpiredPreserved,
			historySnapshotsPreserved,
			historyAuditContextPreserved,
			holdRemovalsMatched
		);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void cleanup(Fixture fixture) {
		if (fixture == null) {
			return;
		}
		deleteByReservationIds("reservation_history", fixture.allReservationIds());
		deleteExactIds("reservation", fixture.allReservationIds());
		deleteExactIds("accommodation", List.of(fixture.accommodationId()));
		deleteExactIds("member", List.of(fixture.memberId()));
	}

	private long insertMember() {
		String unique = UUID.randomUUID().toString();
		String sql = """
			INSERT INTO member (
				email, nickname, role, status, created_at, updated_at
			) VALUES (?, ?, 'MEMBER', 'ACTIVE', ?, ?)
			""";
		return insertAndReturnKey(sql, statement -> {
			statement.setString(1, "bulk-write-" + unique + "@benchmark.local");
			statement.setString(2, "bulk-write-reservation-member");
			statement.setTimestamp(3, Timestamp.valueOf(CREATED_AT));
			statement.setTimestamp(4, Timestamp.valueOf(CREATED_AT));
		});
	}

	private long insertAccommodation(long memberId) {
		String sql = """
			INSERT INTO accommodation (
				member_id, check_in_time, check_out_time, accommodation_uid, status,
				name, base_price, currency, created_at, updated_at, created_by, updated_by
			) VALUES (?, '15:00:00', '11:00:00', UUID_TO_BIN(?), 'PUBLISHED',
				?, 100000, 'KRW', ?, ?, ?, ?)
			""";
		return insertAndReturnKey(sql, statement -> {
			statement.setLong(1, memberId);
			statement.setString(2, UUID.randomUUID().toString());
			statement.setString(3, "bulk-write-reservation-accommodation");
			statement.setTimestamp(4, Timestamp.valueOf(CREATED_AT));
			statement.setTimestamp(5, Timestamp.valueOf(CREATED_AT));
			statement.setLong(6, memberId);
			statement.setLong(7, memberId);
		});
	}

	private ReservationExpectation insertReservation(
		long accommodationId,
		long memberId,
		LocalDateTime checkIn,
		LocalDateTime checkOut,
		String status,
		String message,
		LocalDateTime expiresAt
	) {
		String reservationUid = UUID.randomUUID().toString();
		String reservationCode = UUID.randomUUID().toString()
			.replace("-", "")
			.substring(0, 10)
			.toUpperCase();
		String sql = """
			INSERT INTO reservation (
				reservation_uid, reservation_code, accommodation_id, guest_id,
				check_in, check_out, guest_count, total_price, discount_amount,
				currency, status, message, expires_at, created_at, updated_at, created_by, updated_by
			) VALUES (
				UUID_TO_BIN(?), ?, ?, ?, ?, ?, 2, 200000, 0,
				'KRW', ?, ?, ?, ?, ?, ?, ?
			)
			""";
		long reservationId = insertAndReturnKey(sql, statement -> {
			statement.setString(1, reservationUid);
			statement.setString(2, reservationCode);
			statement.setLong(3, accommodationId);
			statement.setLong(4, memberId);
			statement.setTimestamp(5, Timestamp.valueOf(checkIn));
			statement.setTimestamp(6, Timestamp.valueOf(checkOut));
			statement.setString(7, status);
			statement.setString(8, message);
			statement.setTimestamp(9, Timestamp.valueOf(expiresAt));
			statement.setTimestamp(10, Timestamp.valueOf(CREATED_AT));
			statement.setTimestamp(11, Timestamp.valueOf(CREATED_AT));
			statement.setLong(12, memberId);
			statement.setLong(13, memberId);
		});

		return new ReservationExpectation(
			reservationId,
			reservationUid,
			reservationCode,
			accommodationId,
			memberId,
			checkIn,
			checkOut,
			2,
			200_000L,
			"KRW",
			message,
			expiresAt,
			CREATED_AT,
			memberId
		);
	}

	private long insertAndReturnKey(String sql, StatementBinder binder) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			binder.bind(statement);
			return statement;
		}, keyHolder);

		Number key = keyHolder.getKey();
		if (key == null) {
			throw new IllegalStateException("ReservationHistory 벤치마크 fixture 키를 얻지 못했습니다.");
		}
		return key.longValue();
	}

	private Map<Long, ReservationRow> findReservations(List<Long> reservationIds) {
		if (reservationIds.isEmpty()) {
			return Map.of();
		}
		String sql = "SELECT id, status, updated_at FROM reservation WHERE id IN ("
			+ placeholders(reservationIds.size()) + ")";
		Map<Long, ReservationRow> rows = new HashMap<>();
		jdbcTemplate.query(sql, resultSet -> {
			ReservationRow row = new ReservationRow(
				resultSet.getLong("id"),
				resultSet.getString("status"),
				toLocalDateTime(resultSet.getTimestamp("updated_at"))
			);
			rows.put(row.id(), row);
		}, reservationIds.toArray());
		return rows;
	}

	private List<HistoryRow> findHistories(List<Long> reservationIds) {
		if (reservationIds.isEmpty()) {
			return List.of();
		}
		String sql = """
			SELECT id, reservation_id, reservation_uid, reservation_code, accommodation_id, guest_id,
			       check_in, check_out, guest_count, total_price, currency, status, message, expires_at,
			       created_at, created_by, history_created_at, history_created_by,
			       change_type, change_reason, source_system, client_ip
			FROM reservation_history
			WHERE reservation_id IN (%s)
			ORDER BY reservation_id, id
			""".formatted(placeholders(reservationIds.size()));
		return jdbcTemplate.query(sql, this::mapHistory, reservationIds.toArray());
	}

	private HistoryRow mapHistory(ResultSet resultSet, int rowNumber) throws SQLException {
		return new HistoryRow(
			resultSet.getLong("id"),
			resultSet.getLong("reservation_id"),
			resultSet.getString("reservation_uid"),
			resultSet.getString("reservation_code"),
			resultSet.getObject("accommodation_id", Long.class),
			resultSet.getObject("guest_id", Long.class),
			toLocalDateTime(resultSet.getTimestamp("check_in")),
			toLocalDateTime(resultSet.getTimestamp("check_out")),
			resultSet.getObject("guest_count", Integer.class),
			resultSet.getObject("total_price", Long.class),
			resultSet.getString("currency"),
			resultSet.getString("status"),
			resultSet.getString("message"),
			toLocalDateTime(resultSet.getTimestamp("expires_at")),
			toLocalDateTime(resultSet.getTimestamp("created_at")),
			resultSet.getObject("created_by", Long.class),
			toLocalDateTime(resultSet.getTimestamp("history_created_at")),
			resultSet.getObject("history_created_by", Long.class),
			resultSet.getString("change_type"),
			resultSet.getString("change_reason"),
			resultSet.getString("source_system"),
			resultSet.getString("client_ip")
		);
	}

	private Map<Long, List<HistoryRow>> historiesByReservation(List<HistoryRow> histories) {
		Map<Long, List<HistoryRow>> grouped = new HashMap<>();
		for (HistoryRow history : histories) {
			grouped.computeIfAbsent(history.reservationId(), ignored -> new ArrayList<>()).add(history);
		}
		return grouped;
	}

	private boolean matchingHistory(ReservationExpectation expected, List<HistoryRow> histories) {
		if (histories == null || histories.size() != 1) {
			return false;
		}
		HistoryRow history = histories.getFirst();
		return history.id() > 0
			&& history.reservationId() == expected.id()
			&& Objects.equals(history.reservationUid(), expected.reservationUid())
			&& Objects.equals(history.reservationCode(), expected.reservationCode())
			&& Objects.equals(history.accommodationId(), expected.accommodationId())
			&& Objects.equals(history.guestId(), expected.guestId())
			&& Objects.equals(history.checkIn(), expected.checkIn())
			&& Objects.equals(history.checkOut(), expected.checkOut())
			&& Objects.equals(history.guestCount(), expected.guestCount())
			&& Objects.equals(history.totalPrice(), expected.totalPrice())
			&& Objects.equals(history.currency(), expected.currency())
			&& Objects.equals(history.status(), "EXPIRED")
			&& Objects.equals(history.message(), expected.message())
			&& Objects.equals(history.expiresAt(), expected.expiresAt())
			&& Objects.equals(history.createdAt(), expected.createdAt())
			&& Objects.equals(history.createdBy(), expected.createdBy());
	}

	private boolean hasSchedulerAuditContext(List<HistoryRow> histories) {
		if (histories == null || histories.size() != 1) {
			return false;
		}
		HistoryRow history = histories.getFirst();
		return history.historyCreatedAt() != null
			&& history.historyCreatedBy() == null
			&& Objects.equals(history.changeType(), "STATUS_CHANGE")
			&& Objects.equals(history.changeReason(), "결제 시간 초과")
			&& Objects.equals(history.sourceSystem(), "BATCH")
			&& history.clientIp() == null;
	}

	private List<HoldRemoval> expectedHoldRemovals(Fixture fixture) {
		return fixture.targets().stream()
			.map(target -> new HoldRemoval(
				target.accommodationId(),
				target.checkIn().toLocalDate(),
				target.checkOut().toLocalDate()
			))
			.toList();
	}

	private Map<HoldRemoval, Integer> holdRemovalCounts(List<HoldRemoval> removals) {
		Map<HoldRemoval, Integer> counts = new HashMap<>();
		for (HoldRemoval removal : removals) {
			counts.merge(removal, 1, Math::addExact);
		}
		return counts;
	}

	private boolean hasStatus(Map<Long, ReservationRow> reservations, long id, String status) {
		ReservationRow row = reservations.get(id);
		return row != null && Objects.equals(row.status(), status);
	}

	private boolean controlPreserved(
		Map<Long, ReservationRow> reservations,
		ReservationExpectation control,
		String expectedStatus
	) {
		ReservationRow row = reservations.get(control.id());
		return row != null
			&& Objects.equals(row.status(), expectedStatus)
			&& Objects.equals(row.updatedAt(), CREATED_AT);
	}

	private void assertNoExistingEligibleReservation() {
		Long eligible = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM reservation WHERE status = 'PAYMENT_PENDING' AND expires_at < NOW(6)",
			Long.class
		);
		if (!Objects.equals(eligible, 0L)) {
			throw new IllegalStateException("전용 벤치마크 DB에 기존 만료 대상 예약이 있습니다.");
		}
	}

	private void assertOnlyFixtureTargetsAreEligible(Fixture fixture) {
		List<Long> eligibleIds = jdbcTemplate.queryForList(
			"SELECT id FROM reservation WHERE status = 'PAYMENT_PENDING' AND expires_at < NOW(6) ORDER BY id",
			Long.class
		);
		List<Long> targetIds = fixture.targets().stream().map(ReservationExpectation::id).sorted().toList();
		if (!eligibleIds.equals(targetIds)) {
			throw new IllegalStateException("ReservationHistory 벤치마크 대상 격리에 실패했습니다.");
		}
	}

	private void deleteByReservationIds(String table, List<Long> reservationIds) {
		if (reservationIds.isEmpty()) {
			return;
		}
		String sql = "DELETE FROM " + table + " WHERE reservation_id IN ("
			+ placeholders(reservationIds.size()) + ")";
		jdbcTemplate.update(sql, reservationIds.toArray());
	}

	private void deleteExactIds(String table, List<Long> ids) {
		if (ids.isEmpty()) {
			return;
		}
		String sql = "DELETE FROM " + table + " WHERE id IN (" + placeholders(ids.size()) + ")";
		jdbcTemplate.update(sql, ids.toArray());
	}

	private String placeholders(int size) {
		return String.join(", ", Collections.nCopies(size, "?"));
	}

	private LocalDateTime toLocalDateTime(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	private void validateDatasetSize(int datasetSize) {
		if (datasetSize < 0 || datasetSize > ReservationHistoryInsertBenchmarkRequest.MAX_DATASET_SIZE) {
			throw new IllegalArgumentException("datasetSize must be between 0 and "
				+ ReservationHistoryInsertBenchmarkRequest.MAX_DATASET_SIZE);
		}
	}

	@FunctionalInterface
	private interface StatementBinder {
		void bind(PreparedStatement statement) throws SQLException;
	}

	private record ReservationRow(long id, String status, LocalDateTime updatedAt) {
	}

	private record HistoryRow(
		long id,
		long reservationId,
		String reservationUid,
		String reservationCode,
		Long accommodationId,
		Long guestId,
		LocalDateTime checkIn,
		LocalDateTime checkOut,
		Integer guestCount,
		Long totalPrice,
		String currency,
		String status,
		String message,
		LocalDateTime expiresAt,
		LocalDateTime createdAt,
		Long createdBy,
		LocalDateTime historyCreatedAt,
		Long historyCreatedBy,
		String changeType,
		String changeReason,
		String sourceSystem,
		String clientIp
	) {
	}

	public record ReservationExpectation(
		long id,
		String reservationUid,
		String reservationCode,
		long accommodationId,
		long guestId,
		LocalDateTime checkIn,
		LocalDateTime checkOut,
		int guestCount,
		long totalPrice,
		String currency,
		String message,
		LocalDateTime expiresAt,
		LocalDateTime createdAt,
		long createdBy
	) {
	}

	public record Fixture(
		int datasetSize,
		long memberId,
		long accommodationId,
		List<ReservationExpectation> targets,
		ReservationExpectation futurePending,
		ReservationExpectation nonPendingExpired
	) {
		public Fixture {
			targets = List.copyOf(targets);
		}

		public List<Long> allReservationIds() {
			List<Long> ids = new ArrayList<>(targets.size() + 2);
			ids.addAll(targets.stream().map(ReservationExpectation::id).toList());
			ids.add(futurePending.id());
			ids.add(nonPendingExpired.id());
			return List.copyOf(ids);
		}
	}
}
