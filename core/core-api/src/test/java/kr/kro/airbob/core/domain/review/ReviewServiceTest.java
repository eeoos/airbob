package kr.kro.airbob.core.domain.review;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.core.domain.accommodation.Accommodation;
import kr.kro.airbob.core.domain.accommodation.AccommodationNotFoundException;
import kr.kro.airbob.core.domain.accommodation.AccommodationReader;
import kr.kro.airbob.core.domain.accommodation.AccommodationReviewSummaryWriter;
import kr.kro.airbob.core.domain.accommodation.AccommodationStatus;
import kr.kro.airbob.core.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.core.domain.accommodation.cache.invalidation.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.core.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.core.domain.accommodation.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.core.domain.member.Member;
import kr.kro.airbob.core.domain.member.MemberReader;
import kr.kro.airbob.core.domain.member.MemberStatus;
import kr.kro.airbob.core.domain.reservation.ReservationReader;
import kr.kro.airbob.core.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.core.domain.review.repository.ReviewImageRepository;
import kr.kro.airbob.core.domain.review.repository.ReviewRepository;
import kr.kro.airbob.core.domain.search.messaging.AccommodationSearchRefreshPublisher;
import kr.kro.airbob.core.support.cursor.util.CursorPageInfoCreator;

@ExtendWith(MockitoExtension.class)
@DisplayName("리뷰 서비스 단위 테스트")
class ReviewServiceTest {

	@Mock
	private ReviewRepository reviewRepository;
	@Mock
	private MemberReader memberReader;
	@Mock
	private ReviewImageRepository reviewImageRepository;
	@Mock
	private ReservationRepository reservationRepository;
	@Mock
	private AccommodationRepository accommodationRepository;
	@Mock
	private AccommodationReviewSummaryRepository summaryRepository;
	@Mock
	private CursorPageInfoCreator cursorPageInfoCreator;
	@Mock
	private AccommodationSearchRefreshPublisher searchRefreshPublisher;
	@Mock
	private Clock clock;
	@Mock
	private AccommodationDetailCacheInvalidationPublisher cacheInvalidationPublisher;

	private ReviewService reviewService;

	@BeforeEach
	void setUp() {
		reviewService = new ReviewService(new ReviewReader(reviewRepository), new ReviewWriter(reviewRepository),
			new ReviewImageReader(reviewImageRepository), memberReader,
			new ReservationReader(reservationRepository), new AccommodationReader(accommodationRepository),
			new AccommodationReviewSummaryWriter(summaryRepository), cursorPageInfoCreator, searchRefreshPublisher,
			cacheInvalidationPublisher, clock);
	}

	@Test
	@DisplayName("숙소가 비공개로 바뀌면 후기 작성과 집계 변경을 거부한다")
	void rejectCreationWhenAccommodationIsNoLongerPublished() {
		given(memberReader.readActiveForUpdate(2L))
			.willReturn(member());
		given(accommodationRepository.findByIdAndStatus(1L, AccommodationStatus.PUBLISHED))
			.willReturn(Optional.empty());

		assertThatThrownBy(() -> reviewService.createReview(1L, 5, "후기", 2L))
			.isInstanceOf(AccommodationNotFoundException.class);
		then(reservationRepository).shouldHaveNoInteractions();
		then(reviewRepository).shouldHaveNoInteractions();
		then(summaryRepository).shouldHaveNoInteractions();
		then(searchRefreshPublisher).shouldHaveNoInteractions();
		then(cacheInvalidationPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("작성 시점에도 본인의 체크아웃 완료 예약이 있어야 한다")
	void recheckCompletedStayBeforeCreatingReview() {
		Instant now = Instant.parse("2030-01-01T00:00:00Z");
		given(memberReader.readActiveForUpdate(2L))
			.willReturn(member());
		given(accommodationRepository.findByIdAndStatus(1L, AccommodationStatus.PUBLISHED))
			.willReturn(Optional.of(accommodation()));
		given(clock.instant()).willReturn(now);
		given(reservationRepository.existsPastCompletedReservationByGuest(1L, 2L, now)).willReturn(false);

		assertThatThrownBy(() -> reviewService.createReview(1L, 5, "후기", 2L))
			.isInstanceOf(ReviewCreationForbiddenException.class);
		then(reviewRepository).shouldHaveNoInteractions();
		then(summaryRepository).shouldHaveNoInteractions();
		then(searchRefreshPublisher).shouldHaveNoInteractions();
		then(cacheInvalidationPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("조회 이후 게시된 후기가 생겼으면 실제 작성 시 중복을 거부한다")
	void recheckPublishedReviewBeforeCreatingReview() {
		Instant now = Instant.parse("2030-01-01T00:00:00Z");
		given(memberReader.readActiveForUpdate(2L))
			.willReturn(member());
		given(accommodationRepository.findByIdAndStatus(1L, AccommodationStatus.PUBLISHED))
			.willReturn(Optional.of(accommodation()));
		given(clock.instant()).willReturn(now);
		given(reservationRepository.existsPastCompletedReservationByGuest(1L, 2L, now)).willReturn(true);
		given(reviewRepository.existsByAccommodationIdAndAuthorIdAndStatus(1L, 2L, ReviewStatus.PUBLISHED))
			.willReturn(true);

		assertThatThrownBy(() -> reviewService.createReview(1L, 5, "후기", 2L))
			.isInstanceOf(ReviewAlreadyExistsException.class);
		then(reviewRepository).should(never()).save(any(Review.class));
		then(summaryRepository).shouldHaveNoInteractions();
		then(searchRefreshPublisher).shouldHaveNoInteractions();
		then(cacheInvalidationPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("리뷰를 생성하면 리뷰 요약이 포함된 숙소 상세 캐시를 무효화한다")
	void invalidateDetailCacheWhenReviewIsCreated() {
		Accommodation accommodation = accommodation();
		Member author = member();
		Review savedReview = review(10L, 5, accommodation, author);
		given(memberReader.readActiveForUpdate(2L))
			.willReturn(author);
		given(accommodationRepository.findByIdAndStatus(1L, AccommodationStatus.PUBLISHED))
			.willReturn(Optional.of(accommodation));
		given(clock.instant()).willReturn(Instant.parse("2030-01-01T00:00:00Z"));
		given(reservationRepository.existsPastCompletedReservationByGuest(
			eq(1L), eq(2L), any(Instant.class)))
			.willReturn(true);
		given(reviewRepository.existsByAccommodationIdAndAuthorIdAndStatus(
			1L, 2L, ReviewStatus.PUBLISHED))
			.willReturn(false);
		given(reviewRepository.save(any(Review.class))).willReturn(savedReview);

		reviewService.createReview(1L, 5, "좋은 숙소", 2L);

		then(cacheInvalidationPublisher).should().publish(
			1L, AccommodationDetailCacheInvalidationReason.REVIEW);
		then(searchRefreshPublisher).should().requestRefresh(accommodation.getAccommodationUid());
	}

	@Test
	@DisplayName("리뷰 평점을 변경하면 숙소 상세 캐시를 무효화한다")
	void invalidateDetailCacheWhenReviewRatingChanges() {
		Review review = review(10L, 3, accommodation(), member());
		given(reviewRepository.findByIdAndAuthorIdForUpdate(10L, 2L)).willReturn(Optional.of(review));

		reviewService.updateReviewContent(10L, "수정한 내용", 5, 2L);

		then(summaryRepository).should().applyRatingChange(1L, 3, 5);
		then(cacheInvalidationPublisher).should().publish(
			1L, AccommodationDetailCacheInvalidationReason.REVIEW);
		then(searchRefreshPublisher).should().requestRefresh(
			review.getAccommodation().getAccommodationUid());
	}

	@ParameterizedTest(name = "요청 평점 {0}이면 캐시 무효화 이벤트를 발행하지 않는다")
	@NullSource
	@ValueSource(ints = 3)
	@DisplayName("내용만 바꾸거나 같은 평점을 보내면 숙소 상세 캐시를 무효화하지 않는다")
	void keepDetailCacheWhenReviewRatingDoesNotChange(Integer requestedRating) {
		Review review = review(10L, 3, accommodation(), member());
		given(reviewRepository.findByIdAndAuthorIdForUpdate(10L, 2L)).willReturn(Optional.of(review));

		reviewService.updateReviewContent(
			10L, "수정한 내용", requestedRating, 2L);

		then(cacheInvalidationPublisher).shouldHaveNoInteractions();
		then(searchRefreshPublisher).shouldHaveNoInteractions();
		then(summaryRepository).should(never()).applyRatingChange(anyLong(), anyInt(), anyInt());
	}

	@Test
	@DisplayName("리뷰를 삭제하면 숙소 상세 캐시를 무효화한다")
	void invalidateDetailCacheWhenReviewIsDeleted() {
		Review review = review(10L, 4, accommodation(), member());
		given(reviewRepository.findByIdAndAuthorIdForUpdate(10L, 2L)).willReturn(Optional.of(review));

		reviewService.deleteReview(10L, 2L);

		then(cacheInvalidationPublisher).should().publish(
			1L, AccommodationDetailCacheInvalidationReason.REVIEW);
		then(searchRefreshPublisher).should().requestRefresh(
			review.getAccommodation().getAccommodationUid());
	}

	private Accommodation accommodation() {
		return Accommodation.builder()
			.id(1L)
			.accommodationUid(UUID.fromString("11111111-1111-1111-1111-111111111111"))
			.status(AccommodationStatus.PUBLISHED)
			.build();
	}

	private Member member() {
		return Member.builder()
			.id(2L)
			.status(MemberStatus.ACTIVE)
			.build();
	}

	private Review review(Long id, int rating, Accommodation accommodation, Member author) {
		return Review.builder()
			.id(id)
			.rating(rating)
			.content("기존 내용")
			.status(ReviewStatus.PUBLISHED)
			.accommodation(accommodation)
			.author(author)
			.build();
	}
}
