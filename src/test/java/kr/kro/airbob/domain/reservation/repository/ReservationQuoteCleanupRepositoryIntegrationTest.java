package kr.kro.airbob.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
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
import org.springframework.transaction.annotation.Isolation;
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
	ReservationQuoteCleanupRepositoryIntegrationTest.CleanupTransactionTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("예약 quote cleanup 저장소 MySQL 통합 계약 테스트")
class ReservationQuoteCleanupRepositoryIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
	private static final Instant RETENTION_CUTOFF = NOW.minusSeconds(30L * 24 * 60 * 60);

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_reservation_quote_cleanup");

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private CleanupTransaction cleanupTransaction;

	private long oldestConsumedId;
	private long middleUnconsumedId;
	private long newestUnconsumedId;
	private long boundaryId;

	@BeforeEach
	void setUp() {
		clearFixtureRows();
		long memberId = insertMember();
		long accommodationId = insertAccommodation(memberId);
		long reservationId = insertReservation(accommodationId, memberId);

		oldestConsumedId = insertQuote(
			memberId, accommodationId, reservationId, RETENTION_CUTOFF.minusSeconds(2), 1);
		middleUnconsumedId = insertQuote(
			memberId, accommodationId, null, RETENTION_CUTOFF.minusSeconds(1), 2);
		newestUnconsumedId = insertQuote(
			memberId, accommodationId, null, RETENTION_CUTOFF.minusMillis(1), 3);
		boundaryId = insertQuote(
			memberId, accommodationId, null, RETENTION_CUTOFF, 4);
		insertQuote(memberId, accommodationId, null, RETENTION_CUTOFF.plusMillis(1), 5);
	}

	@Test
	@DisplayName("created_at 보존 경계 미만의 consumed·unconsumed quote만 오래된 순서로 batch 삭제한다")
	void deletesOnlyTheOldestRetainedRowsWithinTheBatchSize() {
		List<Long> deleted = cleanupTransaction.cleanupOneBatch(RETENTION_CUTOFF, 2);

		assertThat(deleted).containsExactly(oldestConsumedId, middleUnconsumedId);
		assertThat(existingQuoteIds()).contains(
			newestUnconsumedId,
			boundaryId
		).doesNotContain(oldestConsumedId, middleUnconsumedId);
	}

	@Test
	@DisplayName("동시 cleanup은 잠긴 quote를 기다리지 않고 다음 created_at/id batch를 삭제한다")
	void concurrentCleanupSkipsLockedRowsAndDeletesTheNextBatch() throws Exception {
		CountDownLatch firstBatchLocked = new CountDownLatch(1);
		CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			CompletableFuture<List<Long>> first = CompletableFuture.supplyAsync(
				() -> cleanupTransaction.cleanupOneBatchAndHold(
					RETENTION_CUTOFF,
					1,
					firstBatchLocked,
					releaseFirstTransaction
				),
				executor
			);
			assertThat(firstBatchLocked.await(5, TimeUnit.SECONDS)).isTrue();

			List<Long> second = CompletableFuture.supplyAsync(
				() -> cleanupTransaction.cleanupOneBatch(RETENTION_CUTOFF, 10),
				executor
			).get(2, TimeUnit.SECONDS);

			releaseFirstTransaction.countDown();
			List<Long> firstDeleted = first.get(5, TimeUnit.SECONDS);

			assertThat(firstDeleted).containsExactly(oldestConsumedId);
			assertThat(second).containsExactly(middleUnconsumedId, newestUnconsumedId);
			assertThat(existingQuoteIds()).contains(boundaryId)
				.doesNotContain(oldestConsumedId, middleUnconsumedId, newestUnconsumedId);
		} finally {
			releaseFirstTransaction.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private long insertMember() {
		jdbc.update("""
			INSERT INTO member (email, nickname, role, status, updated_at)
			VALUES ('quote-cleanup@test.com', 'quote-cleanup', 'MEMBER', 'ACTIVE', NOW(6))
			""");
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertAccommodation(long memberId) {
		UUID accommodationUid = UUID.nameUUIDFromBytes(
			"quote-cleanup-accommodation".getBytes(StandardCharsets.UTF_8));
		jdbc.update("""
			INSERT INTO accommodation (
			  member_id, check_in_time, check_out_time, accommodation_uid,
			  updated_at, status, time_zone_id
			) VALUES (?, '15:00:00', '11:00:00', UNHEX(REPLACE(?, '-', '')), NOW(6), 'DRAFT', 'UTC')
			""", memberId, accommodationUid.toString());
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertReservation(long accommodationId, long memberId) {
		LocalDate checkIn = LocalDate.of(2026, 9, 1);
		LocalDate checkOut = checkIn.plusDays(1);
		UUID reservationUid = UUID.nameUUIDFromBytes(
			"quote-cleanup-reservation".getBytes(StandardCharsets.UTF_8));
		jdbc.update("""
			INSERT INTO reservation (
			  reservation_uid, accommodation_id, guest_id, check_in_date, check_out_date,
			  check_in_at, check_out_at, time_zone_id, guest_count, total_price, discount_amount,
			  status, reservation_code, created_at, expires_at, updated_at, currency
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, ?, ?,
			  ?, ?, 'UTC', 2, 100000, 0,
			  'CONFIRMED', 'QCLN000001', NOW(6), ?, NOW(6), 'KRW'
			)
			""",
			reservationUid.toString(), accommodationId, memberId,
			Date.valueOf(checkIn), Date.valueOf(checkOut),
			Timestamp.from(checkIn.atTime(15, 0).toInstant(java.time.ZoneOffset.UTC)),
			Timestamp.from(checkOut.atTime(11, 0).toInstant(java.time.ZoneOffset.UTC)),
			Timestamp.from(NOW.plusSeconds(900)));
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertQuote(
		long memberId,
		long accommodationId,
		Long reservationId,
		Instant createdAt,
		int sequence
	) {
		UUID quoteUid = UUID.nameUUIDFromBytes(
			("quote-cleanup-" + sequence).getBytes(StandardCharsets.UTF_8));
		LocalDate checkIn = LocalDate.of(2026, 10, 1).plusDays(sequence);
		LocalDate checkOut = checkIn.plusDays(1);
		Instant expiresAt = createdAt.plusSeconds(300);
		jdbc.update("""
			INSERT INTO reservation_quote (
			  quote_uid, member_id, accommodation_id, order_name,
			  check_in_date, check_out_date, guest_count, coupon_id,
			  nightly_price, nights, subtotal, discount_amount, amount, currency,
			  quoted_at, expires_at, reservation_id, checked_out_at,
			  created_at, updated_at
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, 'Quote cleanup fixture',
			  ?, ?, 2, NULL,
			  100000, 1, 100000, 0, 100000, 'KRW',
			  ?, ?, ?, ?,
			  ?, ?
			)
			""",
			quoteUid.toString(), memberId, accommodationId,
			Date.valueOf(checkIn), Date.valueOf(checkOut),
			Timestamp.from(createdAt), Timestamp.from(expiresAt), reservationId,
			reservationId == null ? null : Timestamp.from(createdAt.plusSeconds(60)),
			Timestamp.from(createdAt), Timestamp.from(createdAt));
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private List<Long> existingQuoteIds() {
		return jdbc.queryForList("SELECT id FROM reservation_quote ORDER BY id", Long.class);
	}

	private void clearFixtureRows() {
		jdbc.update("DELETE FROM reservation_quote");
		jdbc.update("DELETE FROM reservation_checkout_request");
		jdbc.update("DELETE FROM reservation_history");
		jdbc.update("DELETE FROM member_coupon");
		jdbc.update("DELETE FROM reservation");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM coupon");
		jdbc.update("DELETE FROM member");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class CleanupTransactionTestConfiguration {

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		CleanupTransaction cleanupTransaction(ReservationQuoteRepository repository) {
			return new CleanupTransaction(repository);
		}
	}

	static class CleanupTransaction {
		private final ReservationQuoteRepository repository;

		CleanupTransaction(ReservationQuoteRepository repository) {
			this.repository = repository;
		}

		@Transactional(isolation = Isolation.READ_COMMITTED)
		public List<Long> cleanupOneBatch(Instant cutoff, int batchSize) {
			List<Long> ids = lockIds(cutoff, batchSize);
			if (!ids.isEmpty()) {
				repository.deleteCleanupBatchByIds(ids);
			}
			return ids;
		}

		@Transactional(isolation = Isolation.READ_COMMITTED)
		public List<Long> cleanupOneBatchAndHold(
			Instant cutoff,
			int batchSize,
			CountDownLatch locked,
			CountDownLatch release
		) {
			List<Long> ids = lockIds(cutoff, batchSize);
			locked.countDown();
			awaitRelease(release);
			if (!ids.isEmpty()) {
				repository.deleteCleanupBatchByIds(ids);
			}
			return ids;
		}

		private List<Long> lockIds(Instant cutoff, int batchSize) {
			return repository.findExpiredIdsForCleanup(cutoff, batchSize);
		}

		private void awaitRelease(CountDownLatch release) {
			try {
				if (!release.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("timed out waiting to release quote cleanup transaction");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while holding quote cleanup transaction", exception);
			}
		}
	}
}
