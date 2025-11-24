package kr.kro.airbob.search.service;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.GeoLocation;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeRelation;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;

import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonGeneratorFactory;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import kr.kro.airbob.search.document.AccommodationDocument;
import kr.kro.airbob.search.dto.AccommodationSearchRequest;
import kr.kro.airbob.search.dto.AccommodationSearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static kr.kro.airbob.geo.dto.GoogleGeocodeResponse.Geometry.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccommodationSearchService {

    private final ElasticsearchClient esClient;
    private final WishlistAccommodationRepository wishlistRepository;

    private static final String INDEX = "accommodationInfos";

    public AccommodationSearchResponse.AccommodationSearchInfos searchAccommodations(
            AccommodationSearchRequest.AccommodationSearchRequestDto req,
            AccommodationSearchRequest.MapBoundsDto mapBounds,
            Pageable pageable,
            Long memberId
    ) {

        if (!req.isValidOccupancy()) {
            req.setDefaultOccupancy();
        }

        Viewport viewport = determineViewport(mapBounds);
        if (viewport == null && !hasText(req.getDestination())) {
            return createEmpty(pageable);
        }

        Query query = buildQuery(req, viewport);

        /*//
        JsonGeneratorFactory factory = esClient._transport()
            .jsonpMapper()
            .jsonProvider()
            .createGeneratorFactory(null);

        StringWriter writer = new StringWriter();
        JsonGenerator generator = factory.createGenerator(writer);


        // query → JSON 으로 변환
        esClient._transport().jsonpMapper().serialize(query, generator);
        generator.close(); // flush & close

        System.out.println("es query:");
        System.out.println(writer.toString());
        //*/

        SearchRequest searchReq = new SearchRequest.Builder()
                .index(INDEX)
                .query(query)
                .from((int) pageable.getOffset())
                .size(pageable.getPageSize())
                .build();

        SearchResponse<AccommodationDocument> res;
        try {
            res = esClient.search(searchReq, AccommodationDocument.class);
        } catch (IOException e) {
            log.error("ES search failed", e);
            return createEmpty(pageable);
        }

        List<AccommodationDocument> docs = res.hits().hits()
                .stream()
                .map(Hit::source)
                .filter(Objects::nonNull)
                .toList();

        if (docs.isEmpty()) return createEmpty(pageable);

        List<Long> ids = docs.stream().map(AccommodationDocument::accommodationId).toList();
        Set<Long> wishlistIds = getWishlist(ids, memberId);

        List<AccommodationSearchResponse.AccommodationSearchInfo> list =
                docs.stream()
                        .map(d -> AccommodationSearchResponse.AccommodationSearchInfo.from(
                                d,
                                wishlistIds.contains(d.accommodationId())
                        ))
                        .toList();

        long total = res.hits().total() != null ? res.hits().total().value() : docs.size();

        return AccommodationSearchResponse.AccommodationSearchInfos.builder()
                .staySearchResultListing(list)
                .pageInfo(pageInfo(pageable, total))
                .build();
    }

    private Query buildQuery(
            AccommodationSearchRequest.AccommodationSearchRequestDto req,
            Viewport viewport
    ) {

        BoolQuery.Builder b = new BoolQuery.Builder();

        b.filter(f -> f.term(t -> t
                .field("status")
                .value(AccommodationStatus.PUBLISHED.name())
        ));

        if (viewport != null) {
            b.filter(f -> f.geoBoundingBox(g -> g
                .field("location")
                .boundingBox(bb -> bb
                    .tlbr(t -> t
                        .topLeft(GeoLocation.of(gl -> gl.latlon(ll ->
                            ll.lat(viewport.northeast().lat())
                                .lon(viewport.southwest().lng())
                        )))
                        .bottomRight(GeoLocation.of(gl -> gl.latlon(ll ->
                            ll.lat(viewport.southwest().lat())
                                .lon(viewport.northeast().lng())
                        )))
                    )
                )
            ));
        }

        if (hasText(req.getDestination())) {
            b.must(m -> m.multiMatch(mm -> mm
                    .query(req.getDestination())
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

        //country, city, district, street, addressDetail
        if (req.getMinPrice() != null || req.getMaxPrice() != null) {
            b.filter(f -> f.range(r -> r
                .number(n -> {
                    n.field("basePrice");
                    if (req.getMinPrice() != null)
                        n.gte(req.getMinPrice().doubleValue());
                    if (req.getMaxPrice() != null)
                        n.lte(req.getMaxPrice().doubleValue());
                    return n;
                })
            ));
        }

        // 원본
        if (req.getTotalGuests() > 0) {
            b.filter(f -> f.range(r -> r
                .number(n -> n
                    .field("maxGuests")
                    .gte(req.getTotalGuests())   // Integer → Double
                )
            ));
        }

        if (req.getInfantOccupancy() != null && req.getInfantOccupancy() > 0) {
            b.filter(f -> f.range(r -> r
                .number(n -> n
                    .field("maxInfants")
                    .gte(req.getInfantOccupancy().doubleValue())
                )
            ));
        }

        if (req.hasPet()) {
            b.filter(f -> f.range(r -> r
                .number(n -> n
                    .field("maxPets")
                    .gte(req.getPetOccupancy().doubleValue())
                )
            ));
        }

        if (req.getCheckIn() != null && req.getCheckOut() != null) {
            b.mustNot(mn -> mn.range(r -> r
                .date(d -> d
                    .field("reservationRanges")
                    .gte(req.getCheckIn().toString())
                    .lt(req.getCheckOut().toString())
                    .relation(RangeRelation.Intersects)
                )
            ));
        }

        // return Query.of(q -> q.bool(b.build()));
        return new Query.Builder()
            .bool(b.build())
            .build();
    }

    private Viewport determineViewport(AccommodationSearchRequest.MapBoundsDto bounds) {
        if (bounds.hasAllBounds()) {
            Location ne = new Location(bounds.getTopLeftLat(), bounds.getBottomRightLng());
            Location sw = new Location(bounds.getBottomRightLat(), bounds.getTopLeftLng());
            return new Viewport(ne, sw);
        }
        return null;
    }

    private boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private Set<Long> getWishlist(List<Long> ids, Long memberId) {
        if (memberId == null) return Set.of();
        return wishlistRepository.findAccommodationIdsByMemberIdAndAccommodationIds(memberId, ids);
    }

    private AccommodationSearchResponse.AccommodationSearchInfos createEmpty(Pageable pageable) {
        return AccommodationSearchResponse.AccommodationSearchInfos.builder()
                .staySearchResultListing(List.of())
                .pageInfo(AccommodationSearchResponse.PageInfo.fail(
                        pageable.getPageSize(),
                        pageable.getPageNumber()))
                .build();
    }

    private AccommodationSearchResponse.PageInfo pageInfo(Pageable pageable, long total) {
        int size = pageable.getPageSize();
        int page = pageable.getPageNumber();
        int pages = (int) Math.ceil((double) total / size);

        return AccommodationSearchResponse.PageInfo.builder()
                .pageSize(size)
                .currentPage(page)
                .totalPages(pages)
                .totalElements(total)
                .isFirst(page == 0)
                .isLast(page >= pages - 1)
                .hasNext(page < pages - 1)
                .hasPrevious(page > 0)
                .build();
    }
}
