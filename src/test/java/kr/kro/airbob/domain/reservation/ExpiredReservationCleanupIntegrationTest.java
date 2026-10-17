package kr.kro.airbob.domain.reservation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.coupon.service.CouponTimeProvider;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventoryDayRepository;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryBatchWriter;
import kr.kro.airbob.domain.reservation.service.ExpiredReservationCleanupService;

@DataJpaTest(properties = "reservation.expiration.history-batch-size=2")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	JpaAuditingConfig.class, QueryDslConfig.class, CouponTimeProvider.class, CouponUsageService.class,
	AccommodationInventoryDayRepository.class, ReservationInventoryService.class,
	ReservationHistoryBatchWriter.class, ExpiredReservationCleanupService.class,
	ExpiredReservationCleanupIntegrationTest.FixedClockConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("만료 예약 정리와 JDBC 이력 저장의 MySQL 트랜잭션 테스트")
class ExpiredReservationCleanupIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");
	private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 1, 0, 0);
	private static final LocalDateTime USED_AT = LocalDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC);
	private static final long FUTURE_PENDING_ID = 101;
	private static final long CONFIRMED_ID = 102;

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_cleanup_history");

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private ExpiredReservationCleanupService cleanupService;
	@MockitoSpyBean private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		UserContext.clear();
		jdbc.update("DELETE FROM reservation_history");
		jdbc.update("DELETE FROM accommodation_inventory_day");
		jdbc.update("DELETE FROM member_coupon");
		jdbc.update("DELETE FROM reservation");
		jdbc.update("DELETE FROM coupon");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM member");
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
	}

	@Test
	@DisplayName("만료 예약의 상태·재고·쿠폰·이력을 함께 반영하고 다른 예약은 유지한다")
	void commitsCleanupAndHistoryTogether() {
		createFixture(3);

		assertThat(cleanupService.cleanupExpiredPendingReservations()).isEqualTo(3);

		assertThat(count("SELECT COUNT(*) FROM reservation_history")).isEqualTo(3);
		for (long id = 1; id <= 3; id++) {
			assertReservationStatus(id, "EXPIRED");
			assertCouponState(id, false);
			assertThat(jdbc.queryForMap("SELECT * FROM reservation_history WHERE reservation_id = ?", id))
				.containsEntry("reservation_code", reservationCode(id))
				.containsEntry("accommodation_id", 20L)
				.containsEntry("guest_id", 10L)
				.containsEntry("total_price", 200_000L)
				.containsEntry("status", "EXPIRED")
				.containsEntry("change_type", "STATUS_CHANGE")
				.containsEntry("change_reason", "결제 시간 초과")
				.containsEntry("source_system", "BATCH")
				.containsEntry("created_by", 10L)
				.containsEntry("history_created_by", null)
				.containsEntry("client_ip", null);
			assertThat(jdbc.queryForObject(
				"SELECT history_created_at FROM reservation_history WHERE reservation_id = ?",
				LocalDateTime.class, id)).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
		}
		assertThat(count("""
			SELECT COUNT(*) FROM accommodation_inventory_day
			WHERE state = 'FREE' AND reservation_id IS NULL AND hold_expires_at IS NULL
			""")).isEqualTo(6);
		assertControlsPreserved();
	}

	@Test
	@DisplayName("만료 대상이 없으면 이력과 상태를 변경하지 않는다")
	void skipsCleanupWhenNoReservationHasExpired() {
		createFixture(0);

		assertThat(cleanupService.cleanupExpiredPendingReservations()).isZero();

		assertThat(count("SELECT COUNT(*) FROM reservation_history")).isZero();
		verify(jdbc, never()).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
		assertControlsPreserved();
	}

	@Test
	@DisplayName("두 번째 이력 배치 실패 시 첫 배치·예약 상태·재고·쿠폰 복원을 모두 롤백한다")
	void rollsBackAllChangesWhenSecondHistoryBatchFails() {
		createFixture(3);
		AtomicInteger historyBatches = new AtomicInteger();
		doAnswer(invocation -> {
			if (historyBatches.incrementAndGet() == 2) {
				throw new DataIntegrityViolationException("intentional second history batch failure");
			}
			return invocation.callRealMethod();
		}).when(jdbc).batchUpdate(
			argThat(sql -> sql.contains("INSERT INTO reservation_history")),
			any(BatchPreparedStatementSetter.class)
		);

		assertThatThrownBy(cleanupService::cleanupExpiredPendingReservations)
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("intentional second history batch failure");

		assertThat(historyBatches.get()).isEqualTo(2);
		assertThat(count("SELECT COUNT(*) FROM reservation_history")).isZero();
		for (long id = 1; id <= 3; id++) {
			assertReservationStatus(id, "PAYMENT_PENDING");
			assertInventoryState(id, "HOLD", NOW);
			assertCouponState(id, true);
		}
		assertControlsPreserved();
	}

	private void createFixture(int expiredCount) {
		jdbc.update("""
			INSERT INTO member (id, email, nickname, role, status, created_at, updated_at)
			VALUES (10, 'cleanup-history@test.com', 'cleanup-history', 'MEMBER', 'ACTIVE', ?, ?)
			""", CREATED_AT, CREATED_AT);
		jdbc.update("""
			INSERT INTO accommodation (
				id, member_id, check_in_time, check_out_time, accommodation_uid, status,
				name, base_price, currency, time_zone_id, created_at, updated_at
			) VALUES (20, 10, '15:00:00', '11:00:00', UUID_TO_BIN(?), 'PUBLISHED',
				'cleanup-history', 100000, 'KRW', 'UTC', ?, ?)
			""", UUID.randomUUID().toString(), CREATED_AT, CREATED_AT);
		for (long id = 1; id <= expiredCount; id++) {
			insertReservationWithInventoryAndCoupon(id, "PAYMENT_PENDING", NOW);
		}
		insertReservationWithInventoryAndCoupon(FUTURE_PENDING_ID, "PAYMENT_PENDING", NOW.plusSeconds(1));
		insertReservationWithInventoryAndCoupon(CONFIRMED_ID, "CONFIRMED", NOW.minusSeconds(1));
	}

	private void insertReservationWithInventoryAndCoupon(long id, String status, Instant expiresAt) {
		LocalDate checkIn = LocalDate.of(2030, 1, 1).plusDays(id * 3);
		LocalDate checkOut = checkIn.plusDays(2);
		jdbc.update("""
			INSERT INTO reservation (
				id, reservation_uid, reservation_code, accommodation_id, guest_id,
				check_in_date, check_out_date, check_in_at, check_out_at, time_zone_id,
				guest_count, total_price, discount_amount, currency, status, expires_at,
				created_at, updated_at, created_by, updated_by
			) VALUES (?, UUID_TO_BIN(?), ?, 20, 10, ?, ?, ?, ?, 'UTC',
				2, 200000, 10000, 'KRW', ?, ?, ?, ?, 10, 10)
			""", id, UUID.randomUUID().toString(), reservationCode(id), checkIn, checkOut,
			checkIn.atTime(15, 0), checkOut.atTime(11, 0), status,
			LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC), CREATED_AT, CREATED_AT);
		boolean pending = "PAYMENT_PENDING".equals(status);
		checkIn.datesUntil(checkOut).forEach(day -> jdbc.update("""
			INSERT INTO accommodation_inventory_day (
				accommodation_id, stay_date, state, reservation_id, hold_expires_at
			) VALUES (20, ?, ?, ?, ?)
			""", day, pending ? "HOLD" : "OCCUPIED", id,
			pending ? LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC) : null));
		jdbc.update("""
			INSERT INTO coupon (id, name, discount_type, discount_value, min_payment_price,
				issue_start_at, issue_end_at, usable_from, usable_until, is_active,
				total_quantity, created_at, updated_at)
			VALUES (?, 'cleanup-coupon', 'FIXED_AMOUNT', 10000, 50000, ?, ?, ?, ?, true, 10, ?, ?)
			""", id, CREATED_AT, CREATED_AT.plusYears(1), CREATED_AT, CREATED_AT.plusYears(1),
			CREATED_AT, CREATED_AT);
		jdbc.update("""
			INSERT INTO member_coupon (
				member_id, coupon_id, used, used_at, reservation_id, created_at, updated_at
			) VALUES (10, ?, true, ?, ?, ?, ?)
			""", id, USED_AT, id, CREATED_AT, CREATED_AT);
	}

	private void assertControlsPreserved() {
		assertReservationStatus(FUTURE_PENDING_ID, "PAYMENT_PENDING");
		assertInventoryState(FUTURE_PENDING_ID, "HOLD", NOW.plusSeconds(1));
		assertCouponState(FUTURE_PENDING_ID, true);
		assertReservationStatus(CONFIRMED_ID, "CONFIRMED");
		assertInventoryState(CONFIRMED_ID, "OCCUPIED", null);
		assertCouponState(CONFIRMED_ID, true);
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE reservation_id IN (101, 102)"))
			.isZero();
	}

	private void assertReservationStatus(long id, String status) {
		assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id))
			.isEqualTo(status);
	}

	private void assertInventoryState(long reservationId, String state, Instant expiresAt) {
		assertThat(jdbc.queryForList("""
			SELECT state, hold_expires_at FROM accommodation_inventory_day WHERE reservation_id = ?
			""", reservationId)).hasSize(2).allSatisfy(row -> {
				assertThat(row.get("state")).isEqualTo(state);
				assertThat(row.get("hold_expires_at"))
					.isEqualTo(expiresAt == null ? null : LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
			});
	}

	private void assertCouponState(long reservationId, boolean used) {
		assertThat(jdbc.queryForObject(
			"SELECT used FROM member_coupon WHERE reservation_id = ?", Boolean.class, reservationId))
			.isEqualTo(used);
		assertThat(jdbc.queryForObject(
			"SELECT used_at FROM member_coupon WHERE reservation_id = ?", LocalDateTime.class, reservationId))
			.isEqualTo(used ? USED_AT : null);
	}

	private long count(String sql) {
		return jdbc.queryForObject(sql, Long.class);
	}

	private String reservationCode(long id) {
		return "CLN%07d".formatted(id);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {
		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
