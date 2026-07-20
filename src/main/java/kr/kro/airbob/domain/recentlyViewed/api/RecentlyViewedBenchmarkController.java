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
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.recentlyViewed.dto.RecentlyViewedBenchmarkRequest;
import kr.kro.airbob.domain.recentlyViewed.service.RecentlyViewedBenchmarkFixtureService;
import kr.kro.airbob.domain.recentlyViewed.service.RecentlyViewedService;
import lombok.RequiredArgsConstructor;

/**
 * 최근 본 숙소 fixture 준비와 주소 지연 로딩 N+1 기준선 재현을 위한 벤치마크 전용 API
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
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken
	) {
		accessGuard.verify(benchmarkToken);
		Long memberId = UserContext.get().id();
		fixtureService.replaceFixture(memberId, request.accommodationIds());
		return ResponseEntity.ok(ApiResponse.success());
	}

	@GetMapping("/api/v2/members/recently-viewed")
	public ResponseEntity<ApiResponse<AccommodationResponse.RecentlyViewedAccommodationInfos>> getRecentlyViewedBefore(
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken
	) {
		accessGuard.verify(benchmarkToken);
		Long memberId = UserContext.get().id();
		AccommodationResponse.RecentlyViewedAccommodationInfos response =
			recentlyViewedService.getRecentlyViewedBefore(memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
