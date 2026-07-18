package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LegacyEventSchemaRemovalIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_event_removal")
		.withUsername("airbob")
		.withPassword("airbob");

	@Test
	@DisplayName("Flyway 적용 후 레거시 프로모션 이벤트 테이블이 존재하지 않는다")
	void removesLegacyPromotionEventTables() throws SQLException {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();

		try (Connection connection = DriverManager.getConnection(
			MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
			assertThat(tableExists(connection, "event_participant")).isFalse();
			assertThat(tableExists(connection, "event")).isFalse();
		}
	}

	private boolean tableExists(Connection connection, String tableName) throws SQLException {
		try (ResultSet tables = connection.getMetaData()
			.getTables(connection.getCatalog(), null, tableName, new String[] {"TABLE"})) {
			return tables.next();
		}
	}
}
