package kr.kro.airbob.domain.wishlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
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
import kr.kro.airbob.domain.wishlist.dto.WishlistRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistNotFoundException;
import kr.kro.airbob.domain.wishlist.service.WishlistService;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({
	ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class,
	WishlistService.class, CursorPageInfoCreator.class, CursorEncoder.class, CursorDecoder.class,
	WishlistDetailQueryIntegrationTest.ReadTestConfig.class
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
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;
	@Autowired private SqlCapture sqlCapture;

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
	@DisplayName("상세 이름은 권한 검사에서 읽은 값을 재사용하고 빈 목록에서도 두 SELECT를 유지한다")
	void detailIncludesNameWithoutAdditionalRead() {
		insertAccommodation(31, "PUBLISHED");
		insertItem(501, WISHLIST_ID, 31);
		Statistics statistics = prepareMeasurement();

		assertThat(findFirstPage(20).wishlistName()).isEqualTo("여름 여행");
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);

		jdbc.update("DELETE FROM wishlist_accommodation WHERE id = 501");
		statistics = prepareMeasurement();
		var empty = findFirstPage(20);
		assertEmpty(empty);
		assertThat(empty.wishlistName()).isEqualTo("여름 여행");
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("제목·숙소·주소·후기·메모의 현재 값을 다음 조회에 반영한다")
	void readsCurrentHeaderCardAndMemo() {
		insertAccommodation(31, "PUBLISHED");
		insertItem(501, WISHLIST_ID, 31);
		insertReviewSummary(31, 4, 19, "4.75");
		findFirstPage(20);
		service.updateWishlist(WISHLIST_ID, new WishlistRequest.Update("가을 여행"), OWNER_ID);
		jdbc.update("UPDATE wishlist_accommodation SET memo = NULL WHERE id = 501");
		jdbc.update("UPDATE accommodation SET name = '춘천 호수집', thumbnail_url = NULL WHERE id = 31");
		jdbc.update("UPDATE address SET state = '강원특별자치도', city = '춘천', district = '남산면' WHERE id = 21");
		jdbc.update("""
			UPDATE accommodation_review_summary SET total_review_count = 2, rating_sum = 9, average_rating = 4.50
			WHERE accommodation_id = 31
			""");

		var result = findFirstPage(20);

		assertThat(result.wishlistName()).isEqualTo("가을 여행");
		assertThat(result.wishlistAccommodations()).singleElement().satisfies(card -> {
			assertThat(card.wishlistAccommodationId()).isEqualTo(501L);
			assertThat(card.accommodation().id()).isEqualTo(31L);
			assertThat(card.accommodation().name()).isEqualTo("춘천 호수집");
			assertThat(card.accommodation().thumbnailUrl()).isNull();
			assertThat(card.addressSummary().country()).isEqualTo("대한민국");
			assertThat(card.addressSummary().state()).isEqualTo("강원특별자치도");
			assertThat(card.addressSummary().city()).isEqualTo("춘천");
			assertThat(card.addressSummary().district()).isEqualTo("남산면");
			assertThat(card.memo()).isNull();
			assertThat(card.reviewSummary().totalCount()).isEqualTo(2);
			assertThat(card.reviewSummary().averageRating()).isEqualByComparingTo("4.50");
			assertThat(card.createdAt()).isEqualTo(Instant.parse("2026-07-02T00:00:00Z"));
			assertThat(card.isInWishlist()).isTrue();
		});
	}

	@Test
	@DisplayName("주소 행 없는 숙소는 기존처럼 제외하고 주소 필드의 null 값은 유지한다")
	void preservesAddressJoinAndNullableFields() {
		insertAccommodation(31, "PUBLISHED");
		insertAccommodation(32, "PUBLISHED");
		insertItem(501, WISHLIST_ID, 31);
		insertItem(502, WISHLIST_ID, 32);
		jdbc.update("UPDATE accommodation SET address_id = NULL WHERE id = 32");
		jdbc.update("UPDATE address SET country = NULL, state = NULL, city = NULL, district = NULL WHERE id = 21");

		var result = findFirstPage(20);

		assertThat(result.wishlistAccommodations()).singleElement().satisfies(card -> {
			assertThat(card.wishlistAccommodationId()).isEqualTo(501L);
			assertThat(card.addressSummary().country()).isNull();
			assertThat(card.addressSummary().state()).isNull();
			assertThat(card.addressSummary().city()).isNull();
			assertThat(card.addressSummary().district()).isNull();
		});
		assertThat(result.pageInfo()).isEqualTo(new PageInfo(false, null, 1));
	}

	@Test
	@DisplayName("목록 세 페이지로 구하는 찜 상태를 전용 상태 조회 한 번으로 반환한다")
	void membershipSnapshotReplacesThreeSummaryPages() {
		insertAccommodation(31, "PUBLISHED");
		for (long id = 100; id < 145; id++) {
			jdbc.update("""
				INSERT INTO wishlist
					(id, name, member_id, status, accommodation_count, representative_accommodation_id, created_at, updated_at)
				VALUES (?, '여행', ?, 'ACTIVE', 1, 31, '2026-07-01 00:00:00', NOW(6))
				""", id, OWNER_ID);
			insertItem(1000 + id, id, 31);
		}
		Statistics before = prepareMeasurement();
		CursorData cursor = null;
		int pages = 0;
		boolean inAny = false;
		boolean inTarget = false;
		do {
			var page = service.findWishlists(CursorPageRequest.builder().size(20)
				.lastId(cursor == null ? null : cursor.id())
				.lastCreatedAt(cursor == null ? null : cursor.lastCreatedAt()).build(), OWNER_ID, 31L);
			pages++;
			inAny |= page.wishlists().stream().anyMatch(w -> Boolean.TRUE.equals(w.isContained()));
			inTarget |= page.wishlists().stream()
				.anyMatch(w -> w.id() == 100 && Boolean.TRUE.equals(w.isContained()));
			cursor = cursorDecoder.decode(page.pageInfo().nextCursor(), CursorData.class);
		} while (cursor != null);
		assertThat(pages).isEqualTo(3);
		assertThat(before.getPrepareStatementCount()).isEqualTo(3);
		assertThat(before.getEntityLoadCount()).isZero();

		assertThat(membership(OWNER_ID, 31L, 100L))
			.isEqualTo(new WishlistResponse.Membership(inAny, inTarget, true));
	}

	@Test
	@DisplayName("찜 상태는 내 활성 위시리스트만 확인하고 비공개 숙소의 저장 상태도 보존한다")
	void membershipPreservesOwnershipAndSavedUnpublishedAccommodation() {
		insertAccommodation(31, "UNPUBLISHED");
		insertAccommodation(32, "PUBLISHED");
		insertItem(501, WISHLIST_ID, 31);
		insertItem(502, OTHER_WISHLIST_ID, 32);
		jdbc.update("""
			INSERT INTO wishlist (id, name, member_id, status, updated_at)
			VALUES (44, '삭제된 여행', ?, 'DELETED', NOW(6))
			""", OWNER_ID);
		insertItem(503, 44, 32);

		assertThat(membership(OWNER_ID, 31L, WISHLIST_ID))
			.isEqualTo(new WishlistResponse.Membership(true, true, true));
		assertThat(membership(OWNER_ID, 32L, WISHLIST_ID))
			.isEqualTo(new WishlistResponse.Membership(false, false, true));
		assertThat(membership(OWNER_ID, 32L, OTHER_WISHLIST_ID))
			.isEqualTo(new WishlistResponse.Membership(false, null, false));
		assertThat(membership(OWNER_ID, 32L, 44L))
			.isEqualTo(new WishlistResponse.Membership(false, null, false));
		assertThat(membership(OWNER_ID, 31L, null))
			.isEqualTo(new WishlistResponse.Membership(true, null, false));
		assertThat(membership(999L, 31L, WISHLIST_ID))
			.isEqualTo(new WishlistResponse.Membership(false, null, false));
		assertThat(membership(OWNER_ID, 999L, 999L))
			.isEqualTo(new WishlistResponse.Membership(false, null, false));
	}

	@Test
	@DisplayName("여러 위시리스트 중 하나에서 삭제해도 남은 저장 상태를 반환하고 마지막 삭제 후 false가 된다")
	void membershipRemainsTrueUntilLastOwnMembershipIsRemoved() throws Exception {
		insertAccommodation(31, "PUBLISHED");
		jdbc.update("""
			INSERT INTO wishlist (id, name, member_id, status, updated_at)
			VALUES (44, '다음 여행', ?, 'ACTIVE', NOW(6))
			""", OWNER_ID);
		insertItem(501, WISHLIST_ID, 31);
		insertItem(502, 44, 31);
		jdbc.update("DELETE FROM wishlist_accommodation WHERE id = 501");
		var snapshot = membership(OWNER_ID, 31L, WISHLIST_ID);
		try (var fixture = new ClassPathResource("contracts/wishlist-membership.json").getInputStream()) {
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(snapshot)))
				.isEqualTo(objectMapper.readTree(fixture));
		}
		jdbc.update("DELETE FROM wishlist_accommodation WHERE id = 502");
		assertThat(membership(OWNER_ID, 31L, WISHLIST_ID))
			.isEqualTo(new WishlistResponse.Membership(false, false, true));
	}

	private WishlistResponse.Membership membership(long memberId, Long accommodationId, Long wishlistId) {
		Statistics statistics = prepareMeasurement();
		var result = service.findMembership(memberId, accommodationId, wishlistId);
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(statistics.getEntityLoadCount()).isZero();
		return result;
	}

	private Statistics prepareMeasurement() {
		entityManager.flush();
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		sqlCapture.statements.clear();
		return statistics;
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

		WishlistAccommodationInfos second = findPage(
			CursorPageRequest.builder().size(2).lastId(cursor.id()).lastCreatedAt(cursor.lastCreatedAt()).build());

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

	@ParameterizedTest
	@ValueSource(strings = {"DRAFT", "UNPUBLISHED", "DELETED"})
	@DisplayName("비공개 숙소만 있는 위시리스트는 빈 목록을 반환한다")
	void wishlistContainingOnlyUnpublishedAccommodationsReturnsEmptyLastPage(String status) {
		insertAccommodation(31, status);
		insertItem(501, WISHLIST_ID, 31);

		assertEmpty(findFirstPage(20));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("타인의 위시리스트는 항목 유무와 무관하게 항목 조회 전 접근을 거부한다")
	void anotherMembersWishlistIsDeniedEvenWhenEmpty(boolean populated) {
		if (populated) {
			insertAccommodation(31, "PUBLISHED");
			insertItem(501, WISHLIST_ID, 31);
		}
		Statistics statistics = prepareMeasurement();
		assertThatThrownBy(() -> service.findWishlistAccommodations(
			WISHLIST_ID, CursorPageRequest.builder().size(20).build(), OTHER_MEMBER_ID))
			.isInstanceOf(WishlistAccessDeniedException.class);
		assertHeaderOnlyRead(statistics);
	}

	@Test
	@DisplayName("삭제되었거나 존재하지 않는 위시리스트는 찾을 수 없음으로 처리한다")
	void deletedAndMissingWishlistsAreNotFound() {
		jdbc.update("UPDATE wishlist SET status = 'DELETED' WHERE id = ?", WISHLIST_ID);

		Statistics statistics = prepareMeasurement();
		assertThatThrownBy(() -> findFirstPage(20)).isInstanceOf(WishlistNotFoundException.class);
		assertHeaderOnlyRead(statistics);

		statistics = prepareMeasurement();
		assertThatThrownBy(() -> service.findWishlistAccommodations(
			999L, CursorPageRequest.builder().size(20).build(), OWNER_ID))
			.isInstanceOf(WishlistNotFoundException.class);
		assertHeaderOnlyRead(statistics);

		statistics = prepareMeasurement();
		assertThatThrownBy(() -> service.findWishlistAccommodations(
			WISHLIST_ID, CursorPageRequest.builder().size(20).build(), OTHER_MEMBER_ID))
			.isInstanceOf(WishlistNotFoundException.class);
		assertHeaderOnlyRead(statistics);
	}

	private WishlistAccommodationInfos findFirstPage(int size) {
		return findPage(CursorPageRequest.builder().size(size).build());
	}

	private WishlistAccommodationInfos findPage(CursorPageRequest request) {
		Statistics statistics = prepareMeasurement();
		var result = service.findWishlistAccommodations(WISHLIST_ID, request, OWNER_ID);
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
		assertThat(statistics.getEntityLoadCount()).isZero();
		assertThat(selectedColumnCounts()).containsExactly(2, 12);
		assertThat(sqlCapture.statements.getFirst()).doesNotContain(" join ", ".accommodation_count",
			".representative_accommodation_id", ".created_at", ".updated_at", " for update");
		String itemSql = sqlCapture.statements.getLast();
		String selected = itemSql.substring("select ".length(), itemSql.indexOf(" from "));
		assertThat(selected).doesNotContain(".description", ".base_price", ".time_zone_id", ".street", ".detail",
			".latitude", ".longitude", ".updated_at", ".created_by", ".updated_by");
		assertThat(itemSql).contains(" join address ", "left join accommodation_review_summary")
			.doesNotContain("left join address", " join member ", " for update");
		return result;
	}

	private void assertHeaderOnlyRead(Statistics statistics) {
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(statistics.getEntityLoadCount()).isZero();
		assertThat(selectedColumnCounts()).containsExactly(2);
		assertThat(sqlCapture.statements.getFirst()).doesNotContain(" join ", " from wishlist_accommodation ");
	}

	private List<Integer> selectedColumnCounts() {
		return sqlCapture.statements.stream()
			.map(sql -> sql.substring("select ".length(), sql.indexOf(" from ")).split(",").length).toList();
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
