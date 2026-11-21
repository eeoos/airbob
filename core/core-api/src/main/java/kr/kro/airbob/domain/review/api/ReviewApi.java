package kr.kro.airbob.domain.review.api;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

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
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.review.dto.ReviewRequest;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.entity.ReviewSortType;

@Tag(name = "리뷰")
@RequestMapping("/api")
public interface ReviewApi {

	@Operation(operationId = "Review.createReview", summary = "리뷰 작성",
		description = """
			로그인한 활성 회원이 현재 게시된 숙소에 리뷰를 작성합니다. 해당 숙소에 본인의 CONFIRMED 또는 CANCELLATION_FAILED 예약이 있고
			체크아웃 시각에 도달했어야 합니다. 조건 미충족은 403/V003입니다. reservation_uid나 회원 ID는 입력하지 않습니다.

			숙소별로 본인의 게시된 리뷰가 이미 있으면 409/V004입니다. 삭제·숨김 상태의 리뷰는 중복 검사에서 제외됩니다.
			본문과 1~5의 정수 평점은 필수입니다. 성공 시 data.id를 반환하며, 이미지는 생성 후 별도 이미지 API로 추가합니다.
			숙소가 없거나 게시 상태가 아니면 404/A001, 활성 회원을 찾지 못하면 404/M001입니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "리뷰 생성 완료. data.id는 리뷰 ID입니다.", useReturnTypeSchema = true)
	@ApiErrors({ErrorCode.INVALID_INPUT_VALUE, ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.REVIEW_CREATION_DENIED,
		ErrorCode.REVIEW_ALREADY_EXISTS, ErrorCode.INVALID_TYPE_VALUE, ErrorCode.MEMBER_NOT_FOUND, ErrorCode.ACCOMMODATION_NOT_FOUND})
	@PostMapping("/v1/accommodations/{accommodationId}/reviews")
	ResponseEntity<ApiResponse<ReviewResponse.Create>> createReview(
		@Parameter(description = "리뷰를 작성할 숙소 ID", example = "101") @PathVariable Long accommodationId,
		@Valid @RequestBody ReviewRequest.Create request,
		@CurrentMemberId Long memberId);

	@Operation(operationId = "Review.updateReview", summary = "리뷰 수정",
		description = """
			본인이 작성한 게시 상태의 리뷰만 수정합니다. 본문 content는 매번 필수이며, rating은 생략·null이면 유지하고 지정하면 1~5의 정수로 변경합니다.
			평점만 바꾸려면 기존 본문도 함께 보냅니다. 평점 변경은 숙소의 리뷰 수를 바꾸지 않고 평균 평점에 반영합니다.
			없거나 타인의 리뷰는 404/V001, 본인의 삭제·숨김 리뷰는 403/V005입니다. reviewed_at은 최초 작성 시각으로 유지됩니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 완료. data.id는 리뷰 ID입니다.", useReturnTypeSchema = true)
	@ApiErrors({ErrorCode.INVALID_INPUT_VALUE, ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.REVIEW_NOT_FOUND,
		ErrorCode.REVIEW_UPDATE_FORBIDDEN, ErrorCode.INVALID_TYPE_VALUE})
	@PatchMapping("/v1/reviews/{reviewId}")
	ResponseEntity<ApiResponse<ReviewResponse.Update>> updateReview(
		@Parameter(description = "본인이 작성한 리뷰 ID", example = "201") @PathVariable Long reviewId,
		@Valid @RequestBody ReviewRequest.Update request,
		@CurrentMemberId Long memberId);

	@Operation(operationId = "Review.deleteReview", summary = "리뷰 삭제",
		description = "본인의 게시된 리뷰를 삭제 상태로 바꾸고 공개 목록과 숙소 평점 집계에서 제외합니다. 본문 없이 호출합니다. "
			+ "없거나 타인의 리뷰, 이미 삭제·숨김 상태인 리뷰는 404/V001입니다. 리뷰 삭제로 첨부 이미지 파일까지 삭제되지는 않습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 완료", useReturnTypeSchema = true)
	@ApiErrors({ErrorCode.REVIEW_NOT_FOUND, ErrorCode.INVALID_TYPE_VALUE})
	@DeleteMapping("/v1/reviews/{reviewId}")
	ResponseEntity<ApiResponse<Void>> deleteReview(
		@Parameter(description = "본인이 작성한 리뷰 ID", example = "201") @PathVariable Long reviewId,
		@CurrentMemberId Long memberId);

	@Operation(operationId = "Review.uploadReviewImages", summary = "리뷰 이미지 업로드",
		description = """
			본인 리뷰에 이미지를 추가합니다. multipart/form-data에서 파일마다 images 파트를 반복하며 파일 파트의 Content-Type은 image/jpeg 또는 image/png입니다.
			파일당 최대 10MiB, multipart 헤더·구분자를 포함한 요청 전체는 최대 20MiB입니다. 성공은 201이며 data.uploaded_images에 이번에 추가한 이미지의 id와 image_url을 반환합니다.

			없거나 타인의 리뷰는 404/V001입니다. 현재 이미지 API는 리뷰의 삭제·숨김 상태를 별도로 제한하지 않습니다.
			images 파트 누락은 400/C001, 빈 파일은 400/I007, 잘못된 파일 Content-Type은 400/I003입니다.
			서버 업로드 크기 제한은 413/I009, 서비스의 개별 파일 검사에서 발견한 크기 초과는 400/I008입니다. multipart가 아닌 요청은 415/C011입니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "이미지 추가 완료", useReturnTypeSchema = true)
	@ApiErrors({ErrorCode.INVALID_TYPE_VALUE, ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.REVIEW_NOT_FOUND,
		ErrorCode.IMAGE_UPLOAD_FAILED, ErrorCode.INVALID_IMAGE_FORMAT, ErrorCode.EMPTY_IMAGE_FILE, ErrorCode.IMAGE_FILE_SIZE_EXCEEDED})
	@PostMapping(value = "/v1/reviews/{reviewId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<ApiResponse<ImageResponse.ImageUploadResult>> uploadReviewImages(
		@Parameter(description = "본인이 작성한 리뷰 ID", example = "201") @PathVariable Long reviewId,
		@RequestParam("images") List<MultipartFile> images,
		@CurrentMemberId Long memberId);

	@Operation(operationId = "Review.deleteReviewImage", summary = "리뷰 이미지 삭제",
		description = """
			본인 리뷰의 이미지 연결을 삭제합니다. imageId는 업로드 응답 또는 리뷰 목록의 images[].id이며 본문은 없습니다.
			이미지가 없거나 다른 회원의 이미지이면 403/V006, 본인의 이미지지만 reviewId가 실제 소속 리뷰와 다르면 400/C009입니다.
			리뷰의 삭제·숨김 상태와 관계없이 본인 이미지 삭제가 가능합니다. 성공은 200과 {"success":true}, 삭제 후 같은 요청은 403/V006입니다.
			파일 저장소 삭제는 별도로 시도하며, 200이 파일 저장소에서의 삭제 완료까지 보장하지는 않습니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "리뷰 이미지 연결 삭제 완료", useReturnTypeSchema = true)
	@ApiErrors({ErrorCode.INVALID_TYPE_VALUE, ErrorCode.REVIEW_ACCESS_DENIED, ErrorCode.INVALID_INPUT})
	@DeleteMapping("/v1/reviews/{reviewId}/images/{imageId}")
	ResponseEntity<ApiResponse<Void>> deleteReviewImage(
		@Parameter(description = "이미지가 속한 리뷰 ID", example = "201") @PathVariable Long reviewId,
		@Parameter(description = "리뷰 이미지 ID", example = "301") @PathVariable Long imageId,
		@CurrentMemberId Long memberId);

	@Operation(operationId = "Review.findReviews", summary = "숙소 리뷰 조회",
		description = """
			로그인 없이 숙소의 게시된 리뷰를 조회합니다. 삭제·숨김 리뷰는 제외합니다. 숙소의 현재 게시 상태나 존재 여부는 별도로 확인하지 않으며 결과가 없으면 200과 빈 reviews를 반환합니다.
			LATEST는 작성 시각 내림차순, HIGHEST_RATING은 높은 평점순, LOWEST_RATING은 낮은 평점순입니다.
			평점 동률에서는 작성 시각 내림차순, 작성 시각까지 같으면 리뷰 ID 내림차순입니다.

			cursor와 size(기본 20, 1~50)를 사용합니다. 다음 페이지는 page_info.next_cursor를 그대로 전달하며 숙소·정렬을 바꾸면 커서를 생략합니다.
			조회 사이에 리뷰 작성·수정·삭제가 있으면 목록과 정렬 위치가 바뀔 수 있습니다. reviewed_at은 최초 작성 시각(UTC)이며 이미지 표시 순서는 보장하지 않습니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "리뷰 목록. 결과가 없으면 reviews는 빈 배열입니다.", useReturnTypeSchema = true)
	@ApiErrors({ErrorCode.INVALID_TYPE_VALUE})
	@GetMapping("/v1/accommodations/{accommodationId}/reviews")
	ResponseEntity<ApiResponse<ReviewResponse.ReviewInfos>> findReviews(
		@Parameter(description = "조회할 숙소 ID", example = "101") @PathVariable Long accommodationId,
		@Parameter(description = "정렬 방식. 정렬을 바꾸면 cursor를 초기화합니다.") @RequestParam(defaultValue = "LATEST") ReviewSortType sortType,
		@CursorParam CursorRequest.ReviewCursorPageRequest cursorRequest);
}
