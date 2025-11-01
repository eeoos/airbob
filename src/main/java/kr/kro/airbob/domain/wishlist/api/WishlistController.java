package kr.kro.airbob.domain.wishlist.api;

import org.springframework.http.HttpStatus;
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
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.wishlist.service.WishlistService;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationResponse;
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
	public ResponseEntity<ApiResponse<WishlistResponse.Create>> createWishlist(
		@Valid @RequestBody WishlistRequest.Create request){
		Long memberId = UserContext.get().id();
		WishlistResponse.Create response = wishlistService.createWishlist(request, memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@PatchMapping("/v1/members/wishlists/{wishlistId}")
	public ResponseEntity<ApiResponse<WishlistResponse.Update>> updateWishlist(
		@PathVariable Long wishlistId,
		@Valid @RequestBody WishlistRequest.Update request) {
		Long memberId = UserContext.get().id();
		WishlistResponse.Update response = wishlistService.updateWishlist(wishlistId, request, memberId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@DeleteMapping("/v1/members/wishlists/{wishlistId}")
	public ResponseEntity<ApiResponse<Void>> deleteWishlist(@PathVariable Long wishlistId) {
		Long memberId = UserContext.get().id();
		wishlistService.deleteWishlist(wishlistId, memberId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@GetMapping("/v1/members/wishlists")
	public ResponseEntity<ApiResponse<WishlistResponse.WishlistInfos>> findWishlists(
		@CursorParam CursorRequest.CursorPageRequest request) {
		Long memberId = UserContext.get().id();
		WishlistResponse.WishlistInfos response = wishlistService.findWishlists(request, memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@PostMapping("/v1/members/wishlists/{wishlistId}")
	public ResponseEntity<ApiResponse<WishlistAccommodationResponse.Create>> createWishlistAccommodation(
		@PathVariable Long wishlistId,
		@Valid @RequestBody WishlistAccommodationRequest.Create request) {
		Long memberId = UserContext.get().id();
		WishlistAccommodationResponse.Create response =
			wishlistService.createWishlistAccommodation(wishlistId, request, memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@PatchMapping("/v1/members/wishlists/{wishlistAccommodationId}")
	public ResponseEntity<ApiResponse<WishlistAccommodationResponse.Update>> updateWishlistAccommodation(
		@PathVariable Long wishlistAccommodationId,
		@Valid @RequestBody WishlistAccommodationRequest.Update request) {

		Long memberId = UserContext.get().id();
		WishlistAccommodationResponse.Update response =
			wishlistService.updateWishlistAccommodation(wishlistAccommodationId, request, memberId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@DeleteMapping("/v1/members/wishlists/{wishlistAccommodationId}")
	public ResponseEntity<ApiResponse<Void>> deleteWishlistAccommodation(
		@PathVariable Long wishlistAccommodationId) {
		Long memberId = UserContext.get().id();

		wishlistService.deleteWishlistAccommodation(wishlistAccommodationId, memberId);

		return ResponseEntity.ok(ApiResponse.success());
	}

	// todo: 추후 필터링 적용(날짜, 게스트 인원)
	@GetMapping("/v1/members/wishlists/{wishlistId}")
	public ResponseEntity<ApiResponse<WishlistAccommodationResponse.WishlistAccommodationInfos>> findWishlistAccommodations(
		@CursorParam CursorRequest.CursorPageRequest request,
		@PathVariable Long wishlistId
	) {

		WishlistAccommodationResponse.WishlistAccommodationInfos response
			= wishlistService.findWishlistAccommodations(wishlistId, request);

		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
