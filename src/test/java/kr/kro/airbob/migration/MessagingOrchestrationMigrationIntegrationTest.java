package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MessagingOrchestrationMigrationIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_messaging_orchestration")
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
	void outboxHasExplicitRoutingIdentityDeduplicationAndCleanupContract() throws SQLException {
		try (Connection connection = connection()) {
			assertThat(requiredColumn(connection, "outbox", "event_id")).isTrue();
			assertThat(requiredColumn(connection, "outbox", "destination")).isTrue();
			assertThat(requiredColumn(connection, "outbox", "partition_key")).isTrue();
			assertThat(requiredColumn(connection, "outbox", "event_version")).isTrue();
			assertThat(requiredColumn(connection, "outbox", "occurred_at")).isTrue();
			assertThat(nullableColumn(connection, "outbox", "deduplication_key")).isTrue();
			assertThat(indexIsUnique(connection, "outbox", "uk_outbox_event_id")).isTrue();
			assertThat(indexIsUnique(connection, "outbox", "uk_outbox_deduplication_key")).isTrue();
			assertThat(indexExists(connection, "outbox", "idx_outbox_cleanup")).isTrue();
		}
	}

	@Test
	void paymentOperationSupportsBoundedDispatchCancellationAndManualResolution() throws SQLException {
		try (Connection connection = connection()) {
			assertThat(requiredColumn(connection, "payment_operation", "next_action")).isTrue();
			assertThat(requiredColumn(connection, "payment_operation", "dispatch_generation")).isTrue();
			assertThat(requiredColumn(connection, "payment_operation", "queued_at")).isTrue();
			assertThat(nullableColumn(connection, "payment_operation", "review_required_at")).isTrue();
			assertThat(nullableColumn(connection, "payment_operation", "cancellation_reason")).isTrue();
			assertThat(requiredColumn(connection, "payment_operation", "manual_reconciliation_pending")).isTrue();
			assertThat(requiredColumn(connection, "payment_operation", "manual_review_count")).isTrue();
			assertThat(columnExists(connection, "payment_operation", "last_enqueued_at")).isFalse();
			assertThat(indexExists(connection, "payment_operation", "idx_payment_operation_retry_due")).isTrue();
			assertThat(indexExists(connection, "payment_operation", "idx_payment_operation_lease_due")).isTrue();
			assertThat(indexExists(connection, "payment_operation", "idx_payment_operation_queued")).isTrue();
			assertThat(tableExists(connection, "payment_operation_resolution")).isTrue();
			assertThat(indexExists(
				connection, "payment_operation_resolution", "idx_payment_operation_resolution_audit")).isTrue();
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

	private boolean columnExists(Connection connection, String table, String column) throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
			return columns.next();
		}
	}

	private boolean requiredColumn(Connection connection, String table, String column) throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
			return columns.next() && columns.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls;
		}
	}

	private boolean nullableColumn(Connection connection, String table, String column) throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
			return columns.next() && columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
		}
	}

	private boolean indexExists(Connection connection, String table, String index) throws SQLException {
		try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
			while (indexes.next()) {
				if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
					return true;
				}
			}
			return false;
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
