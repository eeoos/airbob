package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.projection.PublicAccommodationDetailProjection;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 상세 원본 조회기 단위 테스트")
class AccommodationDetailReaderTest {

	@Mock private AccommodationRepository accommodationRepository;
	@Mock private AccommodationAmenityRepository accommodationAmenityRepository;
	@Mock private AccommodationImageRepository accommodationImageRepository;
	@Mock private WishlistAccommodationRepository wishlistAccommodationRepository;

	@InjectMocks
	private AccommodationDetailReader reader;

	@Test
	@DisplayName("공개 숙소의 공용 상세 스냅샷을 찜 여부 없이 조회한다")
	void loadSharedSnapshotWithoutViewerState() {
		when(accommodationRepository.findWithDetailsByAccommodationIdAndStatus(
			1L, AccommodationStatus.PUBLISHED))
			.thenReturn(Optional.of(new PublicAccommodationDetailProjection(
				1L, "서울의 집", "설명", "HOUSE", 100000L, "KRW",
				LocalTime.of(15, 0), LocalTime.of(11, 0), "Asia/Seoul",
				null, null, null, null, null, null, 20L, "호스트", null,
				4, 1, 0, 3, new BigDecimal("4.50"))));
		when(accommodationAmenityRepository.findDetailAmenitiesByAccommodationId(1L)).thenReturn(List.of());
		when(accommodationImageRepository.findDetailImagesByAccommodationIdOrderByIdAsc(1L)).thenReturn(List.of());

		AccommodationDetailSnapshot snapshot = reader.load(1L);

		assertThat(snapshot.id()).isEqualTo(1L);
		assertThat(snapshot.name()).isEqualTo("서울의 집");
		assertThat(snapshot.reviewSummary().totalCount()).isEqualTo(3);
		assertThat(snapshot.reviewSummary().averageRating()).isEqualByComparingTo("4.50");
		verifyNoInteractions(wishlistAccommodationRepository);
	}

	@Test
	@DisplayName("게시되지 않은 숙소는 상세 원본을 만들지 않는다")
	void rejectMissingPublishedAccommodation() {
		when(accommodationRepository.findWithDetailsByAccommodationIdAndStatus(
			1L, AccommodationStatus.PUBLISHED)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> reader.load(1L))
			.isInstanceOf(AccommodationNotFoundException.class);

		verifyNoInteractions(accommodationAmenityRepository, accommodationImageRepository);
	}

	@Test
	@DisplayName("로그인 사용자의 찜 여부는 공용 상세와 분리해 조회한다")
	void readsWishlistStateSeparately() {
		when(wishlistAccommodationRepository
			.existsByWishlist_Member_IdAndAccommodation_Id(7L, 1L)).thenReturn(true);

		assertThat(reader.isInWishlist(1L, 7L)).isTrue();
	}
}
