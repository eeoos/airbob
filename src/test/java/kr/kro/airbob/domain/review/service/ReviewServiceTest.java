package kr.kro.airbob.domain.review.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.image.entity.ReviewImage;
import kr.kro.airbob.domain.image.service.S3ImageUploader;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.dto.ReviewRequest;
import kr.kro.airbob.domain.review.entity.Review;
import kr.kro.airbob.domain.review.entity.ReviewStatus;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.domain.review.repository.ReviewImageRepository;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import kr.kro.airbob.outbox.OutboxEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("리뷰 서비스 단위 테스트")
class ReviewServiceTest {

	@Mock private ReviewRepository reviewRepository;
	@Mock private MemberRepository memberRepository;
	@Mock private ReviewImageRepository reviewImageRepository;
	@Mock private ReservationRepository reservationRepository;
	@Mock private AccommodationRepository accommodationRepository;
	@Mock private AccommodationReviewSummaryRepository summaryRepository;
	@Mock private CursorPageInfoCreator cursorPageInfoCreator;
	@Mock private OutboxEventPublisher outboxEventPublisher;
	@Mock private S3ImageUploader s3ImageUploader;
	@Mock private Clock clock;
	@Mock private AccommodationDetailCacheInvalidationPublisher cacheInvalidationPublisher;

	@InjectMocks
	private ReviewService reviewService;

	@Test
	@DisplayName("리뷰를 생성하면 리뷰 요약이 포함된 숙소 상세 캐시를 무효화한다")
	void invalidateDetailCacheWhenReviewIsCreated() {
		Accommodation accommodation = accommodation();
		Member author = member();
		Review savedReview = review(10L, 5, accommodation, author);
		when(memberRepository.findByIdAndStatus(2L, MemberStatus.ACTIVE))
			.thenReturn(Optional.of(author));
		when(accommodationRepository.findByIdAndStatus(1L, AccommodationStatus.PUBLISHED))
			.thenReturn(Optional.of(accommodation));
		when(clock.instant()).thenReturn(Instant.parse("2030-01-01T00:00:00Z"));
		when(reservationRepository.existsPastCompletedReservationByGuest(
			eq(1L), eq(2L), any(Instant.class)))
			.thenReturn(true);
		when(reviewRepository.existsByAccommodationIdAndAuthorIdAndStatus(
			1L, 2L, ReviewStatus.PUBLISHED))
			.thenReturn(false);
		when(reviewRepository.save(any(Review.class))).thenReturn(savedReview);

		reviewService.createReview(1L, new ReviewRequest.Create(5, "좋은 숙소"), 2L);

		verify(cacheInvalidationPublisher).publish(
			1L, AccommodationDetailCacheInvalidationReason.REVIEW);
	}

	@Test
	@DisplayName("리뷰 평점을 변경하면 숙소 상세 캐시를 무효화한다")
	void invalidateDetailCacheWhenReviewRatingChanges() {
		Review review = review(10L, 3, accommodation(), member());
		when(reviewRepository.findByIdAndAuthorId(10L, 2L)).thenReturn(Optional.of(review));

		reviewService.updateReviewContent(10L, new ReviewRequest.Update("수정한 내용", 5), 2L);

		verify(summaryRepository).applyRatingChange(1L, 3, 5);
		verify(cacheInvalidationPublisher).publish(
			1L, AccommodationDetailCacheInvalidationReason.REVIEW);
	}

	@ParameterizedTest(name = "요청 평점 {0}이면 캐시 무효화 이벤트를 발행하지 않는다")
	@NullSource
	@ValueSource(ints = 3)
	@DisplayName("내용만 바꾸거나 같은 평점을 보내면 숙소 상세 캐시를 무효화하지 않는다")
	void keepDetailCacheWhenReviewRatingDoesNotChange(Integer requestedRating) {
		Review review = review(10L, 3, accommodation(), member());
		when(reviewRepository.findByIdAndAuthorId(10L, 2L)).thenReturn(Optional.of(review));

		reviewService.updateReviewContent(
			10L, new ReviewRequest.Update("수정한 내용", requestedRating), 2L);

		verifyNoInteractions(cacheInvalidationPublisher);
		verify(summaryRepository, never()).applyRatingChange(anyLong(), anyInt(), anyInt());
	}

	@Test
	@DisplayName("리뷰를 삭제하면 숙소 상세 캐시를 무효화한다")
	void invalidateDetailCacheWhenReviewIsDeleted() {
		Review review = review(10L, 4, accommodation(), member());
		when(reviewRepository.findByIdAndAuthorId(10L, 2L)).thenReturn(Optional.of(review));

		reviewService.deleteReview(10L, 2L);

		verify(cacheInvalidationPublisher).publish(
			1L, AccommodationDetailCacheInvalidationReason.REVIEW);
	}

	@Test
	@DisplayName("리뷰 이미지를 추가해도 숙소 상세 캐시는 무효화하지 않는다")
	void keepDetailCacheWhenReviewImageIsUploaded() throws IOException {
		Review review = review(10L, 4, accommodation(), member());
		MockMultipartFile image = new MockMultipartFile(
			"images", "review.jpg", "image/jpeg", new byte[] {1});
		ReviewImage savedImage = ReviewImage.builder()
			.id(20L)
			.review(review)
			.imageUrl("https://cdn.example.com/review.jpg")
			.build();
		when(reviewRepository.findByIdAndAuthorId(10L, 2L)).thenReturn(Optional.of(review));
		when(s3ImageUploader.upload(image, "reviews/10"))
			.thenReturn(savedImage.getImageUrl());
		when(reviewImageRepository.saveAll(anyList())).thenReturn(List.of(savedImage));

		reviewService.uploadReviewImages(10L, List.of(image), 2L);

		verifyNoInteractions(cacheInvalidationPublisher);
	}

	@Test
	@DisplayName("리뷰 이미지를 삭제해도 숙소 상세 캐시는 무효화하지 않는다")
	void keepDetailCacheWhenReviewImageIsDeleted() {
		Review review = review(10L, 4, accommodation(), member());
		ReviewImage image = ReviewImage.builder()
			.id(20L)
			.review(review)
			.imageUrl("https://cdn.example.com/review.jpg")
			.build();
		when(reviewImageRepository.findByIdAndReviewAuthorId(20L, 2L))
			.thenReturn(Optional.of(image));

		reviewService.deleteReviewImage(10L, 20L, 2L);

		verifyNoInteractions(cacheInvalidationPublisher);
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
