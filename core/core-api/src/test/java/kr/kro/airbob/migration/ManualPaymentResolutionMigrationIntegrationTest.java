package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ManualPaymentResolutionMigrationIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_manual_payment_resolution")
		.withUsername("airbob")
		.withPassword("airbob");

	@BeforeAll
	static void migrate() {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
	}

	@Test
	void supportsHonestSystemAndAdminActors() throws SQLException {
		try (Connection connection = connection()) {
			assertThat(nullableColumn(connection, "payment_operation_resolution", "actor_member_id")).isTrue();
			assertThat(requiredColumn(connection, "payment_operation_resolution", "actor_type")).isTrue();
			assertThat(columnSize(connection, "payment_operation_resolution", "resolution_action")).isEqualTo(50);
			assertThat(columnSize(connection, "payment_operation_resolution", "reason")).isEqualTo(512);
			assertThat(columnSize(connection, "payment_operation_resolution", "evidence_reference")).isEqualTo(512);
		}
	}

	@Test
	void addsTheStableManualReviewQueueIndex() throws SQLException {
		try (Connection connection = connection()) {
			assertThat(indexColumns(
				connection,
				"payment_operation",
				"idx_payment_operation_manual_review_queue"
			)).containsExactly("status", "review_required_at", "id");
		}
	}

	@Test
	void v20PersistsEligibilityAndTiesEveryAuditToADispatchCycle() throws SQLException {
		try (Connection connection = connection()) {
			assertThat(requiredColumn(
				connection, "payment_operation", "not_paid_resolution_eligible")).isTrue();
			assertThat(requiredColumn(
				connection, "payment_operation_resolution", "dispatch_generation")).isTrue();
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}

	private boolean nullableColumn(Connection connection, String table, String column) throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
			return columns.next() && columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
		}
	}

	private boolean requiredColumn(Connection connection, String table, String column) throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
			return columns.next() && columns.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls;
		}
	}

	private int columnSize(Connection connection, String table, String column) throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
			assertThat(columns.next()).isTrue();
			return columns.getInt("COLUMN_SIZE");
		}
	}

	private List<String> indexColumns(Connection connection, String table, String index) throws SQLException {
		List<String> columns = new ArrayList<>();
		try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
			while (indexes.next()) {
				if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
					columns.add(indexes.getString("COLUMN_NAME").toLowerCase());
				}
			}
		}
		return columns;
	}
}
