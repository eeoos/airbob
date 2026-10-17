package kr.kro.airbob.domain.accommodation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.awspring.cloud.s3.S3Template;
import jakarta.persistence.EntityManager;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.history.HistoryConstants;
import kr.kro.airbob.common.monitoring.SqlQueryType;
import kr.kro.airbob.common.monitoring.QueryCountContext;
import kr.kro.airbob.common.monitoring.QueryCountContextHolder;
import kr.kro.airbob.common.monitoring.QueryCountSnapshot;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AddressRequest;
import kr.kro.airbob.domain.accommodation.dto.AmenityRequest;
import kr.kro.airbob.domain.accommodation.dto.PolicyRequest;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.exception.InvalidAccommodationAmenityException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.service.AccommodationCommandService;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;
import kr.kro.airbob.geo.GeocodingService;
import kr.kro.airbob.geo.dto.GeocodeResult;

@Testcontainers
@SpringBootTest(properties = {
	"spring.cloud.aws.s3.enabled=false",
	"accommodation.detail-cache.enabled=false",
	"spring.jpa.open-in-view=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("편의시설 벌크 삭제 및 숙소 수정 MySQL 통합 테스트")
class AccommodationAmenityDeleteIntegrationTest {
	private static final String INACTIVE_AMENITY_CODE = "INACTIVE_TEST_AMENITY";

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbob_amenity_delete_test");

	@Container
	private static final GenericContainer<?> REDIS =
		new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
			.withExposedPorts(6379);

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
	}

	@Autowired private AccommodationCommandService accommodationCommandService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private TransactionTemplate transactionTemplate;
	@Autowired private EntityManager entityManager;

	@MockitoSpyBean private AccommodationAmenityRepository accommodationAmenityRepository;

	@MockitoBean private ElasticsearchClient elasticsearchClient;
	@MockitoBean private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean private S3Template s3Template;
	@MockitoBean private GeocodingService geocodingService;

	private long ownerId;

	@BeforeEach
	void setUp() {
		ownerId = insertOwner();
		UserContext.set(new UserInfo(ownerId, "127.0.0.1", "TEST"));
	}

	@AfterEach
	void tearDown() {
		reset(accommodationAmenityRepository);
		UserContext.clear();
		QueryCountContextHolder.clear();
		jdbcTemplate.update("DELETE FROM outbox");
		jdbcTemplate.update("DELETE FROM accommodation_amenity");
		jdbcTemplate.update("DELETE FROM accommodation_history");
		jdbcTemplate.update("DELETE FROM accommodation");
		jdbcTemplate.update("DELETE FROM address");
		jdbcTemplate.update("DELETE FROM occupancy_policy");
		jdbcTemplate.update("DELETE FROM member");
		jdbcTemplate.update(
			"DELETE FROM common_code WHERE group_code = 'AMENITY_TYPE' AND code = ?",
			INACTIVE_AMENITY_CODE
		);
	}

	@Test
	@DisplayName("대상 숙소의 편의시설만 DELETE 한 번으로 제거하고 영향 행 수를 반환한다")
	void deletesOnlyTargetAmenitiesWithOneStatement() {
		Fixture fixture = createFixture(3);
		Map<String, Object> parent = parentSnapshot(fixture.targetAccommodationId());
		List<Map<String, Object>> history = historySnapshots(fixture.targetAccommodationId());
		var queries = captureQueries(() -> transactionTemplate.executeWithoutResult(status ->
			assertThat(accommodationAmenityRepository.deleteByAccommodationIdInBulk(
				fixture.targetAccommodationId())).isEqualTo(3)));

		assertThat(queries.countOf(SqlQueryType.DELETE)).isEqualTo(1);
		assertThat(queries.countOf(SqlQueryType.TOTAL)).isEqualTo(1);
		assertThat(amenityMap(fixture.targetAccommodationId())).isEmpty();
		assertThat(parentSnapshot(fixture.targetAccommodationId())).isEqualTo(parent);
		assertThat(historySnapshots(fixture.targetAccommodationId())).isEqualTo(history);
		assertControlPreserved(fixture);
	}

	@Test
	@DisplayName("삭제 대상이 없어도 영향 행 수 0으로 완료한다")
	void deletesAbsentAmenities() {
		transactionTemplate.executeWithoutResult(status ->
			assertThat(accommodationAmenityRepository.deleteByAccommodationIdInBulk(Long.MAX_VALUE)).isZero());
	}

	@Test
	@DisplayName("null은 유지하고 빈 목록은 전체 삭제하며 중복 코드는 합산한다")
	void preservesCurrentAmenityReplacementSemantics() {
		Fixture nullFixture = createFixture(3);
		Map<String, Integer> oldMap = amenityMap(nullFixture.targetAccommodationId());

		accommodationCommandService.updateAccommodation(nullFixture.targetAccommodationId(),
			update(null),
			ownerId
		);
		assertThat(amenityMap(nullFixture.targetAccommodationId())).isEqualTo(oldMap);
		assertControlPreserved(nullFixture);

		Fixture emptyFixture = createFixture(3);
		accommodationCommandService.updateAccommodation(emptyFixture.targetAccommodationId(),
			update(List.of()),
			ownerId
		);
		assertThat(amenityMap(emptyFixture.targetAccommodationId())).isEmpty();
		assertControlPreserved(emptyFixture);

		Fixture populatedFixture = createFixture(3);
		String first = populatedFixture.activeAmenityCodes().get(0);
		String second = populatedFixture.activeAmenityCodes().get(1);
		List<AmenityRequest.AmenityInfo> request = List.of(
			new AmenityRequest.AmenityInfo(first.toLowerCase(), 1),
			new AmenityRequest.AmenityInfo(first, 2),
			new AmenityRequest.AmenityInfo("NOT_A_REAL_AMENITY", 0),
			new AmenityRequest.AmenityInfo(second, -1),
			new AmenityRequest.AmenityInfo(second.toLowerCase(), 5)
		);

		accommodationCommandService.updateAccommodation(populatedFixture.targetAccommodationId(),
			update(request),
			ownerId
		);

		assertThat(amenityMap(populatedFixture.targetAccommodationId()))
			.containsExactlyInAnyOrderEntriesOf(Map.of(first, 3, second, 5));
		assertControlPreserved(populatedFixture);
	}

	@Test
	@DisplayName("비활성 편의시설 코드가 있으면 수정 전 상태를 유지한다")
	void rejectsInactiveAmenityAndPreservesExistingState() {
		jdbcTemplate.update(
			"DELETE FROM common_code WHERE group_code = 'AMENITY_TYPE' AND code = ?",
			INACTIVE_AMENITY_CODE
		);
		jdbcTemplate.update("""
			INSERT INTO common_code (
				group_code, code, name, sort_order, is_active, updated_at
			) VALUES ('AMENITY_TYPE', ?, '비활성 테스트 편의시설', 999, false, CURRENT_TIMESTAMP(6))
			""", INACTIVE_AMENITY_CODE);
		Integer activeCount = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM common_code
			WHERE group_code = 'AMENITY_TYPE' AND code = ? AND is_active = true
			""", Integer.class, INACTIVE_AMENITY_CODE);
		assertThat(activeCount).isZero();

		Fixture fixture = createFixture(3);
		Map<String, Integer> targetBefore = amenityMap(fixture.targetAccommodationId());
		Map<String, Object> parentBefore = parentSnapshot(fixture.targetAccommodationId());
		List<Map<String, Object>> historyBefore = historySnapshots(fixture.targetAccommodationId());
		Map<String, Integer> controlBefore = amenityMap(fixture.controlAccommodationId());
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.name("변경되면 안 되는 이름")
			.amenityInfos(List.of(
				new AmenityRequest.AmenityInfo(fixture.activeAmenityCodes().getFirst(), 1),
				new AmenityRequest.AmenityInfo(INACTIVE_AMENITY_CODE, 1)
			))
			.build();

		assertThatThrownBy(() -> accommodationCommandService.updateAccommodation(
			fixture.targetAccommodationId(), request, ownerId
		)).isInstanceOf(InvalidAccommodationAmenityException.class);

		assertThat(amenityMap(fixture.targetAccommodationId())).isEqualTo(targetBefore);
		assertThat(parentSnapshot(fixture.targetAccommodationId())).isEqualTo(parentBefore);
		assertThat(historySnapshots(fixture.targetAccommodationId())).isEqualTo(historyBefore);
		assertThat(amenityMap(fixture.controlAccommodationId())).isEqualTo(controlBefore);
	}

	@Test
	@DisplayName("벌크 삭제 후 숙소·주소·정책·이력의 변경을 커밋한다")
	void preservesManagedParentAndOwnedStateAfterPredicateDelete() {
		Fixture fixture = createFixture(1);
		when(geocodingService.getCoordinates(anyString()))
			.thenReturn(GeocodeResult.success(37.5665, 126.9780, "Seoul", null));
		AccommodationRequest.Update request = new AccommodationRequest.Update(
			"managed parent after bulk delete",
			null,
			null,
			null,
			new AddressRequest.AddressInfo(
				"04524", "KR", "Seoul", "Seoul", "Jung", "Sejong-daero", "110"
			),
			List.of(new AmenityRequest.AmenityInfo(fixture.activeAmenityCodes().getFirst(), 2)),
			new PolicyRequest.OccupancyPolicyInfo(4, 1, 0),
			null,
			null,
			null
		);

		accommodationCommandService.updateAccommodation(fixture.targetAccommodationId(), request, ownerId);

		Map<String, Object> parent = parentSnapshot(fixture.targetAccommodationId());
		assertThat(parent.get("name")).isEqualTo("managed parent after bulk delete");
		assertThat(parent.get("address_id")).isNotNull();
		assertThat(parent.get("occupancy_policy_id")).isNotNull();
		assertThat(amenityMap(fixture.targetAccommodationId()))
			.isEqualTo(Map.of(fixture.activeAmenityCodes().getFirst(), 2));
		assertThat(historySnapshots(fixture.targetAccommodationId()))
			.anySatisfy(history -> {
				assertThat(history.get("name")).isEqualTo("managed parent after bulk delete");
				assertThat(history.get("address_city")).isEqualTo("Seoul");
				assertThat(history.get("max_occupancy")).isEqualTo(4);
			});
		assertControlPreserved(fixture);
	}

	@Test
	@DisplayName("재등록 flush 후 실패하면 삭제와 INSERT 및 숙소 변경을 롤백한다")
	void rollsBackReplacementAfterDatabaseInsertFailure() {
		Fixture fixture = createFixture(4);
		Map<String, Integer> oldTarget = amenityMap(fixture.targetAccommodationId());
		Map<String, Object> parentBefore = parentSnapshot(fixture.targetAccommodationId());
		List<Map<String, Object>> historyBefore = historySnapshots(fixture.targetAccommodationId());
		Map<String, Integer> controlBefore = amenityMap(fixture.controlAccommodationId());

		List<AmenityRequest.AmenityInfo> replacement = fixture.activeAmenityCodes().subList(0, 4)
			.stream()
			.map(code -> new AmenityRequest.AmenityInfo(code, 1))
			.toList();
		doAnswer(invocation -> {
			Iterable<AccommodationAmenity> amenities = invocation.getArgument(0);
			amenities.forEach(entityManager::persist);
			entityManager.flush();
			throw new IntentionalAmenitySaveFailure();
		}).when(accommodationAmenityRepository).saveAll(any());

		assertThatThrownBy(() -> accommodationCommandService.updateAccommodation(
			fixture.targetAccommodationId(),
			update(replacement),
			ownerId
		)).isInstanceOf(IntentionalAmenitySaveFailure.class);

		assertThat(amenityMap(fixture.targetAccommodationId())).isEqualTo(oldTarget);
		assertThat(parentSnapshot(fixture.targetAccommodationId())).isEqualTo(parentBefore);
		assertThat(historySnapshots(fixture.targetAccommodationId())).isEqualTo(historyBefore);
		assertThat(amenityMap(fixture.controlAccommodationId())).isEqualTo(controlBefore);
	}

	@Test
	@DisplayName("터키어 기본 Locale에서도 편의시설 코드를 일관되게 정규화한다")
	void normalizesCodesIndependentlyOfDefaultLocale() {
		Fixture fixture = createFixture(1);
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			accommodationCommandService.updateAccommodation(fixture.targetAccommodationId(),
				update(List.of(new AmenityRequest.AmenityInfo("wifi", 2))), ownerId);
			assertThat(amenityMap(fixture.targetAccommodationId())).containsExactlyEntriesOf(Map.of("WIFI", 2));
		} finally {
			Locale.setDefault(previous);
		}
	}

	@Test
	@DisplayName("숙소 수정은 이력의 요청 정보와 캐시 무효화 outbox를 함께 기록한다")
	void commitsHistoryAndCacheInvalidation() {
		Fixture fixture = createFixture(3);
		UserContext.set(new UserInfo(ownerId, "192.0.2.8", "API"));
		var queries = captureQueries(() -> accommodationCommandService.updateAccommodation(
			fixture.targetAccommodationId(), update(List.of(new AmenityRequest.AmenityInfo("wifi", 2))), ownerId));

		assertThat(queries.countOf(SqlQueryType.DELETE)).isEqualTo(1);
		assertThat(amenityMap(fixture.targetAccommodationId())).isEqualTo(Map.of("WIFI", 2));
		assertThat(historySnapshots(fixture.targetAccommodationId())).anySatisfy(history -> {
			assertThat(history.get("change_type")).isEqualTo("UPDATE");
			assertThat(history.get("source_system")).isEqualTo("API");
			assertThat(history.get("client_ip")).isEqualTo("192.0.2.8");
		});
		assertThat(jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM outbox
			WHERE destination = 'ACCOMMODATION_CACHE.events' AND aggregate_id = ?
			""", Long.class, Long.toString(fixture.targetAccommodationId()))).isEqualTo(1L);
		assertControlPreserved(fixture);
	}

	@Test
	@DisplayName("편의시설 목록을 생략하면 기존 편의시설을 유지하고 DELETE를 실행하지 않는다")
	void nullAmenityInfosSkipsDeletion() {
		Fixture fixture = createFixture(3);
		Map<String, Integer> original = amenityMap(fixture.targetAccommodationId());
		var queries = captureQueries(() -> accommodationCommandService.updateAccommodation(
			fixture.targetAccommodationId(), update(null), ownerId));
		assertThat(queries.countOf(SqlQueryType.DELETE)).isZero();
		assertThat(amenityMap(fixture.targetAccommodationId())).isEqualTo(original);
	}

	@Test
	@DisplayName("없는 숙소의 수정은 저장 상태를 바꾸지 않는다")
	void rejectsAbsentAccommodationWithoutSideEffects() {
		Fixture fixture = createFixture(3);
		Map<String, Object> parent = parentSnapshot(fixture.targetAccommodationId());
		Map<String, Integer> amenities = amenityMap(fixture.targetAccommodationId());
		assertThatThrownBy(() -> accommodationCommandService.updateAccommodation(
			Long.MAX_VALUE, update(List.of()), ownerId)).isInstanceOf(AccommodationNotFoundException.class);
		assertThat(parentSnapshot(fixture.targetAccommodationId())).isEqualTo(parent);
		assertThat(amenityMap(fixture.targetAccommodationId())).isEqualTo(amenities);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox", Long.class)).isZero();
	}

	private QueryCountSnapshot captureQueries(Runnable action) {
		QueryCountContext context = new QueryCountContext("PATCH", "/api/v1/accommodations/{id}");
		QueryCountContextHolder.initContext(context);
		try {
			action.run();
			return context.snapshot();
		} finally {
			QueryCountContextHolder.clear();
		}
	}

	private Fixture createFixture(int size) {
		List<String> codes = activeAmenityCodes();
		long target = insertAccommodation("amenity-target");
		long control = insertAccommodation("amenity-control");
		for (int index = 0; index < size; index++) {
			insertAmenity(target, codes.get(index % codes.size()), index + 1);
		}
		insertAmenity(control, codes.getFirst(), 77);
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		jdbcTemplate.update("""
			INSERT INTO accommodation_history (
			  accommodation_id, accommodation_uid, name, status, check_in_time, check_out_time,
			  member_id, time_zone_id, created_at, created_by, history_created_at, history_created_by,
			  change_type, change_reason, source_system, client_ip, valid_from, valid_to)
			SELECT id, BIN_TO_UUID(accommodation_uid), name, status, check_in_time, check_out_time,
			       member_id, time_zone_id, created_at, created_by, ?, ?, 'CREATE',
			       'test history', 'TEST', '127.0.0.1', ?, ?
			FROM accommodation WHERE id = ?
			""", now, ownerId, now, HistoryConstants.FOREVER, target);
		return new Fixture(target, control, codes, codes.getFirst(), 77);
	}

	private long insertAccommodation(String name) {
		KeyHolder key = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement("""
				INSERT INTO accommodation (member_id, name, accommodation_uid, status, time_zone_id,
				  check_in_time, check_out_time, created_at, updated_at, created_by, updated_by)
				VALUES (?, ?, UUID_TO_BIN(UUID()), 'DRAFT', 'UTC', '15:00:00', '11:00:00', NOW(6), NOW(6), ?, ?)
				""", Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, ownerId);
			statement.setString(2, name);
			statement.setLong(3, ownerId);
			statement.setLong(4, ownerId);
			return statement;
		}, key);
		return key.getKey().longValue();
	}

	private void insertAmenity(long accommodationId, String code, int count) {
		jdbcTemplate.update("""
			INSERT INTO accommodation_amenity
			(accommodation_id, amenity_code, count, created_at, updated_at, created_by, updated_by)
			VALUES (?, ?, ?, NOW(6), NOW(6), ?, ?)
			""", accommodationId, code, count, ownerId, ownerId);
	}

	private record Fixture(long targetAccommodationId, long controlAccommodationId,
		List<String> activeAmenityCodes, String controlAmenityCode, int controlAmenityCount) {
	}

	private AccommodationRequest.Update update(List<AmenityRequest.AmenityInfo> amenities) {
		return new AccommodationRequest.Update(
			null, null, null, null, null, amenities, null, null, null, null
		);
	}

	private List<String> activeAmenityCodes() {
		return jdbcTemplate.queryForList("""
			SELECT code
			FROM common_code
			WHERE group_code = 'AMENITY_TYPE' AND is_active = 1
			ORDER BY sort_order, code
			""", String.class);
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
		return result;
	}

	private void assertControlPreserved(Fixture fixture) {
		assertThat(amenityMap(fixture.controlAccommodationId()))
			.isEqualTo(Map.of(fixture.controlAmenityCode(), fixture.controlAmenityCount()));
	}

	private Map<String, Object> parentSnapshot(long accommodationId) {
		return jdbcTemplate.queryForMap("""
			SELECT id, BIN_TO_UUID(accommodation_uid) AS accommodation_uid, member_id, name,
			       description, base_price, currency, thumbnail_url, type,
			       status, check_in_time, check_out_time, time_zone_id, address_id, occupancy_policy_id,
			       created_at, updated_at, created_by, updated_by
			FROM accommodation
			WHERE id = ?
			""", accommodationId);
	}

	private List<Map<String, Object>> historySnapshots(long accommodationId) {
		return new ArrayList<>(jdbcTemplate.queryForList("""
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
			""", accommodationId));
	}

	private long insertOwner() {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement("""
				INSERT INTO member (email, nickname, role, status, created_at, updated_at)
				VALUES (?, ?, 'MEMBER', 'ACTIVE', NOW(6), NOW(6))
				""", Statement.RETURN_GENERATED_KEYS);
			statement.setString(1, "amenity-test-owner@example.test");
			statement.setString(2, "amenity-test-owner");
			return statement;
		}, keyHolder);
		assertThat(keyHolder.getKey()).isNotNull();
		return keyHolder.getKey().longValue();
	}

	private static final class IntentionalAmenitySaveFailure extends RuntimeException {
	}
}
