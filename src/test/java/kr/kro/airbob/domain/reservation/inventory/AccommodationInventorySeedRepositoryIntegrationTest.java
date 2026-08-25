package kr.kro.airbob.domain.reservation.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("실제 MySQL inventory rolling seed")
class AccommodationInventorySeedRepositoryIntegrationTest {

	private static final long MEMBER_ID = 71_001L;
	private static final long ACCOMMODATION_ID = 72_001L;
	private static final long RESERVATION_ID = 73_001L;
	private static final LocalDate START = LocalDate.of(2026, 9, 10);
	private static final LocalDate PAST_START = LocalDate.of(2025, 1, 1);
	private static final Instant HOLD_EXPIRES_AT = Instant.parse("2026-08-25T03:15:00.123456Z");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_inventory_seed")
		.withUsername("airbob")
		.withPassword("airbob");

	private static JdbcTemplate jdbc;
	private static AccommodationInventoryDayRepository repository;

	@BeforeAll
	static void setUpDatabase() {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		repository = new AccommodationInventoryDayRepository(jdbc);
		insertOwnerFixture();
	}

	@Test
	@DisplayName("반복 seed는 기존 HOLD/OCCUPIED owner tuple을 보존하고 누락된 날짜만 FREE로 추가한다")
	void repeatedSeedNeverOverwritesAnExistingOwner() {
		repository.seedMissingDays(
			ACCOMMODATION_ID, START.datesUntil(START.plusDays(2)).toList());
		jdbc.update("""
			UPDATE accommodation_inventory_day
			SET state = 'HOLD', reservation_id = ?, hold_expires_at = ?
			WHERE accommodation_id = ? AND stay_date = ?
			""", RESERVATION_ID, Timestamp.from(HOLD_EXPIRES_AT),
			ACCOMMODATION_ID, Date.valueOf(START));
		jdbc.update("""
			UPDATE accommodation_inventory_day
			SET state = 'OCCUPIED', reservation_id = ?, hold_expires_at = NULL
			WHERE accommodation_id = ? AND stay_date = ?
			""", RESERVATION_ID, ACCOMMODATION_ID, Date.valueOf(START.plusDays(1)));

		repository.seedMissingDays(ACCOMMODATION_ID, List.of(START.plusDays(2)));

		assertThat(repository.findSnapshot(ACCOMMODATION_ID, START, START.plusDays(3)))
			.containsExactly(
				new AccommodationInventoryDay(
					ACCOMMODATION_ID, START, AccommodationInventoryState.HOLD,
					RESERVATION_ID, HOLD_EXPIRES_AT),
				new AccommodationInventoryDay(
					ACCOMMODATION_ID, START.plusDays(1), AccommodationInventoryState.OCCUPIED,
					RESERVATION_ID, null),
				new AccommodationInventoryDay(
					ACCOMMODATION_ID, START.plusDays(2), AccommodationInventoryState.FREE,
					null, null)
			);
	}

	@Test
	@DisplayName("bounded retention deletes only past FREE rows and never touches HOLD/OCCUPIED")
	void retentionDeletesOnlyPastFreeRows() {
		repository.seedMissingDays(
			ACCOMMODATION_ID, PAST_START.datesUntil(PAST_START.plusDays(4)).toList());
		jdbc.update("""
			UPDATE accommodation_inventory_day
			SET state = 'HOLD', reservation_id = ?, hold_expires_at = ?
			WHERE accommodation_id = ? AND stay_date = ?
			""", RESERVATION_ID, Timestamp.from(HOLD_EXPIRES_AT),
			ACCOMMODATION_ID, Date.valueOf(PAST_START.plusDays(2)));
		jdbc.update("""
			UPDATE accommodation_inventory_day
			SET state = 'OCCUPIED', reservation_id = ?, hold_expires_at = NULL
			WHERE accommodation_id = ? AND stay_date = ?
			""", RESERVATION_ID, ACCOMMODATION_ID, Date.valueOf(PAST_START.plusDays(3)));

		assertThat(repository.deletePastFreeDays(PAST_START.plusMonths(1), 1)).isOne();
		assertThat(repository.findSnapshot(
			ACCOMMODATION_ID, PAST_START, PAST_START.plusDays(4)))
			.extracting(AccommodationInventoryDay::stayDate)
			.containsExactly(
				PAST_START.plusDays(1), PAST_START.plusDays(2), PAST_START.plusDays(3));

		assertThat(repository.deletePastFreeDays(PAST_START.plusMonths(1), 100)).isOne();
		assertThat(repository.findSnapshot(
			ACCOMMODATION_ID, PAST_START, PAST_START.plusDays(4)))
			.containsExactly(
				new AccommodationInventoryDay(
					ACCOMMODATION_ID, PAST_START.plusDays(2),
					AccommodationInventoryState.HOLD, RESERVATION_ID, HOLD_EXPIRES_AT),
				new AccommodationInventoryDay(
					ACCOMMODATION_ID, PAST_START.plusDays(3),
					AccommodationInventoryState.OCCUPIED, RESERVATION_ID, null)
			);
	}

	private static void insertOwnerFixture() {
		jdbc.update("""
			INSERT INTO member (id, email, nickname, role, status, updated_at)
			VALUES (?, 'inventory-seed@test.com', 'inventory-seed', 'MEMBER', 'ACTIVE', NOW(6))
			""", MEMBER_ID);
		jdbc.update("""
			INSERT INTO accommodation (
			  id, member_id, base_price, currency, check_in_time, check_out_time,
			  accommodation_uid, time_zone_id, updated_at, status
			) VALUES (
			  ?, ?, 100000, 'KRW', '15:00:00', '11:00:00',
			  UUID_TO_BIN('3263887c-1c83-4310-8031-038bbadf3fb1'), 'Asia/Seoul', NOW(6), 'PUBLISHED'
			)
			""", ACCOMMODATION_ID, MEMBER_ID);
		jdbc.update("""
			INSERT INTO reservation (
			  id, reservation_uid, accommodation_id, guest_id,
			  check_in_date, check_out_date, check_in_at, check_out_at, time_zone_id,
			  guest_count, total_price, discount_amount, currency, status, reservation_code,
			  expires_at, payment_attempt_required, created_at, updated_at
			) VALUES (
			  ?, UUID_TO_BIN('f8e1734d-6f7b-447b-a1ae-33436eb1d399'), ?, ?,
			  ?, ?, '2026-09-10 06:00:00', '2026-09-12 02:00:00', 'Asia/Seoul',
			  2, 200000, 0, 'KRW', 'PAYMENT_PENDING', 'SED001',
			  '2026-08-25 03:15:00.123456', FALSE, NOW(6), NOW(6)
			)
			""", RESERVATION_ID, ACCOMMODATION_ID, MEMBER_ID,
			Date.valueOf(START), Date.valueOf(START.plusDays(2)));
	}
}
