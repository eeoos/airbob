package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CouponPeriodMigrationIntegrationTest {

	private static final LocalDateTime LEGACY_START = LocalDateTime.of(2026, 7, 18, 10, 0);
	private static final LocalDateTime LEGACY_END = LocalDateTime.of(2026, 8, 18, 0, 0);

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_coupon_period")
		.withUsername("airbob")
		.withPassword("airbob");

	@Test
	@DisplayName("기존 쿠폰 기간을 발급 기간과 사용 기간 양쪽에 보존한다")
	void copiesLegacyPeriodIntoIssueAndUsagePeriods() throws SQLException {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.target(MigrationVersion.fromVersion("13"))
			.load()
			.migrate();

		long couponId = insertLegacyCoupon();

		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();

		try (Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("""
				select issue_start_at, issue_end_at, usable_from, usable_until
				from coupon
				where id = ?
				""")) {
			statement.setLong(1, couponId);
			try (ResultSet result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getTimestamp("issue_start_at").toLocalDateTime()).isEqualTo(LEGACY_START);
				assertThat(result.getTimestamp("issue_end_at").toLocalDateTime()).isEqualTo(LEGACY_END);
				assertThat(result.getTimestamp("usable_from").toLocalDateTime()).isEqualTo(LEGACY_START);
				assertThat(result.getTimestamp("usable_until").toLocalDateTime()).isEqualTo(LEGACY_END);
			}
		}

		try (Connection connection = connection()) {
			assertThat(columnExists(connection, "coupon", "start_date")).isFalse();
			assertThat(columnExists(connection, "coupon", "end_date")).isFalse();
			assertThat(columnNullable(connection, "coupon", "issue_start_at")).isFalse();
			assertThat(columnNullable(connection, "coupon", "issue_end_at")).isFalse();
		}
	}

	private long insertLegacyCoupon() throws SQLException {
		try (Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("""
				insert into coupon (
				  name, discount_type, discount_value, start_date, end_date,
				  is_active, total_quantity, issued_quantity, updated_at
				) values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp(6))
				""", Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, "기존 쿠폰");
			statement.setString(2, "FIXED_AMOUNT");
			statement.setInt(3, 10_000);
			statement.setTimestamp(4, Timestamp.valueOf(LEGACY_START));
			statement.setTimestamp(5, Timestamp.valueOf(LEGACY_END));
			statement.setBoolean(6, true);
			statement.setInt(7, 100);
			statement.setInt(8, 0);
			statement.executeUpdate();

			try (ResultSet keys = statement.getGeneratedKeys()) {
				assertThat(keys.next()).isTrue();
				return keys.getLong(1);
			}
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}

	private boolean columnExists(Connection connection, String table, String column) throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
			return columns.next();
		}
	}

	private boolean columnNullable(Connection connection, String table, String column) throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
			assertThat(columns.next()).isTrue();
			return columns.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNullable;
		}
	}
}
