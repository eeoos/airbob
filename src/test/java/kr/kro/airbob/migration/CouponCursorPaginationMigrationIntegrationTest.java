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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("V32 쿠폰 커서 페이지네이션 마이그레이션")
class CouponCursorPaginationMigrationIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11");

	@BeforeEach
	void restoreOwnedV31Database() {
		flyway("31").clean();
		flyway("31").migrate();
	}

	@Test
	@DisplayName("기존 쿠폰·발급·재고와 UTC 마이크로초를 보존하고 정렬 인덱스와 NOT NULL을 적용한다")
	void preservesExistingDataAndAddsCursorConstraintsAndIndexes() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertExistingRows(statement);
			var couponsBefore = rows(statement, "SELECT * FROM coupon ORDER BY id");
			var memberCouponsBefore = rows(statement, "SELECT * FROM member_coupon ORDER BY id");
			var unitsBefore = rows(statement, "SELECT * FROM coupon_issue_unit ORDER BY id");

			assertThat(flyway("32").migrate().migrationsExecuted).isEqualTo(1);
			assertThat(flyway("32").migrate().migrationsExecuted).isZero();

			assertThat(rows(statement, "SELECT * FROM coupon ORDER BY id")).isEqualTo(couponsBefore);
			assertThat(rows(statement, "SELECT * FROM member_coupon ORDER BY id")).isEqualTo(memberCouponsBefore);
			assertThat(rows(statement, "SELECT * FROM coupon_issue_unit ORDER BY id")).isEqualTo(unitsBefore);
			assertThat(createdAtDefinition(statement))
				.containsExactly(Map.of("is_nullable", "NO", "datetime_precision", "6"));
			assertThat(indexColumns(statement, "member_coupon", "idx_member_coupon_member_created_id"))
				.containsExactly("member_id:A", "created_at:D", "id:D");
			assertThat(indexColumns(statement, "coupon", "idx_coupon_active_issue_start_id"))
				.containsExactly("is_active:A", "issue_start_at:D", "id:D");

			assertThatThrownBy(() -> statement.executeUpdate(
				"UPDATE member_coupon SET created_at = NULL WHERE id = 11"))
				.isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> statement.executeUpdate("""
				INSERT INTO member_coupon (member_id, coupon_id, used, created_at, updated_at)
				VALUES (2, 2, false, NULL, '2026-10-16 02:00:00.123456')
				"""))
				.isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> statement.executeUpdate("""
				INSERT INTO member_coupon (member_id, coupon_id, used, updated_at)
				VALUES (2, 2, false, '2026-10-16 02:00:00.123456')
				"""))
				.isInstanceOf(SQLException.class);
			assertThat(rows(statement, "SELECT * FROM member_coupon ORDER BY id")).isEqualTo(memberCouponsBefore);
		}
	}

	@Test
	@DisplayName("발급 시각이 누락되면 기존 데이터와 테이블 구조를 변경하기 전에 정합성 복구를 요구한다")
	void refusesMissingIssuanceTimestampBeforeChangingDataOrTableSchema() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertExistingRows(statement);
			statement.executeUpdate("UPDATE member_coupon SET created_at = NULL WHERE id = 11");
			var couponsBefore = rows(statement, "SELECT * FROM coupon ORDER BY id");
			var memberCouponsBefore = rows(statement, "SELECT * FROM member_coupon ORDER BY id");
			var unitsBefore = rows(statement, "SELECT * FROM coupon_issue_unit ORDER BY id");

			assertThatThrownBy(() -> flyway("32").migrate())
				.hasMessageContaining("Reconcile member_coupon.created_at from trusted issuance evidence before V32");

			assertThat(rows(statement, "SELECT * FROM coupon ORDER BY id")).isEqualTo(couponsBefore);
			assertThat(rows(statement, "SELECT * FROM member_coupon ORDER BY id")).isEqualTo(memberCouponsBefore);
			assertThat(rows(statement, "SELECT * FROM coupon_issue_unit ORDER BY id")).isEqualTo(unitsBefore);
			assertThat(createdAtDefinition(statement))
				.containsExactly(Map.of("is_nullable", "YES", "datetime_precision", "6"));
			assertThat(indexColumns(statement, "member_coupon", "idx_member_coupon_member_created_id")).isEmpty();
			assertThat(indexColumns(statement, "coupon", "idx_coupon_active_issue_start_id")).isEmpty();
		}
	}

	private void insertExistingRows(Statement statement) throws SQLException {
		statement.executeUpdate("""
			INSERT INTO coupon (
			  id, name, discount_type, discount_value, is_active, total_quantity,
			  issue_start_at, issue_end_at, usable_from, usable_until, stock_prepared_at, created_at, updated_at
			) VALUES
			  (1, 'prepared', 'FIXED_AMOUNT', 1000, true, 4,
			   '2026-10-15 01:00:00.123456', '2026-10-17 02:00:00.123456',
			   '2026-10-15 01:00:00.123456', '2026-11-17 02:00:00.123456',
			   '2026-10-14 23:00:00.654321', '2026-10-14 22:00:00.654321', '2026-10-14 23:00:00.654321'),
			  (2, 'historical', 'FIXED_AMOUNT', 500, false, NULL,
			   '2026-10-15 01:00:00.123456', '2026-10-17 02:00:00.123456',
			   '2026-10-15 01:00:00.123456', '2026-11-17 02:00:00.123456',
			   NULL, '2026-10-14 22:00:00.654321', '2026-10-14 23:00:00.654321')
			""");
		statement.executeUpdate("""
			INSERT INTO member (id, email, nickname, role, status, updated_at)
			VALUES (1, 'cursor-1@test.com', 'member-1', 'MEMBER', 'ACTIVE', '2026-10-14 22:00:00.654321'),
			       (2, 'cursor-2@test.com', 'member-2', 'MEMBER', 'ACTIVE', '2026-10-14 22:00:00.654321')
			""");
		statement.executeUpdate("""
			INSERT INTO member_coupon (
			  id, member_id, coupon_id, used, used_at, reservation_id, created_at, updated_at, created_by, updated_by
			) VALUES
			  (11, 1, 1, true, '2026-10-16 01:30:00.123456', 42,
			   '2026-10-15 01:00:00.654321', '2026-10-16 01:30:00.123456', 1, 1),
			  (12, 1, 2, false, NULL, NULL, '2026-10-15 01:00:00.654321', '2026-10-15 01:00:00.654321', 1, 1),
			  (13, 2, 1, false, NULL, NULL, '2026-10-15 01:00:00.654322', '2026-10-15 01:00:00.654322', 2, 2)
			""");
		statement.executeUpdate("INSERT INTO coupon_issue_unit (id, coupon_id, unit_no) VALUES (91, 1, 3), (92, 1, 4)");
	}

	private List<Map<String, String>> createdAtDefinition(Statement statement) throws SQLException {
		return rows(statement, """
			SELECT is_nullable AS is_nullable, datetime_precision AS datetime_precision
			FROM information_schema.columns
			WHERE table_schema = DATABASE() AND table_name = 'member_coupon' AND column_name = 'created_at'
			""");
	}

	private List<String> indexColumns(Statement statement, String table, String index) throws SQLException {
		return rows(statement, """
			SELECT CONCAT(column_name, ':', collation) AS column_order
			FROM information_schema.statistics
			WHERE table_schema = DATABASE() AND table_name = '%s' AND index_name = '%s'
			ORDER BY seq_in_index
			""".formatted(table, index)).stream().map(row -> row.get("column_order")).toList();
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

	private Flyway flyway(String target) {
		return Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.target(target)
			.cleanDisabled(false)
			.load();
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}
}
