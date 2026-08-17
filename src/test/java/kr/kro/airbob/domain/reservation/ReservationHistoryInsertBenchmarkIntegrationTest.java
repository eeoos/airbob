package kr.kro.airbob.domain.reservation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.awspring.cloud.s3.S3Template;
import jakarta.persistence.EntityManager;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.monitoring.SqlQueryType;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest.Variant;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBeforeBenchmarkService;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkFixtureService;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkFixtureService.Fixture;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkHoldService;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkHoldService.HoldRemovalSnapshot;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkService;
import kr.kro.airbob.domain.reservation.service.ExpiredReservationCleanupService;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = {
	"spring.cloud.aws.s3.enabled=false",
	"benchmark.bulk-write.enabled=true",
	"benchmark.bulk-write.token=bulk-write-integration-token-123456789",
	"benchmark.bulk-write.allowed-schema=airbob_bulk_write_benchmark",
	"reservation.expiration.history-batch-size=2"
})
@ActiveProfiles({"test", "bulk-write-benchmark"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("ReservationHistory IDENTITY INSERT Before 벌크 쓰기 통합 테스트")
class ReservationHistoryInsertBenchmarkIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbob_bulk_write_benchmark");

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
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
	}

	@Autowired private ReservationHistoryInsertBenchmarkService benchmarkService;
	@Autowired private ReservationHistoryInsertBenchmarkFixtureService fixtureService;
	@Autowired private ReservationHistoryInsertBenchmarkHoldService holdService;
	@Autowired private ReservationHistoryInsertBeforeBenchmarkService beforeService;
	@Autowired private ExpiredReservationCleanupService cleanupService;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private EntityManager entityManager;
	@MockitoSpyBean private ReservationHistoryRepository historyRepository;
	@MockitoSpyBean private JdbcTemplate jdbcTemplate;

	@MockitoBean private ElasticsearchClient elasticsearchClient;
	@MockitoBean private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean private S3Template s3Template;

	private UserInfo requestAdmin;

	@BeforeEach
	void setUp() {
		requestAdmin = new UserInfo(9001L, "127.0.0.1", "HTTP");
		UserContext.set(requestAdmin);
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
		holdService.clearRecording();
		reset(historyRepository, jdbcTemplate);
		jdbcTemplate.update("DELETE FROM reservation_history");
		jdbcTemplate.update("DELETE FROM reservation");
		jdbcTemplate.update("DELETE FROM accommodation");
		jdbcTemplate.update("DELETE FROM member");
	}

	@Test
	@DisplayName("N개 만료 예약은 N개 IDENTITY history INSERT와 N개 dirty-check UPDATE를 만든다")
	void measuresActualIdentityInsertBaseline() throws Exception {
		var response = benchmarkService.run(
			new ReservationHistoryInsertBenchmarkRequest(Variant.BEFORE, 3)
		);

		assertThat(AopUtils.isAopProxy(beforeService)).isTrue();
		assertThat(UserContext.get()).isSameAs(requestAdmin);
		assertThat(response.expectedRows()).isEqualTo(3);
		assertThat(response.verifiedRows()).isEqualTo(3);
		assertThat(response.verificationSucceeded()).isTrue();
		assertThat(response.targetReservationsExpired()).isTrue();
		assertThat(response.targetHistoriesInserted()).isTrue();
		assertThat(response.futurePendingPreserved()).isTrue();
		assertThat(response.nonPendingExpiredPreserved()).isTrue();
		assertThat(response.historySnapshotsPreserved()).isTrue();
		assertThat(response.historyAuditContextPreserved()).isTrue();
		assertThat(response.holdRemovalsMatched()).isTrue();
		assertThat(response.holdRemovalCalls()).isEqualTo(3);
		assertThat(response.redisNetworkExcluded()).isTrue();
		assertThat(response.operation().hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 1)
			.containsEntry(SqlQueryType.INSERT, 3)
			.containsEntry(SqlQueryType.UPDATE, 3)
			.containsEntry(SqlQueryType.DELETE, 0)
			.containsEntry(SqlQueryType.OTHER, 0)
			.containsEntry(SqlQueryType.TOTAL, 7);
		assertThat(response.operation().jdbcBatchCalls()).isZero();
		assertThat(response.operation().jdbcSubmittedRows()).isZero();
		assertThat(response.operation().jdbcConfiguredBatchSize()).isNull();
		assertThat(response.operation().jdbcAffectedRows()).isNull();

		assertThat(countRows("reservation_history")).isZero();
		assertThat(countRows("reservation")).isZero();
		assertThat(countRows("accommodation")).isZero();
		assertThat(countRows("member")).isZero();

		String serialized = objectMapper.writeValueAsString(response);
		assertThat(serialized)
			.doesNotContain("reservation_id")
			.doesNotContain("member_id")
			.doesNotContain("accommodation_id")
			.doesNotContain("9001")
			.doesNotContain("bulk-write-integration-token");
	}

	@Test
	@DisplayName("대상 0건은 Before 서비스 SELECT 1회만 실행하고 history나 hold 제거를 만들지 않는다")
	void supportsEmptyDataset() {
		var response = benchmarkService.run(
			new ReservationHistoryInsertBenchmarkRequest(Variant.BEFORE, 0)
		);

		assertThat(response.verifiedRows()).isZero();
		assertThat(response.verificationSucceeded()).isTrue();
		assertThat(response.holdRemovalCalls()).isZero();
		assertThat(response.operation().hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 1)
			.containsEntry(SqlQueryType.INSERT, 0)
			.containsEntry(SqlQueryType.UPDATE, 0)
			.containsEntry(SqlQueryType.DELETE, 0)
			.containsEntry(SqlQueryType.TOTAL, 1);
	}

	@Test
	@DisplayName("AFTER N=3은 쿠폰 일괄 복원, JDBC history와 dirty-check update를 함께 측정한다")
	void measuresActualAfterCleanup() {
		var response = benchmarkService.run(
			new ReservationHistoryInsertBenchmarkRequest(Variant.AFTER, 3)
		);

		assertThat(AopUtils.isAopProxy(cleanupService)).isTrue();
		assertThat(UserContext.get()).isSameAs(requestAdmin);
		assertThat(response.expectedRows()).isEqualTo(3);
		assertThat(response.verifiedRows()).isEqualTo(3);
		assertThat(response.operation().operationName())
			.isEqualTo(ReservationHistoryInsertBenchmarkService.AFTER_OPERATION_NAME);
		assertThat(response.operation().hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 1)
			.containsEntry(SqlQueryType.INSERT, 0)
			.containsEntry(SqlQueryType.UPDATE, 4)
			.containsEntry(SqlQueryType.TOTAL, 5);
		assertThat(response.operation().jdbcBatchCalls()).isEqualTo(2);
		assertThat(response.operation().jdbcSubmittedRows()).isEqualTo(3);
		assertThat(response.operation().jdbcConfiguredBatchSize()).isEqualTo(2);
		assertThat(response.operation().jdbcAffectedRows())
			.satisfies(value -> assertThat(value == null || value == 3L).isTrue());
		assertThat(response.holdRemovalCalls()).isEqualTo(3);
		assertThat(response.verificationSucceeded()).isTrue();
	}

	@Test
	@DisplayName("AFTER N=0은 proxied 운영 cleanup SELECT 1회만 측정한다")
	void supportsEmptyAfterDataset() {
		var response = benchmarkService.run(
			new ReservationHistoryInsertBenchmarkRequest(Variant.AFTER, 0)
		);

		assertThat(AopUtils.isAopProxy(cleanupService)).isTrue();
		assertThat(response.verifiedRows()).isZero();
		assertThat(response.verificationSucceeded()).isTrue();
		assertThat(response.holdRemovalCalls()).isZero();
		assertThat(response.operation().hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 1)
			.containsEntry(SqlQueryType.INSERT, 0)
			.containsEntry(SqlQueryType.UPDATE, 0)
			.containsEntry(SqlQueryType.TOTAL, 1);
		assertThat(response.operation().jdbcBatchCalls()).isZero();
		assertThat(response.operation().jdbcSubmittedRows()).isZero();
		assertThat(response.operation().jdbcConfiguredBatchSize()).isNull();
		assertThat(response.operation().jdbcAffectedRows()).isNull();
	}

	@Test
	@DisplayName("서로 다른 hold 대상은 Before 서비스 조회 순서와 무관하게 정확히 검증한다")
	void verifiesDistinctHoldRemovalsWithoutDependingOnQueryOrder() {
		Fixture fixture = fixtureService.createFixture(3);
		assertThat(fixture.targets())
			.extracting(target -> List.of(target.checkIn(), target.checkOut()))
			.doesNotHaveDuplicates();
		UserContext.clear();
		holdService.startRecording();
		beforeService.cleanupExpiredPendingReservations();
		var recorded = holdService.finishRecording();
		var reversed = new java.util.ArrayList<>(recorded.removals());
		Collections.reverse(reversed);

		var verification = fixtureService.verify(fixture, new HoldRemovalSnapshot(reversed));

		assertThat(verification.holdRemovalsMatched()).isTrue();
		fixtureService.cleanup(fixture);
		UserContext.set(requestAdmin);
	}

	@Test
	@DisplayName("두 번째 history 저장 실패는 DB 전체를 rollback하지만 첫 hold 제거 호출은 이미 발생한다")
	void capturesCurrentRollbackAndExternalSideEffectBoundary() {
		Fixture fixture = fixtureService.createFixture(3);
		AtomicInteger saveInvocations = new AtomicInteger();
		doAnswer(invocation -> {
			if (saveInvocations.incrementAndGet() == 2) {
				throw new IntentionalHistoryFailure();
			}
			ReservationHistory history = invocation.getArgument(0);
			entityManager.persist(history);
			return history;
		}).when(historyRepository).save(any(ReservationHistory.class));
		UserContext.clear();
		holdService.startRecording();

		assertThatThrownBy(beforeService::cleanupExpiredPendingReservations)
			.isInstanceOf(IntentionalHistoryFailure.class);
		var holdSnapshot = holdService.finishRecording();

		assertThat(holdSnapshot.callCount()).isOne();
		assertThat(countTargetStatus(fixture, "PAYMENT_PENDING")).isEqualTo(3);
		assertThat(countHistories(fixture)).isZero();

		fixtureService.cleanup(fixture);
		fixtureService.cleanup(fixture);
		assertThat(countRows("reservation_history")).isZero();
		assertThat(countRows("reservation")).isZero();
		assertThat(countRows("accommodation")).isZero();
		assertThat(countRows("member")).isZero();
		UserContext.set(requestAdmin);
	}

	@Test
	@DisplayName("AFTER 두 번째 JDBC chunk 실패는 첫 chunk와 dirty-check를 rollback하고 hold를 제거하지 않는다")
	void rollsBackAfterSecondJdbcChunkFailureBeforeHoldRemoval() {
		Fixture fixture = fixtureService.createFixture(3);
		AtomicInteger historyBatches = new AtomicInteger();
		doAnswer(invocation -> {
			if (historyBatches.incrementAndGet() == 2) {
				throw new DataIntegrityViolationException("intentional second chunk failure");
			}
			return invocation.callRealMethod();
		}).when(jdbcTemplate).batchUpdate(
			argThat(sql -> sql.contains("INSERT INTO reservation_history")),
			any(BatchPreparedStatementSetter.class)
		);

		UserContext.clear();
		holdService.startRecording();
		assertThatThrownBy(cleanupService::cleanupExpiredPendingReservations)
			.isInstanceOf(DataIntegrityViolationException.class);
		HoldRemovalSnapshot holds = holdService.finishRecording();

		assertThat(holds.callCount()).isZero();
		verify(jdbcTemplate, times(2)).batchUpdate(
			argThat(sql -> sql.contains("INSERT INTO reservation_history")),
			any(BatchPreparedStatementSetter.class)
		);
		assertThat(countTargetStatus(fixture, "PAYMENT_PENDING")).isEqualTo(3);
		assertThat(countHistories(fixture)).isZero();

		fixtureService.cleanup(fixture);
		UserContext.set(requestAdmin);
	}

	private long countTargetStatus(Fixture fixture, String status) {
		String sql = "SELECT COUNT(*) FROM reservation WHERE status = ? AND id IN ("
			+ placeholders(fixture.targets().size()) + ")";
		Object[] parameters = new Object[fixture.targets().size() + 1];
		parameters[0] = status;
		for (int index = 0; index < fixture.targets().size(); index++) {
			parameters[index + 1] = fixture.targets().get(index).id();
		}
		return jdbcTemplate.queryForObject(sql, Long.class, parameters);
	}

	private long countHistories(Fixture fixture) {
		String sql = "SELECT COUNT(*) FROM reservation_history WHERE reservation_id IN ("
			+ placeholders(fixture.targets().size()) + ")";
		return jdbcTemplate.queryForObject(
			sql,
			Long.class,
			fixture.targets().stream().map(target -> target.id()).toArray()
		);
	}

	private String placeholders(int size) {
		return String.join(", ", Collections.nCopies(size, "?"));
	}

	private long countRows(String table) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
	}

	private static final class IntentionalHistoryFailure extends RuntimeException {
	}
}
