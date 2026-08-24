package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalTime;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.config.AccommodationDetailRedisConfig;
import kr.kro.airbob.domain.accommodation.cache.config.AccommodationDetailCacheConfiguration;
import kr.kro.airbob.domain.accommodation.cache.invalidation.AccommodationDetailCacheInvalidationListener;
import kr.kro.airbob.domain.accommodation.cache.monitoring.FailSafeAccommodationDetailCacheMetricRecorder;
import kr.kro.airbob.domain.accommodation.cache.monitoring.MicrometerAccommodationDetailCacheMetricRecorder;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import kr.kro.airbob.domain.image.service.S3ImageUploader;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.geo.GeocodingService;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	ClockConfig.class,
	JpaAuditingConfig.class,
	QueryDslConfig.class,
	AccommodationDetailCacheQueryCountTest.TestRedisConfiguration.class,
	AccommodationDetailCacheConfiguration.class,
	AccommodationDetailRedisConfig.class,
	AccommodationDetailCache.class,
	MicrometerAccommodationDetailCacheMetricRecorder.class,
	FailSafeAccommodationDetailCacheMetricRecorder.class,
	AccommodationDetailCacheInvalidationListener.class,
	AccommodationDetailReader.class,
	AccommodationQueryService.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("숙소 상세 캐시 쿼리 수 통합 테스트")
class AccommodationDetailCacheQueryCountTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_accommodation_detail_cache_query");

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>(
		DockerImageName.parse("redis:7.2-alpine"))
		.withExposedPorts(6379);

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired private AccommodationQueryService queryService;
	@Autowired private AccommodationRepository accommodationRepository;
	@Autowired private AccommodationReviewSummaryRepository reviewSummaryRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;

	@MockitoBean private CommonCodeService commonCodeService;
	@MockitoBean private CursorPageInfoCreator cursorPageInfoCreator;
	@MockitoBean private AccommodationSearchRefreshPublisher searchRefreshPublisher;
	@MockitoBean private GeocodingService geocodingService;
	@MockitoBean private S3ImageUploader s3ImageUploader;
	@MockitoBean private BookingWindowProvider bookingWindowProvider;

	private Accommodation accommodation;
	private Member host;

	@BeforeEach
	void setUp() {
		host = memberRepository.save(Member.builder()
			.email("cache-query@test.com")
			.nickname("cache-query-host")
			.build());
		accommodation = accommodationRepository.save(Accommodation.builder()
			.member(host)
			.name("cache-query-accommodation")
			.address(Address.builder().country("대한민국").city("서울").build())
			.occupancyPolicy(OccupancyPolicy.builder()
				.maxOccupancy(4).infantOccupancy(1).petOccupancy(0).build())
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId("Asia/Seoul")
			.status(AccommodationStatus.PUBLISHED)
			.build());
		reviewSummaryRepository.save(AccommodationReviewSummary.builder()
			.accommodation(accommodation)
			.totalReviewCount(4)
			.ratingSum(18L)
			.averageRating(new BigDecimal("4.50"))
			.build());
	}

	@Test
	@DisplayName("비로그인 상세은 첫 조회 3 SELECT 후 warm cache에서 0 SELECT이다")
	void anonymousColdMissThenWarmHit() {
		Statistics cold = prepareMeasurement();

		AccommodationResponse.DetailInfo first = queryService.findAccommodation(accommodation.getId(), null);

		assertThat(first.reviewSummary().averageRating()).isEqualByComparingTo("4.50");
		assertThat(cold.getPrepareStatementCount()).isEqualTo(3);

		Statistics warm = prepareMeasurement();
		AccommodationResponse.DetailInfo second = queryService.findAccommodation(accommodation.getId(), null);

		assertThat(second).isEqualTo(first);
		assertThat(warm.getPrepareStatementCount()).isZero();
	}

	@Test
	@DisplayName("로그인 상세은 첫 조회 4 SELECT 후 warm cache에서 찜 조회 1 SELECT만 실행한다")
	void authenticatedColdMissThenWarmHit() {
		Statistics cold = prepareMeasurement();

		queryService.findAccommodation(accommodation.getId(), host.getId());

		assertThat(cold.getPrepareStatementCount()).isEqualTo(4);

		Statistics warm = prepareMeasurement();
		AccommodationResponse.DetailInfo second =
			queryService.findAccommodation(accommodation.getId(), host.getId());

		assertThat(second.isInWishlist()).isFalse();
		assertThat(warm.getPrepareStatementCount()).isEqualTo(1);
	}

	private Statistics prepareMeasurement() {
		entityManager.flush();
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		return statistics;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestRedisConfiguration {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper().findAndRegisterModules();
		}

		@Bean
		MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}
	}
}
