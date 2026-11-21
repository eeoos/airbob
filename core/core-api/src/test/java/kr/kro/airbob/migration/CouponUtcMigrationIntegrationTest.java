package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CouponUtcMigrationIntegrationTest {

	@Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11");

	@Test
	void convertsBusinessDatesOnceWhilePreservingAuditInventoryAndOwnership() throws Exception {
		flyway("29").migrate();
		try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
			 var sql = connection.createStatement()) {
			sql.execute("SET time_zone = '+09:00'");
			sql.executeUpdate("""
				INSERT INTO coupon (id,name,discount_type,discount_value,is_active,total_quantity,issued_quantity,
				issue_strategy,issue_start_at,issue_end_at,usable_from,usable_until,stock_prepared_at,created_at,updated_at)
				VALUES (1,'surrogate','FIXED_AMOUNT',1000,true,2,0,'SKIP_SURROGATE_RR',
				'2026-10-10 00:30:00.123456','2026-10-10 01:30:00.123456',
				'2026-10-10 00:30:00.123456','2026-11-10 00:30:00.123456',
				'2026-10-09 23:30:00.123456','2026-10-09 14:00:00.654321','2026-10-09 14:05:00.654321'),
				(2,'composite','FIXED_AMOUNT',1000,true,1,0,'SKIP_COMPOSITE_RC',
				'2026-10-10 00:30:00.123456','2026-10-10 01:30:00.123456',
				'2026-10-10 00:30:00.123456','2026-11-10 00:30:00.123456',
				'2026-10-09 23:30:00.123456','2026-10-09 14:00:00.654321','2026-10-09 14:05:00.654321'),
				(3,'historical','FIXED_AMOUNT',1000,false,NULL,1,'DB',
				'2026-10-10 00:30:00.123456','2026-10-10 01:30:00.123456',
				'2026-10-10 00:30:00.123456','2026-11-10 00:30:00.123456',
				NULL,'2026-10-09 14:00:00.654321','2026-10-09 14:05:00.654321')
				""");
			sql.executeUpdate("""
				INSERT INTO member(id,email,nickname,role,status,updated_at)
				VALUES (1,'utc-1@test.com','member','MEMBER','ACTIVE',UTC_TIMESTAMP(6)),
				(2,'utc-2@test.com','member','MEMBER','ACTIVE',UTC_TIMESTAMP(6))
				""");
			sql.executeUpdate("""
				INSERT INTO member_coupon(id,member_id,coupon_id,used,used_at,reservation_id,created_at,updated_at)
				VALUES (1,1,1,true,'2026-10-10 01:00:00.123456',42,
				'2026-10-09 15:30:00.654321','2026-10-09 16:00:00.654321'),
				(2,2,3,false,NULL,NULL,'2026-10-09 15:30:00.654321','2026-10-09 16:00:00.654321')
				""");
			sql.executeUpdate("INSERT INTO coupon_issue_unit(id,coupon_id,unit_no) VALUES (73,1,2)");
			sql.executeUpdate("INSERT INTO coupon_issue_unit_composite(coupon_id,unit_no) VALUES (2,1)");

			assertThat(flyway("30").migrate().migrationsExecuted).isEqualTo(1);
			assertThat(flyway("30").migrate().migrationsExecuted).isZero();
			try (var rows = sql.executeQuery("SELECT * FROM coupon ORDER BY id")) {
				for (int id = 1; id <= 3; id++) {
					assertThat(rows.next()).isTrue();
					assertThat(rows.getInt("id")).isEqualTo(id);
					assertThat(rows.getString("issue_start_at")).isEqualTo("2026-10-09 15:30:00.123456");
					assertThat(rows.getString("issue_end_at")).isEqualTo("2026-10-09 16:30:00.123456");
					assertThat(rows.getString("usable_from")).isEqualTo("2026-10-09 15:30:00.123456");
					assertThat(rows.getString("usable_until")).isEqualTo("2026-11-09 15:30:00.123456");
					assertThat(rows.getString("stock_prepared_at")).isEqualTo(id == 3 ? null : "2026-10-09 14:30:00.123456");
					assertThat(rows.getString("created_at")).isEqualTo("2026-10-09 14:00:00.654321");
					assertThat(rows.getString("updated_at")).isEqualTo("2026-10-09 14:05:00.654321");
					assertThat(rows.getObject("total_quantity")).isEqualTo(id == 3 ? null : 3 - id);
					assertThat(rows.getInt("issued_quantity")).isEqualTo(id == 3 ? 1 : 0);
				}
				assertThat(rows.next()).isFalse();
			}
			try (var rows = sql.executeQuery("SELECT * FROM member_coupon ORDER BY id")) {
				assertThat(rows.next()).isTrue();
				assertThat(rows.getLong("member_id")).isEqualTo(1);
				assertThat(rows.getLong("coupon_id")).isEqualTo(1);
				assertThat(rows.getBoolean("used")).isTrue();
				assertThat(rows.getString("used_at")).isEqualTo("2026-10-09 16:00:00.123456");
				assertThat(rows.getLong("reservation_id")).isEqualTo(42);
				assertThat(rows.getString("created_at")).isEqualTo("2026-10-09 15:30:00.654321");
				assertThat(rows.getString("updated_at")).isEqualTo("2026-10-09 16:00:00.654321");
				assertThat(rows.next()).isTrue();
				assertThat(rows.getLong("member_id")).isEqualTo(2);
				assertThat(rows.getLong("coupon_id")).isEqualTo(3);
				assertThat(rows.getBoolean("used")).isFalse();
				assertThat(rows.getObject("used_at")).isNull();
				assertThat(rows.next()).isFalse();
			}
			try (var rows = sql.executeQuery("SELECT id,coupon_id,unit_no FROM coupon_issue_unit")) {
				assertThat(rows.next()).isTrue();
				assertThat(rows.getLong(1)).isEqualTo(73);
				assertThat(rows.getLong(2)).isEqualTo(1);
				assertThat(rows.getInt(3)).isEqualTo(2);
				assertThat(rows.next()).isFalse();
			}
			try (var rows = sql.executeQuery("SELECT coupon_id,unit_no FROM coupon_issue_unit_composite")) {
				assertThat(rows.next()).isTrue();
				assertThat(rows.getLong(1)).isEqualTo(2);
				assertThat(rows.getInt(2)).isEqualTo(1);
				assertThat(rows.next()).isFalse();
			}
		}
	}

	private Flyway flyway(String target) {
		return Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration").target(target).load();
	}
}
