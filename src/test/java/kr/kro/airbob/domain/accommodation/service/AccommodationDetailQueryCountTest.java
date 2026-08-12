package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
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
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.geo.GeocodingService;
import kr.kro.airbob.outbox.OutboxEventPublisher;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	JpaAuditingConfig.class,
	QueryDslConfig.class,
	AccommodationQueryService.class
})
@DisplayName("숙소 상세 조회 쿼리 테스트")
class AccommodationDetailQueryCountTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_accommodation_detail_query");

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
	private AccommodationQueryService accommodationQueryService;

	@Autowired
	private AccommodationRepository accommodationRepository;

	@Autowired
	private AccommodationReviewSummaryRepository reviewSummaryRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@MockitoBean
	private CommonCodeService commonCodeService;

	@MockitoBean
	private CursorPageInfoCreator cursorPageInfoCreator;

	@MockitoBean
	private OutboxEventPublisher outboxEventPublisher;

	@MockitoBean
	private GeocodingService geocodingService;

	@MockitoBean
	private S3ImageUploader s3ImageUploader;

	@MockitoBean
	private BookingWindowProvider bookingWindowProvider;

	@BeforeEach
	void setUpBookingWindow() {
		when(bookingWindowProvider.currentFor("Asia/Seoul"))
			.thenReturn(BookingWindow.startingOn(LocalDate.of(2026, 8, 12)));
	}

	@Test
	@DisplayName("공개 숙소 상세는 리뷰 요약을 포함해 SELECT 네 번으로 조회한다")
	void findsPublicAccommodationDetailWithReviewSummaryInFourSelects() {
		Member host = saveHost("accommodation-detail-query");
		Accommodation accommodation = savePublishedAccommodation(host, "query-count-accommodation");
		saveReviewSummary(accommodation, 4, 18L, "4.50");
		Statistics statistics = prepareQueryMeasurement();

		AccommodationResponse.DetailInfo response =
			accommodationQueryService.findAccommodation(accommodation.getId(), null);

		assertThat(response.reviewSummary().totalCount()).isEqualTo(4);
		assertThat(response.reviewSummary().averageRating()).isEqualByComparingTo("4.50");
		assertThat(response.timeZoneId()).isEqualTo("Asia/Seoul");
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
	}

	@Test
	@DisplayName("리뷰 요약이 없는 공개 숙소도 조회하고 리뷰 수와 평점을 0으로 반환한다")
	void findsPublicAccommodationWithoutReviewSummary() {
		Member host = saveHost("accommodation-without-review-summary");
		Accommodation accommodation = savePublishedAccommodation(host, "accommodation-without-review-summary");
		Accommodation otherAccommodation = savePublishedAccommodation(host, "other-accommodation-with-review-summary");
		saveReviewSummary(otherAccommodation, 9, 45L, "5.00");
		Statistics statistics = prepareQueryMeasurement();

		AccommodationResponse.DetailInfo response =
			accommodationQueryService.findAccommodation(accommodation.getId(), null);

		assertThat(response.reviewSummary().totalCount()).isZero();
		assertThat(response.reviewSummary().averageRating()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
	}

	@Test
	@DisplayName("로그인한 공개 숙소 상세는 찜 조회를 포함해 SELECT 다섯 번으로 조회한다")
	void findsAuthenticatedAccommodationDetailInFiveSelects() {
		Member host = saveHost("authenticated-accommodation-detail-query");
		Accommodation accommodation = savePublishedAccommodation(host, "authenticated-query-count-accommodation");
		saveReviewSummary(accommodation, 2, 9L, "4.50");
		Statistics statistics = prepareQueryMeasurement();

		AccommodationResponse.DetailInfo response =
			accommodationQueryService.findAccommodation(accommodation.getId(), host.getId());

		assertThat(response.isInWishlist()).isFalse();
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(5);
	}

	private Member saveHost(String nickname) {
		return memberRepository.save(Member.builder()
			.email(nickname + "@test.com")
			.nickname(nickname)
			.build());
	}

	private Accommodation savePublishedAccommodation(Member host, String name) {
		return accommodationRepository.save(Accommodation.builder()
			.member(host)
			.name(name)
			.address(Address.builder()
				.country("대한민국")
				.city("서울")
				.build())
			.occupancyPolicy(OccupancyPolicy.builder()
				.maxOccupancy(4)
				.infantOccupancy(1)
				.petOccupancy(1)
				.build())
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId("Asia/Seoul")
			.status(AccommodationStatus.PUBLISHED)
			.build());
	}

	private void saveReviewSummary(
		Accommodation accommodation,
		int totalReviewCount,
		long ratingSum,
		String averageRating
	) {
		reviewSummaryRepository.save(AccommodationReviewSummary.builder()
			.accommodation(accommodation)
			.totalReviewCount(totalReviewCount)
			.ratingSum(ratingSum)
			.averageRating(new BigDecimal(averageRating))
			.build());
	}

	private Statistics prepareQueryMeasurement() {
		entityManager.flush();
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		return statistics;
	}
}
