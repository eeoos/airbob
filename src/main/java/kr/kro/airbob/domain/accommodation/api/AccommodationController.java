package kr.kro.airbob.domain.accommodation.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.service.AccommodationQueryService;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import lombok.RequiredArgsConstructor;

@Tag(name = "숙소")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class AccommodationController {

    private final AccommodationQueryService accommodationQueryService;

    @Operation(operationId = "Accommodation.getAccommodation", summary = "숙소 상세 조회",
        description = """
            게시된 숙소의 공개 상세를 조회합니다. 로그인은 선택 사항이며 비로그인 요청의 is_in_wishlist는 false입니다.
            로그인하면 현재 회원의 찜 여부를 포함합니다. 체크인·체크아웃 시간은 time_zone_id에 따른 숙소 현지 시간입니다.
            예약 가능 날짜는 별도의 availability API에서 조회합니다. 게시 상태 변경은 캐시 갱신까지 잠시 지연될 수 있습니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
    @ApiErrors({
        ErrorCode.ACCOMMODATION_NOT_FOUND,
        ErrorCode.INVALID_TYPE_VALUE
    })
    @GetMapping("/v1/accommodations/{accommodationId}")
    public ResponseEntity<ApiResponse<AccommodationResponse.DetailInfo>> getAccommodation(
        @PathVariable Long accommodationId,
        @CurrentMemberId(required = false) Long viewerId) {
        AccommodationResponse.DetailInfo response = accommodationQueryService.findAccommodation(accommodationId,
            viewerId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(operationId = "Accommodation.getAccommodationAvailability", summary = "예약 가능 정보 조회",
        description = """
            로그인 없이 게시 숙소의 예약 달력을 조회합니다. 날짜 입력은 받지 않으며 숙소 현지 오늘부터 3개월 뒤까지의 구간을 반환합니다.
            booking_window_start_inclusive는 포함, booking_window_end_exclusive는 제외하는 숙박 날짜 경계입니다.
            unavailable_ranges에는 점유 중이거나 아직 만료되지 않은 임시 확보 구간을 반환하며 각 end_date_exclusive의 밤은 포함하지 않습니다.
            조회가 재고를 확보하지는 않습니다. 실제 예약 가능 여부는 체크아웃에서 다시 확인합니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
    @ApiErrors({
        ErrorCode.ACCOMMODATION_NOT_FOUND,
        ErrorCode.INVALID_TYPE_VALUE
    })
    @GetMapping("/v1/accommodations/{accommodationId}/availability")
    public ResponseEntity<ApiResponse<AccommodationResponse.Availability>> getAccommodationAvailability(
        @PathVariable Long accommodationId) {
        AccommodationResponse.Availability response =
            accommodationQueryService.findAccommodationAvailability(accommodationId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
