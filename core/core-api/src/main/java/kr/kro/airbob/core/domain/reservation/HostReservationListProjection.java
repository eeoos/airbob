package kr.kro.airbob.core.domain.reservation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import com.querydsl.core.annotations.QueryProjection;

public record HostReservationListProjection(
	Long id,
	UUID reservationUid,
	String reservationCode,
	Long totalPrice,
	String currency,
	Integer guestCount,
	LocalDate checkInDate,
	LocalDate checkOutDate,
	String timeZoneId,
	ReservationStatus status,
	LocalDateTime createdAt,
	Long guestId,
	String guestNickname,
	String guestThumbnailImageUrl,
	Long accommodationId,
	String accommodationName,
	String accommodationThumbnailUrl) {
	@QueryProjection
	public HostReservationListProjection {
	}
}
