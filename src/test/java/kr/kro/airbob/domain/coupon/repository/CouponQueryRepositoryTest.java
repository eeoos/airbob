package kr.kro.airbob.domain.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.hibernate.Hibernate;
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

import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.entity.MemberCoupon;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class})
@DisplayName("쿠폰 조회 저장소 테스트")
class CouponQueryRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 9, 30);

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_coupon_query");

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
	private MemberCouponRepository memberCouponRepository;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("Redis 재고가 준비된 활성·미종료 캠페인만 발급 시작 최신순으로 조회한다")
	void findsVisibleCampaignsInLatestIssueOrder() {
		long older = insertCoupon("진행 중", true, NOW.minusHours(1), NOW.plusHours(1));
		long latest = insertCoupon("오픈 예정", true, NOW.plusMinutes(30), NOW.plusHours(2));
		insertCoupon("종료", true, NOW.minusHours(2), NOW);
		insertCoupon("비활성", false, NOW.plusHours(1), NOW.plusHours(2));
		insertUnpreparedCoupon("재고 미준비", NOW.minusMinutes(30), NOW.plusHours(1));

		assertThat(couponRepository.findCampaigns(NOW))
			.extracting(Coupon::getId)
			.containsExactly(latest, older);
	}

	@Test
	@DisplayName("로그인 회원의 보유 쿠폰만 발급 최신순으로 쿠폰 정보와 함께 조회한다")
	void findsOnlyOwnedCouponsInStableLatestOrder() {
		long memberId = insertMember("owner");
		long anotherMemberId = insertMember("another");
		LocalDateTime olderTime = NOW.minusMinutes(2);
		LocalDateTime latestTime = NOW.minusMinutes(1);

		long olderCouponId = insertCoupon("먼저 발급", true, NOW.minusDays(1), NOW.plusDays(1));
		long sameTimeOlderCouponId = insertCoupon("동시 발급 1", true, NOW.minusDays(1), NOW.plusDays(1));
		long sameTimeLatestCouponId = insertCoupon("동시 발급 2", true, NOW.minusDays(1), NOW.plusDays(1));
		long otherCouponId = insertCoupon("다른 회원 쿠폰", true, NOW.minusDays(1), NOW.plusDays(1));

		insertMemberCoupon(memberId, olderCouponId, olderTime);
		insertMemberCoupon(memberId, sameTimeOlderCouponId, latestTime);
		insertMemberCoupon(memberId, sameTimeLatestCouponId, latestTime);
		insertMemberCoupon(anotherMemberId, otherCouponId, NOW);

		List<MemberCoupon> result =
			memberCouponRepository.findByMemberIdOrderByCreatedAtDescIdDesc(memberId);

		assertThat(result).allMatch(memberCoupon -> Hibernate.isInitialized(memberCoupon.getCoupon()));
		assertThat(result)
			.extracting(memberCoupon -> memberCoupon.getCoupon().getId())
			.containsExactly(sameTimeLatestCouponId, sameTimeOlderCouponId, olderCouponId);
	}

	private long insertCoupon(
		String name,
		boolean active,
		LocalDateTime issueStartAt,
		LocalDateTime issueEndAt
	) {
		return insertCoupon(name, active, issueStartAt, issueEndAt, NOW);
	}

	private long insertUnpreparedCoupon(
		String name,
		LocalDateTime issueStartAt,
		LocalDateTime issueEndAt
	) {
		return insertCoupon(name, true, issueStartAt, issueEndAt, null);
	}

	private long insertCoupon(
		String name,
		boolean active,
		LocalDateTime issueStartAt,
		LocalDateTime issueEndAt,
		LocalDateTime redisStockPreparedAt
	) {
		jdbc.update("""
			INSERT INTO coupon (
			  name, discount_type, discount_value,
			  issue_start_at, issue_end_at, usable_from, usable_until,
			  is_active, total_quantity, issued_quantity, redis_stock_prepared_at,
			  created_at, updated_at
			) VALUES (?, 'FIXED_AMOUNT', 10000, ?, ?, ?, ?, ?, 100, 0, ?, NOW(6), NOW(6))
			""",
			name,
			issueStartAt,
			issueEndAt,
			issueStartAt,
			issueEndAt.plusDays(30),
			active,
			redisStockPreparedAt);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertMember(String suffix) {
		jdbc.update("""
			INSERT INTO member (email, nickname, role, status, created_at, updated_at)
			VALUES (?, '쿠폰 회원', 'MEMBER', 'ACTIVE', NOW(6), NOW(6))
			""", suffix + "-coupon-query@test.com");
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void insertMemberCoupon(Long memberId, Long couponId, LocalDateTime createdAt) {
		jdbc.update("""
			INSERT INTO member_coupon (member_id, coupon_id, used, created_at, updated_at)
			VALUES (?, ?, false, ?, ?)
			""", memberId, couponId, createdAt, createdAt);
	}
}
