package kr.kro.airbob.core.domain.review;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.domain.accommodation.Accommodation;
import kr.kro.airbob.core.domain.accommodation.AccommodationReader;
import kr.kro.airbob.core.domain.accommodation.AccommodationReviewSummaryWriter;
import kr.kro.airbob.core.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.core.domain.accommodation.cache.invalidation.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.core.domain.image.StoredImage;
import kr.kro.airbob.core.domain.member.Member;
import kr.kro.airbob.core.domain.member.MemberReader;
import kr.kro.airbob.core.domain.reservation.ReservationReader;
import kr.kro.airbob.core.domain.search.messaging.AccommodationSearchRefreshPublisher;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.core.support.cursor.dto.CursorResponse;
import kr.kro.airbob.core.support.cursor.util.CursorPageInfoCreator;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReviewService {

	private final ReviewReader reviewReader;
	private final ReviewWriter reviewWriter;
	private final ReviewImageReader reviewImageReader;
	private final MemberReader memberReader;
	private final ReservationReader reservationReader;
	private final AccommodationReader accommodationReader;
	private final AccommodationReviewSummaryWriter summaryWriter;

	private final CursorPageInfoCreator cursorPageInfoCreator;
	private final AccommodationSearchRefreshPublisher searchRefreshPublisher;
	private final AccommodationDetailCacheInvalidationPublisher cacheInvalidationPublisher;
	private final Clock clock;

	/** 리뷰를 쓰고 리뷰 ID를 돌려준다. */
	@Transactional
	public Long createReview(Long accommodationId, int rating, String content, Long memberId) {

		// Serialize this author's duplicate check and insertion before any consistent read.
		Member author = lockActiveMember(memberId);
		Accommodation accommodation = findAccommodationById(accommodationId);

		validateReviewCreation(accommodationId, memberId);

		Review savedReview = reviewWriter.create(accommodation, author, rating, content);

		updateReviewSummaryOnCreate(accommodation, rating);
		searchRefreshPublisher.requestRefresh(accommodation.getAccommodationUid());
		cacheInvalidationPublisher.publish(
			accommodationId, AccommodationDetailCacheInvalidationReason.REVIEW);

		return savedReview.getId();
	}

	/** 리뷰 본문을 바꾸고 리뷰 ID를 돌려준다. rating이 null이거나 지금과 같으면 평점은 그대로 둔다. */
	@Transactional
	public Long updateReviewContent(Long reviewId, String content, Integer rating, Long memberId) {
		Review review = lockReviewByIdAndAuthorId(reviewId, memberId);

		if (review.getStatus() != ReviewStatus.PUBLISHED) {
			throw new ReviewUpdateForbiddenException();
		}

		review.updateContent(content);

		// 평점 수정 시 요약(rating_sum/average) 원자적 반영 + 색인 갱신 이벤트
		if (rating != null && !rating.equals(review.getRating())) {
			int oldRating = review.getRating();
			review.updateRating(rating);
			summaryWriter.applyRatingChange(review.getAccommodation().getId(), oldRating, rating);
			searchRefreshPublisher.requestRefresh(
				review.getAccommodation().getAccommodationUid());
			cacheInvalidationPublisher.publish(
				review.getAccommodation().getId(), AccommodationDetailCacheInvalidationReason.REVIEW);
		}

		return review.getId();
	}

	@Transactional
	public void deleteReview(Long reviewId, Long memberId) {
		Review review = lockReviewByIdAndAuthorId(reviewId, memberId);

		// 이미 삭제/숨김된 리뷰는 삭제 대상이 아니다.
		// 가드가 없으면 재요청 시 removeReview 가 중복 호출되어 요약 집계가 어긋남
		if (review.getStatus() != ReviewStatus.PUBLISHED) {
			throw new ReviewNotFoundException();
		}

		review.deleteByUser();

		updateReviewSummaryOnDelete(review.getAccommodation().getId(), review.getRating());

		searchRefreshPublisher.requestRefresh(
			review.getAccommodation().getAccommodationUid());
		cacheInvalidationPublisher.publish(
			review.getAccommodation().getId(), AccommodationDetailCacheInvalidationReason.REVIEW);
	}

	@Transactional(readOnly = true)
	public ReviewPage findReviews(
		Long accommodationId,
		CursorRequest.ReviewCursorPageRequest cursorRequest,
		ReviewSortType sortType) {

		Slice<ReviewItem> reviewSlice = reviewReader.readPublishedPage(accommodationId, cursorRequest, sortType);

		List<ReviewItem> reviews = reviewSlice.getContent();

		if (!reviews.isEmpty()) {
			List<Long> reviewIds = reviews.stream()
				.map(ReviewItem::id)
				.toList();

			Map<Long, List<StoredImage>> imagesByReviewId = reviewImageReader.readByReviewIds(reviewIds);

			reviews = reviews.stream()
				.map(review -> review.withImages(imagesByReviewId.getOrDefault(review.id(), List.of())))
				.toList();
		}

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			reviewSlice.getContent(),
			reviewSlice.hasNext(),
			ReviewItem::id,
			ReviewItem::reviewedAt,
			ReviewItem::rating);

		return new ReviewPage(reviews, pageInfo);
	}

	private void validateReviewCreation(Long accommodationId, Long memberId) {
		// 예약한 사용자인지와 체크아웃까지 완료했는지 확인
		if (!reservationReader.existsPastCompletedReservationByGuest(
			accommodationId, memberId, clock.instant())) {
			throw new ReviewCreationForbiddenException();
		}
		// 이미 리뷰를 작성했는지 확인
		if (reviewReader.hasPublishedReview(accommodationId, memberId)) {
			throw new ReviewAlreadyExistsException();
		}
	}

	private Member lockActiveMember(Long memberId) {
		return memberReader.readActiveForUpdate(memberId);
	}

	private Accommodation findAccommodationById(Long accommodationId) {
		return accommodationReader.readPublished(accommodationId);
	}

	private Review lockReviewByIdAndAuthorId(Long reviewId, Long memberId) {
		// Read the current status/rating under the same lock held until the summary commits.
		return reviewReader.readByAuthorForUpdate(reviewId, memberId);
	}

	private void updateReviewSummaryOnCreate(Accommodation accommodation, int rating) {
		// 원자적 upsert: 행이 없으면 INSERT, 있으면 증가. 동시성/첫 리뷰 PK 중복 모두 안전.
		summaryWriter.applyNewReview(accommodation.getId(), rating);
	}

	private void updateReviewSummaryOnDelete(Long accommodationId, int rating) {
		summaryWriter.removeReview(accommodationId, rating);
	}
}
