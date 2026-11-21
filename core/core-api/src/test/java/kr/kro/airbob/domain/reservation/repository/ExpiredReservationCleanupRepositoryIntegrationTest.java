package kr.kro.airbob.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	JpaAuditingConfig.class,
	QueryDslConfig.class,
	ExpiredReservationCleanupRepositoryIntegrationTest.CleanupQueryTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("만료 예약 cleanup 저장소 통합 계약 테스트")
class ExpiredReservationCleanupRepositoryIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_expired_reservation_cleanup");

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private CleanupQueryTransaction cleanupQuery;

	private long oldestExpiredId;
	private long middleExpiredId;
	private long boundaryExpiredId;

	@BeforeEach
	void setUp() {
		clearFixtureRows();
		long memberId = insertMember();
		long accommodationId = insertAccommodation(memberId);

		oldestExpiredId = insertReservation(
			accommodationId, memberId, "PAYMENT_PENDING", NOW.minusSeconds(2), 1);
		middleExpiredId = insertReservation(
			accommodationId, memberId, "PAYMENT_PENDING", NOW.minusSeconds(1), 2);
		boundaryExpiredId = insertReservation(
			accommodationId, memberId, "PAYMENT_PENDING", NOW, 3);
		insertReservation(
			accommodationId, memberId, "PAYMENT_PENDING", NOW.plusMillis(1), 4);
		insertReservation(
			accommodationId, memberId, "PAYMENT_PROCESSING", NOW.minusSeconds(10), 5);
	}

	@Test
	@DisplayName("cleanup 조회는 만료된 PAYMENT_PENDING만 오래된 순서로 batch 크기만큼 잠근다")
	void locksOnlyTheOldestExpiredPendingReservationsWithinBatchSize() {
		List<Long> firstBatch = cleanupQuery.findIds(NOW, 2);

		assertThat(firstBatch).containsExactly(oldestExpiredId, middleExpiredId);
	}

	@Test
	@DisplayName("동시 cleanup 조회는 이미 잠긴 행을 기다리지 않고 다음 batch를 가져온다")
	void concurrentCleanupSkipsRowsLockedByAnotherTransaction() throws Exception {
		CountDownLatch firstBatchLocked = new CountDownLatch(1);
		CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			CompletableFuture<List<Long>> first = CompletableFuture.supplyAsync(
				() -> cleanupQuery.findIdsAndHold(
					NOW, 1, firstBatchLocked, releaseFirstTransaction),
				executor
			);
			assertThat(firstBatchLocked.await(5, TimeUnit.SECONDS)).isTrue();

			List<Long> secondBatch = CompletableFuture.supplyAsync(
				() -> cleanupQuery.findIds(NOW, 10),
				executor
			).get(2, TimeUnit.SECONDS);

			releaseFirstTransaction.countDown();
			List<Long> firstBatch = first.get(5, TimeUnit.SECONDS);

			assertThat(firstBatch).containsExactly(oldestExpiredId);
			assertThat(secondBatch).containsExactly(middleExpiredId, boundaryExpiredId);
		} finally {
			releaseFirstTransaction.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private long insertMember() {
		jdbc.update("""
			INSERT INTO member (email, nickname, role, status, updated_at)
			VALUES ('reservation-cleanup@test.com', 'reservation-cleanup', 'MEMBER', 'ACTIVE', NOW(6))
			""");
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertAccommodation(long memberId) {
		UUID accommodationUid = UUID.nameUUIDFromBytes(
			"reservation-cleanup-accommodation".getBytes(StandardCharsets.UTF_8));
		jdbc.update("""
			INSERT INTO accommodation (
			  member_id, check_in_time, check_out_time, accommodation_uid,
			  updated_at, status, time_zone_id
			) VALUES (?, '15:00:00', '11:00:00', UNHEX(REPLACE(?, '-', '')), NOW(6), 'DRAFT', 'UTC')
			""", memberId, accommodationUid.toString());
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertReservation(
		long accommodationId,
		long memberId,
		String status,
		Instant expiresAt,
		int sequence
	) {
		LocalDate checkIn = LocalDate.of(2026, 9, 1).plusDays(sequence);
		LocalDate checkOut = checkIn.plusDays(1);
		UUID reservationUid = UUID.nameUUIDFromBytes(
			("reservation-cleanup-" + sequence).getBytes(StandardCharsets.UTF_8));
		jdbc.update("""
			INSERT INTO reservation (
			  reservation_uid, accommodation_id, guest_id, check_in_date, check_out_date,
			  check_in_at, check_out_at, time_zone_id, guest_count, total_price, discount_amount,
			  status, reservation_code, created_at, expires_at, updated_at, currency
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, ?, ?,
			  ?, ?, 'UTC', 2, 100000, 0,
			  ?, ?, NOW(6), ?, NOW(6), 'KRW'
			)
			""",
			reservationUid.toString(), accommodationId, memberId,
			Date.valueOf(checkIn), Date.valueOf(checkOut),
			Timestamp.from(checkIn.atTime(15, 0).toInstant(java.time.ZoneOffset.UTC)),
			Timestamp.from(checkOut.atTime(11, 0).toInstant(java.time.ZoneOffset.UTC)),
			status, "CLN" + String.format("%07d", sequence), Timestamp.from(expiresAt));
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void clearFixtureRows() {
		jdbc.update("DELETE FROM reservation_history");
		jdbc.update("DELETE FROM member_coupon");
		jdbc.update("DELETE FROM reservation");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM member");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class CleanupQueryTestConfiguration {

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		CleanupQueryTransaction cleanupQueryTransaction(ReservationRepository repository) {
			return new CleanupQueryTransaction(repository);
		}
	}

	static class CleanupQueryTransaction {
		private final ReservationRepository repository;

		CleanupQueryTransaction(ReservationRepository repository) {
			this.repository = repository;
		}

		@Transactional
		public List<Long> findIds(Instant cutoff, int batchSize) {
			return repository.findExpiredPendingBatchForCleanup(cutoff, batchSize).stream()
				.map(reservation -> reservation.getId())
				.toList();
		}

		@Transactional
		public List<Long> findIdsAndHold(
			Instant cutoff,
			int batchSize,
			CountDownLatch locked,
			CountDownLatch release
		) {
			List<Long> ids = repository.findExpiredPendingBatchForCleanup(cutoff, batchSize).stream()
				.map(reservation -> reservation.getId())
				.toList();
			locked.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("timed out waiting to release cleanup transaction");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while holding cleanup transaction", exception);
			}
			return ids;
		}
	}
}
