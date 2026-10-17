package kr.kro.airbob.domain.recentlyviewed.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.recentlyviewed.service.RecentlyViewedService;
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
	public ResponseEntity<ApiResponse<AccommodationResponse.RecentlyViewedAccommodationInfos>> getRecentlyViewed(
		Long memberId) {
		AccommodationResponse.RecentlyViewedAccommodationInfos response =
			recentlyViewedService.getRecentlyViewed(memberId);
		return ResponseEntity.ok(ApiResponse.success(response));

	}
}
