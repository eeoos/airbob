package kr.kro.airbob.core.domain.review;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.reservation.port.ReservationReviewReader;
import kr.kro.airbob.core.domain.review.repository.ReviewRepository;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import lombok.RequiredArgsConstructor;

/** 리뷰 조회 구현 컴포넌트. 다른 도메인은 ReviewRepository 대신 이 컴포넌트(또는 이 컴포넌트가 구현한 포트)로 읽는다. */
@Component
@RequiredArgsConstructor
public class ReviewReader implements ReservationReviewReader {

	private final ReviewRepository reviewRepository;

	@Override
	public boolean hasPublishedReview(Long accommodationId, Long memberId) {
		return reviewRepository.existsByAccommodationIdAndAuthorIdAndStatus(
			accommodationId, memberId, ReviewStatus.PUBLISHED);
	}

	/** 회원이 쓴 리뷰. 없거나 다른 회원의 리뷰면 ReviewNotFoundException. */
	public Review readByAuthor(Long reviewId, Long memberId) {
		return reviewRepository.findByIdAndAuthorId(reviewId, memberId).orElseThrow(ReviewNotFoundException::new);
	}

	/** 회원이 쓴 리뷰를 쓰기 잠금으로 읽는다. 요약 갱신이 커밋될 때까지 같은 잠금 아래에서 현재 상태와 평점을 본다. */
	public Review readByAuthorForUpdate(Long reviewId, Long memberId) {
		return reviewRepository.findByIdAndAuthorIdForUpdate(reviewId, memberId)
			.orElseThrow(ReviewNotFoundException::new);
	}

	/** 숙소의 게시된 리뷰를 커서 다음부터 한 페이지 읽는다. 이미지는 채우지 않는다. */
	public Slice<ReviewItem> readPublishedPage(
		Long accommodationId, CursorRequest.ReviewCursorPageRequest cursorRequest, ReviewSortType sortType) {
		return reviewRepository.findByAccommodationIdAndStatusWithCursor(
			accommodationId, ReviewStatus.PUBLISHED, cursorRequest.lastId(), cursorRequest.lastCreatedAt(),
			cursorRequest.lastRating(), sortType, PageRequest.of(0, cursorRequest.size()));
	}
}
