package kr.kro.airbob.domain.accommodation.service;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.projection.AccommodationBookingProjection;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 직접 availability 점유 계약 테스트")
class AccommodationAvailabilityOccupancyContractTest {

	private static final Instant QUERIED_AT = Instant.parse("2026-08-25T00:00:00Z");
	private static final LocalDate WINDOW_START = LocalDate.of(2026, 8, 25);
	private static final LocalDate WINDOW_END_EXCLUSIVE = WINDOW_START.plusMonths(3);

	@Mock private AccommodationReviewSummaryRepository reviewSummaryRepository;
	@Mock private AccommodationRepository accommodationRepository;
	@Mock private ReservationRepository reservationRepository;
	@Mock private CursorPageInfoCreator cursorPageInfoCreator;
	@Mock private BookingWindowProvider bookingWindowProvider;
	@Mock private AccommodationDetailReader accommodationDetailReader;
	@Mock private AccommodationDetailCache accommodationDetailCache;
	@Mock private Clock clock;

	@InjectMocks
	private AccommodationQueryService service;

	@Test
	@DisplayName("직접 availability는 조회 기준 시각을 포함한 점유 구간 조회를 사용한다")
	void directAvailabilityUsesTimeAwareOccupancyQuery() {
		long accommodationId = 42L;
		when(accommodationRepository.findBookingProjectionByIdAndStatus(
			accommodationId,
			AccommodationStatus.PUBLISHED
		)).thenReturn(Optional.of(new AccommodationBookingProjection("Asia/Seoul")));
		when(bookingWindowProvider.currentFor("Asia/Seoul"))
			.thenReturn(new BookingWindow(WINDOW_START, WINDOW_END_EXCLUSIVE));
		when(clock.instant()).thenReturn(QUERIED_AT);

		service.findAccommodationAvailability(accommodationId);

		verify(reservationRepository).findUnavailableReservationRangesByAccommodationId(
			accommodationId,
			WINDOW_START,
			WINDOW_END_EXCLUSIVE,
			QUERIED_AT
		);
		verify(reservationRepository, never()).findActiveReservationRangesByAccommodationId(
			eq(accommodationId),
			eq(WINDOW_START),
			eq(WINDOW_END_EXCLUSIVE)
		);
	}
}
