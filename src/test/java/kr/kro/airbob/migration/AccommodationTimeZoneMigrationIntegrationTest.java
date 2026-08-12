package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("숙소 시간대 V15 마이그레이션 통합 테스트")
class AccommodationTimeZoneMigrationIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_accommodation_time_zone")
		.withUsername("airbob")
		.withPassword("airbob");

	@Test
	@DisplayName("기존 숙소와 이력은 서울로 백필하고 새 DRAFT는 null 시간대를 허용한다")
	void backfillsLegacyRowsAndKeepsNewDraftNullable() throws SQLException {
		migrateTo("14");
		long memberId = insertMember("legacy-host@test.com");
		long legacyAccommodationId = insertAccommodation(memberId, "00112233445566778899AABBCCDDEEFF");
		long legacyHistoryId = insertAccommodationHistory(legacyAccommodationId);

		migrateAll();

		try (Connection connection = connection()) {
			assertThat(columnExists(connection, "accommodation", "time_zone_id")).isTrue();
			assertThat(columnExists(connection, "accommodation_history", "time_zone_id")).isTrue();
			assertThat(columnNullable(connection, "accommodation", "time_zone_id")).isTrue();
			assertThat(columnNullable(connection, "accommodation_history", "time_zone_id")).isTrue();
			assertThat(readTimeZone(connection, "accommodation", legacyAccommodationId))
				.isEqualTo("Asia/Seoul");
			assertThat(readTimeZone(connection, "accommodation_history", legacyHistoryId))
				.isEqualTo("Asia/Seoul");
		}

		long draftAccommodationId = insertAccommodation(memberId, "FFEEDDCCBBAA99887766554433221100");
		try (Connection connection = connection()) {
			assertThat(readTimeZone(connection, "accommodation", draftAccommodationId)).isNull();
		}
	}

	private void migrateTo(String version) {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.target(MigrationVersion.fromVersion(version))
			.load()
			.migrate();
	}

	private void migrateAll() {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
	}

	private long insertMember(String email) throws SQLException {
		try (Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("""
				insert into member (email, nickname, status, updated_at)
				values (?, 'host', 'ACTIVE', current_timestamp(6))
				""", Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, email);
			statement.executeUpdate();
			return generatedId(statement);
		}
	}

	private long insertAccommodation(long memberId, String uidHex) throws SQLException {
		try (Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("""
				insert into accommodation (
				  member_id, check_in_time, check_out_time, accommodation_uid, updated_at, status
				) values (?, '15:00:00', '11:00:00', unhex(?), current_timestamp(6), 'DRAFT')
				""", Statement.RETURN_GENERATED_KEYS)) {
			statement.setLong(1, memberId);
			statement.setString(2, uidHex);
			statement.executeUpdate();
			return generatedId(statement);
		}
	}

	private long insertAccommodationHistory(long accommodationId) throws SQLException {
		try (Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("""
				insert into accommodation_history (
				  accommodation_id, status, history_created_at, change_type, valid_from, valid_to
				) values (?, 'DRAFT', current_timestamp(6), 'CREATE', current_timestamp(6),
				  '9999-12-31 23:59:59')
				""", Statement.RETURN_GENERATED_KEYS)) {
			statement.setLong(1, accommodationId);
			statement.executeUpdate();
			return generatedId(statement);
		}
	}

	private long generatedId(PreparedStatement statement) throws SQLException {
		try (ResultSet keys = statement.getGeneratedKeys()) {
			assertThat(keys.next()).isTrue();
			return keys.getLong(1);
		}
	}

	private String readTimeZone(Connection connection, String table, long id) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"select time_zone_id from " + table + " where id = ?")) {
			statement.setLong(1, id);
			try (ResultSet result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				return result.getString("time_zone_id");
			}
		}
	}

	private boolean columnExists(Connection connection, String table, String column) throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(
			connection.getCatalog(), null, table, column)) {
			return columns.next();
		}
	}

	private boolean columnNullable(Connection connection, String table, String column) throws SQLException {
		try (ResultSet columns = connection.getMetaData().getColumns(
			connection.getCatalog(), null, table, column)) {
			assertThat(columns.next()).isTrue();
			return columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}
}
