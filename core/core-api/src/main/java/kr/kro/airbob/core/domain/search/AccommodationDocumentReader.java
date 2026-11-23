package kr.kro.airbob.core.domain.search;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.GeoLocation;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeRelation;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import kr.kro.airbob.core.domain.accommodation.AccommodationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 숙소 색인 검색 구현 컴포넌트. 검색 엔진 장애는 빈 결과가 아니라 {@link SearchUnavailableException}으로 알린다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccommodationDocumentReader {

	private static final String INDEX = "accommodations";

	private final ElasticsearchClient esClient;

	public AccommodationDocumentHits search(
		AccommodationSearchCondition condition,
		Set<String> eligibleTimeZones,
		Pageable pageable
	) {
		SearchRequest searchReq = new SearchRequest.Builder()
			.index(INDEX)
			.query(buildQuery(condition, eligibleTimeZones))
			.from((int)pageable.getOffset())
			.size(pageable.getPageSize())
			.build();

		SearchResponse<AccommodationDocument> res;
		try {
			res = esClient.search(searchReq, AccommodationDocument.class);
		} catch (IOException | ElasticsearchException e) {
			log.error("ES search failed", e);
			throw new SearchUnavailableException(e);
		}

		List<AccommodationDocument> docs = res.hits().hits()
			.stream()
			.map(Hit::source)
			.filter(Objects::nonNull)
			.toList();

		long total = res.hits().total() != null ? res.hits().total().value() : docs.size();
		return new AccommodationDocumentHits(docs, total);
	}

	Query buildQuery(AccommodationSearchCondition condition, Set<String> eligibleTimeZones) {

		BoolQuery.Builder b = new BoolQuery.Builder();

		b.filter(f -> f.term(t -> t
			.field("status")
			.value(AccommodationStatus.PUBLISHED.name())
		));

		AccommodationSearchCondition.MapArea area = condition.mapArea();
		if (area != null) {
			b.filter(f -> f.geoBoundingBox(g -> g
				.field("location")
				.boundingBox(bb -> bb
					.tlbr(t -> t
						.topLeft(GeoLocation.of(gl -> gl.latlon(ll ->
							ll.lat(area.topLeftLat())
								.lon(area.topLeftLng())
						)))
						.bottomRight(GeoLocation.of(gl -> gl.latlon(ll ->
							ll.lat(area.bottomRightLat())
								.lon(area.bottomRightLng())
						)))
					)
				)
			));
		}

		if (condition.hasDestination()) {
			b.must(m -> m.multiMatch(mm -> mm
				.query(condition.destination())
				.fields(
					"city^4",
					"district^3",
					"street^2",
					"addressDetail^1.5",
					"country",
					"name^2",
					"description"
				)
			));
		}

		if (condition.minPrice() != null || condition.maxPrice() != null) {
			b.filter(f -> f.range(r -> r
				.number(n -> {
					n.field("basePrice");
					if (condition.minPrice() != null)
						n.gte(condition.minPrice().doubleValue());
					if (condition.maxPrice() != null)
						n.lte(condition.maxPrice().doubleValue());
					return n;
				})
			));
		}

		if (condition.totalGuests() > 0) {
			b.filter(f -> f.range(r -> r
				.number(n -> n
					.field("maxGuests")
					.gte(condition.totalGuests())
				)
			));
		}

		if (condition.infantOccupancy() != null && condition.infantOccupancy() > 0) {
			b.filter(f -> f.range(r -> r
				.number(n -> n
					.field("maxInfants")
					.gte(condition.infantOccupancy().doubleValue())
				)
			));
		}

		if (condition.hasPet()) {
			b.filter(f -> f.range(r -> r
				.number(n -> n
					.field("maxPets")
					.gte(condition.petOccupancy().doubleValue())
				)
			));
		}

		if (condition.hasDateRange()) {
			List<FieldValue> timeZoneValues = eligibleTimeZones.stream()
				.sorted()
				.map(FieldValue::of)
				.toList();
			b.filter(f -> f.terms(t -> t
				.field("timeZoneId")
				.terms(values -> values.value(timeZoneValues))
			));
			b.mustNot(mn -> mn.range(r -> r
				.date(d -> d
					.field("reservationRanges")
					.gte(condition.checkIn().toString())
					.lt(condition.checkOut().toString())
					.relation(RangeRelation.Intersects)
				)
			));
		}

		return new Query.Builder()
			.bool(b.build())
			.build();
	}
}
