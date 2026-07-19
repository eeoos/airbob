package kr.kro.airbob.domain.recentlyViewed.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.recentlyViewed.service.RecentlyViewedService;
import lombok.RequiredArgsConstructor;

/**
 * 최근 본 숙소의 주소 지연 로딩 N+1 기준선을 재현하는 벤치마크 전용 API다.
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "benchmark.nplus1", name = "enabled", havingValue = "true")
public class RecentlyViewedBenchmarkController {

	private final RecentlyViewedService recentlyViewedService;

	@GetMapping("/api/v2/members/recently-viewed")
	public ResponseEntity<ApiResponse<AccommodationResponse.RecentlyViewedAccommodationInfos>> getRecentlyViewedBefore() {
		Long memberId = UserContext.get().id();
		AccommodationResponse.RecentlyViewedAccommodationInfos response =
			recentlyViewedService.getRecentlyViewedBefore(memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
