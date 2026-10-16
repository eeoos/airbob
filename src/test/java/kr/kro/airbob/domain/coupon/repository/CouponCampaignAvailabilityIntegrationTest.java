package kr.kro.airbob.domain.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.coupon.common.CouponIssuanceStatus;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CouponCampaignAvailabilityIntegrationTest {
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 0, 0);
	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("coupon_campaign_availability");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private CouponRepository coupons;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;
	@Autowired private PlatformTransactionManager transactionManager;

	@BeforeEach
	void resetOwnedDatabase() {
		jdbc.update("DELETE FROM coupon_issue_unit");
		jdbc.update("DELETE FROM member_coupon");
		jdbc.update("DELETE FROM coupon");
		jdbc.update("DELETE FROM member");
		for (int id = 1; id <= 3; id++) {
			jdbc.update("""
				INSERT INTO member (id,email,nickname,role,status,created_at,updated_at)
				VALUES (?,?,'fixture','MEMBER','ACTIVE',?,?)
				""", id, "campaign-read-" + id + "@example.invalid", NOW, NOW);
		}
	}

	@Test
	void preservesResponseForSurrogateUnits() {
		campaign(1, 0, NOW.minusDays(1));
		campaign(2, 2, NOW.minusDays(1));
		campaign(3, 3, NOW.minusDays(1));
		campaign(4, 0, NOW.plusHours(1));
		assertThat(candidate(NOW)).isEqualTo(baseline(NOW));
		assertThat(candidate(NOW)).extracting(CouponResponse.CouponInfo::issuanceStatus)
			.containsExactly(CouponIssuanceStatus.UPCOMING, CouponIssuanceStatus.SOLD_OUT,
				CouponIssuanceStatus.OPEN, CouponIssuanceStatus.OPEN);
	}

	@Test
	void preservesVisibilityOrderingAndTimeBoundaries() {
		for (int id = 1; id <= 6; id++) campaign(id, 0, NOW);
		jdbc.update("UPDATE coupon SET is_active = false WHERE id = 1");
		jdbc.update("UPDATE coupon SET stock_prepared_at = NULL WHERE id = 2");
		jdbc.update("UPDATE coupon SET issue_end_at = ? WHERE id = 3", NOW);
		jdbc.update("UPDATE coupon SET stock_prepared_at = NULL WHERE id = 4");
		for (var time : List.of(NOW.minusNanos(1000), NOW, NOW.plusDays(3))) {
			assertThat(candidate(time)).isEqualTo(baseline(time));
		}
		assertThat(candidate(NOW)).extracting(CouponResponse.CouponInfo::id).containsExactly(6L, 5L);
		assertThat(candidate(NOW)).extracting(CouponResponse.CouponInfo::issuanceStatus)
			.containsOnly(CouponIssuanceStatus.OPEN);
	}

	@Test
	void uncommittedLastUnitDeletionAndRollbackDoNotReportSoldOut() throws Exception {
		campaign(1, 2, NOW.minusDays(1));
		try (var writer = dataSource.getConnection()) {
			writer.setAutoCommit(false);
			consumeLast(writer);
			assertOpenAndEqual();
			writer.rollback();
		}
		assertOpenAndEqual();
		try (var writer = dataSource.getConnection()) {
			writer.setAutoCommit(false);
			consumeLast(writer);
			writer.commit();
		}
		assertThat(candidate(NOW)).isEqualTo(baseline(NOW));
		assertThat(candidate(NOW)).singleElement()
			.satisfies(c -> assertThat(c.issuanceStatus()).isEqualTo(CouponIssuanceStatus.SOLD_OUT));
	}

	@Test
	void bothQueriesObserveTheSameRepeatableReadSnapshotAcrossCommit() throws Exception {
		campaign(1, 2, NOW.minusDays(1));
		try (var writer = dataSource.getConnection()) {
			writer.setAutoCommit(false);
			var reader = new TransactionTemplate(transactionManager);
			reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
			reader.setReadOnly(true);
			reader.executeWithoutResult(status -> {
				assertOpenAndEqual();
				try {
					consumeLast(writer);
					writer.commit();
				} catch (Exception failure) {
					throw new IllegalStateException(failure);
				}
				assertOpenAndEqual();
			});
		}
		assertThat(candidate(NOW)).isEqualTo(baseline(NOW));
		assertThat(candidate(NOW)).singleElement()
			.satisfies(c -> assertThat(c.issuanceStatus()).isEqualTo(CouponIssuanceStatus.SOLD_OUT));
	}

	@Test
	void emptyCampaignListIsIdentical() {
		assertThat(candidate(NOW)).isEmpty();
		assertThat(candidate(NOW)).isEqualTo(baseline(NOW));
	}

	private void consumeLast(Connection writer) throws Exception {
		try (var statement = writer.createStatement()) {
			statement.setQueryTimeout(3);
			assertThat(statement.executeUpdate("DELETE FROM coupon_issue_unit WHERE coupon_id = 1")).isEqualTo(1);
			statement.executeUpdate("INSERT INTO member_coupon (coupon_id,member_id,used,created_at,updated_at) "
				+ "VALUES (1,3,false,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
		}
	}

	private void assertOpenAndEqual() {
		assertThat(candidate(NOW)).isEqualTo(baseline(NOW));
		assertThat(candidate(NOW)).singleElement()
			.satisfies(c -> assertThat(c.issuanceStatus()).isEqualTo(CouponIssuanceStatus.OPEN));
	}

	private List<CouponResponse.CouponInfo> baseline(LocalDateTime now) {
		return coupons.findCampaigns(now).stream().map(c -> CouponResponse.CouponInfo.of(c, now)).toList();
	}

	private List<CouponResponse.CouponInfo> candidate(LocalDateTime now) {
		return coupons.findCampaignsWithRemainingUnits(now).stream().map(c -> c.toResponse(now)).toList();
	}

	private void campaign(long id, int issued, LocalDateTime start) {
		jdbc.update("""
			INSERT INTO coupon (id,name,description,discount_type,discount_value,min_payment_price,
				issue_start_at,issue_end_at,usable_from,usable_until,is_active,total_quantity,
				stock_prepared_at,created_at,updated_at)
			VALUES (?,?,'same response','FIXED_AMOUNT',10000,50000,?,?,?,?,true,3,?,?,?)
			""", id, "campaign-" + id, start, NOW.plusDays(2), NOW, NOW.plusDays(30),
			NOW.minusDays(2), NOW, NOW);
		for (int member = 1; member <= issued; member++) {
			jdbc.update("INSERT INTO member_coupon (coupon_id,member_id,used,created_at,updated_at) VALUES (?,?,false,?,?)",
				id, member, NOW, NOW);
		}
		for (int unit = issued + 1; unit <= 3; unit++) {
			jdbc.update("INSERT INTO coupon_issue_unit (coupon_id,unit_no) VALUES (?,?)", id, unit);
		}
	}
}
