package kr.kro.airbob.domain.recentlyviewed.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;

@Tag(name = "최근 본 숙소")
@RequestMapping("/api")
public interface RecentlyViewedApi {

	@Operation(operationId = "RecentlyViewed.addRecentlyViewed", summary = "최근 본 숙소 기록",
		description = "로그인 회원의 최근 본 숙소에 서버 현재 시각으로 기록합니다. 같은 숙소를 다시 기록하면 기존 항목의 시각을 갱신합니다. 회원별 최대 100개를 유지하며 초과하면 오래된 기록부터 제거합니다. 기록할 때마다 목록 전체의 만료가 7일 뒤로 연장됩니다. 숙소의 존재·공개 여부는 여기서 확인하지 않으며 조회 시 유효하지 않은 기록을 정리합니다. 요청 본문은 없습니다. 숙소 상세 조회만으로 자동 기록되지 않으므로 이 API를 별도로 호출합니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 완료", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_TYPE_VALUE
	})
	@PostMapping("/v1/members/recently-viewed/{accommodationId}")
	ResponseEntity<ApiResponse<Void>> addRecentlyViewed(
		@Parameter(description = "숙소 ID. 기록 ID나 위시리스트 연결 ID가 아닙니다.", example = "101")
		@PathVariable Long accommodationId,
		@CurrentMemberId Long memberId);

	@Operation(operationId = "RecentlyViewed.removeRecentlyViewed", summary = "최근 본 숙소 삭제",
		description = "로그인 회원의 기록에서 해당 숙소를 제거합니다. 숙소 자체나 다른 회원의 기록은 삭제하지 않습니다. 기록이 없거나 이미 삭제했어도 200이며 요청 본문은 없습니다. 남은 목록의 만료 시각은 연장하지 않습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 완료", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_TYPE_VALUE
	})
	@DeleteMapping("/v1/members/recently-viewed/{accommodationId}")
	ResponseEntity<ApiResponse<Void>> removeRecentlyViewed(
		@Parameter(description = "숙소 ID. 기록 ID나 위시리스트 연결 ID가 아닙니다.", example = "101")
		@PathVariable Long accommodationId,
		@CurrentMemberId Long memberId);

	@Operation(operationId = "RecentlyViewed.getRecentlyViewed", summary = "최근 본 숙소 조회",
		description = "로그인 회원이 기록한 숙소 중 현재 공개된 숙소를 최근 기록 시각 내림차순으로 최대 100개 반환합니다. 같은 밀리초의 기록은 숙소 ID 문자열 내림차순입니다. 페이지·커서 매개변수는 없습니다. 없는 숙소·초안·비공개·삭제된 숙소는 응답에서 제외하고 기록에서도 제거합니다. 숙소·주소·리뷰 요약·본인의 활성 위시리스트 저장 여부는 현재 값입니다. 리뷰가 없으면 0건·0점이며 주소가 없는 과거 숙소도 주소 필드가 null인 객체로 반환합니다. 빈 기록은 accommodations=[], total_count=0입니다. 조회는 기록 시각과 목록 만료 시각을 갱신하지 않습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 완료", useReturnTypeSchema = true)
	@GetMapping("/v1/members/recently-viewed")
	ResponseEntity<ApiResponse<AccommodationResponse.RecentlyViewedAccommodationInfos>> getRecentlyViewed(
		@CurrentMemberId Long memberId);
}
