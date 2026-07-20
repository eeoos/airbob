package kr.kro.airbob.domain.review.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;

@Service
@Profile("read-model-benchmark")
@RequiredArgsConstructor
public class ReviewSummaryBenchmarkService {

	private final ReviewRepository reviewRepository;

	@Transactional(readOnly = true)
	public ReviewResponse.ReviewSummary findReviewSummaryBefore(Long accommodationId) {
		return ReviewResponse.ReviewSummary.of(
			reviewRepository.aggregateSummaryNaive(accommodationId)
		);
	}
}
