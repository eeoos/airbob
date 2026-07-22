package kr.kro.airbob.domain.accommodation.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

import kr.kro.airbob.common.code.CommonCodeGroups;
import kr.kro.airbob.common.code.CommonCodeResponse;
import kr.kro.airbob.common.code.CommonCodeService;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest.Measurement;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkVerification;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkVerification.WorkloadClass;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AmenityRequest;

@Service
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class AccommodationAmenityDeleteBenchmarkFixtureService {

	private static final String FOREVER = "9999-12-31 23:59:59";

	private final JdbcTemplate jdbcTemplate;
	private final CommonCodeService commonCodeService;

	public AccommodationAmenityDeleteBenchmarkFixtureService(
		JdbcTemplate jdbcTemplate,
		CommonCodeService commonCodeService
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.commonCodeService = commonCodeService;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Fixture createFixture(long ownerId, int datasetSize) {
		validateDatasetSize(datasetSize);
		validateAdminOwner(ownerId);
		List<String> activeCodes = commonCodeService.getCodes(CommonCodeGroups.AMENITY_TYPE).stream()
			.map(CommonCodeResponse::code)
			.toList();
		if (activeCodes.isEmpty()) {
			throw new IllegalStateException("활성 AMENITY_TYPE 코드가 필요합니다.");
		}

		long targetAccommodationId = insertAccommodation(ownerId, "bulk-write-amenity-target");
		long controlAccommodationId = insertAccommodation(ownerId, "bulk-write-amenity-control");
		List<Long> oldTargetAmenityIds = new ArrayList<>(datasetSize);
		for (int index = 0; index < datasetSize; index++) {
			oldTargetAmenityIds.add(insertAmenity(
				targetAccommodationId,
				activeCodes.get(index % activeCodes.size()),
				index + 1,
				ownerId
			));
		}
		String controlAmenityCode = activeCodes.getFirst();
		int controlAmenityCount = 77;
		long controlAmenityId = insertAmenity(
			controlAccommodationId,
			controlAmenityCode,
			controlAmenityCount,
			ownerId
		);
		long targetHistoryId = insertCurrentHistory(targetAccommodationId, ownerId);
		Replacement replacement = replacement(activeCodes, datasetSize);

		return new Fixture(
			ownerId,
			datasetSize,
			activeCodes.size(),
			datasetSize <= activeCodes.size() ? WorkloadClass.REALISTIC : WorkloadClass.STRESS,
			activeCodes,
			targetAccommodationId,
			controlAccommodationId,
			oldTargetAmenityIds,
			controlAmenityId,
			controlAmenityCode,
			controlAmenityCount,
			targetHistoryId,
			parentSnapshot(targetAccommodationId),
			parentSnapshot(controlAccommodationId),
			historySnapshots(targetAccommodationId),
			replacement.request(),
			replacement.expectedMap()
		);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public AccommodationAmenityDeleteBenchmarkVerification verify(
		Fixture fixture,
		Measurement measurement
	) {
		Objects.requireNonNull(fixture, "fixture must not be null");
		Objects.requireNonNull(measurement, "measurement must not be null");

		long remainingOldRows = countExactIds("accommodation_amenity", fixture.oldTargetAmenityIds());
		long deletedOldRows = fixture.oldTargetAmenityIds().size() - remainingOldRows;
		Map<String, Integer> replacementMap = amenityMap(fixture.targetAccommodationId());
		long replacementRows = countByAccommodationId(
			"accommodation_amenity",
			fixture.targetAccommodationId()
		);
		Map<String, Integer> expectedMap = measurement == Measurement.FULL_REPLACEMENT
			? fixture.replacementMap()
			: Map.of();

		boolean targetParentPreserved = fixture.targetParentBefore()
			.equals(parentSnapshotOrNull(fixture.targetAccommodationId()));
		boolean controlAccommodationPreserved = fixture.controlParentBefore()
			.equals(parentSnapshotOrNull(fixture.controlAccommodationId()));
		boolean controlAmenitiesPreserved = controlAmenityPreserved(fixture);
		boolean historyEffectMatched = historyEffectMatched(fixture, measurement);
		boolean succeeded = deletedOldRows == fixture.datasetSize()
			&& remainingOldRows == 0
			&& replacementRows == expectedMap.size()
			&& replacementMap.equals(expectedMap)
			&& targetParentPreserved
			&& historyEffectMatched
			&& controlAccommodationPreserved
			&& controlAmenitiesPreserved;

		return new AccommodationAmenityDeleteBenchmarkVerification(
			deletedOldRows,
			deletedOldRows,
			replacementRows,
			replacementMap,
			targetParentPreserved,
			historyEffectMatched,
			controlAccommodationPreserved,
			controlAmenitiesPreserved,
			succeeded
		);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void cleanup(Fixture fixture) {
		if (fixture == null) {
			return;
		}
		jdbcTemplate.update(
			"DELETE FROM accommodation_amenity WHERE accommodation_id IN (?, ?)",
			fixture.targetAccommodationId(),
			fixture.controlAccommodationId()
		);
		jdbcTemplate.update(
			"DELETE FROM accommodation_history WHERE accommodation_id = ?",
			fixture.targetAccommodationId()
		);
		jdbcTemplate.update(
			"DELETE FROM accommodation WHERE id IN (?, ?)",
			fixture.targetAccommodationId(),
			fixture.controlAccommodationId()
		);
	}

	private void validateDatasetSize(int datasetSize) {
		if (datasetSize < 0 || datasetSize > AccommodationAmenityDeleteBenchmarkRequest.MAX_DATASET_SIZE) {
			throw new IllegalArgumentException("datasetSize must be between 0 and "
				+ AccommodationAmenityDeleteBenchmarkRequest.MAX_DATASET_SIZE);
		}
	}

	private void validateAdminOwner(long ownerId) {
		Long count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM member
			WHERE id = ? AND role = 'ADMIN' AND status = 'ACTIVE'
			""", Long.class, ownerId);
		if (!Objects.equals(count, 1L)) {
			throw new IllegalArgumentException("fixture owner must be an active ADMIN member");
		}
	}

	private long insertAccommodation(long ownerId, String name) {
		return insertAndReturnKey("""
			INSERT INTO accommodation (
			  member_id, check_in_time, check_out_time, accommodation_uid, status,
			  name, created_at, updated_at, created_by, updated_by
			) VALUES (?, '15:00:00', '11:00:00', UUID_TO_BIN(?), 'DRAFT', ?,
			  NOW(6), NOW(6), ?, ?)
			""", statement -> {
			statement.setLong(1, ownerId);
			statement.setString(2, UUID.randomUUID().toString());
			statement.setString(3, name);
			statement.setLong(4, ownerId);
			statement.setLong(5, ownerId);
		});
	}

	private long insertAmenity(long accommodationId, String code, int count, long ownerId) {
		return insertAndReturnKey("""
			INSERT INTO accommodation_amenity (
			  accommodation_id, amenity_code, count,
			  created_at, updated_at, created_by, updated_by
			) VALUES (?, ?, ?, NOW(6), NOW(6), ?, ?)
			""", statement -> {
			statement.setLong(1, accommodationId);
			statement.setString(2, code);
			statement.setInt(3, count);
			statement.setLong(4, ownerId);
			statement.setLong(5, ownerId);
		});
	}

	private long insertCurrentHistory(long accommodationId, long ownerId) {
		return insertAndReturnKey("""
			INSERT INTO accommodation_history (
			  accommodation_id, accommodation_uid, name, status, check_in_time, check_out_time,
			  member_id, created_at, created_by, history_created_at, history_created_by,
			  change_type, change_reason, source_system, client_ip, valid_from, valid_to
			)
			SELECT id, BIN_TO_UUID(accommodation_uid), name, status, check_in_time, check_out_time,
			       member_id, created_at, created_by, NOW(6), ?, 'CREATE',
			       'benchmark current history', 'BENCHMARK', '127.0.0.1', NOW(6), ?
			FROM accommodation
			WHERE id = ?
			""", statement -> {
			statement.setLong(1, ownerId);
			statement.setString(2, FOREVER);
			statement.setLong(3, accommodationId);
		});
	}

	private Replacement replacement(List<String> activeCodes, int datasetSize) {
		List<AmenityRequest.AmenityInfo> amenities = new ArrayList<>(datasetSize);
		Map<String, Integer> expectedMap = new LinkedHashMap<>();
		for (int index = 0; index < datasetSize; index++) {
			String code = activeCodes.get(index % activeCodes.size());
			int count = index + 1;
			amenities.add(new AmenityRequest.AmenityInfo(code.toLowerCase(Locale.ROOT), count));
			expectedMap.merge(code, count, Integer::sum);
		}
		AccommodationRequest.Update request = new AccommodationRequest.Update(
			null, null, null, null, null, amenities, null, null, null, null
		);
		return new Replacement(request, expectedMap);
	}

	private ParentSnapshot parentSnapshot(long accommodationId) {
		ParentSnapshot snapshot = parentSnapshotOrNull(accommodationId);
		if (snapshot == null) {
			throw new IllegalStateException("Accommodation fixture 생성에 실패했습니다.");
		}
		return snapshot;
	}

	private ParentSnapshot parentSnapshotOrNull(long accommodationId) {
		return jdbcTemplate.query("""
			SELECT id, BIN_TO_UUID(accommodation_uid) AS accommodation_uid, member_id, name,
			       status, check_in_time, check_out_time, address_id, occupancy_policy_id,
			       created_at, updated_at, created_by, updated_by
			FROM accommodation
			WHERE id = ?
			""", (resultSet, rowNumber) -> {
				Map<String, Object> values = new LinkedHashMap<>();
				values.put("id", resultSet.getLong("id"));
				values.put("accommodation_uid", resultSet.getString("accommodation_uid"));
				values.put("member_id", resultSet.getLong("member_id"));
				values.put("name", resultSet.getString("name"));
				values.put("status", resultSet.getString("status"));
				values.put("check_in_time", resultSet.getTime("check_in_time").toLocalTime());
				values.put("check_out_time", resultSet.getTime("check_out_time").toLocalTime());
				values.put("address_id", nullableLong(resultSet, "address_id"));
				values.put("occupancy_policy_id", nullableLong(resultSet, "occupancy_policy_id"));
				values.put("created_at", toLocalDateTime(resultSet.getTimestamp("created_at")));
				values.put("updated_at", toLocalDateTime(resultSet.getTimestamp("updated_at")));
				values.put("created_by", nullableLong(resultSet, "created_by"));
				values.put("updated_by", nullableLong(resultSet, "updated_by"));
				return new ParentSnapshot(values);
			}, accommodationId).stream().findFirst().orElse(null);
	}

	private List<HistorySnapshot> historySnapshots(long accommodationId) {
		return jdbcTemplate.query("""
			SELECT id, accommodation_id, status, member_id, change_type, change_reason,
			       valid_from, valid_to, history_created_at, history_created_by
			FROM accommodation_history
			WHERE accommodation_id = ?
			ORDER BY id
			""", (resultSet, rowNumber) -> new HistorySnapshot(
			resultSet.getLong("id"),
			resultSet.getLong("accommodation_id"),
			resultSet.getString("status"),
			nullableLong(resultSet, "member_id"),
			resultSet.getString("change_type"),
			resultSet.getString("change_reason"),
			toLocalDateTime(resultSet.getTimestamp("valid_from")),
			toLocalDateTime(resultSet.getTimestamp("valid_to")),
			toLocalDateTime(resultSet.getTimestamp("history_created_at")),
			nullableLong(resultSet, "history_created_by")
		), accommodationId);
	}

	private boolean historyEffectMatched(Fixture fixture, Measurement measurement) {
		List<HistorySnapshot> after = historySnapshots(fixture.targetAccommodationId());
		if (measurement == Measurement.DELETE_ONLY) {
			return after.equals(fixture.targetHistoryBefore());
		}
		if (after.size() != 2) {
			return false;
		}
		HistorySnapshot original = after.stream()
			.filter(history -> history.id() == fixture.targetHistoryId())
			.findFirst()
			.orElse(null);
		HistorySnapshot current = after.stream()
			.filter(history -> history.id() != fixture.targetHistoryId())
			.findFirst()
			.orElse(null);
		return original != null
			&& original.validTo().isBefore(LocalDateTime.of(9999, 12, 31, 23, 59, 59))
			&& current != null
			&& current.validTo().equals(LocalDateTime.of(9999, 12, 31, 23, 59, 59))
			&& "UPDATE".equals(current.changeType())
			&& "DRAFT".equals(current.status())
			&& Objects.equals(current.memberId(), fixture.ownerId());
	}

	private boolean controlAmenityPreserved(Fixture fixture) {
		Long count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM accommodation_amenity
			WHERE id = ? AND accommodation_id = ? AND amenity_code = ? AND count = ?
			""", Long.class,
			fixture.controlAmenityId(),
			fixture.controlAccommodationId(),
			fixture.controlAmenityCode(),
			fixture.controlAmenityCount());
		return Objects.equals(count, 1L);
	}

	private Map<String, Integer> amenityMap(long accommodationId) {
		Map<String, Integer> result = new LinkedHashMap<>();
		List<Map.Entry<String, Integer>> rows = jdbcTemplate.query("""
			SELECT amenity_code, SUM(count) AS total_count
			FROM accommodation_amenity
			WHERE accommodation_id = ?
			GROUP BY amenity_code
			ORDER BY amenity_code
			""", (resultSet, rowNumber) -> Map.entry(
				resultSet.getString("amenity_code"),
				resultSet.getInt("total_count")
			), accommodationId);
		rows.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
		return Collections.unmodifiableMap(result);
	}

	private long countByAccommodationId(String tableName, long accommodationId) {
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM " + tableName + " WHERE accommodation_id = ?",
			Long.class,
			accommodationId
		);
		return Objects.requireNonNull(count);
	}

	private long countExactIds(String tableName, List<Long> ids) {
		if (ids.isEmpty()) {
			return 0;
		}
		String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM " + tableName + " WHERE id IN (" + placeholders + ")",
			Long.class,
			ids.toArray()
		);
		return Objects.requireNonNull(count);
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
			throw new IllegalStateException("AccommodationAmenity benchmark fixture 키가 없습니다.");
		}
		return key.longValue();
	}

	private Long nullableLong(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
		return resultSet.getObject(column, Long.class);
	}

	private LocalDateTime toLocalDateTime(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	@FunctionalInterface
	private interface StatementBinder {
		void bind(PreparedStatement statement) throws java.sql.SQLException;
	}

	private record Replacement(
		AccommodationRequest.Update request,
		Map<String, Integer> expectedMap
	) {
		private Replacement {
			expectedMap = Collections.unmodifiableMap(new LinkedHashMap<>(expectedMap));
		}
	}

	public record ParentSnapshot(Map<String, Object> values) {
		public ParentSnapshot {
			values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
		}
	}

	public record HistorySnapshot(
		long id,
		long accommodationId,
		String status,
		Long memberId,
		String changeType,
		String changeReason,
		LocalDateTime validFrom,
		LocalDateTime validTo,
		LocalDateTime historyCreatedAt,
		Long historyCreatedBy
	) {
	}

	public record Fixture(
		long ownerId,
		int datasetSize,
		int activeAmenityCodeCount,
		WorkloadClass workloadClass,
		List<String> activeAmenityCodes,
		long targetAccommodationId,
		long controlAccommodationId,
		List<Long> oldTargetAmenityIds,
		long controlAmenityId,
		String controlAmenityCode,
		int controlAmenityCount,
		long targetHistoryId,
		ParentSnapshot targetParentBefore,
		ParentSnapshot controlParentBefore,
		List<HistorySnapshot> targetHistoryBefore,
		AccommodationRequest.Update replacementRequest,
		Map<String, Integer> replacementMap
	) {
		public Fixture {
			activeAmenityCodes = List.copyOf(activeAmenityCodes);
			oldTargetAmenityIds = List.copyOf(oldTargetAmenityIds);
			targetHistoryBefore = List.copyOf(targetHistoryBefore);
			replacementMap = Collections.unmodifiableMap(new LinkedHashMap<>(replacementMap));
		}
	}
}
