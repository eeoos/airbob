package kr.kro.airbob.search.api;

import static kr.kro.airbob.search.dto.AccommodationSearchRequest.DEFAULT_PAGE_SIZE;
import static kr.kro.airbob.search.dto.AccommodationSearchRequest.MAX_PAGE_NUMBER;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.search.dto.AccommodationSearchRequest;
import kr.kro.airbob.search.dto.AccommodationSearchResponse;
import kr.kro.airbob.search.service.AccommodationSearchService;
import lombok.RequiredArgsConstructor;

@Tag(name = "검색")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class AccommodationSearchController {

	private final AccommodationSearchService accommodationSearchService;

	@Operation(operationId = "AccommodationSearch.searchAccommodations", summary = "숙소 검색",
		description = """
			로그인 없이 목적지·지도 범위·가격·날짜·인원으로 숙소를 검색합니다. 목적지 또는 지도 좌표 4개가 필요하며 둘 다 없으면 200과 빈 목록입니다. 목적지와 지도를 함께 입력하면 두 조건을 모두 적용합니다.

			편의시설(amenityTypes)·숙소 유형(accommodationTypes)은 현재 미지원으로 입력해도 무시합니다. 쿼리는 camelCase, 응답은 snake_case입니다. 검색 엔진의 기본 관련도 순서이며 sort 입력은 적용하지 않습니다.

			페이지는 0~14, 크기는 18로 고정입니다. 다음 페이지 여부는 has_next로 판단합니다. total_elements는 검색 엔진의 기본 집계 한도(10,000건)를 넘으면 하한값일 수 있으며 total_pages는 접근 가능한 페이지 수보다 클 수 있습니다.

			유효한 세션이 있으면 본인의 활성 위시리스트 저장 여부를 반영합니다. 세션이 없거나 만료되면 is_in_wishlist는 false입니다. 검색 색인은 비동기로 갱신되므로 예약 가능 확정이 아니며, 예약 체크아웃에서 현재 가격과 재고를 다시 검증합니다.
			""",
		parameters = {
			@Parameter(name = "sort", hidden = true),
			@Parameter(name = "page", description = "0부터 시작하는 페이지(기본 0). 음수·정수 해석 실패는 0, 해석 가능한 14 초과 값은 14로 보정합니다."),
			@Parameter(name = "size", description = "항상 18로 보정합니다.")
		})
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "검색 결과와 페이지 정보. 조건이 없거나 일치하는 숙소가 없으면 빈 배열", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.SEARCH_UNAVAILABLE
	})
	@GetMapping("/v1/search/accommodations")
	public ResponseEntity<ApiResponse<AccommodationSearchResponse.AccommodationSearchInfos>> searchAccommodations(
		@ParameterObject @Valid @ModelAttribute AccommodationSearchRequest.MapBoundsDto mapBounds,
		@ParameterObject @Valid @ModelAttribute AccommodationSearchRequest.AccommodationSearchRequestDto searchRequest,
		@ParameterObject @PageableDefault(size = DEFAULT_PAGE_SIZE, page = 0) Pageable pageable,
		@CurrentMemberId(required = false) Long memberId) {

		if (pageable.getPageNumber() > MAX_PAGE_NUMBER) {
			pageable = PageRequest.of(MAX_PAGE_NUMBER, DEFAULT_PAGE_SIZE);
		}

		if (pageable.getPageSize() != DEFAULT_PAGE_SIZE) {
			pageable = PageRequest.of(pageable.getPageNumber(), DEFAULT_PAGE_SIZE);
		}

		AccommodationSearchResponse.AccommodationSearchInfos infos =
			accommodationSearchService.searchAccommodations(searchRequest, mapBounds, pageable, memberId);

		return ResponseEntity.ok(ApiResponse.success(infos));
	}
}
