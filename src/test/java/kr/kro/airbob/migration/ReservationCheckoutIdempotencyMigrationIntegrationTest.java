package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ReservationCheckoutIdempotencyMigrationIntegrationTest {

	private static final String TABLE = "reservation_checkout_request";

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_reservation_checkout_idempotency")
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
	void createsTheCheckoutRequestLedgerWithHashedIdentities() throws SQLException {
		try (Connection connection = connection()) {
			assertThat(tableExists(connection, TABLE)).isTrue();
			assertThat(requiredColumns(connection, TABLE)).containsExactlyInAnyOrder(
				"id",
				"member_id",
				"endpoint",
				"key_hash",
				"request_fingerprint",
				"created_at",
				"updated_at"
			);
			assertThat(nullableColumns(connection, TABLE)).containsExactlyInAnyOrder(
				"reservation_id",
				"completed_at"
			);
			assertThat(columnSize(connection, TABLE, "endpoint")).isEqualTo(50);
			assertThat(columnSize(connection, TABLE, "key_hash")).isEqualTo(64);
			assertThat(columnSize(connection, TABLE, "request_fingerprint")).isEqualTo(64);
			assertThat(columnType(connection, TABLE, "key_hash")).isEqualToIgnoringCase("CHAR");
			assertThat(columnType(connection, TABLE, "request_fingerprint")).isEqualToIgnoringCase("CHAR");
		}
	}

	@Test
	void scopesEachIdempotencyKeyToAMemberAndEndpointAndLinksOneReservation() throws SQLException {
		try (Connection connection = connection()) {
			assertThat(indexColumns(connection, TABLE, "uk_reservation_checkout_request_key"))
				.containsExactly("member_id", "endpoint", "key_hash");
			assertThat(indexIsUnique(connection, TABLE, "uk_reservation_checkout_request_key")).isTrue();
			assertThat(indexColumns(connection, TABLE, "uk_reservation_checkout_request_reservation"))
				.containsExactly("reservation_id");
			assertThat(indexIsUnique(connection, TABLE, "uk_reservation_checkout_request_reservation")).isTrue();
		}
	}

	@Test
	void keepsTheLedgerBoundToExistingMembersAndReservations() throws SQLException {
		try (Connection connection = connection()) {
			assertThat(foreignKey(connection, TABLE, "fk_reservation_checkout_request_member"))
				.isEqualTo(new ForeignKey("member_id", "member", "id"));
			assertThat(foreignKey(connection, TABLE, "fk_reservation_checkout_request_reservation"))
				.isEqualTo(new ForeignKey("reservation_id", "reservation", "id"));
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

	private List<String> requiredColumns(Connection connection, String table) throws SQLException {
		return columnsByNullability(connection, table, DatabaseMetaData.columnNoNulls);
	}

	private List<String> nullableColumns(Connection connection, String table) throws SQLException {
		return columnsByNullability(connection, table, DatabaseMetaData.columnNullable);
	}

	private List<String> columnsByNullability(Connection connection, String table, int nullability)
		throws SQLException {
		List<String> columns = new ArrayList<>();
		try (ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), null, table, null)) {
			while (result.next()) {
				if (result.getInt("NULLABLE") == nullability) {
					columns.add(result.getString("COLUMN_NAME").toLowerCase());
				}
			}
		}
		return columns;
	}

	private int columnSize(Connection connection, String table, String column) throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
			assertThat(columns.next()).isTrue();
			return columns.getInt("COLUMN_SIZE");
		}
	}

	private String columnType(Connection connection, String table, String column) throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
			assertThat(columns.next()).isTrue();
			return columns.getString("TYPE_NAME");
		}
	}

	private List<String> indexColumns(Connection connection, String table, String index) throws SQLException {
		List<OrderedColumn> columns = new ArrayList<>();
		try (ResultSet result = connection.getMetaData().getIndexInfo(
			connection.getCatalog(), null, table, false, false)) {
			while (result.next()) {
				if (index.equalsIgnoreCase(result.getString("INDEX_NAME"))) {
					columns.add(new OrderedColumn(
						result.getInt("ORDINAL_POSITION"),
						result.getString("COLUMN_NAME").toLowerCase()
					));
				}
			}
		}
		return columns.stream()
			.sorted(Comparator.comparingInt(OrderedColumn::position))
			.map(OrderedColumn::name)
			.toList();
	}

	private boolean indexIsUnique(Connection connection, String table, String index) throws SQLException {
		try (ResultSet indexes = connection.getMetaData().getIndexInfo(
			connection.getCatalog(), null, table, false, false)) {
			while (indexes.next()) {
				if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
					return !indexes.getBoolean("NON_UNIQUE");
				}
			}
			return false;
		}
	}

	private ForeignKey foreignKey(Connection connection, String table, String constraint) throws SQLException {
		try (ResultSet keys = connection.getMetaData().getImportedKeys(connection.getCatalog(), null, table)) {
			while (keys.next()) {
				if (constraint.equalsIgnoreCase(keys.getString("FK_NAME"))) {
					return new ForeignKey(
						keys.getString("FKCOLUMN_NAME").toLowerCase(),
						keys.getString("PKTABLE_NAME").toLowerCase(),
						keys.getString("PKCOLUMN_NAME").toLowerCase()
					);
				}
			}
		}
		return null;
	}

	private record OrderedColumn(int position, String name) {
	}

	private record ForeignKey(String column, String referencedTable, String referencedColumn) {
	}
}
