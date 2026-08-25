package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
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
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.projection.AccommodationBookingProjection;
import kr.kro.airbob.domain.reservation.dto.ReservationDateRange;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 조회 서비스 단위 테스트")
class AccommodationQueryServiceTest {
	private static final String DEFAULT_TIME_ZONE_ID = "Asia/Seoul";
	private static final Instant AVAILABILITY_QUERIED_AT = Instant.parse("2026-08-12T00:00:00Z");
	private static final LocalDate BOOKING_WINDOW_START = LocalDate.of(2026, 8, 12);
	private static final BookingWindow BOOKING_WINDOW = BookingWindow.startingOn(BOOKING_WINDOW_START);

	@Mock private AccommodationRepository accommodationRepository;
	@Mock private AccommodationReviewSummaryRepository reviewSummaryRepository;
	@Mock private ReservationRepository reservationRepository;
	@Mock private CursorPageInfoCreator cursorPageInfoCreator;
	@Mock private BookingWindowProvider bookingWindowProvider;
	@Mock private AccommodationDetailReader accommodationDetailReader;
	@Mock private AccommodationDetailCache accommodationDetailCache;
	@Mock private Clock clock;

	@InjectMocks
	private AccommodationQueryService accommodationQueryService;

	@Test
	@DisplayName("비로그인 숙소 상세 조회는 찜 여부를 조회하지 않고 false를 반환한다")
	void anonymousAccommodationDetailSkipsWishlistLookup() {
		givenCachedAccommodation(1L);

		AccommodationResponse.DetailInfo response = accommodationQueryService.findAccommodation(1L, null);

		assertThat(response.isInWishlist()).isFalse();
		verify(accommodationDetailReader, never()).isInWishlist(anyLong(), anyLong());
	}

	@Test
	@DisplayName("로그인 숙소 상세 조회는 현재 회원과 숙소 ID로 찜 여부를 조회한다")
	void authenticatedAccommodationDetailUsesViewerIdForWishlistLookup() {
		givenCachedAccommodation(1L);
		when(accommodationDetailReader.isInWishlist(1L, 7L)).thenReturn(true);

		AccommodationResponse.DetailInfo response = accommodationQueryService.findAccommodation(1L, 7L);

		assertThat(response.isInWishlist()).isTrue();
		verify(accommodationDetailReader).isInWishlist(1L, 7L);
	}

	@Test
	@DisplayName("숙소 예약 가능 조회는 숙소 현지 시간대의 예약 가능 기간을 반환한다")
	void accommodationAvailabilityUsesBookingWindowForAccommodationTimeZone() {
		BookingWindow newYorkWindow = BookingWindow.startingOn(LocalDate.of(2026, 8, 11));
		givenPublishedAccommodationAvailability(1L, "America/New_York", newYorkWindow);

		AccommodationResponse.Availability response =
			accommodationQueryService.findAccommodationAvailability(1L);

		assertThat(response.bookingWindowStartInclusive()).isEqualTo(LocalDate.of(2026, 8, 11));
		assertThat(response.bookingWindowEndExclusive()).isEqualTo(LocalDate.of(2026, 11, 11));
		verify(bookingWindowProvider).currentFor("America/New_York");
	}

	@Test
	@DisplayName("숙소 상세 조회는 예약 정보를 조회하지 않는다")
	void accommodationDetailSkipsAvailabilityLookup() {
		givenCachedAccommodation(1L);

		accommodationQueryService.findAccommodation(1L, null);

		verify(accommodationDetailCache).getOrLoad(eq(1L), any());
		verifyNoInteractions(bookingWindowProvider, reservationRepository);
	}

	@Test
	@DisplayName("숙소 예약 가능 조회는 3개월 예약 구간을 숙소 ID로 조회한다")
	void accommodationAvailabilityUsesAccommodationIdForReservations() {
		givenPublishedAccommodationAvailability(1L);

		AccommodationResponse.Availability response =
			accommodationQueryService.findAccommodationAvailability(1L);
		ArgumentCaptor<LocalDate> windowStartCaptor = ArgumentCaptor.forClass(LocalDate.class);
		ArgumentCaptor<LocalDate> windowEndCaptor = ArgumentCaptor.forClass(LocalDate.class);

		verify(reservationRepository).findUnavailableReservationRangesByAccommodationId(
			eq(1L), windowStartCaptor.capture(), windowEndCaptor.capture(),
			eq(AVAILABILITY_QUERIED_AT));
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
	@DisplayName("숙소 예약 가능 조회는 숙박일을 펼치지 않고 checkout 제외 구간으로 반환한다")
	void accommodationAvailabilityReturnsRangeWithoutExpandingDates() {
		Long accommodationId = 1L;
		LocalDate today = BOOKING_WINDOW_START;
		givenPublishedAccommodationAvailability(accommodationId);
		when(reservationRepository.findUnavailableReservationRangesByAccommodationId(
			eq(accommodationId), any(LocalDate.class), any(LocalDate.class), any(Instant.class)))
			.thenReturn(List.of(new ReservationDateRange(
				today.plusDays(1),
				today.plusDays(4)
			)));

		AccommodationResponse.Availability response =
			accommodationQueryService.findAccommodationAvailability(accommodationId);

		assertThat(response.bookingWindowEndExclusive()).isEqualTo(today.plusMonths(3));
		assertThat(response.unavailableRanges()).containsExactly(
			new AccommodationResponse.UnavailableDateRange(today.plusDays(1), today.plusDays(4))
		);
	}

	@Test
	@DisplayName("숙소 예약 불가 구간은 3개월 범위로 자르고 정렬해 합친다")
	void accommodationAvailabilityNormalizesRangesWithinBookingWindow() {
		Long accommodationId = 1L;
		LocalDate windowStart = BOOKING_WINDOW_START;
		LocalDate windowEndExclusive = windowStart.plusMonths(3);
		givenPublishedAccommodationAvailability(accommodationId);
		when(reservationRepository.findUnavailableReservationRangesByAccommodationId(
			eq(accommodationId), any(LocalDate.class), any(LocalDate.class), any(Instant.class)))
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

		AccommodationResponse.Availability response =
			accommodationQueryService.findAccommodationAvailability(accommodationId);

		assertThat(response.unavailableRanges()).containsExactly(
			new AccommodationResponse.UnavailableDateRange(windowStart, windowStart.plusDays(6)),
			new AccommodationResponse.UnavailableDateRange(
				windowEndExclusive.minusDays(1), windowEndExclusive)
		);
	}

	@Test
	@DisplayName("게시되지 않은 숙소의 예약 가능 정보는 조회할 수 없다")
	void accommodationAvailabilityRequiresPublishedAccommodation() {
		when(accommodationRepository.findBookingProjectionByIdAndStatus(
			1L, AccommodationStatus.PUBLISHED)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> accommodationQueryService.findAccommodationAvailability(1L))
			.isInstanceOf(AccommodationNotFoundException.class);
		verifyNoInteractions(bookingWindowProvider, reservationRepository);
	}

	@Test
	@DisplayName("호스트 숙소 상세 조회는 이미지를 숙소 ID로 조회한다")
	void hostAccommodationDetailUsesAccommodationIdForImages() {
		Accommodation accommodation = mock(Accommodation.class);
		when(accommodationRepository.findWithDetailsByIdAndHostId(1L, 7L))
			.thenReturn(Optional.of(accommodation));
		when(accommodationDetailReader.loadAmenities(1L)).thenReturn(List.of());
		when(accommodationDetailReader.loadImages(1L)).thenReturn(List.of());
		when(reviewSummaryRepository.findByAccommodationId(1L)).thenReturn(Optional.empty());

		accommodationQueryService.findHostAccommodationDetail(1L, 7L);

		verify(accommodationDetailReader).loadImages(1L);
	}

	private void givenCachedAccommodation(Long accommodationId) {
		AccommodationDetailSnapshot snapshot = new AccommodationDetailSnapshot(
			accommodationId, "숙소", null, null, null, null, null, null,
			DEFAULT_TIME_ZONE_ID, null, null, null, null, List.of(), List.of(), null);
		when(accommodationDetailCache.getOrLoad(eq(accommodationId), any())).thenReturn(snapshot);
	}

	private void givenPublishedAccommodationAvailability(Long accommodationId) {
		givenPublishedAccommodationAvailability(accommodationId, DEFAULT_TIME_ZONE_ID, BOOKING_WINDOW);
	}

	private void givenPublishedAccommodationAvailability(
		Long accommodationId,
		String timeZoneId,
		BookingWindow bookingWindow
	) {
		when(accommodationRepository.findBookingProjectionByIdAndStatus(
			accommodationId, AccommodationStatus.PUBLISHED))
			.thenReturn(Optional.of(new AccommodationBookingProjection(timeZoneId)));
		when(bookingWindowProvider.currentFor(timeZoneId)).thenReturn(bookingWindow);
		when(clock.instant()).thenReturn(AVAILABILITY_QUERIED_AT);
	}
}
