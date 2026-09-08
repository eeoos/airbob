package kr.kro.airbob.domain.wishlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.cursor.dto.CursorData;
import kr.kro.airbob.cursor.dto.CursorRequest.CursorPageRequest;
import kr.kro.airbob.cursor.dto.CursorResponse.PageInfo;
import kr.kro.airbob.cursor.util.CursorDecoder;
import kr.kro.airbob.cursor.util.CursorEncoder;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationResponse.WishlistAccommodationInfo;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationResponse.WishlistAccommodationInfos;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistNotFoundException;
import kr.kro.airbob.domain.wishlist.service.WishlistService;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({
	ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class,
	WishlistService.class, CursorPageInfoCreator.class, CursorEncoder.class, CursorDecoder.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("위시리스트 상세 조회 계약 MySQL 통합 테스트")
class WishlistDetailQueryIntegrationTest {

	private static final long OWNER_ID = 7L;
	private static final long OTHER_MEMBER_ID = 8L;
	private static final long WISHLIST_ID = 42L;
	private static final long OTHER_WISHLIST_ID = 43L;

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_wishlist_detail_query");

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private WishlistService service;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private CursorDecoder cursorDecoder;

	@BeforeEach
	void setUp() {
		jdbc.update("""
			INSERT INTO member (id, nickname, status, updated_at)
			VALUES (?, 'owner', 'ACTIVE', NOW(6)), (?, 'other', 'ACTIVE', NOW(6))
			""", OWNER_ID, OTHER_MEMBER_ID);
		jdbc.update("""
			INSERT INTO address (id, country, city, district, updated_at)
			VALUES (21, '대한민국', '서울', '마포구', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO wishlist (id, name, member_id, status, updated_at)
			VALUES (?, '여름 여행', ?, 'ACTIVE', NOW(6)), (?, '다른 여행', ?, 'ACTIVE', NOW(6))
			""", WISHLIST_ID, OWNER_ID, OTHER_WISHLIST_ID, OTHER_MEMBER_ID);
	}

	@Test
	@DisplayName("요청한 위시리스트의 공개 숙소만 주소와 리뷰 요약을 포함해 반환한다")
	void returnsOnlyPublishedAccommodationsFromRequestedWishlist() {
		insertAccommodation(31, "PUBLISHED");
		insertAccommodation(32, "PUBLISHED");
		insertAccommodation(33, "DRAFT");
		insertAccommodation(34, "PUBLISHED");
		insertItem(501, WISHLIST_ID, 31);
		insertItem(502, WISHLIST_ID, 32);
		insertItem(503, WISHLIST_ID, 33);
		insertItem(601, OTHER_WISHLIST_ID, 31);
		insertItem(602, OTHER_WISHLIST_ID, 34);
		insertReviewSummary(31, 4, 19, "4.75");

		WishlistAccommodationInfos result = findFirstPage(20);

		assertThat(result.wishlistAccommodations())
			.extracting(WishlistAccommodationInfo::wishlistAccommodationId)
			.containsExactly(502L, 501L);
		WishlistAccommodationInfo reviewed = result.wishlistAccommodations().getLast();
		assertThat(reviewed.accommodation().id()).isEqualTo(31L);
		assertThat(reviewed.addressSummary().city()).isEqualTo("서울");
		assertThat(reviewed.memo()).isEqualTo("창가 방");
		assertThat(reviewed.isInWishlist()).isTrue();
		assertThat(reviewed.createdAt()).isEqualTo(Instant.parse("2026-07-02T00:00:00Z"));
		assertThat(reviewed.reviewSummary().totalCount()).isEqualTo(4);
		assertThat(reviewed.reviewSummary().averageRating()).isEqualByComparingTo("4.75");
		assertThat(result.pageInfo()).isEqualTo(new PageInfo(false, null, 2));
	}

	@Test
	@DisplayName("리뷰 요약 행이 없는 숙소를 프론트와 공유하는 0건·0점 JSON 계약으로 반환한다")
	void missingReviewSummaryMatchesFrontendJsonContract() throws Exception {
		insertAccommodation(31, "PUBLISHED");
		insertItem(501, WISHLIST_ID, 31);

		WishlistAccommodationInfos result = findFirstPage(20);

		try (var fixture = new ClassPathResource("contracts/wishlist-detail-without-reviews.json")
			.getInputStream()) {
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(result)))
				.isEqualTo(objectMapper.readTree(fixture));
		}
	}

	@Test
	@DisplayName("리뷰가 모두 삭제되어 요약 행만 남은 경우도 0건·0점으로 반환한다")
	void existingEmptyReviewSummaryReturnsZeros() {
		insertAccommodation(31, "PUBLISHED");
		insertItem(501, WISHLIST_ID, 31);
		insertReviewSummary(31, 0, 0, "0.00");

		var summary = findFirstPage(20).wishlistAccommodations().getFirst().reviewSummary();

		assertThat(summary.totalCount()).isZero();
		assertThat(summary.averageRating()).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("동일 생성 시각에서도 연결 ID 내림차순 커서로 중복·누락 없이 다음 페이지를 반환한다")
	void paginatesTiedCreationTimesWithoutDuplicatesOrMissingItems() {
		for (int offset = 0; offset < 4; offset++) {
			insertAccommodation(31 + offset, "PUBLISHED");
			insertItem(501 + offset, WISHLIST_ID, 31 + offset);
		}
		jdbc.update("UPDATE wishlist_accommodation SET created_at = '2026-07-01 00:00:00' WHERE id = 504");

		WishlistAccommodationInfos first = findFirstPage(2);
		assertThat(first.wishlistAccommodations())
			.extracting(WishlistAccommodationInfo::wishlistAccommodationId)
			.containsExactly(503L, 502L);
		assertThat(first.pageInfo().hasNext()).isTrue();
		assertThat(first.pageInfo().currentSize()).isEqualTo(2);
		CursorData cursor = cursorDecoder.decode(first.pageInfo().nextCursor(), CursorData.class);

		WishlistAccommodationInfos second = service.findWishlistAccommodations(
			WISHLIST_ID,
			CursorPageRequest.builder().size(2).lastId(cursor.id()).lastCreatedAt(cursor.lastCreatedAt()).build(),
			OWNER_ID);

		assertThat(second.wishlistAccommodations())
			.extracting(WishlistAccommodationInfo::wishlistAccommodationId)
			.containsExactly(501L, 504L);
		assertThat(second.pageInfo()).isEqualTo(new PageInfo(false, null, 2));
	}

	@Test
	@DisplayName("빈 위시리스트는 빈 목록과 마지막 페이지 정보를 반환한다")
	void emptyWishlistReturnsEmptyLastPage() {
		assertEmpty(findFirstPage(20));
	}

	@Test
	@DisplayName("비공개 숙소만 있는 위시리스트는 빈 목록을 반환한다")
	void wishlistContainingOnlyUnpublishedAccommodationsReturnsEmptyLastPage() {
		insertAccommodation(31, "DRAFT");
		insertItem(501, WISHLIST_ID, 31);

		assertEmpty(findFirstPage(20));
	}

	@Test
	@DisplayName("타인의 위시리스트는 빈 목록 대신 접근 거부를 유지한다")
	void anotherMembersWishlistIsDeniedEvenWhenEmpty() {
		assertThatThrownBy(() -> service.findWishlistAccommodations(
			WISHLIST_ID, CursorPageRequest.builder().size(20).build(), OTHER_MEMBER_ID))
			.isInstanceOf(WishlistAccessDeniedException.class);
	}

	@Test
	@DisplayName("삭제되었거나 존재하지 않는 위시리스트는 찾을 수 없음으로 처리한다")
	void deletedAndMissingWishlistsAreNotFound() {
		jdbc.update("UPDATE wishlist SET status = 'DELETED' WHERE id = ?", WISHLIST_ID);

		assertThatThrownBy(() -> findFirstPage(20)).isInstanceOf(WishlistNotFoundException.class);
		assertThatThrownBy(() -> service.findWishlistAccommodations(
			999L, CursorPageRequest.builder().size(20).build(), OWNER_ID))
			.isInstanceOf(WishlistNotFoundException.class);
	}

	private WishlistAccommodationInfos findFirstPage(int size) {
		return service.findWishlistAccommodations(
			WISHLIST_ID, CursorPageRequest.builder().size(size).build(), OWNER_ID);
	}

	private void assertEmpty(WishlistAccommodationInfos result) {
		assertThat(result.wishlistAccommodations()).isEmpty();
		assertThat(result.pageInfo()).isEqualTo(new PageInfo(false, null, 0));
	}

	private void insertAccommodation(long id, String status) {
		jdbc.update("""
			INSERT INTO accommodation
				(id, name, member_id, address_id, thumbnail_url, status, accommodation_uid,
				check_in_time, check_out_time, time_zone_id, updated_at)
			VALUES (?, '서울 하우스', ?, 21, '/stay.jpg', ?, UUID_TO_BIN(UUID()),
				'15:00:00', '11:00:00', 'Asia/Seoul', NOW(6))
			""", id, OWNER_ID, status);
	}

	private void insertItem(long id, long wishlistId, long accommodationId) {
		jdbc.update("""
			INSERT INTO wishlist_accommodation
				(id, wishlist_id, accommodation_id, memo, created_at, updated_at)
			VALUES (?, ?, ?, '창가 방', '2026-07-02 00:00:00.000000', NOW(6))
			""", id, wishlistId, accommodationId);
	}

	private void insertReviewSummary(long accommodationId, int count, int ratingSum, String averageRating) {
		jdbc.update("""
			INSERT INTO accommodation_review_summary
				(accommodation_id, total_review_count, rating_sum, average_rating, updated_at)
			VALUES (?, ?, ?, ?, NOW(6))
			""", accommodationId, count, ratingSum, averageRating);
	}
}
