package kr.kro.airbob.domain.accommodation.api;

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
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.image.dto.ImageResponse;

@Tag(name = "숙소")
@RequestMapping("/api")
public interface AccommodationHostApi {

    @Operation(operationId = "Accommodation.registerAccommodation", summary = "숙소 초안 생성",
        description = """
            로그인한 활성 회원 소유의 DRAFT 숙소를 생성합니다.
            기본 체크인 시간은 15:00, 체크아웃 시간은 11:00입니다. 응답 data.id를 이후 수정·이미지 업로드·게시 경로에 사용합니다.
            초안은 공개 조회와 검색에 노출되지 않습니다. 숙소 정보 수정 -> 이미지 업로드 -> 게시 순서로 진행합니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 완료", useReturnTypeSchema = true)
    @ApiErrors({
        ErrorCode.MEMBER_NOT_FOUND
    })
    @PostMapping("/v1/accommodations")
    ResponseEntity<ApiResponse<AccommodationResponse.Create>> registerAccommodation(
        @CurrentMemberId Long memberId);

    @Operation(operationId = "Accommodation.updateAccommodation", summary = "숙소 수정",
        description = """
            본인 소유의 삭제되지 않은 숙소를 부분 수정합니다. 생략하거나 null인 필드는 유지하며, null로 기존 값을 지우지는 않습니다.
            address_info와 occupancy_policy_info를 보내면 해당 객체의 필수 항목을 모두 채웁니다.
            amenity_infos는 전체 교체입니다. []는 모두 삭제, null은 유지하며 중복 코드는 개수를 합산하고 0개 항목은 제외합니다.

            숙소 유형은 ACCOMMODATION_TYPE, 편의시설은 AMENITY_TYPE 공통 코드의 활성 code를 사용합니다.
            주소로 좌표와 시간대를 계산하며 예시 주소는 실제 숙소 주소로 바꿔야 합니다. 현재 통화는 KRW만 지원합니다.
            체크아웃 시간은 체크인 시간보다 늦을 수 없습니다. 미래 재고를 점유하는 예약이 있으면 체크인·체크아웃 시간과 시간대를 변경할 수 없습니다(400/C001).

            존재하지 않거나 삭제된 숙소, 다른 회원의 숙소는 모두 404/A001입니다. 성공 응답은 {"success":true}이며 수정된 정보는 내 숙소 상세에서 조회합니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
    @ApiErrors({
        ErrorCode.INVALID_INPUT_VALUE,
        ErrorCode.ACCOMMODATION_NOT_FOUND,
        ErrorCode.ACCOMMODATION_INVALID_TYPE,
        ErrorCode.INVALID_TYPE_VALUE,
        ErrorCode.ACCOMMODATION_INVALID_AMENITY,
        ErrorCode.ACCOMMODATION_LOCATION_RESOLUTION_FAILED,
        ErrorCode.COMMON_CODE_GROUP_NOT_FOUND
    })
    @PatchMapping("/v1/accommodations/{accommodationId}")
    ResponseEntity<ApiResponse<Void>> updateAccommodation(@PathVariable Long accommodationId,
        @RequestBody @Valid AccommodationRequest.Update request,
        @CurrentMemberId Long memberId);

    @Operation(operationId = "Accommodation.publishAccommodation", summary = "숙소 게시",
        description = """
            본인 소유 숙소를 PUBLISHED로 변경합니다.
            이름·설명·주소와 좌표·양수 기본 요금·숙소 유형·시간대·수용 인원 정책·체크인/체크아웃 시간이 필요하고 이미지가 최소 1장 있어야 합니다.
            주소의 좌표와 시간대는 숙소 수정 시 서버가 계산합니다. 필수 정보 누락은 400/A009와 error.errors의 field·reason, 이미지 부족은 400/A006으로 확인합니다.
            이미 게시된 숙소도 조건을 재검사한 뒤 200을 반환합니다. 다른 회원의 숙소와 삭제된 숙소는 404/A001입니다.
            검색 결과와 공개 상세 캐시는 비동기로 갱신되어 게시 직후 잠시 이전 상태가 보일 수 있습니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
    @ApiErrors({
        ErrorCode.ACCOMMODATION_NOT_FOUND,
        ErrorCode.PUBLISHING_VALIDATION_FAILED,
        ErrorCode.INVALID_TYPE_VALUE,
        ErrorCode.ACCOMMODATION_IMAGE_COUNT_TOO_LOW
    })
    @PatchMapping("/v1/accommodations/{accommodationId}/publish")
    ResponseEntity<ApiResponse<Void>> publishAccommodation(@PathVariable Long accommodationId,
        @CurrentMemberId Long memberId);

    @Operation(operationId = "Accommodation.unpublishAccommodation", summary = "숙소 게시 중단",
        description = """
            본인 소유의 PUBLISHED 숙소를 UNPUBLISHED로 변경합니다. 요청 본문은 없으며 기존 예약을 자동 취소하지 않습니다.
            게시 상태가 아니면 400/A003이고, 다른 회원의 숙소와 삭제된 숙소는 404/A001입니다.
            검색 결과와 공개 상세 캐시는 비동기로 갱신됩니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
    @ApiErrors({
        ErrorCode.ACCOMMODATION_NOT_FOUND,
        ErrorCode.ACCOMMODATION_NOT_PUBLISHED,
        ErrorCode.INVALID_TYPE_VALUE
    })
    @PatchMapping("/v1/accommodations/{accommodationId}/unpublish")
    ResponseEntity<ApiResponse<Void>> unpublishAccommodation(@PathVariable Long accommodationId,
        @CurrentMemberId Long memberId);

    @Operation(operationId = "Accommodation.deleteAccommodation", summary = "숙소 삭제",
        description = """
            본인 소유 숙소를 DELETED로 논리 삭제합니다. 요청 본문은 없으며 기존 예약을 자동 취소하지 않습니다.
            내 숙소 목록에서 제외되고, 이후 수정·게시·이미지 변경이나 삭제 재요청은 404/A001입니다.
            소유자의 내 숙소 상세 조회는 삭제 후에도 가능합니다. 검색 결과와 공개 상세 캐시는 비동기로 갱신됩니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
    @ApiErrors({
        ErrorCode.ACCOMMODATION_NOT_FOUND,
        ErrorCode.INVALID_TYPE_VALUE
    })
    @DeleteMapping("/v1/accommodations/{accommodationId}")
    ResponseEntity<ApiResponse<Void>> deleteAccommodation(@PathVariable Long accommodationId,
        @CurrentMemberId Long memberId);

    @Operation(operationId = "Accommodation.uploadAccommodationImages", summary = "숙소 이미지 업로드",
        description = """
            본인 소유의 삭제되지 않은 숙소에 multipart/form-data로 이미지를 추가합니다. 각 파일을 같은 images 필드명으로 전송합니다.
            JSON 등 다른 요청 Content-Type은 415/C011로 거절합니다.
            파일 파트의 Content-Type은 image/jpeg 또는 image/png여야 합니다. 파일당 최대 10MiB, 요청 전체 최대 20MiB입니다.
            images 누락은 400/C001, 빈 파일은 400/I007, 형식 오류는 400/I003입니다. 서버의 multipart 크기 제한 초과는 413/I009입니다.
            큰 요청은 웹서버가 응답 전달 전에 연결을 종료할 수도 있으므로 클라이언트에서도 전송 전 크기를 확인합니다.
            응답 data.uploaded_images는 이번에 추가한 이미지의 id·image_url입니다. 가장 작은 이미지 ID의 사진이 대표 이미지가 됩니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 완료", useReturnTypeSchema = true)
    @ApiErrors({
        ErrorCode.INVALID_TYPE_VALUE,
        ErrorCode.ACCOMMODATION_NOT_FOUND,
        ErrorCode.IMAGE_UPLOAD_FAILED,
        ErrorCode.INVALID_IMAGE_FORMAT,
        ErrorCode.EMPTY_IMAGE_FILE,
        ErrorCode.IMAGE_FILE_SIZE_EXCEEDED,
        ErrorCode.UNSUPPORTED_MEDIA_TYPE
    })
    @PostMapping(value = "/v1/accommodations/{accommodationId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiResponse<ImageResponse.ImageUploadResult>> uploadAccommodationImages(
        @PathVariable Long accommodationId,
        @RequestParam("images") List<MultipartFile> images,
        @CurrentMemberId Long memberId);

    @Operation(operationId = "Accommodation.deleteAccommodationImage", summary = "숙소 이미지 삭제",
        description = """
            본인 소유 숙소의 이미지를 삭제합니다. imageId는 이미지 조회 또는 업로드 응답의 id입니다.
            숙소 소유권이 없으면 404/A001, 이미지가 없으면 404/I004, 이미지가 다른 숙소에 속하면 400/C009입니다.
            대표 이미지를 삭제하면 남은 이미지 중 가장 작은 ID의 사진으로 바뀌며, 하나도 없으면 대표 이미지는 null입니다.
            마지막 이미지 삭제로 게시 상태가 자동 변경되지는 않습니다. 이후 게시 요청 시 최소 1장 조건을 다시 확인합니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
    @ApiErrors({
        ErrorCode.INVALID_TYPE_VALUE,
        ErrorCode.ACCOMMODATION_NOT_FOUND,
        ErrorCode.IMAGE_NOT_FOUND,
        ErrorCode.INVALID_INPUT
    })
    @DeleteMapping("/v1/accommodations/{accommodationId}/images/{imageId}")
    ResponseEntity<ApiResponse<Void>> deleteAccommodationImage(
        @PathVariable Long accommodationId,
        @PathVariable Long imageId,
        @CurrentMemberId Long memberId);

    @Operation(operationId = "Accommodation.getHostAccommodations", summary = "내 숙소 목록 조회",
        description = """
            본인 소유 숙소를 생성 시각 내림차순, 같은 시각이면 ID 내림차순으로 조회합니다.
            status 생략 시 DRAFT·PUBLISHED·UNPUBLISHED를 함께 조회합니다. 삭제된 숙소는 항상 제외하므로 DELETED 필터는 빈 목록을 반환합니다.
            첫 페이지는 cursor를 생략하고 다음 페이지부터 page_info.next_cursor를 사용합니다. status를 바꾸면 cursor를 초기화합니다.
            초안의 이름·유형·대표 이미지·주소 항목은 null일 수 있습니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
    @ApiErrors({
        ErrorCode.INVALID_TYPE_VALUE
    })
    @GetMapping("/v1/profile/host/accommodations")
    ResponseEntity<ApiResponse<AccommodationResponse.HostAccommodationInfos>> getHostAccommodations(
        @CursorParam CursorRequest.CursorPageRequest request,
        @Parameter(description = "숙소 상태 필터. DELETED는 빈 목록을 반환합니다.", example = "DRAFT")
        @RequestParam(required = false) AccommodationStatus status,
        @CurrentMemberId Long memberId);

    @Operation(operationId = "Accommodation.getHostAccommodationDetail", summary = "내 숙소 상세 조회",
        description = """
            본인 소유 숙소의 관리용 상세를 조회합니다. DRAFT·PUBLISHED·UNPUBLISHED·DELETED 모두 조회할 수 있습니다.
            존재하지 않거나 다른 회원의 숙소이면 404/A001입니다. 주소나 인원 정책이 없는 초안은 객체 내부 항목이 null이며 이미지·편의시설이 없으면 []입니다.
            공개 상세의 address_summary와 달리 전체 주소는 address에 담깁니다. 요청의 amenity_infos[].name에 대응하는 편의시설 코드는 응답 amenities[].type입니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
    @ApiErrors({
        ErrorCode.INVALID_TYPE_VALUE,
        ErrorCode.ACCOMMODATION_NOT_FOUND
    })
    @GetMapping("/v1/profile/host/accommodations/{accommodationId}")
    ResponseEntity<ApiResponse<AccommodationResponse.HostDetail>> getHostAccommodationDetail(
        @PathVariable Long accommodationId,
        @CurrentMemberId Long hostId);
}
