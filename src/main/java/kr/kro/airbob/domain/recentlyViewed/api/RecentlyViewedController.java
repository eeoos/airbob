package kr.kro.airbob.domain.recentlyViewed.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.recentlyViewed.service.RecentlyViewedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api")
public class RecentlyViewedController {

	private final RecentlyViewedService recentlyViewedService;

	@PostMapping("/v1/members/recently-viewed/{accommodationId}")
	public ResponseEntity<ApiResponse<Void>> addRecentlyViewed(
		@PathVariable Long accommodationId,
		@CurrentMemberId Long memberId) {
		recentlyViewedService.addRecentlyViewed(accommodationId, memberId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@DeleteMapping("/v1/members/recently-viewed/{accommodationId}")
	public ResponseEntity<ApiResponse<Void>> removeRecentlyViewed(
		@PathVariable Long accommodationId,
		@CurrentMemberId Long memberId) {
		recentlyViewedService.removeRecentlyViewed(accommodationId, memberId);
		return ResponseEntity.ok(ApiResponse.success());
	}
	@GetMapping("/v1/members/recently-viewed")
	public ResponseEntity<ApiResponse<AccommodationResponse.RecentlyViewedAccommodationInfos>> getRecentlyViewed(
		@CurrentMemberId Long memberId) {
		AccommodationResponse.RecentlyViewedAccommodationInfos response =
			recentlyViewedService.getRecentlyViewed(memberId);
		return ResponseEntity.ok(ApiResponse.success(response));

	}
}
