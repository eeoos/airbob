package kr.kro.airbob.domain.reservation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbob_bulk_write_benchmark");

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
	}

	@Autowired private ReservationHistoryInsertBenchmarkService benchmarkService;
	@Autowired private ReservationHistoryInsertBenchmarkFixtureService fixtureService;
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
	@MockitoBean(name = "redissonClient") private RedissonClient redissonClient;

	private UserInfo requestAdmin;

	@BeforeEach
	void setUp() {
		requestAdmin = new UserInfo(9001L, "127.0.0.1", "HTTP");
		UserContext.set(requestAdmin);
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
		reset(historyRepository, jdbcTemplate);
		jdbcTemplate.update("DELETE FROM member_coupon");
		jdbcTemplate.update("DELETE FROM coupon");
		jdbcTemplate.update("DELETE FROM reservation_history");
		jdbcTemplate.update("DELETE FROM accommodation_inventory_day");
		jdbcTemplate.update("DELETE FROM reservation");
		jdbcTemplate.update("DELETE FROM accommodation");
		jdbcTemplate.update("DELETE FROM member");
	}

	@Test
	@DisplayName("N개 만료 예약은 이력 INSERT와 예약 갱신·쿠폰 복원 UPDATE를 각각 N개 만든다")
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
		assertThat(response.operation().hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 1)
			.containsEntry(SqlQueryType.INSERT, 3)
			.containsEntry(SqlQueryType.UPDATE, 6)
			.containsEntry(SqlQueryType.DELETE, 0)
			.containsEntry(SqlQueryType.OTHER, 0)
			.containsEntry(SqlQueryType.TOTAL, 10);
		assertThat(response.operation().jdbcBatchCalls()).isZero();
		assertThat(response.operation().jdbcSubmittedRows()).isZero();
		assertThat(response.operation().jdbcConfiguredBatchSize()).isNull();
		assertThat(response.operation().jdbcAffectedRows()).isNull();

		assertThat(countRows("reservation_history")).isZero();
		assertThat(countRows("accommodation_inventory_day")).isZero();
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
	@DisplayName("대상 0건은 Before 서비스 SELECT 1회만 실행하고 history를 만들지 않는다")
	void supportsEmptyDataset() {
		var response = benchmarkService.run(
			new ReservationHistoryInsertBenchmarkRequest(Variant.BEFORE, 0)
		);

		assertThat(response.verifiedRows()).isZero();
		assertThat(response.verificationSucceeded()).isTrue();
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
	@DisplayName("BEFORE 만료는 사용 쿠폰을 복원하고 반복 실행은 데이터를 바꾸지 않는다")
	void restoresUsedCouponsWithTheReservation() {
		Fixture fixture = fixtureService.createFixture(3);
		attachUsedCoupons(fixture);
		UserContext.clear();

		beforeService.cleanupExpiredPendingReservations();
		assertThat(countTargetStatus(fixture, "EXPIRED")).isEqualTo(3);
		assertThat(countHistories(fixture)).isEqualTo(3);
		assertThat(jdbcTemplate.queryForList(
			"SELECT reservation_id FROM member_coupon WHERE used=false AND used_at IS NULL ORDER BY reservation_id",
			Long.class)).containsExactlyElementsOf(fixture.targets().stream().map(target -> target.id()).toList());
		beforeService.cleanupExpiredPendingReservations();
		assertThat(countHistories(fixture)).isEqualTo(3);
		assertThat(countRows("member_coupon")).isEqualTo(3);
	}

	@Test
	@DisplayName("두 번째 history 저장 실패는 쿠폰 복원·재고·예약·이력을 모두 rollback한다")
	void rollsBackBeforeServiceWhenHistorySaveFails() {
		Fixture fixture = fixtureService.createFixture(3);
		attachUsedCoupons(fixture);
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

		assertThatThrownBy(beforeService::cleanupExpiredPendingReservations)
			.isInstanceOf(IntentionalHistoryFailure.class);

		assertThat(countTargetStatus(fixture, "PAYMENT_PENDING")).isEqualTo(3);
		assertThat(countHistories(fixture)).isZero();
		assertThat(countTargetInventoryState(fixture, "HOLD")).isEqualTo(6);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM member_coupon WHERE used=true AND used_at IS NOT NULL AND reservation_id IS NOT NULL",
			Long.class)).isEqualTo(3);

		jdbcTemplate.update("DELETE FROM member_coupon");
		jdbcTemplate.update("DELETE FROM coupon");
		fixtureService.cleanup(fixture);
		fixtureService.cleanup(fixture);
		assertThat(countRows("reservation_history")).isZero();
		assertThat(countRows("accommodation_inventory_day")).isZero();
		assertThat(countRows("reservation")).isZero();
		assertThat(countRows("accommodation")).isZero();
		assertThat(countRows("member")).isZero();
		UserContext.set(requestAdmin);
	}

	@Test
	@DisplayName("AFTER 두 번째 JDBC chunk 실패는 첫 chunk와 dirty-check를 rollback한다")
	void rollsBackAfterSecondJdbcChunkFailure() {
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
		assertThatThrownBy(cleanupService::cleanupExpiredPendingReservations)
			.isInstanceOf(DataIntegrityViolationException.class);

		verify(jdbcTemplate, times(2)).batchUpdate(
			argThat(sql -> sql.contains("INSERT INTO reservation_history")),
			any(BatchPreparedStatementSetter.class)
		);
		assertThat(countTargetStatus(fixture, "PAYMENT_PENDING")).isEqualTo(3);
		assertThat(countHistories(fixture)).isZero();
		assertThat(countTargetInventoryState(fixture, "HOLD")).isEqualTo(6);

		fixtureService.cleanup(fixture);
		UserContext.set(requestAdmin);
	}

	private void attachUsedCoupons(Fixture fixture) {
		for (var reservation : fixture.targets()) {
			String name = "expiry-test-" + reservation.id();
			jdbcTemplate.update("""
				INSERT INTO coupon (name,discount_type,discount_value,min_payment_price,max_discount_amount,
				is_active,total_quantity,issued_quantity,usable_from,usable_until,issue_start_at,issue_end_at,updated_at)
				VALUES (?,'FIXED_AMOUNT',1000,0,1000,true,1,1,
				'2026-01-01','2027-01-01','2026-01-01','2027-01-01',CURRENT_TIMESTAMP(6))
				""", name);
			Long couponId = jdbcTemplate.queryForObject("SELECT id FROM coupon WHERE name=?", Long.class, name);
			jdbcTemplate.update("""
				INSERT INTO member_coupon (member_id,coupon_id,used,used_at,reservation_id,created_at,updated_at)
				VALUES (?,?,true,CURRENT_TIMESTAMP(6),?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
				""", fixture.memberId(), couponId, reservation.id());
		}
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

	private long countTargetInventoryState(Fixture fixture, String state) {
		String sql = "SELECT COUNT(*) FROM accommodation_inventory_day WHERE state = ? AND reservation_id IN ("
			+ placeholders(fixture.targets().size()) + ")";
		Object[] parameters = new Object[fixture.targets().size() + 1];
		parameters[0] = state;
		for (int index = 0; index < fixture.targets().size(); index++) {
			parameters[index + 1] = fixture.targets().get(index).id();
		}
		return jdbcTemplate.queryForObject(sql, Long.class, parameters);
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
