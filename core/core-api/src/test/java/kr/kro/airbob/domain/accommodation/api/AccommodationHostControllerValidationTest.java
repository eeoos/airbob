package kr.kro.airbob.domain.accommodation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.domain.accommodation.cache.invalidation.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.*;
import kr.kro.airbob.domain.accommodation.service.AccommodationCommandService;
import kr.kro.airbob.domain.accommodation.service.AccommodationImageService;
import kr.kro.airbob.domain.accommodation.service.AccommodationQueryService;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import kr.kro.airbob.domain.image.service.S3ImageUploader;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventorySeedService;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.geo.GeocodingService;
import kr.kro.airbob.geo.TimeZoneResolver;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@ExtendWith(MockitoExtension.class)
class AccommodationHostControllerValidationTest {

	@Mock AccommodationRepository accommodations;
	@Mock AccommodationAmenityRepository amenities;
	@Mock AccommodationImageRepository images;
	@Mock AccommodationHistoryRepository history;
	@Mock AddressRepository addresses;
	@Mock OccupancyPolicyRepository policies;
	@Mock ReservationRepository reservations;
	@Mock MemberRepository members;
	@Mock CommonCodeService codes;
	@Mock GeocodingService geocoding;
	@Mock TimeZoneResolver timeZones;
	@Mock AccommodationSearchRefreshPublisher search;
	@Mock AccommodationDetailCacheInvalidationPublisher cache;
	@Mock AccommodationInventorySeedService inventory;
	@Mock Clock clock;
	@Mock S3ImageUploader uploader;
	@Mock ApplicationEventPublisher events;
	@Mock AccommodationQueryService queryService;
	@InjectMocks AccommodationCommandService commandService;
	@InjectMocks AccommodationImageService imageService;

	private MockMvc mvc;
	private ObjectMapper mapper;
	private Accommodation accommodation;

	@BeforeEach
	void setUp() {
		mapper = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		mvc = MockMvcBuilders.standaloneSetup(new AccommodationHostController(commandService, imageService, queryService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
			.build();
		accommodation = Accommodation.builder().id(101L).accommodationUid(UUID.randomUUID())
			.name("기존 숙소").description("기존 설명").currency("KRW").basePrice(100000L)
			.status(AccommodationStatus.DRAFT).timeZoneId("Asia/Seoul")
			.checkInTime(LocalTime.of(15, 0)).checkOutTime(LocalTime.of(11, 0)).build();
		lenient().when(accommodations.findByIdAndMemberIdAndStatusNotForUpdate(101L, 10L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		lenient().when(clock.instant()).thenReturn(Instant.parse("2027-01-01T00:00:00Z"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidUpdates")
	void invalidUpdateIsRejectedBeforeDatabaseOrGeocoding(String label, String body) throws Exception {
		asMember(() -> mvc.perform(patch("/api/v1/accommodations/101").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("C001")));
		verifyNoInteractions(accommodations, geocoding, addresses, amenities, history, cache, search);
	}

	static Stream<Arguments> invalidUpdates() {
		Stream<Arguments> simple = Stream.of(
			Arguments.of("null 편의시설 항목", "{\"amenity_infos\":[null]}"),
			Arguments.of("누락된 편의시설 개수", "{\"amenity_infos\":[{\"name\":\"WIFI\"}]}"),
			Arguments.of("음수 편의시설 개수", "{\"amenity_infos\":[{\"name\":\"WIFI\",\"count\":-1}]}"),
			Arguments.of("공백 숙소 이름", "{\"name\":\"   \"}"),
			Arguments.of("전각 공백 숙소 이름", "{\"name\":\"　\"}"),
			Arguments.of("유니코드 공백 숙소 설명", "{\"description\":\"  \"}"),
			Arguments.of("공백 숙소 설명", "{\"description\":\" \\n \\t \"}"),
			Arguments.of("지원하지 않는 통화", "{\"currency\":\"USD\"}"),
			Arguments.of("빈 통화", "{\"currency\":\"\"}"),
			Arguments.of("음수 가격", "{\"base_price\":-1}"),
			Arguments.of("불완전한 인원 정책", "{\"occupancy_policy_info\":{\"max_occupancy\":2}}"),
			Arguments.of("누락된 주소 필수값", "{\"address_info\":{\"country\":\"대한민국\"}}")
		);
		Stream<Arguments> longAddresses = Stream.of("country", "state", "city", "district", "street", "detail")
			.map(field -> {
				var address = new ObjectMapper().createObjectNode().put("country", "대한민국")
					.put("city", "서울").put("street", "가상로 123").put("postal_code", "03000");
				address.put(field, "a".repeat(256));
				return Arguments.of("주소 길이 초과: " + field, "{\"address_info\":" + address + "}");
			});
		return Stream.concat(simple, longAddresses);
	}

	@Test
	void nullPatchFieldsPreserveValuesAndEmptyAmenityListClearsOnlyAmenities() throws Exception {
		asMember(() -> mvc.perform(patch("/api/v1/accommodations/101").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":null,\"currency\":null,\"address_info\":null,\"occupancy_policy_info\":null,\"amenity_infos\":null}"))
			.andExpect(status().isOk()));
		assertThat(accommodation.getName()).isEqualTo("기존 숙소");
		assertThat(accommodation.getCurrency()).isEqualTo("KRW");
		verifyNoInteractions(amenities, addresses, policies);
		asMember(() -> mvc.perform(patch("/api/v1/accommodations/101").contentType(MediaType.APPLICATION_JSON)
			.content("{\"amenity_infos\":[]}"))
			.andExpect(status().isOk()));
		verify(amenities).deleteByAccommodationIdInBulk(101L);
		verify(amenities, never()).saveAll(anyList());
	}

	@Test
	void duplicatedAmenityCountsAreMergedAfterUppercasingAndZeroCountsAreOmitted() throws Exception {
		when(codes.isValidCode("AMENITY_TYPE", "WIFI")).thenReturn(true);
		asMember(() -> mvc.perform(patch("/api/v1/accommodations/101").contentType(MediaType.APPLICATION_JSON)
			.content("{\"amenity_infos\":[{\"name\":\"wifi\",\"count\":1},{\"name\":\"WIFI\",\"count\":2},{\"name\":\"UNUSED\",\"count\":0}]}"))
			.andExpect(status().isOk()));
		verify(amenities).saveAll(argThat(values -> {
			var list = (List<kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity>)values;
			return list.size() == 1 && list.getFirst().getCount() == 3 && list.getFirst().getAmenityCode().equals("WIFI");
		}));
	}

	@Test
	void overflowingAmenityTotalDoesNotMutateAccommodationOrAmenities() throws Exception {
		lenient().when(codes.isValidCode("AMENITY_TYPE", "WIFI")).thenReturn(true);
		asMember(() -> mvc.perform(patch("/api/v1/accommodations/101").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"바뀌면 안 됨\",\"amenity_infos\":[{\"name\":\"wifi\",\"count\":2147483647},{\"name\":\"WIFI\",\"count\":1}]}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("C001")));
		assertThat(accommodation.getName()).isEqualTo("기존 숙소");
		verifyNoInteractions(amenities, history, cache, search);
	}

	@Test
	void missingImagePartReturnsInputErrorBeforeCallingStorage() throws Exception {
		asMember(() -> mvc.perform(multipart("/api/v1/accommodations/101/images"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("C001")));
		verifyNoInteractions(uploader, images, accommodations);
	}

	@Test
	void emptyFileIsDifferentFromMissingImagePart() throws Exception {
		when(accommodations.findByIdAndMemberIdAndStatusNot(101L, 10L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		asMember(() -> mvc.perform(multipart("/api/v1/accommodations/101/images")
			.file(new MockMultipartFile("images", "empty.png", "image/png", new byte[0])))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("I007")));
		verifyNoInteractions(uploader, images);
	}

	@Test
	void unsupportedContentTypeIsRejectedBeforeUpload() throws Exception {
		when(accommodations.findByIdAndMemberIdAndStatusNot(101L, 10L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		asMember(() -> mvc.perform(multipart("/api/v1/accommodations/101/images")
			.file(new MockMultipartFile("images", "image.jpg", "image/gif", new byte[] {1})))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("I003")));
		verifyNoInteractions(uploader, images);
	}

	private void asMember(CheckedRequest action) throws Exception {
		try {
			UserContext.set(new UserInfo(10L));
			action.perform();
		} finally {
			UserContext.clear();
		}
	}

	@FunctionalInterface
	private interface CheckedRequest {
		void perform() throws Exception;
	}
}
