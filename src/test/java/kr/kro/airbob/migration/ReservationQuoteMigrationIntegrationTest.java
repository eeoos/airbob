package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("V23 예약 견적 마이그레이션")
class ReservationQuoteMigrationIntegrationTest {

	private static final String TABLE = "reservation_quote";

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_reservation_quote")
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
	@DisplayName("견적 스냅샷과 checkout 연결에 필요한 컬럼을 정확히 만든다")
	void createsExactQuoteSnapshotColumns() throws SQLException {
		try (Connection connection = connection()) {
			assertThat(columnDefinition(connection, "reservation", "message"))
				.as("V21 must preserve legacy reservation messages while the API caps new input")
				.isEqualTo(new ColumnDefinition("VARCHAR", 500));
			assertThat(tableExists(connection, TABLE)).isTrue();
			assertThat(columnNames(connection, TABLE)).containsExactly(
				"id",
				"quote_uid",
				"member_id",
				"accommodation_id",
				"order_name",
				"check_in_date",
				"check_out_date",
				"guest_count",
				"coupon_id",
				"nightly_price",
				"nights",
				"subtotal",
				"discount_amount",
				"amount",
				"currency",
				"quoted_at",
				"expires_at",
				"reservation_id",
				"checked_out_at",
				"created_at",
				"updated_at",
				"created_by",
				"updated_by"
			);
			assertThat(requiredColumns(connection, TABLE)).containsExactlyInAnyOrder(
				"id",
				"quote_uid",
				"member_id",
				"accommodation_id",
				"order_name",
				"check_in_date",
				"check_out_date",
				"guest_count",
				"nightly_price",
				"nights",
				"subtotal",
				"discount_amount",
				"amount",
				"currency",
				"quoted_at",
				"expires_at",
				"created_at",
				"updated_at"
			);
			assertThat(nullableColumns(connection, TABLE)).containsExactlyInAnyOrder(
				"coupon_id",
				"reservation_id",
				"checked_out_at",
				"created_by",
				"updated_by"
			);
			assertThat(columnDefinition(connection, TABLE, "quote_uid"))
				.isEqualTo(new ColumnDefinition("BINARY", 16));
			assertThat(columnDefinition(connection, TABLE, "order_name"))
				.isEqualTo(new ColumnDefinition("VARCHAR", 255));
			assertThat(columnDefinition(connection, TABLE, "currency"))
				.isEqualTo(new ColumnDefinition("CHAR", 3));
		}
	}

	@Test
	@DisplayName("견적 UID와 예약 연결은 각각 유일하며 조회 인덱스를 보존한다")
	void createsExactUniqueAndLookupIndexes() throws SQLException {
		try (Connection connection = connection()) {
			assertThat(uniqueIndexes(connection, TABLE)).containsExactlyInAnyOrderEntriesOf(Map.of(
				"uk_reservation_quote_uid", List.of("quote_uid"),
				"uk_reservation_quote_reservation", List.of("reservation_id")
			));
			assertThat(indexColumns(connection, TABLE, "idx_reservation_quote_member_created"))
				.containsExactly("member_id", "created_at", "id");
			assertThat(indexColumns(connection, TABLE, "idx_reservation_quote_cleanup"))
				.containsExactly("created_at", "id");
		}
	}

	@Test
	@DisplayName("견적의 회원·숙소·쿠폰·예약 참조와 값 불변식을 DB가 강제한다")
	void createsExactForeignKeysAndChecks() throws SQLException {
		try (Connection connection = connection()) {
			assertThat(foreignKeys(connection, TABLE)).containsExactlyInAnyOrderEntriesOf(Map.of(
				"fk_reservation_quote_member", new ForeignKey("member_id", "member", "id"),
				"fk_reservation_quote_accommodation",
				new ForeignKey("accommodation_id", "accommodation", "id"),
				"fk_reservation_quote_coupon", new ForeignKey("coupon_id", "coupon", "id"),
				"fk_reservation_quote_reservation",
				new ForeignKey("reservation_id", "reservation", "id")
			));

			Map<String, String> checks = checkClauses(connection, TABLE);
			assertThat(checks).containsOnlyKeys(
				"chk_reservation_quote_dates",
				"chk_reservation_quote_guests",
				"chk_reservation_quote_expiry",
				"chk_reservation_quote_stay_price",
				"chk_reservation_quote_checkout",
				"chk_reservation_quote_price"
			);
			assertThat(checks.get("chk_reservation_quote_dates"))
				.contains("check_out_date>check_in_date");
			assertThat(checks.get("chk_reservation_quote_guests"))
				.contains("guest_count>0");
			assertThat(checks.get("chk_reservation_quote_expiry"))
				.contains("expires_at>quoted_at");
			assertThat(checks.get("chk_reservation_quote_stay_price"))
				.contains(
					"nights=to_dayscheck_out_date-to_dayscheck_in_date",
					"subtotal=nightly_price*nights"
				);
			assertThat(checks.get("chk_reservation_quote_checkout"))
				.contains(
					"reservation_idisnull",
					"checked_out_atisnull",
					"reservation_idisnotnull",
					"checked_out_atisnotnull"
				);
			assertThat(checks.get("chk_reservation_quote_price"))
				.contains(
					"nightly_price>=0",
					"nights>0",
					"subtotal>=0",
					"discount_amount>=0",
					"discount_amount<=subtotal",
					"amount=subtotal-discount_amount"
				);
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

	private List<String> columnNames(Connection connection, String table) throws SQLException {
		List<OrderedColumn> columns = new ArrayList<>();
		try (ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), null, table, null)) {
			while (result.next()) {
				columns.add(new OrderedColumn(
					result.getInt("ORDINAL_POSITION"),
					result.getString("COLUMN_NAME").toLowerCase()
				));
			}
		}
		return columns.stream()
			.sorted(Comparator.comparingInt(OrderedColumn::position))
			.map(OrderedColumn::name)
			.toList();
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

	private ColumnDefinition columnDefinition(Connection connection, String table, String column)
		throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(
			connection.getCatalog(), null, table, column)) {
			assertThat(columns.next()).isTrue();
			return new ColumnDefinition(
				columns.getString("TYPE_NAME").toUpperCase(),
				columns.getInt("COLUMN_SIZE")
			);
		}
	}

	private Map<String, List<String>> uniqueIndexes(Connection connection, String table) throws SQLException {
		Map<String, List<OrderedColumn>> orderedIndexes = new LinkedHashMap<>();
		try (ResultSet result = connection.getMetaData().getIndexInfo(
			connection.getCatalog(), null, table, true, false)) {
			while (result.next()) {
				String name = result.getString("INDEX_NAME");
				String column = result.getString("COLUMN_NAME");
				if (name == null || column == null || "PRIMARY".equalsIgnoreCase(name)) {
					continue;
				}
				orderedIndexes.computeIfAbsent(name.toLowerCase(), ignored -> new ArrayList<>())
					.add(new OrderedColumn(
						result.getInt("ORDINAL_POSITION"),
						column.toLowerCase()
					));
			}
		}
		Map<String, List<String>> indexes = new LinkedHashMap<>();
		orderedIndexes.forEach((name, columns) -> indexes.put(name, columns.stream()
			.sorted(Comparator.comparingInt(OrderedColumn::position))
			.map(OrderedColumn::name)
			.toList()));
		return indexes;
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

	private Map<String, ForeignKey> foreignKeys(Connection connection, String table) throws SQLException {
		Map<String, ForeignKey> keys = new LinkedHashMap<>();
		try (ResultSet result = connection.getMetaData().getImportedKeys(connection.getCatalog(), null, table)) {
			while (result.next()) {
				keys.put(
					result.getString("FK_NAME").toLowerCase(),
					new ForeignKey(
						result.getString("FKCOLUMN_NAME").toLowerCase(),
						result.getString("PKTABLE_NAME").toLowerCase(),
						result.getString("PKCOLUMN_NAME").toLowerCase()
					)
				);
			}
		}
		return keys;
	}

	private Map<String, String> checkClauses(Connection connection, String table) throws SQLException {
		Map<String, String> checks = new LinkedHashMap<>();
		try (var statement = connection.prepareStatement("""
			SELECT tc.constraint_name, cc.check_clause
			FROM information_schema.table_constraints tc
			JOIN information_schema.check_constraints cc
			  ON cc.constraint_schema = tc.constraint_schema
			 AND cc.constraint_name = tc.constraint_name
			WHERE tc.table_schema = ?
			  AND tc.table_name = ?
			  AND tc.constraint_type = 'CHECK'
			ORDER BY tc.constraint_name
			""")) {
			statement.setString(1, connection.getCatalog());
			statement.setString(2, table);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					checks.put(
						result.getString("constraint_name").toLowerCase(),
						normalizeCheckClause(result.getString("check_clause"))
					);
				}
			}
		}
		return checks;
	}

	private String normalizeCheckClause(String clause) {
		return clause.toLowerCase()
			.replace("`", "")
			.replaceAll("\\s+", "")
			.replace("(", "")
			.replace(")", "");
	}

	private record OrderedColumn(int position, String name) {
	}

	private record ColumnDefinition(String type, int size) {
	}

	private record ForeignKey(String column, String referencedTable, String referencedColumn) {
	}
}
