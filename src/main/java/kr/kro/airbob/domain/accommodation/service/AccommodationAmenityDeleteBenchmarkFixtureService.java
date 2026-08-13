package kr.kro.airbob.domain.accommodation.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

import kr.kro.airbob.domain.commoncode.common.CommonCodeGroups;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeResponse;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.history.HistoryConstants;
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

	private static final LocalDateTime FOREVER = HistoryConstants.FOREVER;
	private static final String FIXTURE_TIME_ZONE_ID = "UTC";

	private final JdbcTemplate jdbcTemplate;
	private final CommonCodeService commonCodeService;
	private final Clock clock;

	public AccommodationAmenityDeleteBenchmarkFixtureService(
		JdbcTemplate jdbcTemplate,
		CommonCodeService commonCodeService,
		Clock clock
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.commonCodeService = commonCodeService;
		this.clock = clock;
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
		String expectedSourceSystem = UserContext.currentSourceSystem();
		String expectedClientIp = UserContext.currentClientIp();

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
			expectedSourceSystem,
			expectedClientIp,
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

		long remainingOldRows = countAmenitiesByIds(fixture.oldTargetAmenityIds());
		long deletedOldRows = fixture.oldTargetAmenityIds().size() - remainingOldRows;
		Map<String, Integer> replacementMap = amenityMap(fixture.targetAccommodationId());
		long replacementRows = countAmenitiesByAccommodationId(fixture.targetAccommodationId());
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
		LocalDateTime currentAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		return insertAndReturnKey("""
			INSERT INTO accommodation (
			  member_id, check_in_time, check_out_time, accommodation_uid, status,
			  name, time_zone_id, created_at, updated_at, created_by, updated_by
			) VALUES (?, '15:00:00', '11:00:00', UUID_TO_BIN(?), 'DRAFT', ?, ?,
			  ?, ?, ?, ?)
			""", statement -> {
			statement.setLong(1, ownerId);
			statement.setString(2, UUID.randomUUID().toString());
			statement.setString(3, name);
			statement.setString(4, FIXTURE_TIME_ZONE_ID);
			statement.setObject(5, currentAt);
			statement.setObject(6, currentAt);
			statement.setLong(7, ownerId);
			statement.setLong(8, ownerId);
		});
	}

	private long insertAmenity(long accommodationId, String code, int count, long ownerId) {
		LocalDateTime currentAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		return insertAndReturnKey("""
			INSERT INTO accommodation_amenity (
			  accommodation_id, amenity_code, count,
			  created_at, updated_at, created_by, updated_by
			) VALUES (?, ?, ?, ?, ?, ?, ?)
			""", statement -> {
			statement.setLong(1, accommodationId);
			statement.setString(2, code);
			statement.setInt(3, count);
			statement.setObject(4, currentAt);
			statement.setObject(5, currentAt);
			statement.setLong(6, ownerId);
			statement.setLong(7, ownerId);
		});
	}

	private long insertCurrentHistory(long accommodationId, long ownerId) {
		LocalDateTime currentAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		return insertAndReturnKey("""
			INSERT INTO accommodation_history (
			  accommodation_id, accommodation_uid, name, status, check_in_time, check_out_time,
			  member_id, time_zone_id, created_at, created_by, history_created_at, history_created_by,
			  change_type, change_reason, source_system, client_ip, valid_from, valid_to
			)
			SELECT id, BIN_TO_UUID(accommodation_uid), name, status, check_in_time, check_out_time,
			       member_id, time_zone_id, created_at, created_by, ?, ?, 'CREATE',
			       'benchmark current history', 'BENCHMARK', '127.0.0.1', ?, ?
			FROM accommodation
			WHERE id = ?
			""", statement -> {
			statement.setObject(1, currentAt);
			statement.setLong(2, ownerId);
			statement.setObject(3, currentAt);
			statement.setObject(4, FOREVER);
			statement.setLong(5, accommodationId);
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
			       description, base_price, currency, thumbnail_url, type,
			       status, check_in_time, check_out_time, time_zone_id, address_id, occupancy_policy_id,
			       created_at, updated_at, created_by, updated_by
			FROM accommodation
			WHERE id = ?
			""", (resultSet, rowNumber) -> {
				Map<String, Object> values = new LinkedHashMap<>();
				values.put("id", resultSet.getLong("id"));
				values.put("accommodation_uid", resultSet.getString("accommodation_uid"));
				values.put("member_id", resultSet.getLong("member_id"));
				values.put("name", resultSet.getString("name"));
				values.put("description", resultSet.getString("description"));
				values.put("base_price", nullableLong(resultSet, "base_price"));
				values.put("currency", resultSet.getString("currency"));
				values.put("thumbnail_url", resultSet.getString("thumbnail_url"));
				values.put("type", resultSet.getString("type"));
				values.put("status", resultSet.getString("status"));
				values.put("check_in_time", resultSet.getTime("check_in_time").toLocalTime());
				values.put("check_out_time", resultSet.getTime("check_out_time").toLocalTime());
				values.put("time_zone_id", resultSet.getString("time_zone_id"));
				values.put("address_id", nullableLong(resultSet, "address_id"));
				values.put("occupancy_policy_id", nullableLong(resultSet, "occupancy_policy_id"));
				values.put("created_at", resultSet.getObject("created_at", LocalDateTime.class));
				values.put("updated_at", resultSet.getObject("updated_at", LocalDateTime.class));
				values.put("created_by", nullableLong(resultSet, "created_by"));
				values.put("updated_by", nullableLong(resultSet, "updated_by"));
				return new ParentSnapshot(values);
			}, accommodationId).stream().findFirst().orElse(null);
	}

	private List<HistorySnapshot> historySnapshots(long accommodationId) {
		return jdbcTemplate.query("""
			SELECT id, accommodation_id, accommodation_uid, name, description, base_price,
			       currency, thumbnail_url, type, status, check_in_time, check_out_time, member_id,
			       time_zone_id,
			       address_country, address_state, address_city, address_district, address_street,
			       address_detail, address_postal_code, address_latitude, address_longitude,
			       max_occupancy, infant_occupancy, pet_occupancy, created_at, created_by,
			       history_created_at, history_created_by, change_type, change_reason,
			       source_system, client_ip, valid_from, valid_to
			FROM accommodation_history
			WHERE accommodation_id = ?
			ORDER BY id
			""", (resultSet, rowNumber) -> {
				Map<String, Object> values = new LinkedHashMap<>();
				values.put("id", resultSet.getLong("id"));
				values.put("accommodation_id", resultSet.getLong("accommodation_id"));
				values.put("accommodation_uid", resultSet.getString("accommodation_uid"));
				values.put("name", resultSet.getString("name"));
				values.put("description", resultSet.getString("description"));
				values.put("base_price", nullableLong(resultSet, "base_price"));
				values.put("currency", resultSet.getString("currency"));
				values.put("thumbnail_url", resultSet.getString("thumbnail_url"));
				values.put("type", resultSet.getString("type"));
				values.put("status", resultSet.getString("status"));
				values.put("check_in_time", resultSet.getTime("check_in_time").toLocalTime());
				values.put("check_out_time", resultSet.getTime("check_out_time").toLocalTime());
				values.put("member_id", nullableLong(resultSet, "member_id"));
				values.put("time_zone_id", resultSet.getString("time_zone_id"));
				values.put("address_country", resultSet.getString("address_country"));
				values.put("address_state", resultSet.getString("address_state"));
				values.put("address_city", resultSet.getString("address_city"));
				values.put("address_district", resultSet.getString("address_district"));
				values.put("address_street", resultSet.getString("address_street"));
				values.put("address_detail", resultSet.getString("address_detail"));
				values.put("address_postal_code", resultSet.getString("address_postal_code"));
				values.put("address_latitude", resultSet.getObject("address_latitude", Double.class));
				values.put("address_longitude", resultSet.getObject("address_longitude", Double.class));
				values.put("max_occupancy", resultSet.getObject("max_occupancy", Integer.class));
				values.put("infant_occupancy", resultSet.getObject("infant_occupancy", Integer.class));
				values.put("pet_occupancy", resultSet.getObject("pet_occupancy", Integer.class));
				values.put("created_at", resultSet.getObject("created_at", LocalDateTime.class));
				values.put("created_by", nullableLong(resultSet, "created_by"));
				values.put("history_created_at", resultSet.getObject("history_created_at", LocalDateTime.class));
				values.put("history_created_by", nullableLong(resultSet, "history_created_by"));
				values.put("change_type", resultSet.getString("change_type"));
				values.put("change_reason", resultSet.getString("change_reason"));
				values.put("source_system", resultSet.getString("source_system"));
				values.put("client_ip", resultSet.getString("client_ip"));
				values.put("valid_from", resultSet.getObject("valid_from", LocalDateTime.class));
				values.put("valid_to", resultSet.getObject("valid_to", LocalDateTime.class));
				return new HistorySnapshot(values);
			}, accommodationId);
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
		HistorySnapshot before = fixture.targetHistoryBefore().stream()
			.filter(history -> history.id() == fixture.targetHistoryId())
			.findFirst()
			.orElse(null);
		return original != null
			&& before != null
			&& original.equalsExceptValidTo(before)
			&& original.validTo() != null
			&& original.validTo().isBefore(FOREVER)
			&& !original.validTo().isBefore(before.validFrom())
			&& current != null
			&& current.id() > 0
			&& current.id() != original.id()
			&& current.mirrors(fixture.targetParentBefore())
			&& current.validFrom() != null
			&& !current.validFrom().isBefore(original.validTo())
			&& FOREVER.equals(current.validTo())
			&& current.historyCreatedAt() != null
			&& Objects.equals(current.historyCreatedBy(), fixture.ownerId())
			&& "UPDATE".equals(current.stringValue("change_type"))
			&& "숙소 정보 수정".equals(current.stringValue("change_reason"))
			&& Objects.equals(current.stringValue("source_system"), fixture.expectedSourceSystem())
			&& Objects.equals(current.stringValue("client_ip"), fixture.expectedClientIp());
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

	private long countAmenitiesByAccommodationId(long accommodationId) {
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM accommodation_amenity WHERE accommodation_id = ?",
			Long.class,
			accommodationId
		);
		return Objects.requireNonNull(count);
	}

	private long countAmenitiesByIds(List<Long> ids) {
		if (ids.isEmpty()) {
			return 0;
		}
		String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM accommodation_amenity WHERE id IN (" + placeholders + ")",
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

	public record HistorySnapshot(Map<String, Object> values) {
		public HistorySnapshot {
			values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
		}

		long id() {
			return (long)values.get("id");
		}

		LocalDateTime validFrom() {
			return (LocalDateTime)values.get("valid_from");
		}

		LocalDateTime validTo() {
			return (LocalDateTime)values.get("valid_to");
		}

		LocalDateTime historyCreatedAt() {
			return (LocalDateTime)values.get("history_created_at");
		}

		Long historyCreatedBy() {
			return (Long)values.get("history_created_by");
		}

		String stringValue(String key) {
			return (String)values.get(key);
		}

		boolean equalsExceptValidTo(HistorySnapshot other) {
			Map<String, Object> expected = new LinkedHashMap<>(other.values);
			expected.put("valid_to", validTo());
			return values.equals(expected);
		}

		boolean mirrors(ParentSnapshot parent) {
			Map<String, Object> parentValues = parent.values();
			return Objects.equals(values.get("accommodation_id"), parentValues.get("id"))
				&& Objects.equals(values.get("accommodation_uid"), parentValues.get("accommodation_uid"))
				&& Objects.equals(values.get("name"), parentValues.get("name"))
				&& Objects.equals(values.get("description"), parentValues.get("description"))
				&& Objects.equals(values.get("base_price"), parentValues.get("base_price"))
				&& Objects.equals(values.get("currency"), parentValues.get("currency"))
				&& Objects.equals(values.get("thumbnail_url"), parentValues.get("thumbnail_url"))
				&& Objects.equals(values.get("type"), parentValues.get("type"))
				&& Objects.equals(values.get("status"), parentValues.get("status"))
				&& Objects.equals(values.get("check_in_time"), parentValues.get("check_in_time"))
				&& Objects.equals(values.get("check_out_time"), parentValues.get("check_out_time"))
				&& Objects.equals(values.get("member_id"), parentValues.get("member_id"))
				&& Objects.equals(values.get("time_zone_id"), parentValues.get("time_zone_id"))
				&& Objects.equals(values.get("created_at"), parentValues.get("created_at"))
				&& Objects.equals(values.get("created_by"), parentValues.get("created_by"))
				&& ownedSnapshotMatchesFixtureParent(parentValues);
		}

		private boolean ownedSnapshotMatchesFixtureParent(Map<String, Object> parentValues) {
			boolean addressMatches = parentValues.get("address_id") == null
				&& List.of(
					"address_country", "address_state", "address_city", "address_district",
					"address_street", "address_detail", "address_postal_code",
					"address_latitude", "address_longitude"
				).stream().allMatch(key -> values.get(key) == null);
			boolean occupancyMatches = parentValues.get("occupancy_policy_id") == null
				&& List.of("max_occupancy", "infant_occupancy", "pet_occupancy")
				.stream().allMatch(key -> values.get(key) == null);
			return addressMatches && occupancyMatches;
		}
	}

	public record Fixture(
		long ownerId,
		String expectedSourceSystem,
		String expectedClientIp,
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
