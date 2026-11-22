package kr.kro.airbob.domain.review.service;

import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.reservation.port.ReservationReviewReader;
import kr.kro.airbob.domain.review.entity.ReviewStatus;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
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
}
