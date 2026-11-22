package kr.kro.airbob.domain.wishlist.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.core.support.response.ApiResponse;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.wishlist.WishlistAccommodationPage;
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
		return ResponseEntity.ok(ApiResponse.success(WishlistResponse.Membership.from(
			wishlistService.findMembership(memberId, accommodationId, wishlistId))));
	}

	@Override
	public ResponseEntity<ApiResponse<WishlistAccommodationResponse.Create>> createWishlistAccommodation(
		Long wishlistId,
		WishlistAccommodationRequest.Create request,
		Long memberId) {
		Long wishlistAccommodationId =
			wishlistService.createWishlistAccommodation(wishlistId, request.accommodationId(), memberId);
		return ResponseEntity.ok(ApiResponse.success(new WishlistAccommodationResponse.Create(wishlistAccommodationId)));
	}

	@Override
	public ResponseEntity<ApiResponse<WishlistAccommodationResponse.Update>> updateWishlistAccommodation(
		Long wishlistAccommodationId,
		WishlistAccommodationRequest.Update request,
		Long memberId) {
		Long updatedId =
			wishlistService.updateWishlistAccommodation(wishlistAccommodationId, request.memo(), memberId);

		return ResponseEntity.ok(ApiResponse.success(new WishlistAccommodationResponse.Update(updatedId)));
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
		WishlistAccommodationPage page = wishlistService.findWishlistAccommodations(wishlistId, request, memberId);

		return ResponseEntity.ok(ApiResponse.success(WishlistAccommodationResponse.WishlistAccommodationInfos.from(page)));
	}
}
