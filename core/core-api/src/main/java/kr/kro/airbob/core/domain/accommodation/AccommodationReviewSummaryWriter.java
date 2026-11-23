package kr.kro.airbob.core.domain.accommodation;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.accommodation.repository.AccommodationReviewSummaryRepository;
import lombok.RequiredArgsConstructor;

/**
 * 숙소 리뷰 요약 변경 구현 컴포넌트. 리뷰 도메인이 리뷰를 쓰거나 지우거나 평점을 바꿀 때 호출한다.
 * 집계는 AccommodationReviewSummaryRepository의 원자적 SQL로 갱신한다.
 */
@Component
@RequiredArgsConstructor
public class AccommodationReviewSummaryWriter {

	private final AccommodationReviewSummaryRepository summaryRepository;

	/** 행이 없으면 만들고, 있으면 원자적으로 늘린다. 첫 리뷰를 동시에 써도 PK 중복 없이 합쳐진다. */
	public void applyNewReview(Long accommodationId, int rating) {
		summaryRepository.applyNewReview(accommodationId, rating);
	}

	/** 리뷰 수는 그대로 두고 평점 합계와 평균만 원자적으로 바꾼다. */
	public void applyRatingChange(Long accommodationId, int oldRating, int newRating) {
		summaryRepository.applyRatingChange(accommodationId, oldRating, newRating);
	}

	/** 원자적으로 줄이고, 리뷰가 0건이 되면 요약 행을 지운다. */
	public void removeReview(Long accommodationId, int rating) {
		summaryRepository.removeReview(accommodationId, rating);
		summaryRepository.deleteIfEmpty(accommodationId);
	}
}
