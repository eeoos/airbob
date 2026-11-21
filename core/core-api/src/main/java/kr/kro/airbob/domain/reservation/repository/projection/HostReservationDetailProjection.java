package kr.kro.airbob.domain.reservation.repository.projection;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.querydsl.core.annotations.QueryProjection;

import kr.kro.airbob.domain.reservation.entity.ReservationStatus;

public record HostReservationDetailProjection(
	UUID reservationUid,
	String reservationCode,
	ReservationStatus status,
	LocalDateTime createdAt,
	Integer guestCount,
	Instant checkInAt,
	Instant checkOutAt,
	String timeZoneId,
	String requestMessage,
	Long accommodationId,
	String accommodationName,
	String accommodationThumbnailUrl,
	String country,
	String state,
	String city,
	String district,
	String street,
	String addressDetail,
	String postalCode,
	Long guestId,
	String guestNickname,
	String guestThumbnailImageUrl,
	Long paymentAmount
) {
	@QueryProjection
	public HostReservationDetailProjection {
	}
}
