package kr.kro.airbob.core.api.controller.v1.wishlist;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.core.api.controller.v1.wishlist.request.WishlistRequest;
import kr.kro.airbob.core.api.controller.v1.wishlist.response.WishlistResponse;
import kr.kro.airbob.core.domain.wishlist.WishlistService;
import kr.kro.airbob.core.domain.wishlist.WishlistSummaryPage;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.core.support.response.ApiResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class WishlistController implements WishlistApi {

	private final WishlistService wishlistService;

	@Override
	public ResponseEntity<ApiResponse<WishlistResponse.Create>> createWishlist(
		WishlistRequest.Create request,
		Long memberId) {
		Long wishlistId = wishlistService.createWishlist(request.name(), memberId);
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(new WishlistResponse.Create(wishlistId)));
	}

	@Override
	public ResponseEntity<ApiResponse<WishlistResponse.Update>> updateWishlist(
		Long wishlistId,
		WishlistRequest.Update request,
		Long memberId) {
		Long updatedId = wishlistService.updateWishlist(wishlistId, request.name(), memberId);

		return ResponseEntity.ok(ApiResponse.success(new WishlistResponse.Update(updatedId)));
	}

	@Override
	public ResponseEntity<ApiResponse<Void>> deleteWishlist(
		Long wishlistId,
		Long memberId) {
		wishlistService.deleteWishlist(wishlistId, memberId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@Override
	public ResponseEntity<ApiResponse<WishlistResponse.WishlistInfos>> getWishlists(
		CursorRequest.CursorPageRequest request,
		Long accommodationId,
		Long memberId) {
		WishlistSummaryPage page = wishlistService.getWishlists(request, memberId, accommodationId);
		return ResponseEntity.ok(ApiResponse.success(
			WishlistResponse.WishlistInfos.from(page, accommodationId != null)));
	}
}
