package kr.kro.airbob.domain.wishlist.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationResponse;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import kr.kro.airbob.domain.wishlist.service.WishlistService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class WishlistAccommodationController implements WishlistAccommodationApi {

	private final WishlistService wishlistService;

	@Override
	public ResponseEntity<ApiResponse<WishlistResponse.Membership>> findMembership(
		Long accommodationId,
		Long wishlistId,
		Long memberId) {
		return ResponseEntity.ok(ApiResponse.success(
			wishlistService.findMembership(memberId, accommodationId, wishlistId)));
	}

	@Override
	public ResponseEntity<ApiResponse<WishlistAccommodationResponse.Create>> createWishlistAccommodation(
		Long wishlistId,
		WishlistAccommodationRequest.Create request,
		Long memberId) {
		WishlistAccommodationResponse.Create response =
			wishlistService.createWishlistAccommodation(wishlistId, request, memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<WishlistAccommodationResponse.Update>> updateWishlistAccommodation(
		Long wishlistAccommodationId,
		WishlistAccommodationRequest.Update request,
		Long memberId) {
		WishlistAccommodationResponse.Update response =
			wishlistService.updateWishlistAccommodation(wishlistAccommodationId, request, memberId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<Void>> deleteWishlistAccommodation(
		Long wishlistAccommodationId,
		Long memberId) {
		wishlistService.deleteWishlistAccommodation(wishlistAccommodationId, memberId);

		return ResponseEntity.ok(ApiResponse.success());
	}

	@Override
	public ResponseEntity<ApiResponse<WishlistAccommodationResponse.WishlistAccommodationInfos>> findWishlistAccommodations(
		CursorRequest.CursorPageRequest request,
		Long wishlistId,
		Long memberId
	) {
		WishlistAccommodationResponse.WishlistAccommodationInfos response
			= wishlistService.findWishlistAccommodations(wishlistId, request, memberId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
