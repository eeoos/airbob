package kr.kro.airbob.domain.statistics;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.awspring.cloud.s3.S3Template;
import kr.kro.airbob.domain.statistics.dto.RevenueStatsResponse;
import kr.kro.airbob.domain.statistics.service.RevenueStatsBenchmarkService;
import kr.kro.airbob.domain.statistics.service.RevenueStatsService;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = {
	"spring.cloud.aws.s3.enabled=false",
	"benchmark.read-model.enabled=true",
	"benchmark.read-model.token=test-token"
})
@ActiveProfiles({"test", "read-model-benchmark"})
@DisplayName("일일 매출 사전집계(daily_revenue_stats) 통합 테스트")
class DailyRevenueStatsIntegrationTest {

	@Autowired private RevenueStatsService revenueStatsService;
	@Autowired private RevenueStatsBenchmarkService benchmarkService;
	@Autowired private JdbcTemplate jdbc;

	@MockitoBean private ElasticsearchClient elasticsearchClient;
	@MockitoBean private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean private S3Template s3Template;

	@Container
	private static final MySQLContainer<?> mySQLContainer = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_test");

	@Container
	private static final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
		.withExposedPorts(6379);

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", mySQLContainer::getJdbcUrl);
		registry.add("spring.datasource.username", mySQLContainer::getUsername);
		registry.add("spring.datasource.password", mySQLContainer::getPassword);
		registry.add("spring.flyway.url", mySQLContainer::getJdbcUrl);
		registry.add("spring.flyway.user", mySQLContainer::getUsername);
		registry.add("spring.flyway.password", mySQLContainer::getPassword);
		registry.add("spring.data.redis.host", redisContainer::getHost);
		registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379).toString());
		registry.add("spring.kafka.consumer.enabled", () -> "false");
		registry.add("spring.kafka.producer.enabled", () -> "false");
	}

	private static final LocalDate D1 = LocalDate.of(2026, 6, 10);
	private static final LocalDate D2 = LocalDate.of(2026, 6, 11);

	private long acc1;
	private long acc2;

	@BeforeEach
	void setup() {
		clean();
		long host = insertMember("host");
		acc1 = insertAccommodation(host);
		acc2 = insertAccommodation(host);

		// D1: acc1 CONFIRM 10000 + 20000 (2건), acc2 CONFIRM 50000 (1건)
		long r1 = insertReservation("R1", acc1, host);
		long r2 = insertReservation("R2", acc1, host);
		long r3 = insertReservation("R3", acc2, host);
		insertConfirm(r1, 10000, D1);
		insertConfirm(r2, 20000, D1);
		insertConfirm(r3, 50000, D1);

		// D2: acc1 PARTIAL_CANCEL 5000 (canceled_at=D2), acc2 CONFIRM 7000
		insertCancel(r1, "PARTIAL_CANCEL", 5000, D2);
		long r4 = insertReservation("R4", acc2, host);
		insertConfirm(r4, 7000, D2);
	}

	@AfterEach
	void tearDown() {
		clean();
	}

	@Test
	@DisplayName("배치 재집계: 숙소별 gross/refund/net 을 올바른 일자 버킷에 적재한다")
	void aggregateGrainCorrectness() {
		revenueStatsService.backfill(D1, D2);

		// (D1, acc1): gross 30000, net 30000, pay 2
		assertRow(D1, acc1, 30000, 0, 30000, 2, 0);
		// (D1, acc2): gross 50000, pay 1
		assertRow(D1, acc2, 50000, 0, 50000, 1, 0);
		// (D2, acc1): 환불은 canceled_at 기준 D2 에 차감 → refund 5000, net -5000
		assertRow(D2, acc1, 0, 5000, -5000, 0, 1);
		// (D2, acc2): gross 7000
		assertRow(D2, acc2, 7000, 0, 7000, 1, 0);

		Integer rowCount = jdbc.queryForObject("SELECT COUNT(*) FROM daily_revenue_stats", Integer.class);
		assertThat(rowCount).isEqualTo(4);
	}

	@Test
	@DisplayName("재집계는 멱등하다: 같은 일자를 두 번 돌려도 결과/행수가 동일하다")
	void idempotentRecompute() {
		revenueStatsService.recompute(D1);
		revenueStatsService.recompute(D1);

		Integer d1Rows = jdbc.queryForObject(
			"SELECT COUNT(*) FROM daily_revenue_stats WHERE stat_date = ?", Integer.class, D1);
		assertThat(d1Rows).isEqualTo(2); // acc1, acc2
		assertRow(D1, acc1, 30000, 0, 30000, 2, 0);
	}

	@Test
	@DisplayName("stats 조회(after)와 raw 원장 집계(before)가 일자별로 동일한 결과를 낸다")
	void statsEqualsRaw() {
		revenueStatsService.backfill(D1, D2);

		RevenueStatsResponse.DailyRevenues statsResponse =
			revenueStatsService.getDailyRevenue(D1, D2);
		RevenueStatsResponse.DailyRevenues rawResponse =
			benchmarkService.getDailyRevenueBefore(D1, D2);
		List<RevenueStatsResponse.DailyRevenue> stats = statsResponse.items();
		List<RevenueStatsResponse.DailyRevenue> raw = rawResponse.items();

		assertThat(statsResponse.source()).isEqualTo("stats");
		assertThat(rawResponse.source()).isEqualTo("raw");
		assertThat(stats).containsExactlyElementsOf(raw);

		// 일자 롤업 값 검증: D1 net 80000(pay 3), D2 net 2000(gross 7000 - refund 5000)
		assertThat(stats).containsExactly(
			new RevenueStatsResponse.DailyRevenue(D1, 80000, 0, 80000, 3, 0),
			new RevenueStatsResponse.DailyRevenue(D2, 7000, 5000, 2000, 1, 1)
		);
	}

	// ===== helpers =====

	private void assertRow(LocalDate d, long accId, long gross, long refund, long net, int payCnt, int refundCnt) {
		var row = jdbc.queryForMap(
			"SELECT gross_amount, refund_amount, net_amount, payment_count, refund_count "
				+ "FROM daily_revenue_stats WHERE stat_date = ? AND accommodation_id = ?", d, accId);
		assertThat(((Number)row.get("gross_amount")).longValue()).isEqualTo(gross);
		assertThat(((Number)row.get("refund_amount")).longValue()).isEqualTo(refund);
		assertThat(((Number)row.get("net_amount")).longValue()).isEqualTo(net);
		assertThat(((Number)row.get("payment_count")).intValue()).isEqualTo(payCnt);
		assertThat(((Number)row.get("refund_count")).intValue()).isEqualTo(refundCnt);
	}

	private void clean() {
		jdbc.update("DELETE FROM daily_revenue_stats");
		jdbc.update("DELETE FROM payment_transaction");
		jdbc.update("DELETE FROM reservation");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM member");
	}

	private long insertMember(String nickname) {
		jdbc.update("INSERT INTO member (nickname, status, updated_at) VALUES (?, 'ACTIVE', NOW(6))", nickname);
		return jdbc.queryForObject("SELECT id FROM member ORDER BY id DESC LIMIT 1", Long.class);
	}

	private long insertAccommodation(long memberId) {
		jdbc.update("""
			INSERT INTO accommodation
				(member_id, check_in_time, check_out_time, accommodation_uid, status, base_price, updated_at)
			VALUES (?, '15:00:00', '11:00:00', UUID_TO_BIN(UUID()), 'PUBLISHED', 100000, NOW(6))
			""", memberId);
		return jdbc.queryForObject("SELECT id FROM accommodation ORDER BY id DESC LIMIT 1", Long.class);
	}

	private long insertReservation(String code, long accommodationId, long guestId) {
		jdbc.update("""
			INSERT INTO reservation
				(reservation_uid, reservation_code, accommodation_id, guest_id, check_in, check_out,
				 guest_count, total_price, status, expires_at, currency, created_at, updated_at)
			VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, '2026-07-01 15:00:00', '2026-07-03 11:00:00',
				 2, 200000, 'CONFIRMED', '2026-07-01 00:00:00', 'KRW', NOW(6), NOW(6))
			""", code, accommodationId, guestId);
		return jdbc.queryForObject("SELECT id FROM reservation WHERE reservation_code = ?", Long.class, code);
	}

	private void insertConfirm(long reservationId, long amount, LocalDate createdDate) {
		jdbc.update("""
			INSERT INTO payment_transaction
				(reservation_id, transaction_type, status, amount, created_at, updated_at)
			VALUES (?, 'CONFIRM', 'DONE', ?, ?, NOW(6))
			""", reservationId, amount, createdDate.atTime(12, 0));
	}

	private void insertCancel(long reservationId, String type, long cancelAmount, LocalDate canceledDate) {
		jdbc.update("""
			INSERT INTO payment_transaction
				(reservation_id, transaction_type, status, cancel_amount, canceled_at, created_at, updated_at)
			VALUES (?, ?, 'PARTIAL_CANCELED', ?, ?, ?, NOW(6))
			""", reservationId, type, cancelAmount, canceledDate.atTime(9, 0), canceledDate.atTime(9, 0));
	}
}
