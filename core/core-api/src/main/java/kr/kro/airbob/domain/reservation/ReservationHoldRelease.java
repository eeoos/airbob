package kr.kro.airbob.domain.reservation;

import java.time.Instant;
import java.util.UUID;

import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;

/** 게스트의 결제 대기 보유 해제 결과. 이미 만료된 예약이면 releasedNow가 false다. */
public record ReservationHoldRelease(
	UUID reservationUid,
	ReservationStatus status,
	boolean releasedNow,
	Instant serverTime
) {

	public static ReservationHoldRelease of(Reservation reservation, boolean releasedNow, Instant serverTime) {
		return new ReservationHoldRelease(reservation.getReservationUid(), reservation.getStatus(), releasedNow, serverTime);
	}
}
