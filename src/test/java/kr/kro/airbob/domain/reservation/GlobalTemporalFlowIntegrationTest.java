package kr.kro.airbob.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.api.ReservationController;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationLocalTimeException;
import kr.kro.airbob.domain.reservation.exception.ReservationOutsideBookingWindowException;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventorySeedService;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.service.ReservationQuoteService;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.geo.TimeZoneResolver;
import kr.kro.airbob.search.document.AccommodationDocument;
import kr.kro.airbob.search.dto.AccommodationSearchRequest;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;
import kr.kro.airbob.search.service.AccommodationSearchService;
import kr.kro.airbob.search.service.AccommodationSearchSnapshotReader;

/** Real MySQL/services/MVC serialization; authentication is preset and Elasticsearch transport is mocked. */
@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@Import(GlobalTemporalFlowIntegrationTest.TemporalClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("MySQL 8.4 시간 경계: 견적·checkout·날짜 재고·상세·검색 원본")
class GlobalTemporalFlowIntegrationTest {

	private static final Instant INITIAL_NOW = Instant.parse("2026-11-15T07:30:00Z");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_global_temporal")
		.withCommand("--log-bin-trust-function-creators=1", "--skip-log-bin");

	@Container
	private static final GenericContainer<?> REDIS =
		new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired private AdjustableClock clock;
	@Autowired private ReservationController controller;
	@Autowired private ReservationQuoteService quoteService;
	@Autowired private ReservationService reservationService;
	@Autowired private ReservationRepository reservationRepository;
	@Autowired private AccommodationInventorySeedService seedService;
	@Autowired private BookingWindowProvider bookingWindowProvider;
	@Autowired private MemberRepository memberRepository;
	@Autowired private AccommodationRepository accommodationRepository;
	@Autowired private AccommodationSearchSnapshotReader searchReader;
	@Autowired private AccommodationSearchService searchService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private TimeZoneResolver timeZoneResolver;

	@MockitoBean private ElasticsearchClient elasticsearchClient;
	@MockitoBean private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean private io.awspring.cloud.s3.S3Template s3Template;

	private MockMvc mvc;
	private Member host;
	private Member guest;

	@BeforeEach
	void setUp() {
		clock.set(INITIAL_NOW);
		clearRows();
		host = memberRepository.save(Member.builder().email("temporal-host@example.test")
			.nickname("temporal-host").build());
		guest = memberRepository.save(Member.builder().email("temporal-guest@example.test")
			.nickname("temporal-guest").build());
		UserContext.set(new UserInfo(guest.getId()));
		mvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper)).build();
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
		clearRows();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("kr.kro.airbob.domain.reservation.GlobalTemporalCases#stays")
	void fixedFutureAndDstCasesSurviveQuoteCheckoutSqlReadAndReservationDetail(
		GlobalTemporalCases.Stay expected
	) throws Exception {
		clock.set(expected.decisionAt());
		Accommodation accommodation = fixture(expected, 120_000L);
		assertSeedHorizon(accommodation, expected);
		assertThat(bookingWindowProvider.currentFor(expected.zone()).startInclusive()).isEqualTo(expected.localToday());
		assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.4.");
		assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isIn("+00:00", "UTC");

		String quoteUid = quote(accommodation, expected, 120_000L);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accommodation_inventory_day WHERE state <> 'FREE'",
			Long.class)).isZero();
		String reservationUid = checkout(quoteUid, expected, "paid-" + expected.label(), "PAYMENT_PENDING");
		Reservation persisted = reservationRepository.findByReservationUid(UUID.fromString(reservationUid)).orElseThrow();
		assertThat(persisted.getCheckInAt()).isEqualTo(expected.checkInAt());
		assertThat(persisted.getCheckOutAt()).isEqualTo(expected.checkOutAt());
		assertThat(persisted.getTimeZoneId()).isEqualTo(expected.zone());
		assertThat(persisted.getExpiresAt()).isEqualTo(expected.decisionAt().plusSeconds(900));
		assertThat(persisted.getTotalPrice()).isEqualTo(120_000L * expected.nights());
		assertThat(jdbc.<LocalDateTime>queryForObject("SELECT check_in_at FROM reservation WHERE id = ?",
			(rs, row) -> rs.getObject(1, LocalDateTime.class), persisted.getId()))
			.isEqualTo(LocalDateTime.ofInstant(expected.checkInAt(), ZoneOffset.UTC));
		assertThat(jdbc.<LocalDateTime>queryForObject("SELECT check_out_at FROM reservation WHERE id = ?",
			(rs, row) -> rs.getObject(1, LocalDateTime.class), persisted.getId()))
			.isEqualTo(LocalDateTime.ofInstant(expected.checkOutAt(), ZoneOffset.UTC));
		assertOwnedLocalNights(accommodation, persisted.getId(), expected, "HOLD");
		assertGuestAndHostDetail(reservationUid, expected);

		// Complimentary checkout exercises the actual confirmed path without an external payment gateway.
		Accommodation complimentary = fixture(expected, 0L);
		String freeQuote = quote(complimentary, expected, 0L);
		String freeReservationUid = checkout(freeQuote, expected, "free-" + expected.label(), "CONFIRMED");
		Reservation confirmed = reservationRepository.findByReservationUid(UUID.fromString(freeReservationUid)).orElseThrow();
		assertOwnedLocalNights(complimentary, confirmed.getId(), expected, "OCCUPIED");
		AccommodationDocument document = searchReader.readPublished(complimentary.getAccommodationUid()).orElseThrow();
		assertThat(document.timeZoneId()).isEqualTo(expected.zone());
		assertThat(document.reservationRanges()).singleElement().satisfies(range -> {
			assertThat(range.gte()).isEqualTo(expected.checkIn());
			assertThat(range.lt()).isEqualTo(expected.checkOut());
		});
		assertSearchRequestUsesLocalDatesAndRealEligibility(expected);
	}

	@Test
	void gapAtEitherStayEndpointRejectsQuoteWithoutAnyReservationOrOwnedInventory() {
		clock.set(Instant.parse("2026-03-01T12:00:00Z"));
		Accommodation checkInGap = fixture("America/New_York", LocalTime.of(2, 30), LocalTime.of(11, 0), 120_000);
		Accommodation checkOutGap = fixture("America/New_York", LocalTime.of(15, 0), LocalTime.of(2, 30), 120_000);

		assertThatThrownBy(() -> quoteService.createQuote(new ReservationRequest.Quote(checkInGap.getId(),
			LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 9), 2, null), guest.getId()))
			.isInstanceOf(InvalidReservationLocalTimeException.class);
		assertThatThrownBy(() -> quoteService.createQuote(new ReservationRequest.Quote(checkOutGap.getId(),
			LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8), 2, null), guest.getId()))
			.isInstanceOf(InvalidReservationLocalTimeException.class);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_quote", Long.class)).isZero();
		assertThat(reservationRepository.count()).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accommodation_inventory_day WHERE state <> 'FREE'",
			Long.class)).isZero();
	}

	@Test
	void checkoutRevalidatesGapAfterQuoteAndRollsBackOwnershipAndIdempotencyClaim() {
		clock.set(Instant.parse("2026-03-01T12:00:00Z"));
		for (boolean checkInGap : List.of(true, false)) {
			Accommodation accommodation = fixture("America/New_York", LocalTime.of(15, 0), LocalTime.of(11, 0), 120_000);
			LocalDate checkIn = LocalDate.of(2026, 3, checkInGap ? 8 : 7);
			LocalDate checkOut = LocalDate.of(2026, 3, checkInGap ? 9 : 8);
			var quote = quoteService.createQuote(new ReservationRequest.Quote(accommodation.getId(), checkIn,
				checkOut, 2, null), guest.getId());
			String timeColumn = checkInGap ? "check_in_time" : "check_out_time";
			jdbc.update("UPDATE accommodation SET " + timeColumn + " = '02:30:00' WHERE id = ?", accommodation.getId());

			assertThatThrownBy(() -> reservationService.createPendingReservation(
				new ReservationRequest.Checkout(quote.quoteUid(), null), guest.getId(), "gap-changed-" + timeColumn))
				.isInstanceOf(InvalidReservationLocalTimeException.class);
		}
		assertThat(reservationRepository.count()).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_checkout_request", Long.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_quote WHERE reservation_id IS NULL AND checked_out_at IS NULL",
			Long.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accommodation_inventory_day WHERE state <> 'FREE'",
			Long.class)).isZero();
	}

	@Test
	void sameUtcInstantAcceptsSeoulAndRejectsNewYorkAtIndependentThreeMonthBoundary() {
		clock.set(Instant.parse("2026-08-12T01:00:00Z"));
		Accommodation seoul = fixture("Asia/Seoul", LocalTime.of(15, 0), LocalTime.of(11, 0), 120_000);
		Accommodation newYork = fixture("America/New_York", LocalTime.of(15, 0), LocalTime.of(11, 0), 120_000);
		LocalDate checkIn = LocalDate.of(2026, 11, 11);
		LocalDate checkOut = LocalDate.of(2026, 11, 12);

		assertThat(quoteService.createQuote(new ReservationRequest.Quote(seoul.getId(), checkIn, checkOut,
			2, null), guest.getId()).nights()).isOne();
		assertThatThrownBy(() -> quoteService.createQuote(new ReservationRequest.Quote(newYork.getId(),
			checkIn, checkOut, 2, null), guest.getId())).isInstanceOf(ReservationOutsideBookingWindowException.class);
		assertThat(bookingWindowProvider.eligibleTimeZonesForStay(checkIn, checkOut))
			.contains("Asia/Seoul").doesNotContain("America/New_York");
	}

	private Accommodation fixture(GlobalTemporalCases.Stay expected, long nightlyPrice) {
		return fixture(expected.zone(), expected.checkInTime(), expected.checkOutTime(), nightlyPrice);
	}

	private Accommodation fixture(String zone, LocalTime checkIn, LocalTime checkOut, long nightlyPrice) {
		FixtureLocation location = switch (zone) {
			case "America/Vancouver" -> new FixtureLocation("Canada", 49.2827, -123.1207);
			case "Asia/Seoul" -> new FixtureLocation("South Korea", 37.5665, 126.9780);
			case "America/New_York" -> new FixtureLocation("United States", 40.7128, -74.0060);
			case "America/Chicago" -> new FixtureLocation("United States", 41.8781, -87.6298);
			case "Australia/Sydney" -> new FixtureLocation("Australia", -33.8688, 151.2093);
			case "Australia/Adelaide" -> new FixtureLocation("Australia", -34.9285, 138.6007);
			default -> throw new IllegalArgumentException("Missing fixed temporal fixture coordinates: " + zone);
		};
		assertThat(timeZoneResolver.resolve(location.latitude(), location.longitude())).contains(ZoneId.of(zone));
		return new TransactionTemplate(transactionManager).execute(status -> {
			Accommodation accommodation = accommodationRepository.save(Accommodation.builder()
				.name("Temporal " + zone).basePrice(nightlyPrice).currency("KRW")
				.address(Address.builder().country(location.country()).latitude(location.latitude()).longitude(location.longitude()).build())
				.occupancyPolicy(OccupancyPolicy.builder().maxOccupancy(4).build()).member(host)
				.checkInTime(checkIn).checkOutTime(checkOut).timeZoneId(zone)
				.status(AccommodationStatus.PUBLISHED).build());
			seedService.seedCurrentHorizon(accommodation);
			return accommodation;
		});
	}

	private void assertSeedHorizon(Accommodation accommodation, GlobalTemporalCases.Stay expected) {
		assertThat(jdbc.<LocalDate>queryForObject("SELECT MIN(stay_date) FROM accommodation_inventory_day WHERE accommodation_id = ?",
			(rs, row) -> rs.getDate(1).toLocalDate(), accommodation.getId())).isEqualTo(expected.localToday());
		assertThat(jdbc.<LocalDate>queryForObject("SELECT MAX(stay_date) FROM accommodation_inventory_day WHERE accommodation_id = ?",
			(rs, row) -> rs.getDate(1).toLocalDate(), accommodation.getId())).isEqualTo(expected.seedEnd().minusDays(1));
	}

	private String quote(Accommodation accommodation, GlobalTemporalCases.Stay expected, long nightlyPrice) throws Exception {
		String response = mvc.perform(post("/api/v1/reservation-quotes").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(new ReservationRequest.Quote(accommodation.getId(),
					expected.checkIn(), expected.checkOut(), 2, null))))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.data.inventory_held").value(false))
			.andExpect(jsonPath("$.data.nights").value(expected.nights()))
			.andExpect(jsonPath("$.data.subtotal").value(nightlyPrice * expected.nights()))
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).path("data").path("quote_uid").asText();
	}

	private String checkout(String quoteUid, GlobalTemporalCases.Stay expected, String key, String expectedStatus)
		throws Exception {
		String response = mvc.perform(post("/api/v1/reservations").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(
					new ReservationRequest.Checkout(UUID.fromString(quoteUid), null))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(expectedStatus))
			.andExpect(jsonPath("$.data.check_in").value(expected.checkIn().toString()))
			.andExpect(jsonPath("$.data.check_out").value(expected.checkOut().toString()))
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).path("data").path("reservation_uid").asText();
	}

	private void assertOwnedLocalNights(Accommodation accommodation, long owner, GlobalTemporalCases.Stay expected,
		String expectedState) {
		List<LocalDate> dates = jdbc.query("SELECT stay_date FROM accommodation_inventory_day "
				+ "WHERE accommodation_id = ? AND reservation_id = ? AND state = ? ORDER BY stay_date",
			(rs, row) -> rs.getDate(1).toLocalDate(), accommodation.getId(), owner, expectedState);
		assertThat(dates).hasSize(Math.toIntExact(expected.nights()))
			.containsExactlyElementsOf(expected.checkIn().datesUntil(expected.checkOut()).toList());
		assertThat(jdbc.queryForObject("SELECT state FROM accommodation_inventory_day "
				+ "WHERE accommodation_id = ? AND stay_date = ?", String.class, accommodation.getId(), expected.checkOut()))
			.isEqualTo("FREE");
	}

	private void assertGuestAndHostDetail(String reservationUid, GlobalTemporalCases.Stay expected) throws Exception {
		for (boolean hostView : List.of(false, true)) {
			UserContext.set(new UserInfo(hostView ? host.getId() : guest.getId()));
			String path = "/api/v1/profile/" + (hostView ? "host" : "guest") + "/reservations/" + reservationUid;
			String response = mvc.perform(get(path)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.time_zone_id").value(expected.zone()))
				.andReturn().getResponse().getContentAsString();
			JsonNode data = objectMapper.readTree(response).path("data");
			assertThat(LocalDateTime.parse(data.path("check_in_date_time").asText()))
				.isEqualTo(expected.checkIn().atTime(expected.checkInTime()));
			assertThat(LocalDateTime.parse(data.path("check_out_date_time").asText()))
				.isEqualTo(expected.checkOut().atTime(expected.checkOutTime()));
		}
		UserContext.set(new UserInfo(guest.getId()));
	}

	private void assertSearchRequestUsesLocalDatesAndRealEligibility(GlobalTemporalCases.Stay expected) throws Exception {
		SearchResponse<AccommodationDocument> emptyResponse = SearchResponse.of(response -> response.took(1)
			.timedOut(false).shards(shards -> shards.total(1).successful(1).failed(0))
			.hits(hits -> hits.hits(List.of())));
		given(elasticsearchClient.search(any(SearchRequest.class), eq(AccommodationDocument.class))).willReturn(emptyResponse);
		AccommodationSearchRequest.AccommodationSearchRequestDto request =
			new AccommodationSearchRequest.AccommodationSearchRequestDto();
		request.setDestination("Temporal");
		request.setCheckIn(expected.checkIn());
		request.setCheckOut(expected.checkOut());
		searchService.searchAccommodations(request, new AccommodationSearchRequest.MapBoundsDto(), PageRequest.of(0, 18), null);
		ArgumentCaptor<SearchRequest> captured = ArgumentCaptor.forClass(SearchRequest.class);
		verify(elasticsearchClient).search(captured.capture(), eq(AccommodationDocument.class));
		Query query = captured.getValue().query();
		assertThat(query.bool().filter()).anySatisfy(filter -> {
			assertThat(filter.isTerms()).isTrue();
			assertThat(filter.terms().field()).isEqualTo("timeZoneId");
			assertThat(filter.terms().terms().value()).extracting(value -> value.stringValue()).contains(expected.zone());
		});
		assertThat(query.bool().mustNot()).anySatisfy(excluded -> {
			assertThat(excluded.isRange()).isTrue();
			assertThat(excluded.range().date().gte()).isEqualTo(expected.checkIn().toString());
			assertThat(excluded.range().date().lt()).isEqualTo(expected.checkOut().toString());
		});
	}

	private void clearRows() {
		for (String table : List.of("reservation_checkout_request", "reservation_quote", "outbox",
			"reservation_history", "member_coupon", "accommodation_inventory_day", "reservation", "coupon",
			"accommodation", "member", "address", "occupancy_policy")) {
			jdbc.update("DELETE FROM " + table);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TemporalClockConfiguration {
		@Bean
		@Primary
		AdjustableClock temporalClock() {
			return new AdjustableClock();
		}
	}

	static final class AdjustableClock extends Clock {
		private final AtomicReference<Instant> instant = new AtomicReference<>(INITIAL_NOW);
		void set(Instant value) { instant.set(value); }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
		@Override public Instant instant() { return instant.get(); }
	}

	private record FixtureLocation(String country, double latitude, double longitude) {
	}
}
