package kr.kro.airbob.core.domain.reservation;

import java.time.LocalDate;

import com.querydsl.core.annotations.QueryProjection;

public record ReservationDateRange(
	LocalDate checkIn,
	LocalDate checkOut) {
	@QueryProjection
	public ReservationDateRange {
	}
}
