package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("V25-V27 accommodation inventory day migrations")
class AccommodationInventoryMigrationIntegrationTest {

	private static final String TABLE = "accommodation_inventory_day";

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_inventory_migration")
		.withUsername("airbob")
		.withPassword("airbob");

	@Test
	@DisplayName("cutover rejects existing reservations, then creates the exact empty-data inventory contract")
	void guardsZeroDataCutoverAndCreatesInventoryContract() throws Exception {
		migrateThroughV24();
		insertLegacyReservationFixture();

		assertThatThrownBy(this::migrateAll)
			.as("V25 must not silently cut over reservation rows that have no inventory owner")
			.isInstanceOf(RuntimeException.class);
		assertThat(tableExists(TABLE)).isFalse();
		assertThat(indexColumns("reservation", "uk_reservation_id_accommodation")).isEmpty();

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("DELETE FROM reservation");
		}
		repairAndMigrateThroughV25();
		assertThat(indexColumns("reservation", "uk_reservation_id_accommodation"))
			.containsExactly("id", "accommodation_id");

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE accommodation_inventory_day (broken INT NOT NULL)");
		}
		assertThatThrownBy(this::migrateAll)
			.as("a V26 failure must not require replaying V25 non-transactional DDL")
			.isInstanceOf(RuntimeException.class);
		assertThat(indexColumns("reservation", "uk_reservation_id_accommodation"))
			.containsExactly("id", "accommodation_id");
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE accommodation_inventory_day");
		}
		repairAndMigrateAll();

		assertThat(tableExists(TABLE)).isTrue();
		assertThat(primaryKeyColumns(TABLE)).containsExactly("accommodation_id", "stay_date");
		assertThat(columnDefinition(TABLE, "state"))
			.isEqualTo(new ColumnDefinition("VARCHAR", 32, false));
		assertThat(columnDefinition(TABLE, "reservation_id"))
			.isEqualTo(new ColumnDefinition("BIGINT", 19, true));
		assertThat(columnDefinition(TABLE, "hold_expires_at"))
			.isEqualTo(new ColumnDefinition("DATETIME", 26, true));
		assertThat(temporalPrecision(TABLE, "hold_expires_at")).isEqualTo(6);
		assertThat(indexColumns("reservation", "uk_reservation_id_accommodation"))
			.containsExactly("id", "accommodation_id");
		assertThat(indexColumns(TABLE, "idx_inventory_day_free_retention"))
			.containsExactly("state", "stay_date", "accommodation_id");
		assertThat(indexColumns("accommodation", "idx_accommodation_inventory_seed_scan"))
			.containsExactly("status", "id");
		assertThat(foreignKeyColumns(TABLE, "fk_inventory_day_reservation_owner"))
			.containsExactly("reservation_id", "accommodation_id");
		assertThat(checkClause("chk_inventory_day_state_owner"))
			.contains("FREE")
			.contains("HOLD")
			.contains("OCCUPIED")
			.contains("hold_expires_at")
			.contains("reservation_id");
	}

	private void migrateThroughV24() {
		flywayBuilder()
			.target(MigrationVersion.fromVersion("24"))
			.load()
			.migrate();
	}

	private void migrateAll() {
		flywayBuilder().load().migrate();
	}

	private void repairAndMigrateAll() {
		Flyway flyway = flywayBuilder().load();
		flyway.repair();
		flyway.migrate();
	}

	private void repairAndMigrateThroughV25() {
		Flyway flyway = flywayBuilder()
			.target(MigrationVersion.fromVersion("25"))
			.load();
		flyway.repair();
		flyway.migrate();
	}

	private org.flywaydb.core.api.configuration.FluentConfiguration flywayBuilder() {
		return Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration");
	}

	private void insertLegacyReservationFixture() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
				INSERT INTO member (email, nickname, role, status, updated_at)
				VALUES ('inventory-migration@test.com', 'inventory-migration', 'MEMBER', 'ACTIVE', NOW(6))
				""");
			statement.executeUpdate("""
				INSERT INTO accommodation (
				  member_id, base_price, currency, check_in_time, check_out_time,
				  accommodation_uid, time_zone_id, updated_at, status
				) VALUES (
				  LAST_INSERT_ID(), 100000, 'KRW', '15:00:00', '11:00:00',
				  UUID_TO_BIN('86ebf8b6-d0ab-446b-b867-bcc69d78cbe4'), 'Asia/Seoul', NOW(6), 'PUBLISHED'
				)
				""");
			statement.executeUpdate("""
				INSERT INTO reservation (
				  reservation_uid, accommodation_id, guest_id,
				  check_in_date, check_out_date, check_in_at, check_out_at, time_zone_id,
				  guest_count, total_price, discount_amount, currency, status, reservation_code,
				  expires_at, payment_attempt_required, created_at, updated_at
				) VALUES (
				  UUID_TO_BIN('3c61fc02-7ac9-4aea-b4ef-5ac363c76664'),
				  (SELECT id FROM accommodation WHERE accommodation_uid = UUID_TO_BIN('86ebf8b6-d0ab-446b-b867-bcc69d78cbe4')),
				  (SELECT id FROM member WHERE email = 'inventory-migration@test.com'),
				  '2026-09-10', '2026-09-12', '2026-09-10 06:00:00', '2026-09-12 02:00:00', 'Asia/Seoul',
				  2, 200000, 0, 'KRW', 'PAYMENT_PENDING', 'INV001',
				  '2026-08-25 04:00:00', FALSE, NOW(6), NOW(6)
				)
				""");
		}
	}

	private boolean tableExists(String table) throws SQLException {
		try (Connection connection = connection();
			 ResultSet tables = connection.getMetaData().getTables(
				 connection.getCatalog(), null, table, new String[] {"TABLE"})) {
			return tables.next();
		}
	}

	private List<String> primaryKeyColumns(String table) throws SQLException {
		try (Connection connection = connection();
			 ResultSet keys = connection.getMetaData().getPrimaryKeys(connection.getCatalog(), null, table)) {
			List<OrderedColumn> columns = new ArrayList<>();
			while (keys.next()) {
				columns.add(new OrderedColumn(keys.getShort("KEY_SEQ"), keys.getString("COLUMN_NAME")));
			}
			return columns.stream().sorted().map(OrderedColumn::name).toList();
		}
	}

	private ColumnDefinition columnDefinition(String table, String column) throws SQLException {
		try (Connection connection = connection();
			 ResultSet columns = connection.getMetaData().getColumns(
				 connection.getCatalog(), null, table, column)) {
			assertThat(columns.next()).isTrue();
			return new ColumnDefinition(
				columns.getString("TYPE_NAME"),
				columns.getInt("COLUMN_SIZE"),
				columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable
			);
		}
	}

	private List<String> indexColumns(String table, String indexName) throws SQLException {
		try (Connection connection = connection();
			 ResultSet indexes = connection.getMetaData().getIndexInfo(
				 connection.getCatalog(), null, table, false, false)) {
			List<OrderedColumn> columns = new ArrayList<>();
			while (indexes.next()) {
				if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
					columns.add(new OrderedColumn(
						indexes.getShort("ORDINAL_POSITION"), indexes.getString("COLUMN_NAME")));
				}
			}
			return columns.stream().sorted().map(OrderedColumn::name).toList();
		}
	}

	private List<String> foreignKeyColumns(String table, String foreignKeyName) throws SQLException {
		try (Connection connection = connection();
			 ResultSet keys = connection.getMetaData().getImportedKeys(connection.getCatalog(), null, table)) {
			List<OrderedColumn> columns = new ArrayList<>();
			while (keys.next()) {
				if (foreignKeyName.equalsIgnoreCase(keys.getString("FK_NAME"))) {
					columns.add(new OrderedColumn(keys.getShort("KEY_SEQ"), keys.getString("FKCOLUMN_NAME")));
				}
			}
			return columns.stream().sorted().map(OrderedColumn::name).toList();
		}
	}

	private String checkClause(String constraintName) throws SQLException {
		try (Connection connection = connection();
			 var statement = connection.prepareStatement("""
				SELECT cc.check_clause
				FROM information_schema.table_constraints tc
				JOIN information_schema.check_constraints cc
				  ON cc.constraint_schema = tc.constraint_schema
				 AND cc.constraint_name = tc.constraint_name
				WHERE tc.constraint_schema = DATABASE()
				  AND tc.table_name = ?
				  AND tc.constraint_name = ?
				""")) {
			statement.setString(1, TABLE);
			statement.setString(2, constraintName);
			try (ResultSet result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				return result.getString(1);
			}
		}
	}

	private int temporalPrecision(String table, String column) throws SQLException {
		try (Connection connection = connection();
			 var statement = connection.prepareStatement("""
				SELECT datetime_precision
				FROM information_schema.columns
				WHERE table_schema = DATABASE()
				  AND table_name = ?
				  AND column_name = ?
				""")) {
			statement.setString(1, table);
			statement.setString(2, column);
			try (ResultSet result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				return result.getInt(1);
			}
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(
			MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}

	private record ColumnDefinition(String typeName, int size, boolean nullable) {
	}

	private record OrderedColumn(short position, String name) implements Comparable<OrderedColumn> {
		@Override
		public int compareTo(OrderedColumn other) {
			return Short.compare(position, other.position);
		}
	}
}
