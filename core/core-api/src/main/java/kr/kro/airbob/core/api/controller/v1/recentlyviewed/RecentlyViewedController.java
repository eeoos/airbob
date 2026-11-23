package kr.kro.airbob.core.api.controller.v1.recentlyviewed;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.core.api.controller.v1.recentlyviewed.response.RecentlyViewedResponse;
import kr.kro.airbob.core.domain.recentlyviewed.RecentlyViewedService;
import kr.kro.airbob.core.support.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
@RequiredArgsConstructor
public class RecentlyViewedController implements RecentlyViewedApi {

	private final RecentlyViewedService recentlyViewedService;

	@Override
	public ResponseEntity<ApiResponse<Void>> addRecentlyViewed(
		Long accommodationId,
		Long memberId) {
		recentlyViewedService.addRecentlyViewed(accommodationId, memberId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@Override
	public ResponseEntity<ApiResponse<Void>> removeRecentlyViewed(
		Long accommodationId,
		Long memberId) {
		recentlyViewedService.removeRecentlyViewed(accommodationId, memberId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@Override
	public ResponseEntity<ApiResponse<RecentlyViewedResponse.RecentlyViewedAccommodationInfos>> getRecentlyViewed(
		Long memberId) {
		RecentlyViewedResponse.RecentlyViewedAccommodationInfos response =
			RecentlyViewedResponse.RecentlyViewedAccommodationInfos.from(recentlyViewedService.getRecentlyViewed(memberId));
		return ResponseEntity.ok(ApiResponse.success(response));

	}
}
