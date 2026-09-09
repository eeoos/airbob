package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.common.MemberCouponStatus;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class, CouponQueryService.class,
	MemberCouponReadIntegrationTest.ReadTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("내 쿠폰 목록 MySQL 조회 계약")
class MemberCouponReadIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 12, 0);
	private static final long MEMBER_ID = 7L;

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_member_coupon_read");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private CouponQueryService service;
	@Autowired private MemberCouponRepository memberCouponRepository;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;
	@Autowired private SqlCapture sqlCapture;
	@MockitoBean private CouponTimeProvider timeProvider;
	@MockitoBean private CouponRedisStockManager stockManager;

	@BeforeEach
	void fixture() {
		when(timeProvider.now()).thenReturn(NOW);
		jdbc.update("""
			INSERT INTO member (id, nickname, status, updated_at)
			VALUES (7, 'owner', 'ACTIVE', NOW(6)), (8, 'other', 'ACTIVE', NOW(6))
			""");
	}

	@Test
	@DisplayName("공유 JSON의 다섯 상태와 매진·발급 종료 쿠폰을 한 번에 반환한다")
	void returnsSharedFrontendContractInOneQuery() throws Exception {
		String[] names = {"매진 쿠폰", "사용 예정 쿠폰", "사용 완료 쿠폰", "만료 쿠폰", "비활성 쿠폰", "발급 종료 보유 쿠폰"};
		for (int index = 0; index < names.length; index++) {
			long couponId = 12L + index;
			insertCoupon(couponId, names[index], couponId != 16,
				couponId == 12 ? NOW : couponId == 13 ? NOW.plusHours(1) : NOW.minusDays(1),
				couponId == 15 ? NOW : NOW.plusMonths(1));
			insertOwned(101L + index, MEMBER_ID, couponId, couponId == 14, NOW.minusMinutes(index));
		}
		jdbc.update("UPDATE coupon SET issued_quantity = total_quantity WHERE id = 12");
		jdbc.update("UPDATE coupon SET issue_end_at = ? WHERE id = 17", NOW);
		insertOwned(201, 8L, 12, true, NOW.plusMinutes(1));
		Statistics statistics = prepareMeasurement();

		var result = service.findMyCoupons(MEMBER_ID);

		try (var fixture = new ClassPathResource("contracts/member-coupons.json").getInputStream()) {
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(result)))
				.isEqualTo(objectMapper.readTree(fixture));
		}
		assertRead(statistics);
	}

	@ParameterizedTest
	@CsvSource({
		"false, true, 2026-09-08T11:59:59.999999, UPCOMING",
		"false, true, 2026-09-08T12:00:00, AVAILABLE",
		"false, true, 2026-09-08T12:59:59.999999, AVAILABLE",
		"false, true, 2026-09-08T13:00:00, EXPIRED",
		"false, false, 2026-09-08T13:00:00, EXPIRED",
		"false, false, 2026-09-08T11:59:59.999999, UNAVAILABLE",
		"false, false, 2026-09-08T12:00:00, UNAVAILABLE",
		"true, false, 2026-09-08T13:00:00, USED",
		"true, false, 2026-09-08T11:59:59.999999, USED"
	})
	@DisplayName("시작 포함·종료 제외 경계와 사용 완료→만료→비활성→사용 예정 우선순위를 지킨다")
	void preservesStatusBoundariesAndPrecedence(boolean used, boolean active, LocalDateTime now,
		MemberCouponStatus expected) {
		insertCoupon(12, "기간 경계", active, NOW, NOW.plusHours(1));
		insertOwned(101, MEMBER_ID, 12, used, NOW.minusDays(1));
		when(timeProvider.now()).thenReturn(now);
		Statistics statistics = prepareMeasurement();

		assertThat(service.findMyCoupons(MEMBER_ID).infos()).singleElement()
			.satisfies(info -> assertThat(info.status()).isEqualTo(expected));

		assertRead(statistics);
	}

	@Test
	@DisplayName("본인 보유만 발급시각·보유 ID 역순으로 반환하며 쿠폰 ID 순서와 혼동하지 않는다")
	void preservesOwnershipAndStableIssuanceOrder() {
		for (long id : List.of(12L, 13L, 14L)) {
			insertCoupon(id, "쿠폰 " + id, true, NOW.minusDays(1), NOW.plusDays(1));
		}
		insertOwned(300, MEMBER_ID, 14, false, NOW.minusMinutes(1));
		insertOwned(200, MEMBER_ID, 13, false, NOW);
		insertOwned(201, MEMBER_ID, 12, false, NOW);
		insertOwned(400, 8L, 12, true, NOW.plusMinutes(1));
		Statistics statistics = prepareMeasurement();

		var result = service.findMyCoupons(MEMBER_ID);

		assertThat(result.infos()).extracting(CouponResponse.MemberCouponInfo::couponId)
			.containsExactly(12L, 13L, 14L);
		assertThat(result.infos()).allMatch(info -> info.status() == MemberCouponStatus.AVAILABLE);
		assertRead(statistics);

		statistics = prepareMeasurement();
		assertThat(service.findMyCoupons(8L).infos()).singleElement().satisfies(info -> {
			assertThat(info.couponId()).isEqualTo(12L);
			assertThat(info.status()).isEqualTo(MemberCouponStatus.USED);
		});
		assertRead(statistics);
	}

	@ParameterizedTest
	@ValueSource(longs = {7L, 999L})
	@DisplayName("보유 쿠폰이 없거나 미존재 회원이면 다른 회원의 쿠폰 없이 빈 목록을 반환한다")
	void returnsEmptyCollectionForMemberWithoutOwnedCoupons(long memberId) {
		insertCoupon(12, "타인 쿠폰", true, NOW.minusDays(1), NOW.plusDays(1));
		insertOwned(101, 8L, 12, false, NOW);
		Statistics statistics = prepareMeasurement();

		assertThat(service.findMyCoupons(memberId).infos()).isEmpty();

		assertRead(statistics);
	}

	@Test
	@DisplayName("사용·복원·재사용 및 최신 할인 정책을 다음 조회에 반영한다")
	void readsCurrentUsageAndPolicyAfterChanges() {
		insertCoupon(12, "원래 쿠폰", true, NOW.minusDays(1), NOW.plusDays(1));
		insertOwned(101, MEMBER_ID, 12, false, NOW);
		assertCurrentStatus(MemberCouponStatus.AVAILABLE);

		assertThat(memberCouponRepository.markUsed(101L, 88L, NOW)).isEqualTo(1);
		assertCurrentStatus(MemberCouponStatus.USED);

		assertThat(memberCouponRepository.restoreByReservationId(88L)).isEqualTo(1);
		jdbc.update("""
			UPDATE coupon SET name = '정률 쿠폰', description = '최대 만오천 원', discount_type = 'PERCENTAGE',
				discount_value = 15, min_payment_price = NULL, max_discount_amount = 15000 WHERE id = 12
			""");
		Statistics statistics = prepareMeasurement();
		assertThat(service.findMyCoupons(MEMBER_ID).infos()).containsExactly(
			new CouponResponse.MemberCouponInfo(12L, "정률 쿠폰", "최대 만오천 원", DiscountType.PERCENTAGE,
				15, null, 15000, NOW.minusDays(1), NOW.plusDays(1), MemberCouponStatus.AVAILABLE));
		assertRead(statistics);

		assertThat(memberCouponRepository.reuseByReservationId(88L, NOW)).isEqualTo(1);
		assertCurrentStatus(MemberCouponStatus.USED);
	}

	private void assertCurrentStatus(MemberCouponStatus expected) {
		Statistics statistics = prepareMeasurement();
		assertThat(service.findMyCoupons(MEMBER_ID).infos()).singleElement()
			.satisfies(info -> assertThat(info.status()).isEqualTo(expected));
		assertRead(statistics);
	}

	private void insertCoupon(long id, String name, boolean active, LocalDateTime usableFrom,
		LocalDateTime usableUntil) {
		jdbc.update("""
			INSERT INTO coupon (id, name, discount_type, discount_value, min_payment_price,
				issue_start_at, issue_end_at, usable_from, usable_until, is_active, total_quantity,
				issued_quantity, created_at, updated_at)
			VALUES (?, ?, 'FIXED_AMOUNT', 10000, 50000, ?, ?, ?, ?, ?, 10, 0, ?, ?)
			""", id, name, NOW.minusDays(1), NOW.plusDays(1), usableFrom, usableUntil, active, NOW, NOW);
	}

	private void insertOwned(long id, long memberId, long couponId, boolean used, LocalDateTime createdAt) {
		jdbc.update("""
			INSERT INTO member_coupon (id, member_id, coupon_id, used, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?)
			""", id, memberId, couponId, used, createdAt, createdAt);
	}

	private Statistics prepareMeasurement() {
		entityManager.flush();
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		sqlCapture.statements.clear();
		return statistics;
	}

	private void assertRead(Statistics statistics) {
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(statistics.getEntityLoadCount()).isZero();
		assertThat(selectedColumnCount()).isEqualTo(11);
		String sql = sqlCapture.statements.getFirst();
		String selected = sql.substring("select ".length(), sql.indexOf(" from "));
		assertThat(selected).doesNotContain(".member_id", ".reservation_id", ".used_at", ".issue_start_at",
			".issue_end_at", ".total_quantity", ".issued_quantity", ".redis_stock_prepared_at", ".created_at",
			".updated_at", ".created_by", ".updated_by");
		assertThat(sql).contains(" join coupon ").doesNotContain(" join member ", " for update", " for share");
		verifyNoInteractions(stockManager);
	}

	private int selectedColumnCount() {
		String sql = sqlCapture.statements.getFirst();
		return sql.substring("select ".length(), sql.indexOf(" from ")).split(",").length;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ReadTestConfig {
		@Bean
		SqlCapture sqlCapture() {
			return new SqlCapture();
		}

		@Bean
		HibernatePropertiesCustomizer statementInspector(SqlCapture capture) {
			return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
		}
	}

	static class SqlCapture implements StatementInspector {
		private final List<String> statements = new ArrayList<>();

		@Override
		public String inspect(String sql) {
			statements.add(sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).trim());
			return sql;
		}
	}
}
