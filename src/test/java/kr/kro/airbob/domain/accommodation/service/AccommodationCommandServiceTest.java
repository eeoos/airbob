package kr.kro.airbob.domain.accommodation.service;

import static kr.kro.airbob.domain.commoncode.common.CommonCodeGroups.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AddressRequest;
import kr.kro.airbob.domain.accommodation.dto.AmenityRequest;
import kr.kro.airbob.domain.accommodation.dto.PolicyRequest;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationHistoryRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.AddressRepository;
import kr.kro.airbob.domain.accommodation.repository.OccupancyPolicyRepository;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.geo.GeocodingService;
import kr.kro.airbob.geo.TimeZoneResolver;
import kr.kro.airbob.geo.dto.GeocodeResult;
import kr.kro.airbob.outbox.OutboxEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 명령 서비스 단위 테스트")
class AccommodationCommandServiceTest {
	private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

	@Mock private AccommodationRepository accommodationRepository;
	@Mock private ReservationRepository reservationRepository;
	@Mock private AccommodationAmenityRepository accommodationAmenityRepository;
	@Mock private AccommodationHistoryRepository accommodationHistoryRepository;
	@Mock private AccommodationImageRepository accommodationImageRepository;
	@Mock private AddressRepository addressRepository;
	@Mock private OccupancyPolicyRepository occupancyPolicyRepository;
	@Mock private CommonCodeService commonCodeService;
	@Mock private GeocodingService geocodingService;
	@Mock private TimeZoneResolver timeZoneResolver;
	@Mock private MemberRepository memberRepository;
	@Mock private OutboxEventPublisher outboxEventPublisher;
	@Mock private Clock clock;

	@InjectMocks
	private AccommodationCommandService accommodationCommandService;

	@BeforeEach
	void setUpClock() {
		lenient().when(clock.instant()).thenReturn(NOW);
		lenient().when(reservationRepository.existsFutureInventoryReservation(anyLong(), eq(NOW)))
			.thenReturn(false);
	}

	@Test
	@DisplayName("주소가 바뀌면 해석된 좌표와 시간대를 함께 갱신한다")
	void updateAddressAndTimeZoneTogether() {
		Accommodation accommodation = Accommodation.builder()
			.id(1L)
			.name("기존 숙소")
			.status(AccommodationStatus.DRAFT)
			.build();
		AddressRequest.AddressInfo addressInfo = seoulAddressInfo();
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.addressInfo(addressInfo)
			.build();
		GeocodeResult geocodeResult = GeocodeResult.success(37.5665, 126.9780, "서울", null);

		when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(geocodingService.getCoordinates(anyString())).thenReturn(geocodeResult);
		when(timeZoneResolver.resolve(37.5665, 126.9780)).thenReturn(Optional.of(ZoneId.of("Asia/Seoul")));
		when(addressRepository.save(any(Address.class))).thenAnswer(invocation -> invocation.getArgument(0));

		accommodationCommandService.updateAccommodation(1L, request, 2L);

		assertThat(accommodation.getAddress().getLatitude()).isEqualTo(37.5665);
		assertThat(accommodation.getAddress().getLongitude()).isEqualTo(126.9780);
		assertThat(accommodation.getTimeZoneId()).isEqualTo("Asia/Seoul");
		InOrder resolutionOrder = inOrder(geocodingService, timeZoneResolver, addressRepository);
		resolutionOrder.verify(geocodingService).getCoordinates(anyString());
		resolutionOrder.verify(timeZoneResolver).resolve(37.5665, 126.9780);
		resolutionOrder.verify(addressRepository).save(any(Address.class));
	}

	@Test
	@DisplayName("지오코딩 실패는 A008로 거부하고 숙소와 주소를 바꾸지 않는다")
	void rejectGeocodingFailureBeforeAnyMutation() {
		Address currentAddress = Address.builder()
			.country("KR")
			.city("Busan")
			.street("Old street")
			.postalCode("48000")
			.latitude(35.1796)
			.longitude(129.0756)
			.build();
		Accommodation accommodation = Accommodation.builder()
			.id(1L)
			.name("기존 이름")
			.address(currentAddress)
			.status(AccommodationStatus.DRAFT)
			.build();
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.name("바뀐 이름")
			.addressInfo(seoulAddressInfo())
			.build();

		when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(geocodingService.getCoordinates(anyString())).thenReturn(GeocodeResult.fail());

		assertThatThrownBy(() -> accommodationCommandService.updateAccommodation(1L, request, 2L))
			.isInstanceOfSatisfying(BaseException.class, exception -> {
				assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(exception.getErrorCode().getCode()).isEqualTo("A008");
			});
		assertThat(accommodation.getName()).isEqualTo("기존 이름");
		assertThat(accommodation.getAddress()).isSameAs(currentAddress);
		verifyNoInteractions(timeZoneResolver, addressRepository);
	}

	@Test
	@DisplayName("게시 숙소의 시간대 해석 실패는 A008로 거부하고 전체 변경을 중단한다")
	void rejectTimeZoneResolutionFailureForPublishedAccommodation() {
		Address currentAddress = Address.builder()
			.country("KR")
			.city("Busan")
			.street("Old street")
			.postalCode("48000")
			.latitude(35.1796)
			.longitude(129.0756)
			.build();
		Accommodation accommodation = Accommodation.builder()
			.id(1L)
			.name("공개 중인 숙소")
			.address(currentAddress)
			.status(AccommodationStatus.PUBLISHED)
			.build();
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.name("바뀐 이름")
			.addressInfo(seoulAddressInfo())
			.build();
		GeocodeResult geocodeResult = GeocodeResult.success(37.5665, 126.9780, "서울", null);

		when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(geocodingService.getCoordinates(anyString())).thenReturn(geocodeResult);
		when(timeZoneResolver.resolve(37.5665, 126.9780)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> accommodationCommandService.updateAccommodation(1L, request, 2L))
			.isInstanceOfSatisfying(BaseException.class, exception -> {
				assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(exception.getErrorCode().getCode()).isEqualTo("A008");
			});
		assertThat(accommodation.getName()).isEqualTo("공개 중인 숙소");
		assertThat(accommodation.getAddress()).isSameAs(currentAddress);
		verifyNoInteractions(addressRepository, outboxEventPublisher);
	}

	@Test
	@DisplayName("주소가 같아도 시간대가 없으면 위치를 다시 해석해 복구한다")
	void recoverMissingTimeZoneEvenWhenAddressIsUnchanged() {
		AddressRequest.AddressInfo addressInfo = seoulAddressInfo();
		Address currentAddress = Address.builder()
			.country(addressInfo.country())
			.state(addressInfo.state())
			.city(addressInfo.city())
			.district(addressInfo.district())
			.street(addressInfo.street())
			.detail(addressInfo.detail())
			.postalCode(addressInfo.postalCode())
			.latitude(1.0)
			.longitude(2.0)
			.build();
		Accommodation accommodation = Accommodation.builder()
			.id(1L)
			.address(currentAddress)
			.status(AccommodationStatus.DRAFT)
			.build();
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.addressInfo(addressInfo)
			.build();
		GeocodeResult geocodeResult = GeocodeResult.success(37.5665, 126.9780, "서울", null);

		when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(geocodingService.getCoordinates(anyString())).thenReturn(geocodeResult);
		when(timeZoneResolver.resolve(37.5665, 126.9780)).thenReturn(Optional.of(ZoneId.of("Asia/Seoul")));
		when(addressRepository.save(any(Address.class))).thenAnswer(invocation -> invocation.getArgument(0));

		accommodationCommandService.updateAccommodation(1L, request, 2L);

		assertThat(accommodation.getTimeZoneId()).isEqualTo("Asia/Seoul");
		assertThat(accommodation.getAddress().getLatitude()).isEqualTo(37.5665);
	}

	@Test
	@DisplayName("시간대가 없으면 숙소 게시를 거부한다")
	void rejectPublishingWithoutTimeZone() {
		Accommodation accommodation = publishableAccommodation();

		when(accommodationRepository.findWithDetailsExceptHostAndDeletedById(1L, 2L))
			.thenReturn(Optional.of(accommodation));

		assertThatThrownBy(() -> accommodationCommandService.publishAccommodation(1L, 2L))
			.isInstanceOfSatisfying(BaseException.class, exception -> {
				assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(exception.getErrorCode().getCode()).isEqualTo("A003");
			});
		assertThat(accommodation.getStatus()).isEqualTo(AccommodationStatus.DRAFT);
		verifyNoInteractions(outboxEventPublisher);
	}

	@Test
	@DisplayName("IANA 식별자로 해석할 수 없는 시간대면 숙소 게시를 거부한다")
	void rejectPublishingWithInvalidTimeZoneId() {
		Accommodation accommodation = publishableAccommodation("Mars/Olympus_Mons");

		when(accommodationRepository.findWithDetailsExceptHostAndDeletedById(1L, 2L))
			.thenReturn(Optional.of(accommodation));

		assertThatThrownBy(() -> accommodationCommandService.publishAccommodation(1L, 2L))
			.isInstanceOfSatisfying(BaseException.class, exception -> {
				assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(exception.getErrorCode().getCode()).isEqualTo("A003");
			});
		assertThat(accommodation.getStatus()).isEqualTo(AccommodationStatus.DRAFT);
	}

	@ParameterizedTest(name = "{0} 고정 offset 식별자는 게시할 수 없다")
	@ValueSource(strings = {"+09:00", "GMT+09:00"})
	@DisplayName("IANA region ID가 아닌 고정 offset 시간대면 숙소 게시를 거부한다")
	void rejectPublishingWithFixedOffsetTimeZoneId(String timeZoneId) {
		Accommodation accommodation = publishableAccommodation(timeZoneId);

		when(accommodationRepository.findWithDetailsExceptHostAndDeletedById(1L, 2L))
			.thenReturn(Optional.of(accommodation));
		lenient().when(accommodationImageRepository.countByAccommodationId(1L)).thenReturn(1L);

		assertThatThrownBy(() -> accommodationCommandService.publishAccommodation(1L, 2L))
			.isInstanceOfSatisfying(BaseException.class, exception -> {
				assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(exception.getErrorCode().getCode()).isEqualTo("A003");
			});
		assertThat(accommodation.getStatus()).isEqualTo(AccommodationStatus.DRAFT);
		verifyNoInteractions(outboxEventPublisher);
	}

	@Test
	@DisplayName("공백 시간대 식별자면 숙소 게시를 거부한다")
	void rejectPublishingWithBlankTimeZoneId() {
		Accommodation accommodation = publishableAccommodation("   ");

		when(accommodationRepository.findWithDetailsExceptHostAndDeletedById(1L, 2L))
			.thenReturn(Optional.of(accommodation));

		assertThatThrownBy(() -> accommodationCommandService.publishAccommodation(1L, 2L))
			.isInstanceOfSatisfying(BaseException.class, exception -> {
				assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(exception.getErrorCode().getCode()).isEqualTo("A003");
			});
		assertThat(accommodation.getStatus()).isEqualTo(AccommodationStatus.DRAFT);
		verifyNoInteractions(outboxEventPublisher);
	}

	@Test
	@DisplayName("유효하지 않은 숙소 유형은 A004와 400 응답용 예외로 거부한다")
	void rejectInvalidAccommodationTypeAsBadRequest() {
		Accommodation accommodation = mock(Accommodation.class);
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.type("not_a_type")
			.build();

		when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(commonCodeService.isValidCode(ACCOMMODATION_TYPE, "NOT_A_TYPE"))
			.thenReturn(false);

		assertThatThrownBy(() -> accommodationCommandService.updateAccommodation(1L, request, 2L))
			.isInstanceOfSatisfying(BaseException.class, exception -> {
				assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(exception.getErrorCode().getCode()).isEqualTo("A004");
			});
	}

	@Test
	@DisplayName("체크아웃이 체크인보다 늦어 인접 숙박이 겹치는 시간 설정은 거부한다")
	void rejectOverlappingTurnoverTimesOnUpdate() {
		Accommodation accommodation = Accommodation.builder()
			.id(1L)
			.status(AccommodationStatus.DRAFT)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.build();
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.checkInTime(LocalTime.of(10, 0))
			.checkOutTime(LocalTime.of(12, 0))
			.build();
		when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(
			1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));

		assertThatThrownBy(() -> accommodationCommandService.updateAccommodation(1L, request, 2L))
			.isInstanceOfSatisfying(BaseException.class, exception ->
				assertThat(exception.getErrorCode().getCode()).isEqualTo("C001"));

		assertThat(accommodation.getCheckInTime()).isEqualTo(LocalTime.of(15, 0));
		assertThat(accommodation.getCheckOutTime()).isEqualTo(LocalTime.of(11, 0));
		verifyNoInteractions(outboxEventPublisher);
	}

	@Test
	@DisplayName("체크인만 수정해도 기존 체크아웃과 비교해 인접 숙박 겹침을 검증한다")
	void rejectOverlappingTurnoverTimesOnPartialUpdate() {
		Accommodation accommodation = Accommodation.builder()
			.id(1L)
			.status(AccommodationStatus.DRAFT)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.build();
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.checkInTime(LocalTime.of(10, 0))
			.build();
		when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(
			1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));

		assertThatThrownBy(() -> accommodationCommandService.updateAccommodation(1L, request, 2L))
			.isInstanceOfSatisfying(BaseException.class, exception ->
				assertThat(exception.getErrorCode().getCode()).isEqualTo("C001"));

		assertThat(accommodation.getCheckInTime()).isEqualTo(LocalTime.of(15, 0));
		assertThat(accommodation.getCheckOutTime()).isEqualTo(LocalTime.of(11, 0));
		verifyNoInteractions(outboxEventPublisher);
	}

	@Test
	@DisplayName("인접 숙박의 실제 시각이 겹치는 숙소는 게시할 수 없다")
	void rejectPublishingWithOverlappingTurnoverTimes() {
		Accommodation accommodation = publishableAccommodation(
			"Asia/Seoul", LocalTime.of(10, 0), LocalTime.of(12, 0));
		when(accommodationRepository.findWithDetailsExceptHostAndDeletedById(1L, 2L))
			.thenReturn(Optional.of(accommodation));

		assertThatThrownBy(() -> accommodationCommandService.publishAccommodation(1L, 2L))
			.isInstanceOfSatisfying(BaseException.class, exception ->
				assertThat(exception.getErrorCode().getCode()).isEqualTo("A003"));

		assertThat(accommodation.getStatus()).isEqualTo(AccommodationStatus.DRAFT);
		verifyNoInteractions(outboxEventPublisher);
	}

	@Test
	@DisplayName("유효하지 않은 편의시설 코드가 하나라도 있으면 A007 400으로 전체 수정을 거부한다")
	void rejectInvalidAmenityBeforeMutation() {
		Accommodation accommodation = mock(Accommodation.class);
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.addressInfo(new AddressRequest.AddressInfo(
				"04524", "KR", "Seoul", "Seoul", "Jung", "Sejong-daero", "110"
			))
			.amenityInfos(List.of(
				new AmenityRequest.AmenityInfo("wifi", 1),
				new AmenityRequest.AmenityInfo("not_a_real_amenity", 1)
			))
			.occupancyPolicyInfo(new PolicyRequest.OccupancyPolicyInfo(4, 1, 0))
			.build();

		when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(commonCodeService.isValidCode(eq(AMENITY_TYPE), anyString()))
			.thenAnswer(invocation -> "WIFI".equals(invocation.getArgument(1)));

		assertThatThrownBy(() -> accommodationCommandService.updateAccommodation(1L, request, 2L))
			.isInstanceOfSatisfying(BaseException.class, exception -> {
				assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(exception.getErrorCode().getCode()).isEqualTo("A007");
			});

		verify(accommodation, never()).updateAccommodation(any());
		verify(accommodationAmenityRepository, never()).deleteByAccommodationIdInBulk(anyLong());
		verify(accommodationAmenityRepository, never()).saveAll(any());
		verifyNoInteractions(geocodingService, addressRepository, occupancyPolicyRepository);
	}

	@Test
	@DisplayName("숙소 공통 코드 식별자는 JVM Locale과 무관하게 ASCII 대문자로 정규화한다")
	void normalizeCommonCodeIdentifiersWithRootLocale() {
		Locale previousLocale = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			Accommodation accommodation = Accommodation.builder()
				.id(1L)
				.status(AccommodationStatus.DRAFT)
				.build();
			AccommodationRequest.Update request = AccommodationRequest.Update.builder()
				.type("private_room")
				.amenityInfos(List.of(new AmenityRequest.AmenityInfo("wifi", 1)))
				.build();

			when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(
				1L, 2L, AccommodationStatus.DELETED))
				.thenReturn(Optional.of(accommodation));
			when(commonCodeService.isValidCode(ACCOMMODATION_TYPE, "PRIVATE_ROOM"))
				.thenReturn(true);
			when(commonCodeService.isValidCode(AMENITY_TYPE, "WIFI"))
				.thenReturn(true);

			accommodationCommandService.updateAccommodation(1L, request, 2L);

			assertThat(accommodation.getType()).isEqualTo("PRIVATE_ROOM");
			verify(accommodationAmenityRepository).saveAll(argThat(amenities -> {
				AccommodationAmenity amenity = amenities.iterator().next();
				return amenity.getAmenityCode().equals("WIFI");
			}));
		} finally {
			Locale.setDefault(previousLocale);
		}
	}

	@Test
	@DisplayName("체크인 시간이 바뀌는 수정은 기존 예약 절대 시각을 보호하기 위해 거부한다")
	void rejectCheckInTimeChangeThatInvalidatesReservationSnapshot() {
		Accommodation accommodation = Accommodation.builder()
			.id(1L)
			.status(AccommodationStatus.PUBLISHED)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId("Asia/Seoul")
			.build();
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.checkInTime(LocalTime.of(16, 0))
			.build();
		when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(
			1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(clock.instant()).thenReturn(NOW);
		when(reservationRepository.existsFutureInventoryReservation(1L, NOW)).thenReturn(true);

		assertThatThrownBy(() -> accommodationCommandService.updateAccommodation(1L, request, 2L))
			.isInstanceOf(kr.kro.airbob.common.exception.InvalidInputException.class);

		assertThat(accommodation.getCheckInTime()).isEqualTo(LocalTime.of(15, 0));
	}

	@Test
	@DisplayName("체크아웃 시간이 바뀌는 수정은 기존 예약 절대 시각을 보호하기 위해 거부한다")
	void rejectCheckOutTimeChangeThatInvalidatesReservationSnapshot() {
		Accommodation accommodation = Accommodation.builder()
			.id(1L)
			.accommodationUid(UUID.fromString("33333333-3333-3333-3333-333333333333"))
			.status(AccommodationStatus.PUBLISHED)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId("Asia/Seoul")
			.build();
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.checkOutTime(LocalTime.of(10, 0))
			.build();
		when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(
			1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(reservationRepository.existsFutureInventoryReservation(1L, NOW)).thenReturn(true);

		assertThatThrownBy(() -> accommodationCommandService.updateAccommodation(1L, request, 2L))
			.isInstanceOf(kr.kro.airbob.common.exception.InvalidInputException.class);

		assertThat(accommodation.getCheckOutTime()).isEqualTo(LocalTime.of(11, 0));
	}

	@Test
	@DisplayName("주소 해석 결과 시간대가 바뀌면 기존 예약 절대 시각을 보호하기 위해 거부한다")
	void rejectResolvedTimeZoneChangeThatInvalidatesReservationSnapshot() {
		Address currentAddress = Address.builder()
			.country("KR")
			.city("Seoul")
			.street("Old street")
			.postalCode("04500")
			.latitude(37.5)
			.longitude(127.0)
			.build();
		Accommodation accommodation = Accommodation.builder()
			.id(1L)
			.address(currentAddress)
			.status(AccommodationStatus.DRAFT)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId("Asia/Seoul")
			.build();
		AddressRequest.AddressInfo addressInfo = new AddressRequest.AddressInfo(
			"10001", "US", "New York", "NY", "Manhattan", "Broadway", "1");
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.addressInfo(addressInfo)
			.build();
		GeocodeResult geocodeResult = GeocodeResult.success(40.7128, -74.0060, "New York", null);
		when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(
			1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(geocodingService.getCoordinates(anyString())).thenReturn(geocodeResult);
		when(timeZoneResolver.resolve(40.7128, -74.0060))
			.thenReturn(Optional.of(ZoneId.of("America/New_York")));
		when(reservationRepository.existsFutureInventoryReservation(1L, NOW)).thenReturn(true);

		assertThatThrownBy(() -> accommodationCommandService.updateAccommodation(1L, request, 2L))
			.isInstanceOf(kr.kro.airbob.common.exception.InvalidInputException.class);

		assertThat(accommodation.getAddress()).isSameAs(currentAddress);
		assertThat(accommodation.getTimeZoneId()).isEqualTo("Asia/Seoul");
		verify(addressRepository, never()).save(any());
	}

	@Test
	@DisplayName("주소가 바뀌어도 해석된 시간대가 같으면 미래 예약이 있어도 수정할 수 있다")
	void allowAddressChangeWhenResolvedTimeZoneStaysTheSame() {
		Address currentAddress = Address.builder()
			.country("KR")
			.city("Seoul")
			.street("Old street")
			.postalCode("04500")
			.latitude(37.5)
			.longitude(127.0)
			.build();
		Accommodation accommodation = Accommodation.builder()
			.id(1L)
			.accommodationUid(UUID.fromString("44444444-4444-4444-4444-444444444444"))
			.address(currentAddress)
			.status(AccommodationStatus.PUBLISHED)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId("Asia/Seoul")
			.build();
		AddressRequest.AddressInfo addressInfo = new AddressRequest.AddressInfo(
			"48000", "KR", "Busan", "Busan", "Haeundae", "Dalmaji-gil", "1");
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.addressInfo(addressInfo)
			.build();
		GeocodeResult geocodeResult = GeocodeResult.success(35.1796, 129.0756, "Busan", null);
		when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(
			1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(geocodingService.getCoordinates(anyString())).thenReturn(geocodeResult);
		when(timeZoneResolver.resolve(35.1796, 129.0756))
			.thenReturn(Optional.of(ZoneId.of("Asia/Seoul")));
		when(addressRepository.save(any(Address.class))).thenAnswer(invocation -> invocation.getArgument(0));

		accommodationCommandService.updateAccommodation(1L, request, 2L);

		assertThat(accommodation.getAddress().getCity()).isEqualTo("Busan");
		assertThat(accommodation.getTimeZoneId()).isEqualTo("Asia/Seoul");
		verify(reservationRepository, never()).existsFutureInventoryReservation(anyLong(), any());
	}

	@Test
	@DisplayName("미래 유효 예약이 없으면 체크인 시간을 변경할 수 있다")
	void allowCheckInTimeChangeWithoutFutureActiveReservation() {
		Accommodation accommodation = Accommodation.builder()
			.id(1L)
			.accommodationUid(UUID.fromString("22222222-2222-2222-2222-222222222222"))
			.status(AccommodationStatus.PUBLISHED)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId("Asia/Seoul")
			.build();
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.checkInTime(LocalTime.of(16, 0))
			.build();
		when(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(
			1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(clock.instant()).thenReturn(NOW);
		when(reservationRepository.existsFutureInventoryReservation(1L, NOW)).thenReturn(false);

		accommodationCommandService.updateAccommodation(1L, request, 2L);

		assertThat(accommodation.getCheckInTime()).isEqualTo(LocalTime.of(16, 0));
	}

	private AddressRequest.AddressInfo seoulAddressInfo() {
		return new AddressRequest.AddressInfo(
			"04524", "KR", "Seoul", "Seoul", "Jung", "Sejong-daero", "110"
		);
	}

	private Accommodation publishableAccommodation() {
		return publishableAccommodation(null);
	}

	private Accommodation publishableAccommodation(String timeZoneId) {
		return publishableAccommodation(
			timeZoneId, LocalTime.of(15, 0), LocalTime.of(11, 0));
	}

	private Accommodation publishableAccommodation(
		String timeZoneId,
		LocalTime checkInTime,
		LocalTime checkOutTime
	) {
		return Accommodation.builder()
			.id(1L)
			.accommodationUid(UUID.fromString("11111111-1111-1111-1111-111111111111"))
			.name("게시 가능한 숙소")
			.description("설명")
			.basePrice(100_000L)
			.currency("KRW")
			.type("ENTIRE_HOME")
			.timeZoneId(timeZoneId)
			.address(Address.builder()
				.country("KR")
				.city("Seoul")
				.street("Sejong-daero")
				.postalCode("04524")
				.latitude(37.5665)
				.longitude(126.9780)
				.build())
			.occupancyPolicy(OccupancyPolicy.builder()
				.maxOccupancy(4)
				.infantOccupancy(1)
				.petOccupancy(0)
				.build())
			.checkInTime(checkInTime)
			.checkOutTime(checkOutTime)
			.status(AccommodationStatus.DRAFT)
			.build();
	}
}
