package kr.kro.airbob.domain.recentlyViewed.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.recentlyViewed.service.RecentlyViewedService;
import lombok.RequiredArgsConstructor;

/** 주소 N+1 비교와 분리한 리뷰 요약 반정규화 비교 API. */
@RestController
@RequiredArgsConstructor
@Profile("read-model-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
public class RecentlyViewedReviewSummaryBenchmarkController {

	private final RecentlyViewedService recentlyViewedService;
	private final BenchmarkAccessGuard accessGuard;

	@GetMapping("/api/v2/members/recently-viewed/review-summary-before")
	public ResponseEntity<ApiResponse<AccommodationResponse.RecentlyViewedAccommodationInfos>> getRecentlyViewedBeforeReviewSummary(
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken,
		@CurrentMemberId Long memberId
	) {
		accessGuard.verify(benchmarkToken);
		return ResponseEntity.ok(ApiResponse.success(
			recentlyViewedService.getRecentlyViewedBeforeReviewSummary(memberId)));
	}
}
