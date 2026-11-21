package kr.kro.airbob.domain.wishlist.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import kr.kro.airbob.domain.wishlist.service.WishlistService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class WishlistController implements WishlistApi {

	private final WishlistService wishlistService;

	@Override
	public ResponseEntity<ApiResponse<WishlistResponse.Create>> createWishlist(
		WishlistRequest.Create request,
		Long memberId) {
		WishlistResponse.Create response = wishlistService.createWishlist(request, memberId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<WishlistResponse.Update>> updateWishlist(
		Long wishlistId,
		WishlistRequest.Update request,
		Long memberId) {
		WishlistResponse.Update response = wishlistService.updateWishlist(wishlistId, request, memberId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<Void>> deleteWishlist(
		Long wishlistId,
		Long memberId) {
		wishlistService.deleteWishlist(wishlistId, memberId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@Override
	public ResponseEntity<ApiResponse<WishlistResponse.WishlistInfos>> findWishlists(
		CursorRequest.CursorPageRequest request,
		Long accommodationId,
		Long memberId) {
		WishlistResponse.WishlistInfos response = wishlistService.findWishlists(request, memberId, accommodationId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
