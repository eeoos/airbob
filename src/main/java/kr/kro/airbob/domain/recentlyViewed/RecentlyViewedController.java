package kr.kro.airbob.domain.recentlyViewed;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api")
public class RecentlyViewedController {

	private final RecentlyViewedService recentlyViewedService;

	@PostMapping("/v1/members/recentlyViewed/{accommodationId}")
	public ResponseEntity<Void> addRecentlyViewed(@PathVariable Long accommodationId) {


		recentlyViewedService.addRecentlyViewed(accommodationId);
		return ResponseEntity.ok().build();
	}

	@DeleteMapping("/v1/members/recentlyViewed/{accommodationId}")
	public ResponseEntity<Void> removeRecentlyViewed(
		@PathVariable Long accommodationId) {
		recentlyViewedService.removeRecentlyViewed(accommodationId);
		return ResponseEntity.ok().build();
	}

	@GetMapping("/v1/members/recentlyViewed")
	public ResponseEntity<AccommodationResponse.RecentlyViewedAccommodations> getRecentlyViewed() {
		AccommodationResponse.RecentlyViewedAccommodations response =
			recentlyViewedService.getRecentlyViewed();
		return ResponseEntity.ok(response);

	}

}
