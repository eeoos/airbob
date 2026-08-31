package kr.kro.airbob.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeRelation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;

import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import kr.kro.airbob.search.dto.AccommodationSearchRequest;
import kr.kro.airbob.search.document.AccommodationDocument;
import kr.kro.airbob.search.exception.SearchUnavailableException;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 검색 서비스 테스트")
class AccommodationSearchServiceTest {

	@Mock
	private ElasticsearchClient esClient;

	@Mock
	private WishlistAccommodationRepository wishlistRepository;

	@Mock
	private BookingWindowProvider bookingWindowProvider;

	@InjectMocks
	private AccommodationSearchService accommodationSearchService;

	@Test
	@DisplayName("어느 숙소 현지 3개월 예약 창에도 들지 않는 날짜는 빈 결과를 반환한다")
	void returnsEmptyWhenNoTimeZoneCanBookStay() {
		AccommodationSearchRequest.AccommodationSearchRequestDto request = dateRequest(
			LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13));
		request.setDestination("Seoul");
		given(bookingWindowProvider.eligibleTimeZonesForStay(
			request.getCheckIn(), request.getCheckOut())).willReturn(Set.of());

		var result = accommodationSearchService.searchAccommodations(
			request,
			new AccommodationSearchRequest.MapBoundsDto(),
			PageRequest.of(0, 18),
			null
		);

		assertThat(result.staySearchResultListing()).isEmpty();
		assertThat(result.pageInfo().totalElements()).isZero();
		verifyNoInteractions(esClient);
	}

	@Test
	@DisplayName("날짜 검색은 숙박일이 현지 3개월 창 안에 있는 시간대의 숙소만 대상으로 한다")
	void filtersDateSearchByEligibleAccommodationTimeZones() {
		AccommodationSearchRequest.AccommodationSearchRequestDto request = dateRequest(
			LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 13));

		Query query = accommodationSearchService.buildQuery(
			request, null, Set.of("Asia/Seoul", "Pacific/Auckland"));

		assertThat(query.bool().filter())
			.anySatisfy(filter -> {
				assertThat(filter.isTerms()).isTrue();
				assertThat(filter.terms().field()).isEqualTo("timeZoneId");
				assertThat(filter.terms().terms().value())
					.extracting(value -> value.stringValue())
					.containsExactly("Asia/Seoul", "Pacific/Auckland");
			});
		assertThat(query.bool().mustNot())
			.anySatisfy(mustNot -> {
				assertThat(mustNot.isRange()).isTrue();
				assertThat(mustNot.range().date().field()).isEqualTo("reservationRanges");
				assertThat(mustNot.range().date().gte()).isEqualTo("2026-08-12");
				assertThat(mustNot.range().date().lt()).isEqualTo("2026-08-13");
				assertThat(mustNot.range().date().relation()).isEqualTo(RangeRelation.Intersects);
			});
	}

	@Test
	@DisplayName("Elasticsearch 연결 실패를 정상적인 빈 검색 결과로 숨기지 않는다")
	void throwsServiceUnavailableWhenElasticsearchCannotBeReached() throws IOException {
		AccommodationSearchRequest.AccommodationSearchRequestDto request =
			new AccommodationSearchRequest.AccommodationSearchRequestDto();
		request.setDestination("Seoul");
		willThrow(new IOException("connection refused"))
			.given(esClient).search(any(SearchRequest.class), eq(AccommodationDocument.class));

		assertThatThrownBy(() -> accommodationSearchService.searchAccommodations(
			request,
			new AccommodationSearchRequest.MapBoundsDto(),
			PageRequest.of(0, 18),
			null
		))
			.isInstanceOf(SearchUnavailableException.class);
		verifyNoInteractions(wishlistRepository);
	}

	@Test
	@DisplayName("Elasticsearch 오류 응답도 검색 서비스 장애로 명시한다")
	void throwsServiceUnavailableWhenElasticsearchRejectsSearch() throws IOException {
		AccommodationSearchRequest.AccommodationSearchRequestDto request =
			new AccommodationSearchRequest.AccommodationSearchRequestDto();
		request.setDestination("Seoul");
		willThrow(mock(ElasticsearchException.class))
			.given(esClient).search(any(SearchRequest.class), eq(AccommodationDocument.class));

		assertThatThrownBy(() -> accommodationSearchService.searchAccommodations(
			request,
			new AccommodationSearchRequest.MapBoundsDto(),
			PageRequest.of(0, 18),
			null
		))
			.isInstanceOf(SearchUnavailableException.class);
		verifyNoInteractions(wishlistRepository);
	}

	@Test
	@DisplayName("1만 건을 넘는 검색은 Elasticsearch 기본 상한을 유지한다")
	@SuppressWarnings("unchecked")
	void keepsDefaultTotalHitsBoundBeyondElasticsearchThreshold() throws IOException {
		AccommodationSearchRequest.AccommodationSearchRequestDto request =
			new AccommodationSearchRequest.AccommodationSearchRequestDto();
		request.setDestination("Seoul");
		AccommodationDocument document = AccommodationDocument.builder()
			.id("accommodation-1")
			.accommodationId(1L)
			.name("숙소")
			.basePrice(100_000L)
			.currency("KRW")
			.type("ENTIRE_PLACE")
			.location(AccommodationDocument.Location.builder().lat(37.5).lon(127.0).build())
			.averageRating(5.0)
			.reviewCount(10)
			.build();
		Hit<AccommodationDocument> hit = mock(Hit.class);
		HitsMetadata<AccommodationDocument> hits = mock(HitsMetadata.class);
		SearchResponse<AccommodationDocument> response = mock(SearchResponse.class);
		given(hit.source()).willReturn(document);
		given(hits.hits()).willReturn(List.of(hit));
		given(hits.total()).willReturn(TotalHits.of(total -> total
			.value(10_000L)
			.relation(TotalHitsRelation.Gte)));
		given(response.hits()).willReturn(hits);
		given(esClient.search(any(SearchRequest.class), eq(AccommodationDocument.class)))
			.willReturn(response);

		var result = accommodationSearchService.searchAccommodations(
			request,
			new AccommodationSearchRequest.MapBoundsDto(),
			PageRequest.of(0, 18),
			null
		);

		ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(esClient).search(requestCaptor.capture(), eq(AccommodationDocument.class));
		assertThat(requestCaptor.getValue().trackTotalHits()).isNull();
		assertThat(result.pageInfo().totalElements()).isEqualTo(10_000L);
		assertThat(result.pageInfo().totalPages()).isEqualTo(556);
	}

	private AccommodationSearchRequest.AccommodationSearchRequestDto dateRequest(
		LocalDate checkIn,
		LocalDate checkOut
	) {
		AccommodationSearchRequest.AccommodationSearchRequestDto request =
			new AccommodationSearchRequest.AccommodationSearchRequestDto();
		request.setCheckIn(checkIn);
		request.setCheckOut(checkOut);
		return request;
	}
}
