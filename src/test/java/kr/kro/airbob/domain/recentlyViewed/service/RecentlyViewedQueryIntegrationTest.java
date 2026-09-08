package kr.kro.airbob.domain.recentlyViewed.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse.RecentlyViewedAccommodationInfos;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({RedisAutoConfiguration.class, JacksonAutoConfiguration.class})
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class, RecentlyViewedService.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("최근 본 숙소 조회 계약 MySQL·Redis 통합 테스트")
class RecentlyViewedQueryIntegrationTest {

	private static final long MEMBER_ID = 7L;
	private static final String KEY = "recently_viewed:7";
	private static final String OTHER_KEY = "recently_viewed:8";

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_recently_viewed_query");
	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
		.withExposedPorts(6379);

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired private RecentlyViewedService service;
	@Autowired private StringRedisTemplate redis;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;

	@BeforeEach
	void setUp() {
		redis.delete(List.of(KEY, OTHER_KEY));
		jdbc.update("""
			INSERT INTO member (id, nickname, status, updated_at)
			VALUES (7, 'owner', 'ACTIVE', NOW(6)), (8, 'other', 'ACTIVE', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO address (id, country, city, district, updated_at)
			VALUES (21, '대한민국', '서울', '마포구', NOW(6))
			""");
	}

	@Test
	@DisplayName("두 SELECT로 최근 순서·동점 순서·찜 소유권·빈 요약을 보존하고 비공개 기록만 정리한다")
	void returnsFrontendContractInTwoSelectsAndCleansUnavailableHistory() throws Exception {
		insertMixedHistory();
		Statistics statistics = prepareMeasurement();

		RecentlyViewedAccommodationInfos result = service.getRecentlyViewed(MEMBER_ID);

		assertContract(result);
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
		assertHistoryCleaned();
	}

	@Test
	@DisplayName("주소 N+1 벤치마크 경로도 같은 응답과 기록 정리 동작을 유지한다")
	void benchmarkBeforeRetainsTheSameContract() throws Exception {
		insertMixedHistory();
		prepareMeasurement();

		assertContract(service.getRecentlyViewedBefore(MEMBER_ID));
		assertHistoryCleaned();
	}

	@Test
	@DisplayName("기록이 없으면 DB 조회를 하지 않는다")
	void emptyHistoryDoesNotReadDatabase() {
		Statistics statistics = prepareMeasurement();

		assertEmpty(service.getRecentlyViewed(MEMBER_ID));

		assertThat(statistics.getPrepareStatementCount()).isZero();
	}

	@Test
	@DisplayName("유효한 숙소가 없으면 한 SELECT 후 기록을 정리하고 찜 조회를 생략한다")
	void unavailableHistorySkipsDependentReads() {
		insertAccommodation(40, "UNPUBLISHED", 21L);
		addHistory(KEY, 40, "2026-09-07T00:00:00Z");
		addHistory(KEY, 999, "2026-09-06T00:00:00Z");
		Statistics statistics = prepareMeasurement();

		assertEmpty(service.getRecentlyViewed(MEMBER_ID));

		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(redis.hasKey(KEY)).isFalse();
		statistics.clear();
		assertEmpty(service.getRecentlyViewed(MEMBER_ID));
		assertThat(statistics.getPrepareStatementCount()).isZero();
	}

	private void insertMixedHistory() {
		insertAccommodation(31, "PUBLISHED", 21L);
		insertAccommodation(32, "PUBLISHED", 21L);
		insertAccommodation(33, "PUBLISHED", null);
		insertAccommodation(40, "DRAFT", 21L);
		insertAccommodation(41, "UNPUBLISHED", 21L);
		insertAccommodation(42, "DELETED", 21L);
		jdbc.update("""
			INSERT INTO accommodation_review_summary
				(accommodation_id, total_review_count, rating_sum, average_rating, updated_at)
			VALUES (31, 4, 19, 4.75, NOW(6)), (32, 0, 0, 0.00, NOW(6))
			""");
		jdbc.update("""
			INSERT INTO wishlist (id, name, member_id, status, updated_at)
			VALUES (51, '여름 여행', 7, 'ACTIVE', NOW(6)),
				(52, '겨울 여행', 7, 'ACTIVE', NOW(6)), (53, '타인 여행', 8, 'ACTIVE', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO wishlist_accommodation (id, wishlist_id, accommodation_id, updated_at)
			VALUES (61, 51, 31, NOW(6)), (62, 52, 31, NOW(6)), (63, 53, 32, NOW(6))
			""");
		for (long id : List.of(31L, 32L, 40L, 41L, 42L, 999L)) {
			addHistory(KEY, id, "2026-09-06T00:00:00Z");
		}
		addHistory(KEY, 33, "2026-09-07T00:00:00Z");
		addHistory(OTHER_KEY, 40, "2026-09-07T00:00:00Z");
	}

	private void insertAccommodation(long id, String status, Long addressId) {
		jdbc.update("""
			INSERT INTO accommodation
				(id, name, member_id, address_id, thumbnail_url, status, accommodation_uid,
				check_in_time, check_out_time, time_zone_id, updated_at)
			VALUES (?, ?, 7, ?, '/stay.jpg', ?, UUID_TO_BIN(UUID()),
				'15:00:00', '11:00:00', 'Asia/Seoul', NOW(6))
			""", id, "서울 하우스 " + id, addressId, status);
	}

	private void addHistory(String key, long id, String viewedAt) {
		redis.opsForZSet().add(key, String.valueOf(id), Instant.parse(viewedAt).toEpochMilli());
	}

	private Statistics prepareMeasurement() {
		entityManager.flush();
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		return statistics;
	}

	private void assertContract(RecentlyViewedAccommodationInfos result) throws Exception {
		try (var fixture = new ClassPathResource("contracts/recently-viewed-mixed-history.json")
			.getInputStream()) {
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(result)))
				.isEqualTo(objectMapper.readTree(fixture));
		}
	}

	private void assertHistoryCleaned() {
		assertThat(redis.opsForZSet().reverseRangeWithScores(KEY, 0, -1))
			.extracting(ZSetOperations.TypedTuple::getValue).containsExactly("33", "32", "31");
		assertThat(redis.opsForZSet().reverseRange(OTHER_KEY, 0, -1)).containsExactly("40");
	}

	private void assertEmpty(RecentlyViewedAccommodationInfos result) {
		assertThat(result.accommodations()).isEmpty();
		assertThat(result.totalCount()).isZero();
	}
}
