package kr.kro.airbob.domain.wishlist.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.wishlist.WishlistService;
import kr.kro.airbob.domain.wishlist.dto.WishlistRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api")
public class WishlistController {

	private final WishlistService wishlistService;

	@PostMapping("/v1/members/wishlists")
	public ResponseEntity<WishlistResponse.CreateResponse> createWishlist(
		@Valid @RequestBody WishlistRequest.createRequest request){

		WishlistResponse.CreateResponse response = wishlistService.createWishlist(request);
		return ResponseEntity.ok(response);
	}

	@PatchMapping("/v1/members/wishlists/{wishlistId}")
	public ResponseEntity<WishlistResponse.UpdateResponse> updateWishlist(
		@PathVariable Long wishlistId,
		@Valid @RequestBody WishlistRequest.updateRequest request) {

		WishlistResponse.UpdateResponse response = wishlistService.updateWishlist(wishlistId, request);

		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/v1/members/wishlists/{wishlistId}")
	public ResponseEntity<Void> deleteWishlist(@PathVariable Long wishlistId) {
		wishlistService.deleteWishlist(wishlistId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/v1/members/wishlists")
	public ResponseEntity<WishlistResponse.WishlistInfos> findWishlists(
		@CursorParam CursorRequest.CursorPageRequest request) {


		WishlistResponse.WishlistInfos response = wishlistService.findWishlists(request);
		return ResponseEntity.ok(response);
	}

	@PostMapping("/v1/members/wishlists/{wishlistId}/accommodations")
	public ResponseEntity<WishlistResponse.CreateWishlistAccommodationResponse> createWishlistAccommodation(
		@PathVariable Long wishlistId,
		@Valid @RequestBody WishlistRequest.CreateWishlistAccommodationRequest request) {
		WishlistResponse.CreateWishlistAccommodationResponse response =
			wishlistService.createWishlistAccommodation(wishlistId, request);
		return ResponseEntity.ok(response);
	}

	@PatchMapping("/v1/members/wishlists/{wishlistId}/accommodations/{wishlistAccommodationId}")
	public ResponseEntity<WishlistResponse.UpdateWishlistAccommodationResponse> updateWishlistAccommodation(
		@PathVariable Long wishlistAccommodationId,
		@Valid @RequestBody WishlistRequest.UpdateWishlistAccommodationRequest request) {

		WishlistResponse.UpdateWishlistAccommodationResponse response =
			wishlistService.updateWishlistAccommodation(wishlistAccommodationId, request);

		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/v1/members/wishlists/{wishlistId}/accommodations/{wishlistAccommodationId}")
	public ResponseEntity<Void> deleteWishlistAccommodation(
		@PathVariable Long wishlistAccommodationId) {

		wishlistService.deleteWishlistAccommodation(wishlistAccommodationId);

		return ResponseEntity.noContent().build();
	}

	// todo: 추후 필터링 적용(날짜, 게스트 인원)
	@GetMapping("/v1/members/wishlists/{wishlistId}/accommodations")
	public ResponseEntity<WishlistResponse.WishlistAccommodationInfos> findWishlistAccommodations(
		@CursorParam CursorRequest.CursorPageRequest request,
		@PathVariable Long wishlistId
	) {

		WishlistResponse.WishlistAccommodationInfos response
			= wishlistService.findWishlistAccommodations(wishlistId, request);

		return ResponseEntity.ok(response);
	}
}
