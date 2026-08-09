package kr.kro.airbob.domain.accommodation.service;

import static kr.kro.airbob.domain.commoncode.common.CommonCodeGroups.AMENITY_TYPE;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AmenityRequest;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationHistoryRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 편의시설 삭제 Before 벤치마크 서비스 테스트")
class AccommodationAmenityDeleteBeforeBenchmarkServiceTest {

	@Mock private AccommodationAmenityRepository accommodationAmenityRepository;
	@Mock private AccommodationRepository accommodationRepository;
	@Mock private AccommodationHistoryRepository accommodationHistoryRepository;
	@Mock private CommonCodeService commonCodeService;
	@InjectMocks private AccommodationAmenityDeleteBeforeBenchmarkService service;

	@Test
	@DisplayName("편의시설 코드는 JVM Locale과 무관하게 ASCII 대문자로 정규화한다")
	void normalizeAmenityCodeWithRootLocale() {
		Locale previousLocale = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			Accommodation accommodation = Accommodation.builder()
				.id(1L)
				.status(AccommodationStatus.DRAFT)
				.build();
			AccommodationRequest.Update request = AccommodationRequest.Update.builder()
				.amenityInfos(List.of(new AmenityRequest.AmenityInfo("wifi", 1)))
				.build();

			given(accommodationRepository.findByIdAndMemberIdAndStatusNot(
				1L, 2L, AccommodationStatus.DELETED))
				.willReturn(Optional.of(accommodation));
			given(commonCodeService.isValidCode(AMENITY_TYPE, "WIFI")).willReturn(true);

			service.fullReplacement(1L, request, 2L);

			verify(accommodationAmenityRepository).saveAll(argThat(amenities -> {
				AccommodationAmenity amenity = amenities.iterator().next();
				return amenity.getAmenityCode().equals("WIFI");
			}));
		} finally {
			Locale.setDefault(previousLocale);
		}
	}
}
