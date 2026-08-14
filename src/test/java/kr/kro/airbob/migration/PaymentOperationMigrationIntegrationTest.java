package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PaymentOperationMigrationIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_payment_operation")
		.withUsername("airbob")
		.withPassword("airbob");

	@Test
	void v17CreatesOperationAndUniqueLedgerLink() throws SQLException {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();

		try (Connection connection = connection()) {
			assertThat(tableExists(connection, "payment_operation")).isTrue();
			assertThat(columnNullable(connection, "payment_operation", "payment_key")).isFalse();
			assertThat(indexIsUnique(connection, "payment_operation", "uk_payment_operation_uid")).isTrue();
			assertThat(indexIsUnique(connection, "payment_operation", "uk_payment_operation_deduplication_key")).isTrue();
			assertThat(indexIsUnique(connection, "payment_transaction", "uk_payment_transaction_operation_id")).isTrue();
			assertThat(indexIsUnique(connection, "member_coupon", "uk_member_coupon_reservation_id")).isTrue();
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}

	private boolean tableExists(Connection connection, String table) throws SQLException {
		try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, table, null)) {
			return tables.next();
		}
	}

	private boolean columnNullable(Connection connection, String table, String column) throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
			assertThat(columns.next()).isTrue();
			return columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
		}
	}

	private boolean indexIsUnique(Connection connection, String table, String index) throws SQLException {
		try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
			while (indexes.next()) {
				if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
					return !indexes.getBoolean("NON_UNIQUE");
				}
			}
			return false;
		}
	}
}
