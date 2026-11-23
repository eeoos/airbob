package kr.kro.airbob.core.domain.reservation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.querydsl.core.annotations.QueryProjection;

import kr.kro.airbob.core.domain.accommodation.AccommodationStatus;
import kr.kro.airbob.core.domain.payment.PaymentMethod;
import kr.kro.airbob.core.domain.payment.PaymentStatus;

public record GuestReservationDetailProjection(
	UUID reservationUid,
	String reservationCode,
	ReservationStatus status,
	Instant expiresAt,
	Long totalPrice,
	LocalDateTime createdAt,
	Integer guestCount,
	Instant checkInAt,
	Instant checkOutAt,
	String timeZoneId,
	String requestMessage,
	Long accommodationId,
	String accommodationName,
	String accommodationThumbnailUrl,
	AccommodationStatus accommodationStatus,
	String country,
	String state,
	String city,
	String district,
	String street,
	String addressDetail,
	String postalCode,
	Double latitude,
	Double longitude,
	Long hostId,
	String hostNickname,
	String hostThumbnailImageUrl,
	Long paymentId,
	PaymentMethod paymentMethod,
	Long paymentAmount,
	PaymentStatus paymentStatus,
	Instant paymentApprovedAt
) {
	@QueryProjection
	public GuestReservationDetailProjection {
	}
}
