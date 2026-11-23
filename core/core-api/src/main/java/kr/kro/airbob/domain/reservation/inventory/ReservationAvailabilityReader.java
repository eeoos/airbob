package kr.kro.airbob.domain.reservation.inventory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.accommodation.port.AccommodationAvailability;
import kr.kro.airbob.core.domain.accommodation.port.AccommodationAvailabilityReader;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import lombok.RequiredArgsConstructor;

/** 숙소 시간대의 예약 가능 기간을 정하고, 그 기간의 재고 스냅샷에서 지금 예약할 수 없는 구간을 찾는다. */
@Component
@RequiredArgsConstructor
public class ReservationAvailabilityReader implements AccommodationAvailabilityReader {

	private final BookingWindowProvider bookingWindowProvider;
	private final ReservationInventoryManager inventoryManager;
	private final Clock clock;

	@Override
	public AccommodationAvailability readAvailability(Long accommodationId, String timeZoneId) {
		BookingWindow bookingWindow = bookingWindowProvider.currentFor(timeZoneId);
		Instant queriedAt = clock.instant();
		List<AccommodationAvailability.DateRange> unavailableRanges = inventoryManager
			.findUnavailableRangesSnapshot(
				accommodationId,
				bookingWindow.startInclusive(),
				bookingWindow.endExclusive(),
				queriedAt
			)
			.stream()
			.map(range -> new AccommodationAvailability.DateRange(range.startInclusive(), range.endExclusive()))
			.toList();
		return new AccommodationAvailability(
			bookingWindow.startInclusive(), bookingWindow.endExclusive(), unavailableRanges);
	}
}
