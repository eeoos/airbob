package kr.kro.airbob.domain.reservation;

import java.time.Instant;
import java.util.UUID;

import kr.kro.airbob.domain.reservation.entity.Reservation;

/** 결제 시도 토큰. 주문 ID는 예약 UID이고, 남은 시간은 홀드 만료까지의 초다. */
public record ReservationPaymentAttempt(
	UUID paymentAttemptId,
	UUID reservationUid,
	long amount,
	String currency,
	Instant holdExpiresAt,
	long remainingSeconds,
	Instant serverTime
) {

	public static ReservationPaymentAttempt of(Reservation reservation, long remainingSeconds, Instant serverTime) {
		return new ReservationPaymentAttempt(
			reservation.getPaymentAttemptUid(),
			reservation.getReservationUid(),
			reservation.getTotalPrice(),
			reservation.getCurrency(),
			reservation.getExpiresAt(),
			remainingSeconds,
			serverTime);
	}
}
