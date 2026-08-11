package kr.kro.airbob.domain.reservation.dto;

import java.time.LocalDateTime;

import com.querydsl.core.annotations.QueryProjection;

public record ReservationDateRange(
	LocalDateTime checkIn,
	LocalDateTime checkOut
) {
	@QueryProjection
	public ReservationDateRange {
	}
}
