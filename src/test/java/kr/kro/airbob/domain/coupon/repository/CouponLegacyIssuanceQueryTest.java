package kr.kro.airbob.domain.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, QueryDslConfig.class})
@DisplayName("기존 쿠폰 발급 배포 차단 쿼리 테스트")
class CouponLegacyIssuanceQueryTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_coupon_rollout");

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("발급 수나 회원 쿠폰 중 하나라도 남은 활성·미준비 쿠폰만 센다")
	void countsEitherIssuedQuantityOrMemberCouponForActiveUnpreparedCoupons() {
		insertCoupon(true, false, 1);
		long memberCouponOnlyId = insertCoupon(true, false, 0);
		insertMemberCoupon(memberCouponOnlyId);
		insertCoupon(false, false, 1);
		insertCoupon(true, true, 1);

		assertThat(couponRepository.countActiveUnpreparedCouponsWithLegacyIssuances())
			.isEqualTo(2L);
	}

	private long insertCoupon(boolean active, boolean prepared, int issuedQuantity) {
		jdbc.update("""
			INSERT INTO coupon (
			  name, discount_type, discount_value,
			  issue_start_at, issue_end_at, usable_from, usable_until,
			  is_active, total_quantity, issued_quantity, redis_stock_prepared_at, updated_at
			) VALUES (
			  '배포 가드 쿠폰', 'FIXED_AMOUNT', 1000,
			  '2026-08-01 00:00:00', '2026-08-31 00:00:00',
			  '2026-08-01 00:00:00', '2026-09-30 00:00:00',
			  ?, 100, ?, ?, NOW(6)
			)
			""", active, issuedQuantity, prepared ? "2026-07-20 12:00:00" : null);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void insertMemberCoupon(long couponId) {
		jdbc.update("""
			INSERT INTO member (email, nickname, role, status, updated_at)
			VALUES (CONCAT('rollout-', UUID(), '@test.com'), '배포 가드 회원', 'MEMBER', 'ACTIVE', NOW(6))
			""");
		long memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
			INSERT INTO member_coupon (member_id, coupon_id, used, updated_at)
			VALUES (?, ?, false, NOW(6))
			""", memberId, couponId);
	}
}
