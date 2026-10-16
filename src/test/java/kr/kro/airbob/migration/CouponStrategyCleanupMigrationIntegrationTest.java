package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CouponStrategyCleanupMigrationIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11");

    @BeforeEach
    void restoreOwnedV30Database() {
        flyway("30").clean();
        flyway("30").migrate();
    }

    @Test
    void preservesRemainingUnitsAndIssuedCouponsWithoutRestockingConsumedUnits() throws Exception {
        try (var connection = connection(); var sql = connection.createStatement()) {
            coupon(connection, 1, "SKIP_SURROGATE_RR", true);
            coupon(connection, 2, "SKIP_COMPOSITE_RR", true);
            coupon(connection, 3, "SKIP_COMPOSITE_RC", true);
            coupon(connection, 4, "DB", false);
            coupon(connection, 5, "SKIP_SURROGATE_RC", true);
            for (int id = 1; id <= 4; id++) {
                sql.executeUpdate("INSERT INTO member(id,email,nickname,role,status,updated_at) VALUES ("
                    + id + ",'migration-" + id + "@test.com','member','MEMBER','ACTIVE',UTC_TIMESTAMP(6))");
                sql.executeUpdate("INSERT INTO member_coupon(id,member_id,coupon_id,used,updated_at) VALUES ("
                    + id + "," + id + "," + id + ",false,UTC_TIMESTAMP(6))");
            }
            sql.executeUpdate("INSERT INTO coupon_issue_unit(id,coupon_id,unit_no) VALUES (91,1,2),(92,1,3),(93,5,1),(94,5,2),(95,5,3)");
            sql.executeUpdate("INSERT INTO coupon_issue_unit_composite(coupon_id,unit_no) VALUES (2,2),(2,3),(3,2),(3,3)");
            // A previous copy may have committed before DDL: do not duplicate that remaining unit.
            sql.executeUpdate("INSERT INTO coupon_issue_unit(coupon_id,unit_no) VALUES (2,2)");
            assertThat(flyway("31").migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway("31").migrate().migrationsExecuted).isZero();
            assertThat(count(sql, "SELECT COUNT(*) FROM coupon_issue_unit")).isEqualTo(9);
            assertThat(count(sql, "SELECT COUNT(*) FROM coupon_issue_unit WHERE coupon_id IN (1,2,3) AND unit_no=1")).isZero();
            assertThat(count(sql, "SELECT COUNT(*) FROM coupon_issue_unit WHERE id=91 AND coupon_id=1 AND unit_no=2")).isEqualTo(1);
            assertThat(count(sql, "SELECT COUNT(*) FROM coupon_issue_unit WHERE coupon_id=4")).isZero();
            assertThat(count(sql, "SELECT COUNT(*) FROM member_coupon WHERE id=member_id AND member_id=coupon_id AND used=false")).isEqualTo(4);
            assertThat(count(sql, "SELECT COUNT(*) FROM coupon")).isEqualTo(5);
            assertThat(count(sql, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coupon_issue_unit_composite'")).isZero();
            assertThat(count(sql, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coupon' AND column_name IN ('issue_strategy','issued_quantity')")).isZero();
        }
    }

    @Test
    void refusesActiveConditionalUpdateCampaignBeforeChangingStockOrSchema() throws Exception {
        try (var connection = connection(); var sql = connection.createStatement()) {
            coupon(connection, 1, "DB", true);
            assertThatThrownBy(() -> flyway("31").migrate()).hasMessageContaining("Close active conditional-UPDATE campaigns");
            assertV30Schema(sql);
            assertThat(count(sql, "SELECT COUNT(*) FROM coupon_issue_unit")).isZero();
        }
    }

    @Test
    void refusesUnreconciledLegacyCounterBeforeRemovingItsGuard() throws Exception {
        try (var connection = connection(); var sql = connection.createStatement()) {
            coupon(connection, 1, "SKIP_SURROGATE_RR", false);
            sql.executeUpdate("UPDATE coupon SET issue_start_at=UTC_TIMESTAMP(6)+INTERVAL 1 DAY,issued_quantity=1 WHERE id=1");
            assertThatThrownBy(() -> flyway("31").migrate()).hasMessageContaining("Reconcile legacy coupon counters");
            assertV30Schema(sql);
        }
    }

    @Test
    void refusesStockInTheWrongStrategyTable() throws Exception {
        try (var connection = connection(); var sql = connection.createStatement()) {
            coupon(connection, 1, "SKIP_SURROGATE_RR", true);
            sql.executeUpdate("INSERT INTO coupon_issue_unit_composite(coupon_id,unit_no) VALUES (1,1)");
            assertThatThrownBy(() -> flyway("31").migrate()).hasMessageContaining("Reconcile composite stock ownership");
            assertV30Schema(sql);
            assertThat(count(sql, "SELECT COUNT(*) FROM coupon_issue_unit_composite")).isEqualTo(1);
            assertThat(count(sql, "SELECT COUNT(*) FROM coupon_issue_unit")).isZero();
        }
    }

    @Test
    void refusesExtraSurrogateUnitsForCompositeCampaigns() throws Exception {
        try (var connection = connection(); var sql = connection.createStatement()) {
            coupon(connection, 1, "SKIP_COMPOSITE_RR", true);
            sql.executeUpdate("INSERT INTO coupon_issue_unit_composite(coupon_id,unit_no) VALUES (1,2)");
            sql.executeUpdate("INSERT INTO coupon_issue_unit(coupon_id,unit_no) VALUES (1,1)");
            assertThatThrownBy(() -> flyway("31").migrate()).hasMessageContaining("Reconcile mixed coupon stock");
            assertV30Schema(sql);
        }
    }

    private void assertV30Schema(java.sql.Statement sql) throws SQLException {
        assertThat(count(sql, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coupon_issue_unit_composite'")).isEqualTo(1);
        assertThat(count(sql, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coupon' AND column_name IN ('issue_strategy','issued_quantity')")).isEqualTo(2);
    }

    private void coupon(Connection connection, int id, String strategy, boolean active) throws SQLException {
        try (var sql = connection.prepareStatement("""
            INSERT INTO coupon(id,name,discount_type,discount_value,is_active,total_quantity,issued_quantity,
                issue_strategy,issue_start_at,issue_end_at,usable_from,usable_until,stock_prepared_at,updated_at)
            VALUES (?,'migration','FIXED_AMOUNT',1000,?,3,0,?,
                UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6)+INTERVAL 1 DAY,
                UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6)+INTERVAL 2 DAY,
                IF(?='DB',NULL,UTC_TIMESTAMP(6)-INTERVAL 2 DAY),UTC_TIMESTAMP(6))
            """)) {
            sql.setInt(1,id); sql.setBoolean(2,active); sql.setString(3,strategy); sql.setString(4,strategy);
            sql.executeUpdate();
        }
    }

    private long count(java.sql.Statement sql, String query) throws SQLException {
        try (var rows = sql.executeQuery(query)) { rows.next(); return rows.getLong(1); }
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())
            .locations("classpath:db/migration").target(target).cleanDisabled(false).load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
    }
}
