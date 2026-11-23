package kr.kro.airbob.domain.reservation;

import java.time.Instant;

import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;

/** 게스트 예약 상세. 상태와 결제 가능 여부는 serverTime 기준이다. */
public record GuestReservationDetail(
	GuestReservationDetailProjection detail,
	ReservationStatus status,
	boolean paymentAllowed,
	boolean canWriteReview,
	Instant serverTime
) {

	public static GuestReservationDetail of(
		GuestReservationDetailProjection detail, boolean canWriteReview, Instant serverTime) {
		return new GuestReservationDetail(
			detail,
			Reservation.effectiveStatus(detail.status(), detail.expiresAt(), serverTime),
			Reservation.isPaymentAllowedAt(detail.status(), detail.totalPrice(), detail.expiresAt(), serverTime),
			canWriteReview,
			serverTime);
	}
}
