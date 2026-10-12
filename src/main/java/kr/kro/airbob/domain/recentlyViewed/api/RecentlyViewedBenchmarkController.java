package kr.kro.airbob.domain.recentlyViewed.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.recentlyViewed.dto.RecentlyViewedBenchmarkRequest;
import kr.kro.airbob.domain.recentlyViewed.service.RecentlyViewedBenchmarkFixtureService;
import kr.kro.airbob.domain.recentlyViewed.service.RecentlyViewedService;
import lombok.RequiredArgsConstructor;

/**
 * 원본 리뷰 집계를 유지한 채 주소 N+1 개선 전후를 비교하는 벤치마크 전용 API
 */
@RestController
@RequiredArgsConstructor
@Profile("nplus1-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
public class RecentlyViewedBenchmarkController {

	private final RecentlyViewedService recentlyViewedService;
	private final RecentlyViewedBenchmarkFixtureService fixtureService;
	private final BenchmarkAccessGuard accessGuard;

	@PutMapping("/api/v2/members/recently-viewed/fixture")
	public ResponseEntity<ApiResponse<Void>> replaceRecentlyViewedFixture(
		@Valid @RequestBody RecentlyViewedBenchmarkRequest.Replace request,
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken,
		@CurrentMemberId Long memberId
	) {
		accessGuard.verify(benchmarkToken);
		fixtureService.replaceFixture(memberId, request.accommodationIds());
		return ResponseEntity.ok(ApiResponse.success());
	}

	@GetMapping("/api/v2/members/recently-viewed/nplus1-before")
	public ResponseEntity<ApiResponse<AccommodationResponse.RecentlyViewedAccommodationInfos>> getRecentlyViewedBefore(
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken,
		@CurrentMemberId Long memberId
	) {
		accessGuard.verify(benchmarkToken);
		AccommodationResponse.RecentlyViewedAccommodationInfos response =
			recentlyViewedService.getRecentlyViewedBefore(memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@GetMapping("/api/v2/members/recently-viewed/nplus1-after")
	public ResponseEntity<ApiResponse<AccommodationResponse.RecentlyViewedAccommodationInfos>> getRecentlyViewedAfter(
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken,
		@CurrentMemberId Long memberId
	) {
		accessGuard.verify(benchmarkToken);
		// 반정규화 Before와 같은 구현을 사용해 두 비교의 중간 단계를 일치시킨다.
		AccommodationResponse.RecentlyViewedAccommodationInfos response =
			recentlyViewedService.getRecentlyViewedBeforeReviewSummary(memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
