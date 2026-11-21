package kr.kro.airbob.domain.reservation.command;

import java.time.LocalDate;

public record ReservationCreateCommand(
	Long accommodationId,
	LocalDate checkInDate,
	LocalDate checkOutDate,
	Integer guestCount,
	Long couponId,
	String requestMessage
) {

	public ReservationCreateCommand(
		Long accommodationId,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		Integer guestCount
	) {
		this(accommodationId, checkInDate, checkOutDate, guestCount, null, null);
	}

	public ReservationCreateCommand(
		Long accommodationId,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		Integer guestCount,
		Long couponId
	) {
		this(accommodationId, checkInDate, checkOutDate, guestCount, couponId, null);
	}
}
