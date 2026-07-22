package kr.kro.airbob.domain.accommodation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
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
import kr.kro.airbob.common.monitoring.SqlQueryType;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationMonitor;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest.Measurement;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest.Variant;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkVerification.WorkloadClass;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AmenityRequest;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.service.AccommodationAmenityDeleteBeforeBenchmarkService;
import kr.kro.airbob.domain.accommodation.service.AccommodationAmenityDeleteBenchmarkFixtureService;
import kr.kro.airbob.domain.accommodation.service.AccommodationAmenityDeleteBenchmarkFixtureService.Fixture;
import kr.kro.airbob.domain.accommodation.service.AccommodationAmenityDeleteBenchmarkService;
import kr.kro.airbob.domain.accommodation.service.AccommodationService;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = {
	"spring.cloud.aws.s3.enabled=false",
	"benchmark.bulk-write.enabled=true",
	"benchmark.bulk-write.token=amenity-integration-token-1234567890",
	"benchmark.bulk-write.allowed-schema=airbob_bulk_write_benchmark"
})
@ActiveProfiles({"test", "bulk-write-benchmark"})
@DisplayName("AccommodationAmenity 삭제 Before 벤치마크 MySQL 통합 테스트")
class AccommodationAmenityDeleteBenchmarkIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbob_bulk_write_benchmark");

	@Container
	private static final GenericContainer<?> REDIS =
		new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
			.withExposedPorts(6379);

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.baselineOnMigrate(true)
			.baselineVersion("1")
			.load()
			.migrate();

		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
		registry.add("spring.kafka.consumer.enabled", () -> "false");
		registry.add("spring.kafka.producer.enabled", () -> "false");
	}

	@Autowired private AccommodationAmenityDeleteBenchmarkService benchmarkService;
	@Autowired private AccommodationAmenityDeleteBenchmarkFixtureService fixtureService;
	@Autowired private AccommodationAmenityDeleteBeforeBenchmarkService beforeService;
	@Autowired private AccommodationService accommodationService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private EntityManager entityManager;
	@Autowired private BulkOperationMonitor bulkOperationMonitor;

	@MockitoSpyBean private AccommodationAmenityRepository accommodationAmenityRepository;

	@MockitoBean private ElasticsearchClient elasticsearchClient;
	@MockitoBean private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean private S3Template s3Template;

	private long ownerId;

	@BeforeEach
	void setUp() {
		ownerId = insertAdminMember();
		UserContext.set(new UserInfo(ownerId, "127.0.0.1", "BENCHMARK"));
	}

	@AfterEach
	void tearDown() {
		reset(accommodationAmenityRepository);
		UserContext.clear();
		jdbcTemplate.update("DELETE FROM accommodation_amenity");
		jdbcTemplate.update("DELETE FROM accommodation_history");
		jdbcTemplate.update("DELETE FROM accommodation");
		jdbcTemplate.update("DELETE FROM member");
	}

	@Test
	@DisplayName("full replacement는 N=0, 현실 경계, stress에서 N+R+5 SQL 공식과 상태 계약을 지킨다")
	void fullReplacementMatchesSqlFormulaAcrossCardinalities() {
		int activeCodeCount = activeAmenityCodes().size();

		assertFullReplacement(0, activeCodeCount);
		assertFullReplacement(activeCodeCount, activeCodeCount);
		assertFullReplacement(activeCodeCount + 1, activeCodeCount);

		assertThat(AopUtils.isAopProxy(accommodationService)).isTrue();
	}

	@Test
	@DisplayName("delete-only 진단은 N=0, 현실 경계, stress에서 SELECT 1 + DELETE N 공식만 기록한다")
	void deleteOnlyMatchesSqlFormulaAcrossCardinalities() {
		int activeCodeCount = activeAmenityCodes().size();

		assertDeleteOnly(0, activeCodeCount);
		assertDeleteOnly(activeCodeCount, activeCodeCount);
		assertDeleteOnly(activeCodeCount + 1, activeCodeCount);

		assertThat(AopUtils.isAopProxy(beforeService)).isTrue();
	}

	@Test
	@DisplayName("null은 amenity를 유지하고 empty는 전부 삭제하며 populated는 대소문자·중복·무효·0을 정확히 병합한다")
	void preservesCurrentAmenityReplacementSemantics() {
		Fixture nullFixture = fixtureService.createFixture(ownerId, 3);
		Map<String, Integer> oldMap = amenityMap(nullFixture.targetAccommodationId());

		accommodationService.updateAccommodation(
			nullFixture.targetAccommodationId(),
			update(null),
			ownerId
		);
		assertThat(amenityMap(nullFixture.targetAccommodationId())).isEqualTo(oldMap);
		assertControlPreserved(nullFixture);
		fixtureService.cleanup(nullFixture);

		Fixture emptyFixture = fixtureService.createFixture(ownerId, 3);
		accommodationService.updateAccommodation(
			emptyFixture.targetAccommodationId(),
			update(List.of()),
			ownerId
		);
		assertThat(amenityMap(emptyFixture.targetAccommodationId())).isEmpty();
		assertControlPreserved(emptyFixture);
		fixtureService.cleanup(emptyFixture);

		Fixture populatedFixture = fixtureService.createFixture(ownerId, 3);
		String first = populatedFixture.activeAmenityCodes().get(0);
		String second = populatedFixture.activeAmenityCodes().get(1);
		List<AmenityRequest.AmenityInfo> request = List.of(
			new AmenityRequest.AmenityInfo(first.toLowerCase(), 1),
			new AmenityRequest.AmenityInfo(first, 2),
			new AmenityRequest.AmenityInfo("NOT_A_REAL_AMENITY", 9),
			new AmenityRequest.AmenityInfo(second, 0),
			new AmenityRequest.AmenityInfo(second, -1),
			new AmenityRequest.AmenityInfo(second.toLowerCase(), 5)
		);

		accommodationService.updateAccommodation(
			populatedFixture.targetAccommodationId(),
			update(request),
			ownerId
		);

		assertThat(amenityMap(populatedFixture.targetAccommodationId()))
			.containsExactlyInAnyOrderEntriesOf(Map.of(first, 3, second, 5));
		assertControlPreserved(populatedFixture);
		fixtureService.cleanup(populatedFixture);
	}

	@Test
	@DisplayName("amenityInfos=null은 amenity SELECT/DELETE/INSERT를 추가하지 않는다")
	void nullAmenityInfosSkipsAmenityStatements() {
		Fixture fixture = fixtureService.createFixture(ownerId, 3);
		Map<String, Integer> oldMap = amenityMap(fixture.targetAccommodationId());

		var snapshot = bulkOperationMonitor.monitor(
			"accommodation-amenity-null-characterization",
			() -> accommodationService.updateAccommodation(
				fixture.targetAccommodationId(),
				update(null),
				ownerId
			)
		);

		assertThat(snapshot.hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 2)
			.containsEntry(SqlQueryType.INSERT, 1)
			.containsEntry(SqlQueryType.UPDATE, 1)
			.containsEntry(SqlQueryType.TOTAL, 4);
		assertThat(snapshot.hibernateStatementsByType().getOrDefault(SqlQueryType.DELETE, 0)).isZero();
		assertThat(snapshot.hibernateStatementsByType().getOrDefault(SqlQueryType.OTHER, 0)).isZero();
		assertThat(amenityMap(fixture.targetAccommodationId())).isEqualTo(oldMap);
		fixtureService.cleanup(fixture);
	}

	@Test
	@DisplayName("replacement INSERT가 DB에서 실패하면 이미 수행한 derived delete와 parent/history/control을 모두 rollback한다")
	void rollsBackReplacementAfterDatabaseInsertFailure() {
		Fixture fixture = fixtureService.createFixture(ownerId, 4);
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

		assertThatThrownBy(() -> accommodationService.updateAccommodation(
			fixture.targetAccommodationId(),
			update(replacement),
			ownerId
		)).isInstanceOf(IntentionalAmenitySaveFailure.class);

		assertThat(amenityMap(fixture.targetAccommodationId())).isEqualTo(oldTarget);
		assertThat(parentSnapshot(fixture.targetAccommodationId())).isEqualTo(parentBefore);
		assertThat(historySnapshots(fixture.targetAccommodationId())).isEqualTo(historyBefore);
		assertThat(amenityMap(fixture.controlAccommodationId())).isEqualTo(controlBefore);
		fixtureService.cleanup(fixture);
	}

	private void assertFullReplacement(int datasetSize, int activeCodeCount) {
		var response = benchmarkService.run(
			ownerId,
			new AccommodationAmenityDeleteBenchmarkRequest(
				Variant.BEFORE,
				Measurement.FULL_REPLACEMENT,
				datasetSize
			)
		);
		int replacementRows = Math.min(datasetSize, activeCodeCount);

		assertThat(response.activeAmenityCodeCount()).isEqualTo(activeCodeCount);
		assertThat(response.workloadClass()).isEqualTo(
			datasetSize <= activeCodeCount ? WorkloadClass.REALISTIC : WorkloadClass.STRESS
		);
		assertThat(response.oldTargetRowsExpected()).isEqualTo(datasetSize);
		assertThat(response.oldTargetRowsDeleted()).isEqualTo(datasetSize);
		assertThat(response.oldTargetRowsVerified()).isEqualTo(datasetSize);
		assertThat(response.replacementRowsExpected()).isEqualTo(replacementRows);
		assertThat(response.replacementRowsVerified()).isEqualTo(replacementRows);
		assertThat(response.replacementMapVerified()).isEqualTo(response.replacementMapExpected());
		assertThat(response.targetParentPreserved()).isTrue();
		assertThat(response.historyEffectMatched()).isTrue();
		assertThat(response.controlAccommodationPreserved()).isTrue();
		assertThat(response.controlAmenitiesPreserved()).isTrue();
		assertThat(response.verificationSucceeded()).isTrue();
		assertThat(response.operation().hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 3)
			.containsEntry(SqlQueryType.DELETE, datasetSize)
			.containsEntry(SqlQueryType.INSERT, replacementRows + 1)
			.containsEntry(SqlQueryType.UPDATE, 1)
			.containsEntry(SqlQueryType.OTHER, 0)
			.containsEntry(SqlQueryType.TOTAL, datasetSize + replacementRows + 5);
		assertNoJdbcBatchFields(response.operation().jdbcBatchCalls(),
			response.operation().jdbcSubmittedRows(),
			response.operation().jdbcConfiguredBatchSize(),
			response.operation().jdbcAffectedRows());
	}

	private void assertDeleteOnly(int datasetSize, int activeCodeCount) {
		var response = benchmarkService.run(
			ownerId,
			new AccommodationAmenityDeleteBenchmarkRequest(
				Variant.BEFORE,
				Measurement.DELETE_ONLY,
				datasetSize
			)
		);

		assertThat(response.activeAmenityCodeCount()).isEqualTo(activeCodeCount);
		assertThat(response.oldTargetRowsExpected()).isEqualTo(datasetSize);
		assertThat(response.oldTargetRowsDeleted()).isEqualTo(datasetSize);
		assertThat(response.oldTargetRowsVerified()).isEqualTo(datasetSize);
		assertThat(response.replacementRowsExpected()).isZero();
		assertThat(response.replacementRowsVerified()).isZero();
		assertThat(response.replacementMapVerified()).isEmpty();
		assertThat(response.targetParentPreserved()).isTrue();
		assertThat(response.historyEffectMatched()).isTrue();
		assertThat(response.controlAccommodationPreserved()).isTrue();
		assertThat(response.controlAmenitiesPreserved()).isTrue();
		assertThat(response.verificationSucceeded()).isTrue();
		assertThat(response.operation().hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 1)
			.containsEntry(SqlQueryType.DELETE, datasetSize)
			.containsEntry(SqlQueryType.INSERT, 0)
			.containsEntry(SqlQueryType.UPDATE, 0)
			.containsEntry(SqlQueryType.OTHER, 0)
			.containsEntry(SqlQueryType.TOTAL, datasetSize + 1);
		assertNoJdbcBatchFields(response.operation().jdbcBatchCalls(),
			response.operation().jdbcSubmittedRows(),
			response.operation().jdbcConfiguredBatchSize(),
			response.operation().jdbcAffectedRows());
	}

	private void assertNoJdbcBatchFields(
		int calls,
		long rows,
		Integer batchSize,
		Long affectedRows
	) {
		assertThat(calls).isZero();
		assertThat(rows).isZero();
		assertThat(batchSize).isNull();
		assertThat(affectedRows).isNull();
	}

	private AccommodationRequest.Update update(List<AmenityRequest.AmenityInfo> amenities) {
		return new AccommodationRequest.Update(
			null, null, null, null, null, amenities, null, null, null, null
		);
	}

	private List<String> activeAmenityCodes() {
		return jdbcTemplate.queryForList("""
			SELECT code
			FROM common_code_detail
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
			       status, check_in_time, check_out_time, address_id, occupancy_policy_id,
			       created_at, updated_at, created_by, updated_by
			FROM accommodation
			WHERE id = ?
			""", accommodationId);
	}

	private List<Map<String, Object>> historySnapshots(long accommodationId) {
		return new ArrayList<>(jdbcTemplate.queryForList("""
			SELECT id, accommodation_id, status, member_id, change_type, change_reason,
			       valid_from, valid_to, history_created_at, history_created_by
			FROM accommodation_history
			WHERE accommodation_id = ?
			ORDER BY id
			""", accommodationId));
	}

	private long insertAdminMember() {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement("""
				INSERT INTO member (email, nickname, role, status, created_at, updated_at)
				VALUES (?, ?, 'ADMIN', 'ACTIVE', NOW(6), NOW(6))
				""", Statement.RETURN_GENERATED_KEYS);
			statement.setString(1, "amenity-benchmark-admin@example.test");
			statement.setString(2, "amenity-benchmark-admin");
			return statement;
		}, keyHolder);
		assertThat(keyHolder.getKey()).isNotNull();
		return keyHolder.getKey().longValue();
	}

	private static final class IntentionalAmenitySaveFailure extends RuntimeException {
	}
}
