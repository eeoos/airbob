package kr.kro.airbob.search.service;

import static kr.kro.airbob.geo.dto.GoogleGeocodeResponse.Geometry.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.geo.GeoBox;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Service;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import kr.kro.airbob.geo.GeocodingService;
import kr.kro.airbob.geo.IpCountryService;
import kr.kro.airbob.geo.ViewportAdjuster;
import kr.kro.airbob.geo.dto.GeocodeResult;
import kr.kro.airbob.search.document.AccommodationDocument;
import kr.kro.airbob.search.dto.AccommodationSearchRequest;
import kr.kro.airbob.search.dto.AccommodationSearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccommodationSearchService {

	private final GeocodingService geocodingService;
	private final IpCountryService ipCountryService;
	private final ViewportAdjuster viewportAdjuster;
	private final ElasticsearchOperations elasticsearchOperations;
	private final WishlistAccommodationRepository wishlistAccommodationRepository;

	public AccommodationSearchResponse.AccommodationSearchInfos searchAccommodations(
		AccommodationSearchRequest.AccommodationSearchRequestDto searchRequest, String clientIp,
		AccommodationSearchRequest.MapBoundsDto mapBounds, Pageable pageable, Long memberId) {

		// 요청 검증
		if (!validateSearchRequest(searchRequest)) {
			return createEmptySearchResult(pageable);
		}

		// 인원이 유효하지 않으면 기본값 (성인:1)
		if (!searchRequest.isValidOccupancy()) {
			searchRequest.setDefaultOccupancy();
		}

		Viewport viewport = determineViewport(searchRequest, clientIp, mapBounds);
		if (viewport == null) {
			return createEmptySearchResult(pageable);
		}

		CriteriaQuery query = buildElasticsearchQuery(searchRequest, viewport, pageable);

		SearchHits<AccommodationDocument> searchHits = elasticsearchOperations.search(query, AccommodationDocument.class);

		List<AccommodationDocument> documents = searchHits.getSearchHits()
			.stream()
			.map(SearchHit::getContent)
			.toList();

		if (documents.isEmpty()) {
			return createEmptySearchResult(pageable);
		}

		List<Long> accommodationIds = documents.stream().map(AccommodationDocument::accommodationId).toList();
		Set<Long> wishlistAccommodationIds = getWishlistAccommodationIds(accommodationIds, memberId);

		List<AccommodationSearchResponse.AccommodationSearchInfo> searchInfos = documents.stream()
			.map(doc -> AccommodationSearchResponse.AccommodationSearchInfo.from(doc,
				wishlistAccommodationIds.contains(doc.accommodationId()))).toList();

		AccommodationSearchResponse.PageInfo pageInfo = calculatePageInfo(pageable, searchHits.getTotalHits());

		return AccommodationSearchResponse.AccommodationSearchInfos.builder()
			.staySearchResultListing(searchInfos)
			.pageInfo(pageInfo)
			.build();
	}

	private boolean validateSearchRequest(AccommodationSearchRequest.AccommodationSearchRequestDto searchRequest) {

		// 가격 범위 검증
		if (!searchRequest.isValidPriceRange()) {
			log.warn("유효하지 않은 가격 범위: minPrice={}, maxPrice={}",
				searchRequest.getMinPrice(), searchRequest.getMaxPrice());
			return false;
		}

		// 체크인/체크아웃 날짜 검증
		if (searchRequest.getCheckIn() != null && searchRequest.getCheckOut() != null) {
			if (searchRequest.getCheckIn().isAfter(searchRequest.getCheckOut())) {
				log.warn("Check-in 날짜 {}는 check-out 날짜 {}보다 이전이여야 합니다.",
					searchRequest.getCheckIn(), searchRequest.getCheckOut());
				return false;
			}
		}

		return true;
	}

	private AccommodationSearchResponse.AccommodationSearchInfos createEmptySearchResult(Pageable pageable) {
		return AccommodationSearchResponse.AccommodationSearchInfos.builder()
			.staySearchResultListing(List.of())
			.pageInfo(AccommodationSearchResponse.PageInfo.fail(pageable.getPageSize(), pageable.getPageNumber()))
			.build();
	}

	private AccommodationSearchResponse.PageInfo calculatePageInfo(Pageable pageable, long hitCounts) {
		int totalPages = (int)Math.ceil((double)hitCounts / pageable.getPageSize());
		boolean hasNext = pageable.getPageNumber() < totalPages - 1;
		boolean hasPrevious = pageable.getPageNumber() > 0;
		boolean isFirst = pageable.getPageNumber() == 0;
		boolean isLast = pageable.getPageNumber() >= totalPages - 1;

		return AccommodationSearchResponse.PageInfo.builder()
			.pageSize(pageable.getPageSize())
			.currentPage(pageable.getPageNumber())
			.totalPages(totalPages)
			.totalElements(hitCounts)
			.isFirst(isFirst)
			.isLast(isLast)
			.hasNext(hasNext)
			.hasPrevious(hasPrevious)
			.build();
	}

	private Viewport determineViewport(
		AccommodationSearchRequest.AccommodationSearchRequestDto searchRequest, String clientIp, AccommodationSearchRequest.MapBoundsDto mapBounds) {


		// 지도 드래그
		if (mapBounds.isValid()) {
			return createViewportFromDragArea(mapBounds);
		}

		// 여행지 입력
		if (searchRequest.getDestination() != null && !searchRequest.getDestination().trim().isEmpty()) {
			GeocodeResult geocodeResult = geocodingService.getCoordinates(searchRequest.getDestination());

			if (geocodeResult.success() && geocodeResult.viewport() != null) {
				return viewportAdjuster.adjustViewportIfSmall(geocodeResult.viewport());
			} else if (geocodeResult.success()) {
				return viewportAdjuster.createViewportFromCenter(geocodeResult.latitude(), geocodeResult.longitude());
			}
		}

		// ip 기반 국가 조회
		return getViewportFromIpCountry(clientIp);
	}

	private Viewport getViewportFromIpCountry(String clientIp) {
		try {
			Optional<GeocodeResult> countryResult = ipCountryService.getCountryFromIp(clientIp);
			if (countryResult.isPresent()) {
				GeocodeResult result = countryResult.get();
				GeocodeResult countryGeocode = geocodingService.getCoordinates(result.formattedAddress());
				if (countryGeocode.success() && countryGeocode.viewport() != null) {
					return viewportAdjuster.adjustViewportIfSmall(countryGeocode.viewport()); // 국가 단위도 조정 검증을 해야할까?
				}
			}
		} catch (Exception e) {
			log.warn("IP 기반 국가 정보 조회 실패: {}", e.getMessage());
		}

		return null; // 최종 fallback인 ip 기반 국가 조회 실패 시 null 반환
	}

	private Viewport createViewportFromDragArea(AccommodationSearchRequest.MapBoundsDto mapBounds) {
		Location northeast = new Location(mapBounds.getTopLeftLat(), mapBounds.getBottomRightLng());// 북동
		Location southwest = new Location(mapBounds.getBottomRightLat(), mapBounds.getTopLeftLng());// 남서

		return new Viewport(northeast, southwest);
	}

	private CriteriaQuery buildElasticsearchQuery(
		AccommodationSearchRequest.AccommodationSearchRequestDto searchRequest, Viewport viewport, Pageable pageable) {

		Criteria criteria = new Criteria();

		criteria = criteria.and(Criteria.where("status").is(AccommodationStatus.PUBLISHED.name()));

		GeoPoint topLeft = new GeoPoint(viewport.northeast().lat(), viewport.southwest().lng()); // 북동
		GeoPoint bottomRight = new GeoPoint(viewport.southwest().lat(), viewport.northeast().lng()); // 남서

		GeoBox boundingBox = new GeoBox(topLeft, bottomRight);

		// 지리
		criteria = criteria.and(Criteria.where("location").boundedBy(boundingBox));

		// 가격
		if (searchRequest.getMinPrice() != null) {
			criteria = criteria.and(Criteria.where("basePrice").greaterThanEqual(searchRequest.getMinPrice()));
		}
		if (searchRequest.getMaxPrice() != null) {
			criteria = criteria.and(Criteria.where("basePrice").lessThanEqual(searchRequest.getMaxPrice()));
		}

		// 타입
		if (searchRequest.getAccommodationTypes() != null && !searchRequest.getAccommodationTypes().isEmpty()) {
			criteria = criteria.and(Criteria.where("type").in(searchRequest.getAccommodationTypes()));
		}

		// 편의 시설
		if (searchRequest.getAmenityTypes() != null && !searchRequest.getAmenityTypes().isEmpty()) {
			criteria = criteria.and(Criteria.where("amenityTypes").in(searchRequest.getAmenityTypes()));
		}

		// 반려동물 동반
		if (searchRequest.hasPet()) {
			criteria = criteria.and(Criteria.where("petOccupancy").greaterThanEqual(searchRequest.getPetOccupancy()));
		}

		// 인원
		int totalGuests = searchRequest.getTotalGuests();
		if (totalGuests > 0) {
			criteria = criteria.and(Criteria.where("maxOccupancy").greaterThanEqual(totalGuests));
		}

		// 예약 가능 날짜
		if (searchRequest.getCheckIn() != null && searchRequest.getCheckOut() != null) {
			criteria = criteria.and(
				Criteria.where("reservedDates").not().in(
					searchRequest.getCheckIn().datesUntil(searchRequest.getCheckOut())
				)
			);
		}

		return CriteriaQuery.builder(criteria)
			.withPageable(pageable)
			.build();
	}

	private Set<Long> getWishlistAccommodationIds(List<Long> accommodationIds, Long memberId) {
		if (memberId == null) {
			return Set.of();
		}

		return wishlistAccommodationRepository.findAccommodationIdsByMemberIdAndAccommodationIds(memberId,
			accommodationIds);
	}
}
