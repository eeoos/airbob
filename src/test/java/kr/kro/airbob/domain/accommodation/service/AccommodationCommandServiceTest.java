package kr.kro.airbob.domain.accommodation.service;

import static kr.kro.airbob.domain.commoncode.common.CommonCodeGroups.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationHistoryRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.AddressRepository;
import kr.kro.airbob.domain.accommodation.repository.OccupancyPolicyRepository;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.geo.GeocodingService;
import kr.kro.airbob.outbox.OutboxEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 명령 서비스 단위 테스트")
class AccommodationCommandServiceTest {

	@Mock private AccommodationRepository accommodationRepository;
	@Mock private AccommodationAmenityRepository accommodationAmenityRepository;
	@Mock private AccommodationHistoryRepository accommodationHistoryRepository;
	@Mock private AccommodationImageRepository accommodationImageRepository;
	@Mock private AddressRepository addressRepository;
	@Mock private OccupancyPolicyRepository occupancyPolicyRepository;
	@Mock private CommonCodeService commonCodeService;
	@Mock private GeocodingService geocodingService;
	@Mock private MemberRepository memberRepository;
	@Mock private OutboxEventPublisher outboxEventPublisher;

	@InjectMocks
	private AccommodationCommandService accommodationCommandService;

	@Test
	@DisplayName("유효하지 않은 숙소 유형은 A004와 400 응답용 예외로 거부한다")
	void rejectInvalidAccommodationTypeAsBadRequest() {
		Accommodation accommodation = mock(Accommodation.class);
		AccommodationRequest.Update request = AccommodationRequest.Update.builder()
			.type("not_a_type")
			.build();

		when(accommodationRepository.findByIdAndMemberIdAndStatusNot(1L, 2L, AccommodationStatus.DELETED))
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

		when(accommodationRepository.findByIdAndMemberIdAndStatusNot(1L, 2L, AccommodationStatus.DELETED))
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

			when(accommodationRepository.findByIdAndMemberIdAndStatusNot(
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
}
