package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.projection.AccommodationDetailProjection;
import kr.kro.airbob.domain.reservation.dto.ReservationDateRange;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 조회 서비스 단위 테스트")
class AccommodationQueryServiceTest {
	private static final String DEFAULT_TIME_ZONE_ID = "Asia/Seoul";
	private static final LocalDate BOOKING_WINDOW_START = LocalDate.of(2026, 8, 12);
	private static final BookingWindow BOOKING_WINDOW = BookingWindow.startingOn(BOOKING_WINDOW_START);

	@Mock private AccommodationRepository accommodationRepository;
	@Mock private AccommodationAmenityRepository accommodationAmenityRepository;
	@Mock private AccommodationImageRepository accommodationImageRepository;
	@Mock private AccommodationReviewSummaryRepository reviewSummaryRepository;
	@Mock private ReservationRepository reservationRepository;
	@Mock private WishlistAccommodationRepository wishlistAccommodationRepository;
	@Mock private CursorPageInfoCreator cursorPageInfoCreator;
	@Mock private BookingWindowProvider bookingWindowProvider;

	@InjectMocks
	private AccommodationQueryService accommodationQueryService;

	@Test
	@DisplayName("비로그인 숙소 상세 조회는 찜 여부를 조회하지 않고 false를 반환한다")
	void anonymousAccommodationDetailSkipsWishlistLookup() {
		givenPublishedAccommodation(1L);

		AccommodationResponse.DetailInfo response = accommodationQueryService.findAccommodation(1L, null);

		assertThat(response.isInWishlist()).isFalse();
		verifyNoInteractions(wishlistAccommodationRepository);
	}

	@Test
	@DisplayName("로그인 숙소 상세 조회는 현재 회원과 숙소 ID로 찜 여부를 조회한다")
	void authenticatedAccommodationDetailUsesViewerIdForWishlistLookup() {
		givenPublishedAccommodation(1L);
		when(wishlistAccommodationRepository.existsByWishlist_Member_IdAndAccommodation_Id(7L, 1L))
			.thenReturn(true);

		AccommodationResponse.DetailInfo response = accommodationQueryService.findAccommodation(1L, 7L);

		assertThat(response.isInWishlist()).isTrue();
		verify(wishlistAccommodationRepository)
			.existsByWishlist_Member_IdAndAccommodation_Id(7L, 1L);
	}

	@Test
	@DisplayName("숙소 상세 조회는 숙소 현지 시간대의 예약 가능 기간을 반환한다")
	void accommodationDetailUsesBookingWindowForAccommodationTimeZone() {
		BookingWindow newYorkWindow = BookingWindow.startingOn(LocalDate.of(2026, 8, 11));
		givenPublishedAccommodation(1L, "America/New_York", newYorkWindow);

		AccommodationResponse.DetailInfo response = accommodationQueryService.findAccommodation(1L, null);

		assertThat(response.bookingWindowStartInclusive()).isEqualTo(LocalDate.of(2026, 8, 11));
		assertThat(response.bookingWindowEndExclusive()).isEqualTo(LocalDate.of(2026, 11, 11));
		verify(bookingWindowProvider).currentFor("America/New_York");
	}

	@Test
	@DisplayName("숙소 상세 조회는 이미지와 3개월 예약 구간을 숙소 ID로 조회한다")
	void accommodationDetailUsesAccommodationIdForImagesAndReservations() {
		givenPublishedAccommodation(1L);

		AccommodationResponse.DetailInfo response = accommodationQueryService.findAccommodation(1L, null);
		ArgumentCaptor<LocalDate> windowStartCaptor = ArgumentCaptor.forClass(LocalDate.class);
		ArgumentCaptor<LocalDate> windowEndCaptor = ArgumentCaptor.forClass(LocalDate.class);

		verify(accommodationImageRepository).findByAccommodationIdOrderByIdAsc(1L);
		verify(reservationRepository).findActiveReservationRangesByAccommodationId(
			eq(1L), windowStartCaptor.capture(), windowEndCaptor.capture());
		assertThat(windowEndCaptor.getValue()).isEqualTo(windowStartCaptor.getValue().plusMonths(3));
		assertThat(response.bookingWindowStartInclusive())
			.isEqualTo(windowStartCaptor.getValue());
		assertThat(response.bookingWindowEndExclusive())
			.isEqualTo(windowEndCaptor.getValue());
		verify(reservationRepository, never())
			.findActiveReservationRangesByAccommodationUid(
				any(UUID.class), any(LocalDate.class), any(LocalDate.class));
	}

	@Test
	@DisplayName("숙소 상세 예약은 숙박일을 펼치지 않고 checkout 제외 구간으로 반환한다")
	void accommodationDetailReturnsUnavailableRangeWithoutExpandingDates() {
		Long accommodationId = 1L;
		LocalDate today = BOOKING_WINDOW_START;
		givenPublishedAccommodation(accommodationId);
		when(reservationRepository.findActiveReservationRangesByAccommodationId(
			eq(accommodationId), any(LocalDate.class), any(LocalDate.class)))
			.thenReturn(List.of(new ReservationDateRange(
				today.plusDays(1),
				today.plusDays(4)
			)));

		AccommodationResponse.DetailInfo response =
			accommodationQueryService.findAccommodation(accommodationId, null);

		assertThat(response.bookingWindowEndExclusive()).isEqualTo(today.plusMonths(3));
		assertThat(response.unavailableRanges()).containsExactly(
			new AccommodationResponse.UnavailableDateRange(today.plusDays(1), today.plusDays(4))
		);
	}

	@Test
	@DisplayName("숙소 상세 예약 불가 구간은 3개월 범위로 자르고 정렬해 합친다")
	void accommodationDetailNormalizesUnavailableRangesWithinBookingWindow() {
		Long accommodationId = 1L;
		LocalDate windowStart = BOOKING_WINDOW_START;
		LocalDate windowEndExclusive = windowStart.plusMonths(3);
		givenPublishedAccommodation(accommodationId);
		when(reservationRepository.findActiveReservationRangesByAccommodationId(
			eq(accommodationId), any(LocalDate.class), any(LocalDate.class)))
			.thenReturn(List.of(
				new ReservationDateRange(
					windowEndExclusive.minusDays(1),
					windowEndExclusive.plusDays(5)),
				new ReservationDateRange(
					windowStart.plusDays(2),
					windowStart.plusDays(5)),
				new ReservationDateRange(
					windowStart.minusDays(2),
					windowStart.plusDays(3)),
				new ReservationDateRange(
					windowStart.plusDays(5),
					windowStart.plusDays(6)),
				new ReservationDateRange(
					windowStart.minusDays(2),
					windowStart),
				new ReservationDateRange(
					windowEndExclusive,
					windowEndExclusive.plusDays(2))
			));

		AccommodationResponse.DetailInfo response =
			accommodationQueryService.findAccommodation(accommodationId, null);

		assertThat(response.unavailableRanges()).containsExactly(
			new AccommodationResponse.UnavailableDateRange(windowStart, windowStart.plusDays(6)),
			new AccommodationResponse.UnavailableDateRange(
				windowEndExclusive.minusDays(1), windowEndExclusive)
		);
	}

	@Test
	@DisplayName("호스트 숙소 상세 조회는 이미지를 숙소 ID로 조회한다")
	void hostAccommodationDetailUsesAccommodationIdForImages() {
		Accommodation accommodation = mock(Accommodation.class);
		when(accommodationRepository.findWithDetailsByIdAndHostId(1L, 7L))
			.thenReturn(Optional.of(accommodation));
		when(accommodationAmenityRepository.findAllByAccommodationId(1L)).thenReturn(List.of());
		when(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L)).thenReturn(List.of());
		when(reviewSummaryRepository.findByAccommodationId(1L)).thenReturn(Optional.empty());

		accommodationQueryService.findHostAccommodationDetail(1L, 7L);

		verify(accommodationImageRepository).findByAccommodationIdOrderByIdAsc(1L);
	}

	private Accommodation givenPublishedAccommodation(Long accommodationId) {
		return givenPublishedAccommodation(accommodationId, DEFAULT_TIME_ZONE_ID, BOOKING_WINDOW);
	}

	private Accommodation givenPublishedAccommodation(
		Long accommodationId,
		String timeZoneId,
		BookingWindow bookingWindow
	) {
		Accommodation accommodation = mock(Accommodation.class);

		when(accommodationRepository.findWithDetailsByAccommodationIdAndStatus(
			accommodationId, AccommodationStatus.PUBLISHED))
			.thenReturn(Optional.of(new AccommodationDetailProjection(
				accommodation,
				0,
				BigDecimal.ZERO
			)));
		when(accommodationAmenityRepository.findAllByAccommodationId(accommodationId)).thenReturn(List.of());
		when(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(accommodationId))
			.thenReturn(List.of());
		when(accommodation.getTimeZoneId()).thenReturn(timeZoneId);
		when(bookingWindowProvider.currentFor(timeZoneId)).thenReturn(bookingWindow);
		return accommodation;
	}
}
