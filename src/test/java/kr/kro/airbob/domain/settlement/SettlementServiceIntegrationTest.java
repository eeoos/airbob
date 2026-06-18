package kr.kro.airbob.domain.settlement;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

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
import kr.kro.airbob.domain.settlement.service.SettlementService;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@DisplayName("호스트 정산 서비스 통합 테스트")
class SettlementServiceIntegrationTest {

	@Autowired private SettlementService settlementService;
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

	private static final YearMonth MAY = YearMonth.of(2026, 5);
	private static final LocalDate MAY_1 = LocalDate.of(2026, 5, 1);

	private long host1;
	private long host2;

	@BeforeEach
	void setup() {
		clean();
		host1 = insertMember("host1");
		host2 = insertMember("host2");
		long acc1 = insertAccommodation(host1);
		long acc2 = insertAccommodation(host1);
		long acc3 = insertAccommodation(host2);

		// 5월: host1 = (acc1 100000 + acc2 50000) gross 150000, acc1 부분취소 20000 → net 130000
		//      host2 = acc3 80000 → net 80000
		long r1 = insertReservation("R1", acc1, host2);
		long r2 = insertReservation("R2", acc2, host2);
		long r3 = insertReservation("R3", acc3, host1);
		insertConfirm(r1, 100000, LocalDate.of(2026, 5, 10));
		insertConfirm(r2, 50000, LocalDate.of(2026, 5, 12));
		insertConfirm(r3, 80000, LocalDate.of(2026, 5, 15));
		insertCancel(r1, 20000, LocalDate.of(2026, 5, 20));
	}

	@AfterEach
	void tearDown() {
		clean();
	}

	@Test
	@DisplayName("월 정산 생성: 호스트별 net/commission(ROUND(net*0.03))/payout 과 PENDING + CREATE 이력")
	void generateMonthCreatesPendingSettlements() {
		settlementService.generateMonth(MAY);

		// host1: gross 150000, refund 20000, net 130000, commission 3900, payout 126100
		assertSettlement(host1, 150000, 20000, 130000, 3900, 126100, "PENDING");
		// host2: gross 80000, net 80000, commission 2400, payout 77600
		assertSettlement(host2, 80000, 0, 80000, 2400, 77600, "PENDING");

		assertThat(commissionRate(host1)).isEqualByComparingTo("0.03");
		assertThat(historyCount(settlementId(host1))).isEqualTo(1); // CREATE
	}

	@Test
	@DisplayName("지급 처리: PENDING → PAID + settled_at + STATUS_CHANGE 이력")
	void markPaidTransitionsAndRecordsHistory() {
		settlementService.generateMonth(MAY);
		long id = settlementId(host1);

		settlementService.markPaid(id);

		Map<String, Object> row = settlementRow(host1);
		assertThat(row.get("status")).isEqualTo("PAID");
		assertThat(row.get("settled_at")).isNotNull();
		assertThat(historyCount(id)).isEqualTo(2); // CREATE + STATUS_CHANGE
	}

	@Test
	@DisplayName("멱등성: 재집계 시 PENDING은 갱신, PAID는 불변(skip)")
	void regenerateKeepsPaidImmutableAndUpdatesPending() {
		settlementService.generateMonth(MAY);
		long paidId = settlementId(host1);
		settlementService.markPaid(paidId); // host1 PAID

		settlementService.generateMonth(MAY); // 재집계

		// host1: PAID 유지, 금액 그대로, 이력 추가 없음(skip)
		assertThat(settlementRow(host1).get("status")).isEqualTo("PAID");
		assertSettlement(host1, 150000, 20000, 130000, 3900, 126100, "PAID");
		assertThat(historyCount(paidId)).isEqualTo(2); // CREATE + STATUS_CHANGE (재집계로 추가 안 됨)

		// host2: PENDING 유지, 재집계로 UPDATE 이력 추가
		assertThat(settlementRow(host2).get("status")).isEqualTo("PENDING");
		assertThat(historyCount(settlementId(host2))).isEqualTo(2); // CREATE + UPDATE

		// 월·호스트 유니크 → 중복 행 없음
		Integer rows = jdbc.queryForObject(
			"SELECT COUNT(*) FROM settlement WHERE settlement_month = ?", Integer.class, MAY_1);
		assertThat(rows).isEqualTo(2);
	}

	// ===== helpers =====

	private void assertSettlement(long hostId, long gross, long refund, long net,
		long commission, long payout, String status) {
		Map<String, Object> row = settlementRow(hostId);
		assertThat(((Number)row.get("gross_amount")).longValue()).isEqualTo(gross);
		assertThat(((Number)row.get("refund_amount")).longValue()).isEqualTo(refund);
		assertThat(((Number)row.get("net_amount")).longValue()).isEqualTo(net);
		assertThat(((Number)row.get("commission_amount")).longValue()).isEqualTo(commission);
		assertThat(((Number)row.get("payout_amount")).longValue()).isEqualTo(payout);
		assertThat(row.get("status")).isEqualTo(status);
	}

	private Map<String, Object> settlementRow(long hostId) {
		return jdbc.queryForMap(
			"SELECT * FROM settlement WHERE host_id = ? AND settlement_month = ?", hostId, MAY_1);
	}

	private long settlementId(long hostId) {
		return jdbc.queryForObject(
			"SELECT id FROM settlement WHERE host_id = ? AND settlement_month = ?", Long.class, hostId, MAY_1);
	}

	private BigDecimal commissionRate(long hostId) {
		return jdbc.queryForObject(
			"SELECT commission_rate FROM settlement WHERE host_id = ? AND settlement_month = ?",
			BigDecimal.class, hostId, MAY_1);
	}

	private int historyCount(long settlementId) {
		return jdbc.queryForObject(
			"SELECT COUNT(*) FROM settlement_history WHERE settlement_id = ?", Integer.class, settlementId);
	}

	private void clean() {
		jdbc.update("DELETE FROM settlement_history");
		jdbc.update("DELETE FROM settlement");
		jdbc.update("DELETE FROM payment_transaction");
		jdbc.update("DELETE FROM reservation");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM member");
	}

	private long insertMember(String nickname) {
		jdbc.update("INSERT INTO member (nickname, status, updated_at) VALUES (?, 'ACTIVE', NOW(6))", nickname);
		return jdbc.queryForObject("SELECT id FROM member ORDER BY id DESC LIMIT 1", Long.class);
	}

	private long insertAccommodation(long hostId) {
		jdbc.update("""
			INSERT INTO accommodation
				(member_id, check_in_time, check_out_time, accommodation_uid, status, base_price, updated_at)
			VALUES (?, '15:00:00', '11:00:00', UUID_TO_BIN(UUID()), 'PUBLISHED', 100000, NOW(6))
			""", hostId);
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

	private void insertCancel(long reservationId, long cancelAmount, LocalDate canceledDate) {
		jdbc.update("""
			INSERT INTO payment_transaction
				(reservation_id, transaction_type, status, cancel_amount, canceled_at, created_at, updated_at)
			VALUES (?, 'PARTIAL_CANCEL', 'PARTIAL_CANCELED', ?, ?, ?, NOW(6))
			""", reservationId, cancelAmount, canceledDate.atTime(9, 0), canceledDate.atTime(9, 0));
	}
}
