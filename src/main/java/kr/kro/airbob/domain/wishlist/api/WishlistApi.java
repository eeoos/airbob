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
import kr.kro.airbob.domain.wishlist.dto.WishlistRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;

@Tag(name = "위시리스트")
@RequestMapping("/api")
public interface WishlistApi {

	@Operation(operationId = "Wishlist.createWishlist", summary = "위시리스트 생성",
		description = "현재 활성 회원의 빈 위시리스트를 생성합니다. 이름은 필수이며 공백만 사용할 수 없고 최대 255자입니다. 같은 이름의 목록도 생성할 수 있습니다. 응답 id는 wishlistId입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 완료", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.MEMBER_NOT_FOUND
	})
	@PostMapping("/v1/members/wishlists")
	ResponseEntity<ApiResponse<WishlistResponse.Create>> createWishlist(
		@Valid @RequestBody WishlistRequest.Create request,
		@CurrentMemberId Long memberId);

	@Operation(operationId = "Wishlist.updateWishlist", summary = "위시리스트 이름 변경",
		description = "본인의 활성 위시리스트 이름을 변경합니다. 이름은 필수이며 공백만 사용할 수 없고 최대 255자입니다. 없거나 삭제된 목록은 404/W001, 다른 회원의 목록은 403/W002입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.INVALID_TYPE_VALUE,
		ErrorCode.WISHLIST_NOT_FOUND,
		ErrorCode.WISHLIST_ACCESS_DENIED
	})
	@PatchMapping("/v1/members/wishlists/{wishlistId}")
	ResponseEntity<ApiResponse<WishlistResponse.Update>> updateWishlist(
		@Parameter(description = "위시리스트 ID", example = "42") @PathVariable Long wishlistId,
		@Valid @RequestBody WishlistRequest.Update request,
		@CurrentMemberId Long memberId);

	@Operation(operationId = "Wishlist.deleteWishlist", summary = "위시리스트 삭제",
		description = "본인의 활성 위시리스트를 삭제 상태로 바꾸고 저장된 숙소 연결과 메모를 모두 삭제합니다. 숙소 자체는 삭제하지 않습니다. 없거나 이미 삭제된 목록은 404/W001, 다른 회원의 목록은 403/W002입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_TYPE_VALUE,
		ErrorCode.WISHLIST_NOT_FOUND,
		ErrorCode.WISHLIST_ACCESS_DENIED
	})
	@DeleteMapping("/v1/members/wishlists/{wishlistId}")
	ResponseEntity<ApiResponse<Void>> deleteWishlist(
		@Parameter(description = "위시리스트 ID", example = "42") @PathVariable Long wishlistId,
		@CurrentMemberId Long memberId);

	@Operation(operationId = "Wishlist.findWishlists", summary = "내 위시리스트 조회",
		description = "본인의 활성 위시리스트를 생성 시각·목록 ID 내림차순으로 조회합니다. 기본 20개, 최대 50개입니다. accommodationId는 목록 필터가 아니라 각 목록의 저장 여부와 연결 ID를 함께 받기 위한 선택값입니다. 생략하면 is_contained와 wishlist_accommodation_id는 null입니다. 숙소 수·대표 사진·저장 여부에는 비공개 숙소도 포함되므로 공개 숙소만 반환하는 상세 목록과 개수가 다를 수 있습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_TYPE_VALUE
	})
	@GetMapping("/v1/members/wishlists")
	ResponseEntity<ApiResponse<WishlistResponse.WishlistInfos>> findWishlists(
		@CursorParam CursorRequest.CursorPageRequest request,
		@Parameter(description = "각 목록의 저장 여부를 확인할 숙소 ID. 목록을 필터링하지 않습니다.", example = "101")
		@RequestParam(required = false) Long accommodationId,
		@CurrentMemberId Long memberId);
}
