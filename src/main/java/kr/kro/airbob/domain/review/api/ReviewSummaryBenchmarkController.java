package kr.kro.airbob.domain.review.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.service.ReviewSummaryBenchmarkService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Profile("read-model-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
@RequestMapping("/api/v2/accommodations")
public class ReviewSummaryBenchmarkController {

	private final ReviewSummaryBenchmarkService benchmarkService;
	private final BenchmarkAccessGuard accessGuard;

	@GetMapping("/{accommodationId}/reviews/summary")
	public ResponseEntity<ApiResponse<ReviewResponse.ReviewSummary>> findReviewSummaryBefore(
		@PathVariable Long accommodationId,
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken
	) {
		accessGuard.verify(benchmarkToken);
		ReviewResponse.ReviewSummary response =
			benchmarkService.findReviewSummaryBefore(accommodationId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
