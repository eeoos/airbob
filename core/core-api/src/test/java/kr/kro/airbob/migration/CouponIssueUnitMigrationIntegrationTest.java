package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CouponIssueUnitMigrationIntegrationTest {

	@Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11");

	@Test
	void refusesRedisReadinessAndPreservesHistoricalIssuanceAfterExplicitReconciliation() throws Exception {
		flyway("28").migrate();
		try (var connection = connection(); var sql = connection.createStatement()) {
			sql.executeUpdate("""
				insert into coupon (id,name,discount_type,discount_value,is_active,total_quantity,issued_quantity,
				issue_start_at,issue_end_at,usable_from,usable_until,redis_stock_prepared_at,updated_at)
				values (1,'historical','FIXED_AMOUNT',1000,true,null,1,
				'2026-01-01','2026-02-01','2026-01-01','2026-03-01','2026-01-01',now(6))
				""");
			sql.executeUpdate("insert into member(id,email,nickname,role,status,updated_at) values (1,'migration@test.com','member','MEMBER','ACTIVE',now(6))");
			sql.executeUpdate("insert into member_coupon(member_id,coupon_id,used,updated_at) values (1,1,false,now(6))");
			assertThatThrownBy(() -> flyway("29").migrate()).hasMessageContaining("Reconcile and archive Redis");
			try (var result = sql.executeQuery("select redis_stock_prepared_at,issued_quantity from coupon where id=1")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getTimestamp(1)).isNotNull();
				assertThat(result.getInt(2)).isEqualTo(1);
			}
			// Test-only operator reconciliation: archive the campaign and clear the externally archived timestamp.
			sql.executeUpdate("update coupon set is_active=false,redis_stock_prepared_at=null where id=1");
			flyway("29").repair();
			flyway("29").migrate();
			try (var result = sql.executeQuery("select total_quantity,issued_quantity,stock_prepared_at,issue_strategy from coupon where id=1")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getObject(1)).isNull();
				assertThat(result.getInt(2)).isEqualTo(1);
				assertThat(result.getTimestamp(3)).isNull();
				assertThat(result.getString(4)).isEqualTo("SKIP_SURROGATE_RR");
			}
			try (var result = sql.executeQuery("select count(*) from member_coupon where coupon_id=1")) {
				result.next(); assertThat(result.getLong(1)).isEqualTo(1);
			}
			for (String table : new String[] {"coupon_issue_unit", "coupon_issue_unit_composite"}) {
				sql.executeUpdate("insert into " + table + "(coupon_id,unit_no) values(1,1)");
				assertThatThrownBy(() -> sql.executeUpdate("insert into " + table + "(coupon_id,unit_no) values(1,1)"))
					.isInstanceOf(SQLException.class);
				assertThatThrownBy(() -> sql.executeUpdate("insert into " + table + "(coupon_id,unit_no) values(999,2)"))
					.isInstanceOf(SQLException.class);
			}
			assertThatThrownBy(() -> sql.executeUpdate("delete from coupon where id=1")).isInstanceOf(SQLException.class);
		}
	}

	private Flyway flyway(String target) {
		return Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration").target(target).load();
	}
	private Connection connection() throws SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}
}
