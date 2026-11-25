package kr.kro.airbob.core.domain.reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 체크아웃 결과. 상태와 결제 가능 여부는 serverTime 기준이고, 결제 대기일 때만 홀드 만료 시각을 담는다. */
public record ReservationReady(
	UUID reservationUid,
	String orderName,
	LocalDate checkIn,
	LocalDate checkOut,
	Integer guestCount,
	long subtotal,
	long discountAmount,
	long amount,
	String currency,
	ReservationStatus status,
	boolean paymentRequired,
	boolean paymentAllowed,
	Instant holdExpiresAt,
	Instant serverTime,
	String customerEmail,
	String customerName) {

	public static ReservationReady of(Reservation reservation, Instant serverTime) {
		long discountAmount = reservation.getDiscountAmount() == null
			? 0L : reservation.getDiscountAmount();
		return new ReservationReady(
			reservation.getReservationUid(),
			reservation.getAccommodation().getName(),
			reservation.getCheckInDate(),
			reservation.getCheckOutDate(),
			reservation.getGuestCount(),
			Math.addExact(reservation.getTotalPrice(), discountAmount),
			discountAmount,
			reservation.getTotalPrice(),
			reservation.getCurrency(),
			reservation.effectiveStatus(serverTime),
			reservation.requiresPayment(),
			reservation.isPaymentAllowedAt(serverTime),
			reservation.getStatus() == ReservationStatus.PAYMENT_PENDING ? reservation.getExpiresAt() : null,
			serverTime,
			reservation.getGuest().getEmail(),
			reservation.getGuest().getNickname());
	}
}
