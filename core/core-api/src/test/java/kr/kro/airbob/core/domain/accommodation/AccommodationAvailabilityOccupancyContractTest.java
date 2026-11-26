package kr.kro.airbob.core.domain.accommodation;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.core.api.controller.v1.accommodation.response.AccommodationResponse;
import kr.kro.airbob.core.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.core.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.core.domain.accommodation.repository.projection.AccommodationBookingProjection;
import kr.kro.airbob.core.domain.reservation.inventory.ReservationAvailabilityReader;
import kr.kro.airbob.core.domain.reservation.inventory.ReservationInventoryManager;
import kr.kro.airbob.core.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.core.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.core.support.cursor.util.CursorPageInfoCreator;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 직접 availability 점유 계약 테스트")
class AccommodationAvailabilityOccupancyContractTest {

	private static final Instant QUERIED_AT = Instant.parse("2026-08-25T00:00:00Z");
	private static final LocalDate WINDOW_START = LocalDate.of(2026, 8, 25);
	private static final LocalDate WINDOW_END_EXCLUSIVE = WINDOW_START.plusMonths(3);

	@Mock
	private AccommodationRepository accommodationRepository;
	@Mock
	private ReservationInventoryManager inventoryManager;
	@Mock
	private CursorPageInfoCreator cursorPageInfoCreator;
	@Mock
	private BookingWindowProvider bookingWindowProvider;
	@Mock
	private AccommodationDetailReader accommodationDetailReader;
	@Mock
	private AccommodationDetailCache accommodationDetailCache;
	@Mock
	private Clock clock;

	private AccommodationQueryService service;

	@BeforeEach
	void setUp() {
		service = new AccommodationQueryService(new AccommodationReader(accommodationRepository),
			new ReservationAvailabilityReader(bookingWindowProvider, inventoryManager, clock), cursorPageInfoCreator,
			accommodationDetailReader, accommodationDetailCache);
	}

	@Test
	@DisplayName("직접 availability는 reservation overlap 대신 조회 시각을 포함한 inventory snapshot을 사용한다")
	void directAvailabilityUsesInventorySnapshot() {
		long accommodationId = 42L;
		given(accommodationRepository.findBookingProjectionByIdAndStatus(
			accommodationId,
			AccommodationStatus.PUBLISHED)).willReturn(Optional.of(new AccommodationBookingProjection("Asia/Seoul")));
		given(bookingWindowProvider.currentFor("Asia/Seoul"))
			.willReturn(new BookingWindow(WINDOW_START, WINDOW_END_EXCLUSIVE));
		given(clock.instant()).willReturn(QUERIED_AT);

		AccommodationResponse.Availability.from(service.getAccommodationAvailability(accommodationId));

		then(inventoryManager).should().findUnavailableRangesSnapshot(
			accommodationId,
			WINDOW_START,
			WINDOW_END_EXCLUSIVE,
			QUERIED_AT);
	}
}
