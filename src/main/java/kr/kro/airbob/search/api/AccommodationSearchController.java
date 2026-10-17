package kr.kro.airbob.search.api;

import static kr.kro.airbob.search.dto.AccommodationSearchRequest.DEFAULT_PAGE_SIZE;
import static kr.kro.airbob.search.dto.AccommodationSearchRequest.MAX_PAGE_NUMBER;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.search.dto.AccommodationSearchRequest;
import kr.kro.airbob.search.dto.AccommodationSearchResponse;
import kr.kro.airbob.search.service.AccommodationSearchService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class AccommodationSearchController implements AccommodationSearchApi {

	private final AccommodationSearchService accommodationSearchService;

	@Override
	public ResponseEntity<ApiResponse<AccommodationSearchResponse.AccommodationSearchInfos>> searchAccommodations(
		AccommodationSearchRequest.MapBoundsDto mapBounds,
		AccommodationSearchRequest.AccommodationSearchRequestDto searchRequest,
		Pageable pageable,
		Long memberId) {

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
