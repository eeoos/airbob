package kr.kro.airbob.search.service;

import static kr.kro.airbob.geo.dto.GoogleGeocodeResponse.Geometry.*;

import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.geo.GeoBox;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import kr.kro.airbob.geo.GeocodingService;
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
	// private final IpCountryService ipCountryService;
	private final ViewportAdjuster viewportAdjuster;
	private final ElasticsearchOperations elasticsearchOperations;
	private final WishlistAccommodationRepository wishlistAccommodationRepository;

	public AccommodationSearchResponse.AccommodationSearchInfos searchAccommodations(
		AccommodationSearchRequest.AccommodationSearchRequestDto searchRequest,
		AccommodationSearchRequest.MapBoundsDto mapBounds, Pageable pageable, Long memberId) {

		// 인원이 유효하지 않으면 기본값 (성인:1)
		if (!searchRequest.isValidOccupancy()) {
			searchRequest.setDefaultOccupancy();
		}

		Viewport viewport = determineViewport(searchRequest, mapBounds);
		if (viewport == null) {
			return createEmptySearchResult(pageable);
		}

		Query query = buildElasticsearchQuery(searchRequest, viewport, pageable);

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
		AccommodationSearchRequest.AccommodationSearchRequestDto searchRequest, AccommodationSearchRequest.MapBoundsDto mapBounds) {


		// 지도 드래그
		if (mapBounds.hasAllBounds()) {
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

		return null;
	}

	private Viewport createViewportFromDragArea(AccommodationSearchRequest.MapBoundsDto mapBounds) {
		Location northeast = new Location(mapBounds.getTopLeftLat(), mapBounds.getBottomRightLng());// 북동
		Location southwest = new Location(mapBounds.getBottomRightLat(), mapBounds.getTopLeftLng());// 남서

		return new Viewport(northeast, southwest);
	}

	private Query buildElasticsearchQuery(
		AccommodationSearchRequest.AccommodationSearchRequestDto searchRequest, Viewport viewport, Pageable pageable) {

		JSONObject query = new JSONObject();
		JSONObject bool = new JSONObject();
		JSONArray must = new JSONArray();
		JSONArray mustNot = new JSONArray();

		// PUBLISHED 상태 숙소만
		must.put(new JSONObject().put("term", new JSONObject().put("status", AccommodationStatus.PUBLISHED.name())));

		// 지리 (Viewport)
		if (viewport != null) {
			GeoPoint topLeft = new GeoPoint(viewport.northeast().lat(), viewport.southwest().lng());
			GeoPoint bottomRight = new GeoPoint(viewport.southwest().lat(), viewport.northeast().lng());

			must.put(new JSONObject().put("geo_bounding_box", new JSONObject()
				.put("location", new JSONObject()
					.put("top_left", new JSONObject().put("lat", topLeft.getLat()).put("lon", topLeft.getLon()))
					.put("bottom_right", new JSONObject().put("lat", bottomRight.getLat()).put("lon", bottomRight.getLon()))
				)
			));
		}

		// 텍스트 (Destination)
		String destination = searchRequest.getDestination();
		if (destination != null && !destination.trim().isEmpty()) {
			must.put(new JSONObject().put("multi_match", new JSONObject()
				.put("query", destination)
				.put("fields", new JSONArray().put("name").put("description").put("country").put("city").put("district").put("street"))
			));
		}

		// 가격
		JSONObject priceRange = new JSONObject();
		if (searchRequest.getMinPrice() != null) {
			priceRange.put("gte", searchRequest.getMinPrice());
		}
		if (searchRequest.getMaxPrice() != null) {
			priceRange.put("lte", searchRequest.getMaxPrice());
		}
		if (priceRange.length() > 0) {
			must.put(new JSONObject().put("range", new JSONObject().put("basePrice", priceRange)));
		}

		// 숙소 타입
		if (searchRequest.getAccommodationTypes() != null && !searchRequest.getAccommodationTypes().isEmpty()) {
			must.put(new JSONObject().put("terms", new JSONObject().put("type", new JSONArray(searchRequest.getAccommodationTypes()))));
		}

		// 편의시설
		if (searchRequest.getAmenityTypes() != null && !searchRequest.getAmenityTypes().isEmpty()) {
			must.put(new JSONObject().put("terms", new JSONObject().put("amenityTypes", new JSONArray(searchRequest.getAmenityTypes()))));
		}

		// 인원
		if (searchRequest.getTotalGuests() > 0) {
			must.put(new JSONObject().put("range", new JSONObject().put("maxGuests", new JSONObject().put("gte", searchRequest.getTotalGuests()))));
		}
		if (searchRequest.getInfantOccupancy() != null && searchRequest.getInfantOccupancy() > 0) {
			must.put(new JSONObject().put("range", new JSONObject().put("maxInfants", new JSONObject().put("gte", searchRequest.getInfantOccupancy()))));
		}
		if (searchRequest.hasPet()) {
			must.put(new JSONObject().put("range", new JSONObject().put("maxPets", new JSONObject().put("gte", searchRequest.getPetOccupancy()))));
		}

		// 예약 가능 날짜
		if (searchRequest.getCheckIn() != null && searchRequest.getCheckOut() != null) {
			// 요청 범위 ∩ 예약된 범위 == EMPTY
			mustNot.put(new JSONObject().put("range", new JSONObject()
				.put("reservationRanges", new JSONObject()
					.put("gte", searchRequest.getCheckIn().toString())
					.put("lt", searchRequest.getCheckOut().toString())
					.put("relation", "INTERSECTS")
				)
			));
		}

		// 쿼리 조립
		bool.put("must", must);
		if (mustNot.length() > 0) {
			bool.put("must_not", mustNot);
		}

		JSONObject boolQuery = new JSONObject().put("bool", bool);

		return new StringQuery(boolQuery.toString()).setPageable(pageable);
	}

	private Set<Long> getWishlistAccommodationIds(List<Long> accommodationIds, Long memberId) {
		if (memberId == null) {
			return Set.of();
		}

		return wishlistAccommodationRepository.findAccommodationIdsByMemberIdAndAccommodationIds(memberId,
			accommodationIds);
	}
}
