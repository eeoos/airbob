package kr.kro.airbob.domain.reservation.repository.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import com.querydsl.core.annotations.QueryProjection;

import kr.kro.airbob.domain.reservation.entity.ReservationStatus;

public record GuestReservationListProjection(
	Long id,
	UUID reservationUid,
	LocalDate checkInDate,
	LocalDate checkOutDate,
	String timeZoneId,
	ReservationStatus status,
	Instant expiresAt,
	LocalDateTime createdAt,
	Long accommodationId,
	String accommodationName,
	String accommodationThumbnailUrl
) {
	@QueryProjection
	public GuestReservationListProjection {
	}
}
