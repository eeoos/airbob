package kr.kro.airbob.domain.review.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.ReviewCursorData;
import kr.kro.airbob.cursor.util.CursorDecoder;
import kr.kro.airbob.cursor.util.CursorEncoder;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.accommodation.cache.invalidation.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.domain.image.service.S3ImageUploader;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.review.dto.ReviewRequest;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.entity.ReviewSortType;
import kr.kro.airbob.domain.review.exception.ReviewAlreadyExistsException;
import kr.kro.airbob.domain.review.exception.ReviewCreationForbiddenException;
import kr.kro.airbob.domain.review.exception.ReviewNotFoundException;
import kr.kro.airbob.domain.review.exception.ReviewUpdateForbiddenException;
import kr.kro.airbob.domain.review.service.ReviewService;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@DataJpaTest(showSql = false)
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class, ReviewService.class,
	CursorPageInfoCreator.class, CursorEncoder.class, CursorDecoder.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReviewApiIntegrationTest {

	private static final Instant NOW = Instant.parse("2027-01-10T02:00:00Z");
	private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2027-01-01T00:00:00.123456");

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_review_contract");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired ReviewService service;
	@Autowired JdbcTemplate jdbc;
	@Autowired CursorDecoder decoder;
	@MockitoBean Clock clock;
	@MockitoBean S3ImageUploader uploader;
	@MockitoBean AccommodationSearchRefreshPublisher searchRefresh;
	@MockitoBean AccommodationDetailCacheInvalidationPublisher cacheInvalidation;

	@BeforeEach
	void fixture() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		for (String table : List.of("review_image", "review", "accommodation_review_summary", "reservation", "accommodation", "member")) {
			jdbc.update("DELETE FROM " + table);
		}
		jdbc.update("""
			INSERT INTO member (id, nickname, status, created_at, updated_at)
			VALUES (10, NULL, 'ACTIVE', NOW(6), NOW(6)), (20, '다른 여행자', 'ACTIVE', NOW(6), NOW(6)),
				(30, '호스트', 'ACTIVE', NOW(6), NOW(6))
			""");
		jdbc.update("""
			INSERT INTO accommodation (id, member_id, name, accommodation_uid, status, base_price,
				check_in_time, check_out_time, created_at, updated_at)
			VALUES (101, 30, '테스트 숙소', UUID_TO_BIN(?), 'PUBLISHED', 100000, '15:00:00', '11:00:00', NOW(6), NOW(6))
			""", UUID.randomUUID().toString());
		jdbc.update("""
			INSERT INTO reservation (id, reservation_uid, reservation_code, accommodation_id, guest_id,
				check_in_date, check_out_date, check_in_at, check_out_at, time_zone_id,
				guest_count, total_price, discount_amount, currency, status, expires_at, created_at, updated_at)
			VALUES (501, UUID_TO_BIN(?), 'REV00001', 101, 10, '2027-01-08', '2027-01-10',
				'2027-01-08 06:00:00', ?, 'Asia/Seoul', 2, 200000, 0, 'KRW', 'CONFIRMED', ?, NOW(6), NOW(6))
			""", UUID.randomUUID().toString(), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), CREATED_AT);
	}

	@Test
	void createUpdateDeleteAndRecreateMaintainThePublishedAggregate() {
		long id = create();
		assertThat(id).isPositive();
		assertThatThrownBy(this::create).isInstanceOf(ReviewAlreadyExistsException.class);
		service.updateReviewContent(id, new ReviewRequest.Update("수정 본문", null), 10L);
		assertThat(jdbc.queryForMap("SELECT rating, content FROM review WHERE id = ?", id))
			.containsEntry("rating", 3).containsEntry("content", "수정 본문");
		service.updateReviewContent(id, new ReviewRequest.Update("가".repeat(1024), 5), 10L);
		assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(content) FROM review WHERE id = ?", Integer.class, id)).isEqualTo(1024);
		assertSummaryMatchesPublishedRows();
		service.deleteReview(id, 10L);
		assertThatThrownBy(() -> service.deleteReview(id, 10L)).isInstanceOf(ReviewNotFoundException.class);
		assertThatThrownBy(() -> service.updateReviewContent(id, new ReviewRequest.Update("삭제 후 수정", null), 10L))
			.isInstanceOf(ReviewUpdateForbiddenException.class);
		assertThat(jdbc.queryForObject("SELECT status FROM review WHERE id = ?", String.class, id)).isEqualTo("DELETE");
		assertSummaryMatchesPublishedRows();
		assertThat(create()).isNotEqualTo(id);
		assertSummaryMatchesPublishedRows();
	}

	@ParameterizedTest
	@EnumSource(ReservationStatus.class)
	void eligibilityUsesCurrentReservationStatusAndCheckoutInstant(ReservationStatus status) {
		jdbc.update("UPDATE reservation SET status = ?", status.name());
		if (status == ReservationStatus.CONFIRMED || status == ReservationStatus.CANCELLATION_FAILED) {
			assertThat(create()).isPositive();
		} else {
			assertThatThrownBy(this::create).isInstanceOf(ReviewCreationForbiddenException.class);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review", Long.class)).isZero();
		}
	}

	@Test
	void futureCheckoutAndAnotherGuestDoNotGrantCreationRights() {
		jdbc.update("UPDATE reservation SET check_out_at = ?", LocalDateTime.ofInstant(NOW.plusNanos(1000), ZoneOffset.UTC));
		assertThatThrownBy(this::create).isInstanceOf(ReviewCreationForbiddenException.class);
		jdbc.update("UPDATE reservation SET check_out_at = ?, guest_id = 20", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
		assertThatThrownBy(this::create).isInstanceOf(ReviewCreationForbiddenException.class);
	}

	@Test
	void ownersAreCheckedBeforeMutationAndHiddenReviewsCannotBeEditedOrDeleted() {
		long id = create();
		assertThatThrownBy(() -> service.updateReviewContent(id, new ReviewRequest.Update("타인의 수정", 1), 20L))
			.isInstanceOf(ReviewNotFoundException.class);
		assertThatThrownBy(() -> service.deleteReview(id, 20L)).isInstanceOf(ReviewNotFoundException.class);
		jdbc.update("UPDATE review SET status = 'HIDDEN' WHERE id = ?", id);
		assertThatThrownBy(() -> service.updateReviewContent(id, new ReviewRequest.Update("숨긴 리뷰", null), 10L))
			.isInstanceOf(ReviewUpdateForbiddenException.class);
		assertThatThrownBy(() -> service.deleteReview(id, 10L)).isInstanceOf(ReviewNotFoundException.class);
	}

	@ParameterizedTest
	@EnumSource(ReviewSortType.class)
	void followsEverySortAcrossTiedRowsAndFiltersHiddenAndDeletedReviews(ReviewSortType sort) {
		insertReview(201, 10, 3, "PUBLISHED", CREATED_AT);
		insertReview(202, 20, 5, "PUBLISHED", CREATED_AT);
		insertReview(203, 10, 5, "PUBLISHED", CREATED_AT);
		insertReview(204, 20, 1, "PUBLISHED", CREATED_AT.plusSeconds(1));
		insertReview(205, 10, 4, "HIDDEN", CREATED_AT.plusSeconds(2));
		insertReview(206, 20, 2, "DELETE", CREATED_AT.plusSeconds(2));
		jdbc.update("INSERT INTO review_image (id, review_id, image_url, updated_at) VALUES (301, 203, 'https://example.invalid/review.png', NOW(6))");
		List<Long> all = new ArrayList<>();
		var request = CursorRequest.ReviewCursorPageRequest.builder().size(2).build();
		for (int page = 0; page < 2; page++) {
			var result = service.findReviews(101L, request, sort);
			assertThat(result.pageInfo().currentSize()).isEqualTo(2);
			all.addAll(result.reviews().stream().map(ReviewResponse.ReviewInfo::id).toList());
			for (var review : result.reviews()) {
				assertThat(review.reviewedAt()).isEqualTo(review.id() == 204 ? CREATED_AT.plusSeconds(1).toInstant(ZoneOffset.UTC) : CREATED_AT.toInstant(ZoneOffset.UTC));
				if (review.id() == 203) {
					assertThat(review.images()).hasSize(1);
					assertThat(review.images().getFirst().id()).isEqualTo(301);
					assertThat(review.reviewer().nickname()).isNull();
				} else assertThat(review.images()).isEmpty();
			}
			assertThat(result.pageInfo().hasNext()).isEqualTo(page == 0);
			if (page == 0) {
				var cursor = decoder.decode(result.pageInfo().nextCursor(), ReviewCursorData.class);
				request = new CursorRequest.ReviewCursorPageRequest(2, cursor.id(), cursor.lastCreatedAt(), cursor.lastRating());
			} else assertThat(result.pageInfo().nextCursor()).isNull();
		}
		assertThat(all).containsExactlyElementsOf(switch (sort) {
			case LATEST -> List.of(204L, 203L, 202L, 201L);
			case HIGHEST_RATING -> List.of(203L, 202L, 201L, 204L);
			case LOWEST_RATING -> List.of(204L, 201L, 203L, 202L);
		});
	}

	@Test
	void listDoesNotRequireAnExistingOrCurrentlyPublishedAccommodation() {
		insertReview(201, 10, 3, "PUBLISHED", CREATED_AT);
		jdbc.update("UPDATE accommodation SET status = 'DRAFT' WHERE id = 101");
		var firstPage = CursorRequest.ReviewCursorPageRequest.builder().size(20).build();
		assertThat(service.findReviews(101L, firstPage, ReviewSortType.LATEST).reviews()).hasSize(1);
		var missing = service.findReviews(999L, firstPage, ReviewSortType.LATEST);
		assertThat(missing.reviews()).isEmpty();
		assertThat(missing.pageInfo().hasNext()).isFalse();
		assertThat(missing.pageInfo().nextCursor()).isNull();
	}

	@Test
	void failedPublicationRollsBackReviewAndAggregateTogether() {
		doThrow(new IllegalStateException("test publication failure")).when(searchRefresh).requestRefresh(any());
		assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review", Long.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accommodation_review_summary", Long.class)).isZero();
		verifyNoInteractions(cacheInvalidation);
	}

	@Test
	void concurrentCreationFromOneMemberProducesOnePublishedReview() throws Exception {
		var outcomes = concurrently(java.util.Collections.nCopies(8, () -> { create(); return "created"; }));
		assertThat(outcomes).containsOnly("created", "V004");
		assertThat(outcomes.stream().filter("created"::equals)).hasSize(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review WHERE status = 'PUBLISHED'", Long.class)).isEqualTo(1);
		assertSummaryMatchesPublishedRows();
	}

	@Test
	void concurrentRatingChangesUseTheLatestStoredRating() throws Exception {
		long id = create();
		List<Callable<String>> actions = List.of(1, 2, 4, 5).stream().<Callable<String>>map(rating -> () -> {
			service.updateReviewContent(id, new ReviewRequest.Update("동시 수정 " + rating, rating), 10L);
			return "updated";
		}).toList();
		assertThat(concurrently(actions)).containsOnly("updated");
		assertSummaryMatchesPublishedRows();
	}

	@Test
	void concurrentDeletionDecrementsTheSummaryOnlyOnce() throws Exception {
		long id = create();
		jdbc.update("UPDATE reservation SET guest_id = 20");
		service.createReview(101L, new ReviewRequest.Create(5, "다른 회원 리뷰"), 20L);
		var outcomes = concurrently(java.util.Collections.nCopies(4, () -> { service.deleteReview(id, 10L); return "deleted"; }));
		assertThat(outcomes).containsOnly("deleted", "V001");
		assertThat(outcomes.stream().filter("deleted"::equals)).hasSize(1);
		assertSummaryMatchesPublishedRows();
	}

	@Test
	void ratingChangeRacingWithDeletionKeepsTheOtherReviewInTheSummary() throws Exception {
		long id = create();
		jdbc.update("UPDATE reservation SET guest_id = 20");
		service.createReview(101L, new ReviewRequest.Create(4, "남는 리뷰"), 20L);
		assertThat(concurrently(List.of(
			() -> { service.updateReviewContent(id, new ReviewRequest.Update("마지막 수정", 1), 10L); return "updated"; },
			() -> { service.deleteReview(id, 10L); return "deleted"; }
		))).allMatch(outcome -> List.of("updated", "deleted", "V005").contains(outcome));
		assertThat(jdbc.queryForObject("SELECT status FROM review WHERE id = ?", String.class, id)).isEqualTo("DELETE");
		assertSummaryMatchesPublishedRows();
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"PUBLISHED", "HIDDEN", "DELETE"})
	void imageMutationsUseOwnershipAndAssociationRatherThanReviewStatus(String status) throws Exception {
		long id = create();
		jdbc.update("UPDATE review SET status = ? WHERE id = ?", status, id);
		var file = new org.springframework.mock.web.MockMultipartFile("images", "review.png", "image/png", new byte[] {1});
		when(uploader.upload(file, "reviews/" + id)).thenReturn("https://example.invalid/review.png");
		assertThatThrownBy(() -> service.uploadReviewImages(id, List.of(file), 20L)).isInstanceOf(ReviewNotFoundException.class);
		var uploaded = service.uploadReviewImages(id, List.of(file), 10L);
		long imageId = uploaded.uploadedImages().getFirst().id();
		assertThat(uploaded.uploadedImages().getFirst().imageUrl()).isEqualTo("https://example.invalid/review.png");
		assertThatThrownBy(() -> service.deleteReviewImage(id, imageId, 20L))
			.isInstanceOf(kr.kro.airbob.domain.review.exception.ReviewAccessDeniedException.class);
		assertThatThrownBy(() -> service.deleteReviewImage(id + 100, imageId, 10L))
			.isInstanceOf(kr.kro.airbob.common.exception.InvalidInputException.class);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_image", Long.class)).isEqualTo(1);
		service.deleteReviewImage(id, imageId, 10L);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_image", Long.class)).isZero();
		assertThatThrownBy(() -> service.deleteReviewImage(id, imageId, 10L))
			.isInstanceOf(kr.kro.airbob.domain.review.exception.ReviewAccessDeniedException.class);
	}

	private List<String> concurrently(List<Callable<String>> actions) throws Exception {
		try (var pool = Executors.newFixedThreadPool(actions.size())) {
			CountDownLatch ready = new CountDownLatch(actions.size());
			CountDownLatch start = new CountDownLatch(1);
			var futures = actions.stream().map(action -> pool.submit(() -> {
				ready.countDown();
				if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
				try { return action.call(); }
				catch (BaseException e) { return e.getErrorCode().getCode(); }
			})).toList();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			List<String> result = new ArrayList<>();
			for (var future : futures) result.add(future.get(20, TimeUnit.SECONDS));
			return result;
		}
	}

	private long create() {
		return service.createReview(101L, new ReviewRequest.Create(3, "좋은 숙소"), 10L).id();
	}

	private void insertReview(long id, long memberId, int rating, String status, LocalDateTime createdAt) {
		jdbc.update("INSERT INTO review (id, accommodation_id, member_id, rating, content, status, created_at, updated_at) VALUES (?, 101, ?, ?, '리뷰', ?, ?, ?)",
			id, memberId, rating, status, createdAt, createdAt);
	}

	private void assertSummaryMatchesPublishedRows() {
		long count = jdbc.queryForObject("SELECT COUNT(*) FROM review WHERE status = 'PUBLISHED'", Long.class);
		if (count == 0) {
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accommodation_review_summary", Long.class)).isZero();
			return;
		}
		long sum = jdbc.queryForObject("SELECT SUM(rating) FROM review WHERE status = 'PUBLISHED'", Long.class);
		var summary = jdbc.queryForMap("SELECT total_review_count, rating_sum, average_rating FROM accommodation_review_summary WHERE accommodation_id = 101");
		assertThat(((Number)summary.get("total_review_count")).longValue()).isEqualTo(count);
		assertThat(((Number)summary.get("rating_sum")).longValue()).isEqualTo(sum);
		assertThat((BigDecimal)summary.get("average_rating"))
			.isEqualByComparingTo(BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, java.math.RoundingMode.HALF_UP));
	}
}
