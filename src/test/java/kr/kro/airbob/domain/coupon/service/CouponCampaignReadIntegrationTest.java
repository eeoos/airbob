package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.coupon.common.CouponIssuanceStatus;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class, CouponQueryService.class,
	CouponTimeProvider.class, CouponCampaignReadIntegrationTest.ReadTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("발급 쿠폰 목록 MySQL 조회 계약")
class CouponCampaignReadIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 12, 0);
	private long redisNowMillis;

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_coupon_campaign_read");

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
	@Autowired private CouponRepository couponRepository;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;
	@Autowired private SqlCapture sqlCapture;
	@MockitoSpyBean private CouponTimeProvider timeProvider;
	@MockitoBean private CouponRedisStockManager stockManager;

	@BeforeEach
	void setUp() {
		setRedisNow(NOW);
	}

	@Test
	@DisplayName("공유 JSON의 세 발급 상태와 기간을 시작시각·ID 역순으로 한 번에 반환한다")
	void returnsSharedContractInStableCampaignOrder() throws Exception {
		insertCoupon(10, "발급 예정 쿠폰", NOW.plusHours(1), NOW.plusDays(1), 10, 0);
		insertCoupon(11, "발급 중 쿠폰", NOW.minusDays(1), NOW.plusDays(1), 10, 0);
		insertCoupon(12, "매진 쿠폰", NOW.minusDays(1), NOW.plusDays(1), 10, 10);
		Statistics statistics = prepareMeasurement();

		var result = service.findCouponCampaigns();

		assertThat(result.infos()).extracting(CouponResponse.CouponInfo::id).containsExactly(10L, 12L, 11L);
		try (var fixture = new ClassPathResource("contracts/coupon-campaigns.json").getInputStream()) {
			ObjectNode expected = (ObjectNode)objectMapper.readTree(fixture);
			var infos = expected.get("infos");
			// 기존 공유 fixture의 필드는 유지하고, 같은 시작시각의 두 행은 실제 ID 역순으로 비교한다.
			expected.set("infos", objectMapper.createArrayNode().add(infos.get(0)).add(infos.get(2)).add(infos.get(1)));
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(result))).isEqualTo(expected);
		}
		assertRead(statistics);
	}

	@ParameterizedTest
	@CsvSource({
		"-1, 10, 0, UPCOMING",
		"0, 10, 0, OPEN",
		"0, 10, 10, SOLD_OUT",
		"0, 10, 11, SOLD_OUT",
		"-1, 10, 10, UPCOMING",
		"0, , 1000000, OPEN",
		"0, 0, 0, SOLD_OUT"
	})
	@DisplayName("Redis 시각의 시작 경계와 매진·무제한 수량·발급 예정 우선순위를 유지한다")
	void preservesIssuanceStatus(long offsetMillis, Integer totalQuantity, int issuedQuantity,
		CouponIssuanceStatus expected) {
		insertCoupon(10, "발급 경계", NOW, NOW.plusHours(1), totalQuantity, issuedQuantity);
		setRedisNow(NOW.plusNanos(offsetMillis * 1_000_000));
		Statistics statistics = prepareMeasurement();

		assertThat(service.findCouponCampaigns().infos()).singleElement().satisfies(info -> {
			assertThat(info.totalQuantity()).isEqualTo(totalQuantity);
			assertThat(info.issuedQuantity()).isEqualTo(issuedQuantity);
			assertThat(info.issuanceStatus()).isEqualTo(expected);
		});

		assertRead(statistics);
	}

	@Test
	@DisplayName("활성·재고 준비·미종료 조건만 적용하고 매진·발급 예정 및 별도 사용 기간은 보존한다")
	void preservesVisibilityFiltersAndSeparateUsablePeriod() {
		for (long id = 10; id <= 14; id++) {
			insertCoupon(id, "캠페인 " + id, NOW.minusDays(1), NOW.plusHours(1), 10, 0);
		}
		jdbc.update("UPDATE coupon SET is_active = false WHERE id = 10");
		jdbc.update("UPDATE coupon SET redis_stock_prepared_at = NULL WHERE id = 11");
		jdbc.update("UPDATE coupon SET issue_end_at = ? WHERE id = 12", NOW);
		jdbc.update("UPDATE coupon SET issued_quantity = 10, usable_until = ? WHERE id = 13", NOW);
		jdbc.update("UPDATE coupon SET issue_start_at = ?, usable_from = ? WHERE id = 14",
			NOW.plusMinutes(30), NOW.plusDays(2));
		Statistics statistics = prepareMeasurement();

		var result = service.findCouponCampaigns();

		assertThat(result.infos()).extracting(CouponResponse.CouponInfo::id).containsExactly(14L, 13L);
		assertThat(result.infos()).extracting(CouponResponse.CouponInfo::issuanceStatus)
			.containsExactly(CouponIssuanceStatus.UPCOMING, CouponIssuanceStatus.SOLD_OUT);
		assertRead(statistics);

		setRedisNow(NOW.plusHours(1));
		statistics = prepareMeasurement();
		assertThat(service.findCouponCampaigns().infos()).isEmpty();
		assertRead(statistics);
	}

	@Test
	@DisplayName("종료 직전에는 노출하고 Redis 시각이 종료에 도달하면 제외한다")
	void excludesCampaignAtExactIssuanceEnd() {
		insertCoupon(10, "종료 경계", NOW.minusDays(1), NOW, 10, 0);
		setRedisNow(NOW.minusNanos(1_000_000));
		Statistics statistics = prepareMeasurement();

		assertThat(service.findCouponCampaigns().infos()).singleElement()
			.satisfies(info -> assertThat(info.issuanceStatus()).isEqualTo(CouponIssuanceStatus.OPEN));
		assertRead(statistics);

		setRedisNow(NOW);
		statistics = prepareMeasurement();
		assertThat(service.findCouponCampaigns().infos()).isEmpty();
		assertRead(statistics);
	}

	@Test
	@DisplayName("수량 증가와 현재 할인 정보 변경을 다음 조회에 반영한다")
	void readsCurrentQuantityAndDiscountAfterChanges() {
		insertCoupon(10, "원래 쿠폰", NOW.minusDays(1), NOW.plusHours(1), 1, 0);
		Statistics statistics = prepareMeasurement();
		assertThat(service.findCouponCampaigns().infos()).singleElement()
			.satisfies(info -> assertThat(info.issuanceStatus()).isEqualTo(CouponIssuanceStatus.OPEN));
		assertRead(statistics);

		assertThat(couponRepository.incrementIssuedQuantity(10L)).isEqualTo(1);
		jdbc.update("""
			UPDATE coupon SET name = '정률 쿠폰', description = '최대 만오천 원', discount_type = 'PERCENTAGE',
				discount_value = 15, min_payment_price = NULL, max_discount_amount = 15000 WHERE id = 10
			""");
		statistics = prepareMeasurement();

		assertThat(service.findCouponCampaigns().infos()).containsExactly(new CouponResponse.CouponInfo(
			10L, "정률 쿠폰", "최대 만오천 원", DiscountType.PERCENTAGE, 15, null, 15000,
			NOW.minusDays(1), NOW.plusHours(1), NOW.minusDays(1), NOW.plusMonths(1),
			1, 1, CouponIssuanceStatus.SOLD_OUT));
		assertRead(statistics);
	}

	@Test
	@DisplayName("캠페인이 없어도 하나의 조회로 빈 목록을 반환한다")
	void returnsEmptyCollection() {
		Statistics statistics = prepareMeasurement();

		assertThat(service.findCouponCampaigns().infos()).isEmpty();

		assertRead(statistics);
	}

	private void insertCoupon(long id, String name, LocalDateTime issueStart, LocalDateTime issueEnd,
		Integer totalQuantity, int issuedQuantity) {
		jdbc.update("""
			INSERT INTO coupon (id, name, discount_type, discount_value, min_payment_price,
				issue_start_at, issue_end_at, usable_from, usable_until, is_active, total_quantity,
				issued_quantity, redis_stock_prepared_at, created_at, updated_at)
			VALUES (?, ?, 'FIXED_AMOUNT', 10000, 50000, ?, ?, ?, ?, true, ?, ?, ?, ?, ?)
			""", id, name, issueStart, issueEnd, NOW.minusDays(1), NOW.plusMonths(1),
			totalQuantity, issuedQuantity, NOW.minusDays(2), NOW, NOW);
	}

	private void setRedisNow(LocalDateTime now) {
		redisNowMillis = now.atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli();
		when(stockManager.currentEpochMillis()).thenReturn(redisNowMillis);
	}

	private Statistics prepareMeasurement() {
		entityManager.flush();
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		sqlCapture.statements.clear();
		clearInvocations(stockManager, timeProvider);
		return statistics;
	}

	private void assertRead(Statistics statistics) {
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(statistics.getEntityLoadCount()).isZero();
		assertThat(selectedColumnCount()).isEqualTo(13);
		String sql = sqlCapture.statements.getFirst();
		String selected = sql.substring("select ".length(), sql.indexOf(" from "));
		assertThat(selected).doesNotContain(".is_active", ".redis_stock_prepared_at", ".created_at",
			".updated_at", ".created_by", ".updated_by");
		assertThat(sql).doesNotContain(" join ", " for update", " for share");
		verify(stockManager).currentEpochMillis();
		verify(timeProvider).fromEpochMilli(redisNowMillis);
		verify(timeProvider, never()).now();
		verifyNoMoreInteractions(stockManager);
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
