package kr.kro.airbob.domain.reservation.policy;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;

public final class ReservationStayPricePolicy {

	private ReservationStayPricePolicy() {
	}

	public static StayPrice calculate(long nightlyPrice, LocalDate checkInDate, LocalDate checkOutDate) {
		long nights = ChronoUnit.DAYS.between(checkInDate, checkOutDate);
		if (nights <= 0) {
			throw new InvalidReservationDateException();
		}
		return new StayPrice(
			nightlyPrice,
			nights,
			Math.multiplyExact(nightlyPrice, nights)
		);
	}

	public record StayPrice(
		long nightlyPrice,
		long nights,
		long subtotal
	) {
	}
}
