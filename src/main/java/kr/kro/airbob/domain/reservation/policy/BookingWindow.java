package kr.kro.airbob.domain.reservation.policy;

import java.time.LocalDate;

public record BookingWindow(
	LocalDate startInclusive,
	LocalDate endExclusive
) {

	private static final long BOOKING_WINDOW_MONTHS = 3L;

	public static BookingWindow current() {
		return startingOn(LocalDate.now());
	}

	public static BookingWindow startingOn(LocalDate startInclusive) {
		return new BookingWindow(
			startInclusive,
			startInclusive.plusMonths(BOOKING_WINDOW_MONTHS)
		);
	}

	public boolean containsStay(LocalDate checkInDate, LocalDate checkOutDate) {
		return checkOutDate.isAfter(checkInDate)
			&& !checkInDate.isBefore(startInclusive)
			&& checkInDate.isBefore(endExclusive)
			&& !checkOutDate.isAfter(endExclusive);
	}
}
