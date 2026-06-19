package kr.kro.airbob.domain.settlement;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
@DisplayName("정산 생성 동시성 테스트(월 단위 분산 락)")
class SettlementGenerationConcurrencyTest {

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

	@BeforeEach
	void setup() {
		clean();
		long host1 = insertMember("host1");
		long host2 = insertMember("host2");
		long acc1 = insertAccommodation(host1);
		long acc2 = insertAccommodation(host2);
		long r1 = insertReservation("R1", acc1, host2);
		long r2 = insertReservation("R2", acc2, host1);
		insertConfirm(r1, 100000, LocalDate.of(2026, 5, 10)); // host1
		insertConfirm(r2, 50000, LocalDate.of(2026, 5, 12));  // host2
	}

	@AfterEach
	void tearDown() {
		clean();
	}

	@Test
	@DisplayName("같은 달을 동시에 생성해도 중복키 예외 없이 호스트당 1행만 생성된다")
	void concurrentGenerateMonthHasNoDuplicateOrError() throws InterruptedException {
		int threadCount = 30;
		ExecutorService pool = Executors.newFixedThreadPool(threadCount);
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threadCount);
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

		for (int i = 0; i < threadCount; i++) {
			pool.submit(() -> {
				ready.countDown();
				try {
					start.await();
					settlementService.generateMonth(MAY);
				} catch (Throwable t) {
					errors.add(t);
				} finally {
					done.countDown();
				}
			});
		}

		ready.await();
		start.countDown();
		done.await(60, TimeUnit.SECONDS);
		pool.shutdownNow();

		assertThat(errors).isEmpty(); // 중복키/락 예외 없음

		Integer rows = jdbc.queryForObject(
			"SELECT COUNT(*) FROM settlement WHERE settlement_month = ?", Integer.class, MAY_1);
		assertThat(rows).isEqualTo(2); // 호스트당 1행, 중복 없음

		// 금액 정확성(host1 net 100000 → commission 3000 → payout 97000)
		Long host1Net = jdbc.queryForObject(
			"SELECT s.net_amount FROM settlement s JOIN member m ON m.id = s.host_id "
				+ "WHERE m.nickname = 'host1' AND s.settlement_month = ?", Long.class, MAY_1);
		assertThat(host1Net).isEqualTo(100000);
	}

	// ===== helpers =====

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
}
