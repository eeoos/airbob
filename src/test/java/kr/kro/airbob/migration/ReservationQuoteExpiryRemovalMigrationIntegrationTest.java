package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("V28 예약 견적 유효기간 제거 마이그레이션")
class ReservationQuoteExpiryRemovalMigrationIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_quote_expiry_removal")
		.withUsername("airbob")
		.withPassword("airbob");

	@Test
	@DisplayName("기존 견적·checkout 연결·예약 hold는 보존하고 견적 만료 컬럼과 제약만 제거한다")
	void preservesPopulatedQuotesAndReservationHoldsWhileRemovingQuoteExpiry() throws SQLException {
		migrateTo("27");
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertExistingRows(statement);
			List<Map<String, String>> quotesBefore = quoteSnapshot(statement);
			List<Map<String, String>> reservationsBefore = rows(statement,
				"SELECT id, HEX(reservation_uid), status, expires_at FROM reservation ORDER BY id");

			migrateTo("28");

			assertThat(quoteSnapshot(statement)).isEqualTo(quotesBefore).hasSize(2);
			assertThat(rows(statement,
				"SELECT id, HEX(reservation_uid), status, expires_at FROM reservation ORDER BY id"))
				.isEqualTo(reservationsBefore);
			assertThat(rows(statement, """
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = DATABASE() AND table_name = 'reservation_quote' AND column_name = 'expires_at'
				""")).isEmpty();
			assertThat(rows(statement, """
				SELECT constraint_name AS name FROM information_schema.table_constraints
				WHERE table_schema = DATABASE() AND table_name = 'reservation_quote' AND constraint_type = 'CHECK'
				ORDER BY constraint_name
				""")).extracting(row -> row.get("name"))
				.containsExactly(
					"chk_reservation_quote_checkout", "chk_reservation_quote_dates",
					"chk_reservation_quote_guests", "chk_reservation_quote_price", "chk_reservation_quote_stay_price");

			statement.executeUpdate("""
				INSERT INTO reservation_quote (
				  id, quote_uid, member_id, accommodation_id, order_name, check_in_date, check_out_date,
				  guest_count, nightly_price, nights, subtotal, discount_amount, amount, currency,
				  quoted_at, created_at, updated_at
				) VALUES (
				  3, UUID_TO_BIN('3392d003-5b7a-4eb7-9525-9bfcb5668323'), 1, 1, 'New quote',
				  '2026-09-10', '2026-09-12', 2, 100000, 2, 200000, 0, 200000, 'KRW',
				  '2026-08-25 03:00:00', '2026-08-25 03:00:00', '2026-08-25 03:00:00'
				)
				""");
			assertThat(quoteSnapshot(statement)).hasSize(3);
			assertThatThrownBy(() -> statement.executeUpdate(
				"UPDATE reservation_quote SET guest_count = 0 WHERE id = 3"))
				.isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> statement.executeUpdate(
				"UPDATE reservation_quote SET quote_uid = "
					+ "UUID_TO_BIN('fd044ccd-1d8e-4bb8-8fbd-2521964cd1e0') WHERE id = 3"))
				.isInstanceOf(SQLException.class);
		}
	}

	private void insertExistingRows(Statement statement) throws SQLException {
		statement.executeUpdate("""
			INSERT INTO member (id, email, nickname, role, status, updated_at)
			VALUES (1, 'quote-migration@test.com', 'quote-migration', 'MEMBER', 'ACTIVE', NOW(6))
			""");
		statement.executeUpdate("""
			INSERT INTO accommodation (
			  id, member_id, base_price, currency, check_in_time, check_out_time,
			  accommodation_uid, time_zone_id, updated_at, status
			) VALUES (
			  1, 1, 100000, 'KRW', '15:00:00', '11:00:00',
			  UUID_TO_BIN('bced3f27-ff85-43a2-9f2d-d9c5d5f5fbe7'), 'Asia/Seoul', NOW(6), 'PUBLISHED'
			)
			""");
		statement.executeUpdate("""
			INSERT INTO reservation (
			  id, reservation_uid, accommodation_id, guest_id, check_in_date, check_out_date,
			  check_in_at, check_out_at, time_zone_id, guest_count, total_price, discount_amount,
			  currency, status, reservation_code, expires_at, payment_attempt_required, created_at, updated_at
			) VALUES (
			  1, UUID_TO_BIN('c9be3f07-e8da-45b0-b034-4d8f1cf9d00d'), 1, 1,
			  '2026-09-10', '2026-09-12', '2026-09-10 06:00:00', '2026-09-12 02:00:00',
			  'Asia/Seoul', 2, 200000, 0, 'KRW', 'PAYMENT_PENDING', 'QEXP001',
			  '2026-08-25 03:16:00', TRUE, '2026-08-25 03:01:00', '2026-08-25 03:01:00'
			)
			""");
		statement.executeUpdate("""
			INSERT INTO reservation_quote (
			  id, quote_uid, member_id, accommodation_id, order_name, check_in_date, check_out_date,
			  guest_count, nightly_price, nights, subtotal, discount_amount, amount, currency,
			  quoted_at, expires_at, reservation_id, checked_out_at, created_at, updated_at, created_by, updated_by
			) VALUES
			  (1, UUID_TO_BIN('fd044ccd-1d8e-4bb8-8fbd-2521964cd1e0'), 1, 1, 'Unconsumed old quote',
			   '2026-09-10', '2026-09-12', 2, 100000, 2, 200000, 0, 200000, 'KRW',
			   '2026-08-25 03:00:00', '2026-08-25 03:05:00', NULL, NULL,
			   '2026-08-25 03:00:00', '2026-08-25 03:00:00', 1, 1),
			  (2, UUID_TO_BIN('617aa4f3-e4b9-4070-a04f-a4d9da3bcbad'), 1, 1, 'Checked out quote',
			   '2026-09-10', '2026-09-12', 2, 100000, 2, 200000, 0, 200000, 'KRW',
			   '2026-08-25 03:00:00', '2026-08-25 03:05:00', 1, '2026-08-25 03:01:00',
			   '2026-08-25 03:00:00', '2026-08-25 03:01:00', 1, 1)
			""");
	}

	private List<Map<String, String>> quoteSnapshot(Statement statement) throws SQLException {
		return rows(statement, """
			SELECT id, HEX(quote_uid), member_id, accommodation_id, order_name,
			  check_in_date, check_out_date, guest_count, coupon_id, nightly_price, nights,
			  subtotal, discount_amount, amount, currency, quoted_at, reservation_id, checked_out_at,
			  created_at, updated_at, created_by, updated_by
			FROM reservation_quote ORDER BY id
			""");
	}

	private List<Map<String, String>> rows(Statement statement, String sql) throws SQLException {
		List<Map<String, String>> rows = new ArrayList<>();
		try (ResultSet result = statement.executeQuery(sql)) {
			while (result.next()) {
				Map<String, String> row = new LinkedHashMap<>();
				for (int index = 1; index <= result.getMetaData().getColumnCount(); index++) {
					row.put(result.getMetaData().getColumnLabel(index), result.getString(index));
				}
				rows.add(row);
			}
		}
		return rows;
	}

	private void migrateTo(String version) {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.target(version)
			.load()
			.migrate();
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}
}
