package kr.kro.airbob.core.domain.accommodation;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.accommodation.repository.AccommodationReviewSummaryRepository;
import lombok.RequiredArgsConstructor;

/** 숙소 리뷰 요약 조회 구현 컴포넌트. 다른 도메인은 AccommodationReviewSummaryRepository 대신 이 컴포넌트로 읽는다. */
@Component
@RequiredArgsConstructor
public class AccommodationReviewSummaryReader {

	private final AccommodationReviewSummaryRepository summaryRepository;

	/** 숙소의 리뷰 요약. 게시된 리뷰가 없으면 요약 행이 없으므로 비어 있다. */
	public Optional<AccommodationReviewSummary> findByAccommodationUid(UUID accommodationUid) {
		return summaryRepository.findByAccommodationAccommodationUid(accommodationUid);
	}
}
