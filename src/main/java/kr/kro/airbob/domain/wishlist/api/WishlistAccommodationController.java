package kr.kro.airbob.domain.wishlist.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationResponse;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import kr.kro.airbob.domain.wishlist.service.WishlistService;
import lombok.RequiredArgsConstructor;

@Tag(name = "위시리스트")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class WishlistAccommodationController {

	private final WishlistService wishlistService;

	@Operation(operationId = "Wishlist.findMembership", summary = "숙소 저장 여부 조회",
		description = "accommodationId는 필수입니다. 본인의 활성 위시리스트 중 하나라도 해당 숙소를 포함하면 is_in_any_wishlist가 true입니다. 숙소의 현재 공개 상태는 확인하지 않습니다. wishlistId를 생략하거나 본인의 활성 목록이 아니면 target_wishlist_found=false, target_wishlist_contains=null입니다. 유효한 본인 목록이면 found=true와 실제 포함 여부를 반환합니다. 없는 숙소나 목록도 조회 결과로 처리하며 404를 반환하지 않습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@GetMapping("/v1/members/wishlists/membership")
	public ResponseEntity<ApiResponse<WishlistResponse.Membership>> findMembership(
		@Parameter(description = "저장 여부를 확인할 숙소 ID", example = "101") @RequestParam Long accommodationId,
		@Parameter(description = "특정 목록의 저장 여부도 확인할 때 지정하는 목록 ID", example = "42")
		@RequestParam(required = false) Long wishlistId,
		@CurrentMemberId Long memberId) {
		return ResponseEntity.ok(ApiResponse.success(
			wishlistService.findMembership(memberId, accommodationId, wishlistId)));
	}

	@Operation(operationId = "Wishlist.createWishlistAccommodation", summary = "위시리스트에 숙소 추가",
		description = "본인의 활성 위시리스트에 현재 공개된 숙소를 추가하고 대표 썸네일 대상으로 설정합니다. 같은 목록에 이미 저장된 숙소는 동시 요청도 409/W004로 거절합니다. 다른 목록에는 같은 숙소를 저장할 수 있습니다. 성공은 200이며 응답 id는 숙소 ID나 목록 ID가 아닌 wishlistAccommodationId입니다. 메모의 초기값은 null입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.WISHLIST_NOT_FOUND,
		ErrorCode.WISHLIST_ACCESS_DENIED,
		ErrorCode.WISHLIST_ACCOMMODATION_DUPLICATE,
		ErrorCode.INVALID_TYPE_VALUE,
		ErrorCode.ACCOMMODATION_NOT_FOUND
	})
	@PostMapping("/v1/members/wishlists/accommodations/{id}")
	public ResponseEntity<ApiResponse<WishlistAccommodationResponse.Create>> createWishlistAccommodation(
		@Parameter(description = "위시리스트 ID", example = "42") @PathVariable("id") Long wishlistId,
		@Valid @RequestBody WishlistAccommodationRequest.Create request,
		@CurrentMemberId Long memberId) {
		WishlistAccommodationResponse.Create response =
			wishlistService.createWishlistAccommodation(wishlistId, request, memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(operationId = "Wishlist.updateWishlistAccommodation", summary = "저장한 숙소 메모 변경",
		description = "본인의 활성 위시리스트에 저장된 항목의 메모를 변경합니다. memo는 필수이며 공백만 사용할 수 없고 최대 1,024자입니다. null·빈 문자열로 메모를 지울 수 없습니다. 저장 후 숙소가 비공개가 되어도 수정할 수 있습니다. 없거나 다른 회원의 항목은 403/W005입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.INVALID_TYPE_VALUE,
		ErrorCode.WISHLIST_ACCOMMODATION_ACCESS_DENIED
	})
	@PatchMapping("/v1/members/wishlists/accommodations/{id}")
	public ResponseEntity<ApiResponse<WishlistAccommodationResponse.Update>> updateWishlistAccommodation(
		@Parameter(description = "숙소 추가 응답의 연결 ID(wishlistAccommodationId)", example = "501") @PathVariable("id") Long wishlistAccommodationId,
		@Valid @RequestBody WishlistAccommodationRequest.Update request,
		@CurrentMemberId Long memberId) {
		WishlistAccommodationResponse.Update response =
			wishlistService.updateWishlistAccommodation(wishlistAccommodationId, request, memberId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(operationId = "Wishlist.deleteWishlistAccommodation", summary = "위시리스트에서 숙소 삭제",
		description = "본인의 활성 위시리스트에서 숙소 연결과 메모를 삭제합니다. 저장 후 숙소가 비공개가 되어도 삭제할 수 있습니다. 대표 숙소를 삭제하면 남은 항목 중 추가 시각·연결 ID가 가장 큰 숙소를 대표로 선택합니다. 없거나 이미 삭제됐거나 다른 회원의 항목은 403/W005입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_TYPE_VALUE,
		ErrorCode.WISHLIST_ACCOMMODATION_ACCESS_DENIED
	})
	@DeleteMapping("/v1/members/wishlists/accommodations/{id}")
	public ResponseEntity<ApiResponse<Void>> deleteWishlistAccommodation(
		@Parameter(description = "숙소 추가 응답의 연결 ID(wishlistAccommodationId)", example = "501") @PathVariable("id") Long wishlistAccommodationId,
		@CurrentMemberId Long memberId) {
		wishlistService.deleteWishlistAccommodation(wishlistAccommodationId, memberId);

		return ResponseEntity.ok(ApiResponse.success());
	}

	@Operation(operationId = "Wishlist.findWishlistAccommodations", summary = "위시리스트 숙소 조회",
		description = "본인의 활성 위시리스트에서 현재 공개된 숙소를 추가 시각·연결 ID 내림차순으로 조회합니다. 기본 20개, 최대 50개입니다. 비공개 숙소는 제외하며 비어 있어도 wishlist_name과 빈 배열을 반환합니다. 숙소·주소·리뷰 요약은 현재 값이고 리뷰가 없으면 0건·0점입니다. is_in_wishlist는 항상 true입니다. 없거나 삭제된 목록은 404/W001, 다른 회원의 목록은 403/W002입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_TYPE_VALUE,
		ErrorCode.WISHLIST_NOT_FOUND,
		ErrorCode.WISHLIST_ACCESS_DENIED
	})
	@GetMapping("/v1/members/wishlists/accommodations/{id}")
	public ResponseEntity<ApiResponse<WishlistAccommodationResponse.WishlistAccommodationInfos>> findWishlistAccommodations(
		@CursorParam CursorRequest.CursorPageRequest request,
		@Parameter(description = "위시리스트 ID", example = "42") @PathVariable("id") Long wishlistId,
		@CurrentMemberId Long memberId
	) {
		WishlistAccommodationResponse.WishlistAccommodationInfos response
			= wishlistService.findWishlistAccommodations(wishlistId, request, memberId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
