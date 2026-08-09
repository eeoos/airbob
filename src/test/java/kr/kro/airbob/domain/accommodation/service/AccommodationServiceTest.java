package kr.kro.airbob.domain.accommodation.service;

import static kr.kro.airbob.domain.commoncode.common.CommonCodeGroups.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 서비스 단위 테스트")
class AccommodationServiceTest {

	@Mock
	private AccommodationRepository accommodationRepository;

	@Mock
	private AccommodationAmenityRepository accommodationAmenityRepository;

	@Mock
	private AccommodationImageRepository accommodationImageRepository;

	@Mock
	private AccommodationReviewSummaryRepository reviewSummaryRepository;

	@Mock
	private ReservationRepository reservationRepository;

	@Mock
	private WishlistAccommodationRepository wishlistAccommodationRepository;

	@Mock
	private CommonCodeService commonCodeService;

	@InjectMocks
	private AccommodationService accommodationService;

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

		assertThatThrownBy(() -> accommodationService.updateAccommodation(1L, request, 2L))
			.isInstanceOfSatisfying(BaseException.class, exception -> {
				assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(exception.getErrorCode().getCode()).isEqualTo("A004");
			});
	}

	@Test
	@DisplayName("비로그인 숙소 상세 조회는 찜 여부를 조회하지 않고 false를 반환한다")
	void anonymousAccommodationDetailSkipsWishlistLookup() {
		givenPublishedAccommodation(1L);

		AccommodationResponse.DetailInfo response = accommodationService.findAccommodation(1L, null);

		assertThat(response.isInWishlist()).isFalse();
		verifyNoInteractions(wishlistAccommodationRepository);
	}

	@Test
	@DisplayName("로그인 숙소 상세 조회는 현재 회원과 숙소 ID로 찜 여부를 조회한다")
	void authenticatedAccommodationDetailUsesViewerIdForWishlistLookup() {
		givenPublishedAccommodation(1L);
		when(wishlistAccommodationRepository.existsByWishlist_Member_IdAndAccommodation_Id(7L, 1L))
			.thenReturn(true);

		AccommodationResponse.DetailInfo response = accommodationService.findAccommodation(1L, 7L);

		assertThat(response.isInWishlist()).isTrue();
		verify(wishlistAccommodationRepository)
			.existsByWishlist_Member_IdAndAccommodation_Id(7L, 1L);
	}

	private void givenPublishedAccommodation(Long accommodationId) {
		Accommodation accommodation = mock(Accommodation.class);
		UUID accommodationUid = UUID.randomUUID();

		when(accommodationRepository.findWithDetailsByAccommodationIdAndStatus(
			accommodationId, AccommodationStatus.PUBLISHED))
			.thenReturn(Optional.of(accommodation));
		when(accommodation.getId()).thenReturn(accommodationId);
		when(accommodation.getAccommodationUid()).thenReturn(accommodationUid);
		when(accommodationAmenityRepository.findAllByAccommodationId(accommodationId))
			.thenReturn(List.of());
		when(accommodationImageRepository.findByAccommodation_AccommodationUidOrderByIdAsc(accommodationUid))
			.thenReturn(List.of());
		when(reviewSummaryRepository.findByAccommodationId(accommodationId))
			.thenReturn(Optional.empty());
		when(reservationRepository.findFutureCompletedReservations(accommodationUid))
			.thenReturn(List.of());
	}
}
