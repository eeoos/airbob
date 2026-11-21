package kr.kro.airbob.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import kr.kro.airbob.search.document.AccommodationDocument;
import kr.kro.airbob.search.dto.AccommodationSearchRequest.AccommodationSearchRequestDto;
import kr.kro.airbob.search.dto.AccommodationSearchRequest.MapBoundsDto;
import kr.kro.airbob.search.dto.AccommodationSearchResponse.AccommodationSearchInfos;
import kr.kro.airbob.search.infrastructure.elasticsearch.ElasticsearchAccommodationIndexAliasBootstrap;

@Testcontainers
class AccommodationSearchContractIntegrationTest {

	// 배포와 같은 고정 버전·Nori 플러그인·매핑으로 실제 검색 연산을 검증한다.
	@Container
	private static final GenericContainer<?> ES = new GenericContainer<>(new ImageFromDockerfile()
		.withFileFromPath("Dockerfile", Path.of("docker/elasticsearch/Dockerfile")))
		.withEnv("discovery.type", "single-node")
		.withEnv("xpack.security.enabled", "false")
		.withEnv("xpack.ml.enabled", "false")
		.withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m")
		.withExposedPorts(9200)
		.waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200))
		.withStartupTimeout(Duration.ofMinutes(2));

	private static RestClientTransport transport;
	private static ElasticsearchClient client;
	private WishlistAccommodationRepository wishlists;
	private AccommodationSearchService service;

	@BeforeAll
	static void connect() {
		var rest = RestClient.builder(new HttpHost(ES.getHost(), ES.getMappedPort(9200), "http")).build();
		var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		transport = new RestClientTransport(rest, new JacksonJsonpMapper(mapper));
		client = new ElasticsearchClient(transport);
		new ElasticsearchAccommodationIndexAliasBootstrap(client).ensureReady();
	}

	@AfterAll
	static void disconnect() throws Exception {
		if (transport != null) transport.close();
	}

	@BeforeEach
	void fixture() throws Exception {
		client.deleteByQuery(request -> request.index("accommodations").query(query -> query.matchAll(all -> all)).refresh(true));
		wishlists = mock(WishlistAccommodationRepository.class);
		service = new AccommodationSearchService(client, wishlists,
			new BookingWindowProvider(Clock.fixed(Instant.parse("2027-01-10T03:00:00Z"), ZoneOffset.UTC)));
		index(List.of(
			card(1, "서울", 100_000, 2, 1, 0, 37.5, 127.0, "PUBLISHED", "APT", List.of("WIFI"),
				List.of(AccommodationDocument.DateRange.builder().gte(LocalDate.parse("2027-01-11")).lt(LocalDate.parse("2027-01-13")).build())),
			card(2, "서울", 200_000, 4, 2, 1, 37.6, 127.1, "PUBLISHED", "HOUSE", List.of("PARKING"), List.of()),
			card(3, "부산", 150_000, 6, 0, 2, 35.1, 129.0, "PUBLISHED", "HOUSE", List.of(), List.of()),
			card(4, "서울", 100_000, 4, 2, 1, 37.5, 127.0, "DRAFT", "APT", List.of(), List.of()),
			card(5, "태평양", 100_000, 2, 0, 0, 0.0, 175.0, "PUBLISHED", "HOUSE", List.of(), List.of()),
			card(6, "태평양", 100_000, 2, 0, 0, 0.0, -175.0, "PUBLISHED", "HOUSE", List.of(), List.of())));
	}

	@Test
	void destinationOrCompleteMapBoundsAreNeededEvenWhenOtherFiltersExist() {
		var request = request(null);
		request.setMinPrice(0);
		assertThat(search(request, new MapBoundsDto(), 0, null).staySearchResultListing()).isEmpty();
		request.setDestination("  ");
		assertThat(search(request, new MapBoundsDto(), 0, null).pageInfo().totalElements()).isZero();
		verifyNoInteractions(wishlists);
	}

	@Test
	void combinesDestinationAndMapWhileLeavingUnsupportedFiltersIgnored() {
		var request = request("서울");
		assertIds(search(request, bounds(38, 126, 37, 128), 0, null), 1L, 2L);
		assertIds(search(request, bounds(36, 128, 34, 130), 0, null));
		request.setAmenityTypes(List.of("NOT_SUPPORTED"));
		request.setAccommodationTypes(List.of("NOT_SUPPORTED"));
		assertIds(search(request, bounds(38, 126, 37, 128), 0, null), 1L, 2L);
	}

	@Test
	void priceBoundariesAreInclusiveAndGuestInfantAndPetCapacitiesAreSeparate() {
		var price = request("서울");
		price.setMinPrice(100_000);
		price.setMaxPrice(100_000);
		assertIds(search(price, new MapBoundsDto(), 0, null), 1L);
		var guests = request("서울");
		guests.setAdultOccupancy(2);
		guests.setChildOccupancy(1);
		assertIds(search(guests, new MapBoundsDto(), 0, null), 2L);
		var infants = request("서울");
		infants.setInfantOccupancy(2);
		assertIds(search(infants, new MapBoundsDto(), 0, null), 2L);
		var pets = request("서울");
		pets.setPetOccupancy(1);
		assertIds(search(pets, new MapBoundsDto(), 0, null), 2L);
	}

	@Test
	void datesExcludeOverlapsAndAllowAnAdjacentCheckoutOrCheckin() {
		var request = request("서울");
		request.setCheckIn(LocalDate.parse("2027-01-12"));
		request.setCheckOut(LocalDate.parse("2027-01-13"));
		assertIds(search(request, new MapBoundsDto(), 0, null), 2L);
		request.setCheckIn(LocalDate.parse("2027-01-13"));
		request.setCheckOut(LocalDate.parse("2027-01-14"));
		assertIds(search(request, new MapBoundsDto(), 0, null), 1L, 2L);
		request.setCheckIn(LocalDate.parse("2027-01-10"));
		request.setCheckOut(LocalDate.parse("2027-01-11"));
		assertIds(search(request, new MapBoundsDto(), 0, null), 1L, 2L);
		request.setCheckIn(LocalDate.parse("2000-01-10"));
		request.setCheckOut(LocalDate.parse("2000-01-11"));
		assertIds(search(request, new MapBoundsDto(), 0, null));
	}

	@Test
	void mapBoundsCrossingTheDateLineIncludeBothSides() {
		assertIds(search(request(null), bounds(10, 170, -10, -170), 0, null), 5L, 6L);
	}

	@Test
	void aLargeGuestSumCannotOverflowAndRemoveTheCapacityFilter() {
		var request = request("서울");
		request.setAdultOccupancy(Integer.MAX_VALUE);
		request.setChildOccupancy(0);
		assertIds(search(request, new MapBoundsDto(), 0, null));
		request.setChildOccupancy(1);
		assertIds(search(request, new MapBoundsDto(), 0, null));
		request.setChildOccupancy(Integer.MAX_VALUE);
		assertIds(search(request, new MapBoundsDto(), 0, null));
		request.setAdultOccupancy(0);
		assertIds(search(request, new MapBoundsDto(), 0, null));
	}

	@Test
	void emptyLaterPageRetainsTheKnownTotalAndPreviousPageLink() {
		var result = search(request("서울"), new MapBoundsDto(), 1, null);
		assertThat(result.staySearchResultListing()).isEmpty();
		assertThat(result.pageInfo().totalElements()).isEqualTo(2);
		assertThat(result.pageInfo().totalPages()).isEqualTo(1);
		assertThat(result.pageInfo().currentPage()).isEqualTo(1);
		assertThat(result.pageInfo().hasPrevious()).isTrue();
		assertThat(result.pageInfo().hasNext()).isFalse();
	}

	@Test
	void lastAccessiblePageDoesNotAdvertiseAnotherPageEvenWhenMoreResultsExist() throws Exception {
		index(java.util.stream.IntStream.range(1000, 1300).mapToObj(id -> card(id, "서울", 100_000, 2, 0, 0,
			37.5, 127.0, "PUBLISHED", "APT", List.of(), List.of())).toList());
		var first = search(request("서울"), new MapBoundsDto(), 0, null);
		assertThat(first.pageInfo().totalElements()).isEqualTo(302);
		assertThat(first.pageInfo().totalPages()).isEqualTo(17);
		assertThat(first.pageInfo().hasNext()).isTrue();
		var last = search(request("서울"), new MapBoundsDto(), 14, null);
		assertThat(last.staySearchResultListing()).hasSize(18);
		assertThat(last.pageInfo().totalElements()).isEqualTo(302);
		assertThat(last.pageInfo().hasNext()).isFalse();
		assertThat(last.pageInfo().isLast()).isTrue();
	}

	@Test
	void anonymousSearchSkipsMembershipAndAuthenticatedSearchUsesOnlyTheSessionMember() {
		var anonymous = search(request("서울"), new MapBoundsDto(), 0, null);
		assertThat(anonymous.staySearchResultListing()).allSatisfy(card -> assertThat(card.isInWishlist()).isFalse());
		verifyNoInteractions(wishlists);
		when(wishlists.findAccommodationIdsByMemberIdAndAccommodationIds(eq(7L), anyList())).thenReturn(Set.of(2L));
		var authenticated = search(request("서울"), new MapBoundsDto(), 0, 7L);
		assertThat(authenticated.staySearchResultListing()).allSatisfy(card -> assertThat(card.isInWishlist()).isEqualTo(card.id() == 2));
	}

	private AccommodationSearchInfos search(AccommodationSearchRequestDto request, MapBoundsDto bounds, int page, Long memberId) {
		return service.searchAccommodations(request, bounds, PageRequest.of(page, 18), memberId);
	}

	private void assertIds(AccommodationSearchInfos response, Long... ids) {
		assertThat(response.staySearchResultListing()).extracting(card -> card.id()).containsExactlyInAnyOrder(ids);
	}

	private AccommodationSearchRequestDto request(String destination) {
		var request = new AccommodationSearchRequestDto();
		request.setDestination(destination);
		return request;
	}

	private MapBoundsDto bounds(double top, double left, double bottom, double right) {
		var bounds = new MapBoundsDto();
		bounds.setTopLeftLat(top);
		bounds.setTopLeftLng(left);
		bounds.setBottomRightLat(bottom);
		bounds.setBottomRightLng(right);
		return bounds;
	}

	private AccommodationDocument card(long id, String city, long price, int guests, int infants, int pets,
		double latitude, double longitude, String status, String type, List<String> amenities,
		List<AccommodationDocument.DateRange> ranges) {
		return AccommodationDocument.builder().id("search-test-" + id).accommodationId(id).name(city + " 숙소 " + id)
			.city(city).country("대한민국").basePrice(price).currency("KRW").type(type).status(status).timeZoneId("Asia/Seoul")
			.location(AccommodationDocument.Location.builder().lat(latitude).lon(longitude).build())
			.maxGuests(guests).maxInfants(infants).maxPets(pets).amenityTypes(amenities).reservationRanges(ranges).build();
	}

	private void index(List<AccommodationDocument> documents) throws Exception {
		var bulk = new BulkRequest.Builder().refresh(Refresh.True);
		for (var document : documents) bulk.operations(operation -> operation.index(index ->
			index.index("accommodations").id(document.id()).document(document)));
		assertThat(client.bulk(bulk.build()).errors()).isFalse();
	}
}
