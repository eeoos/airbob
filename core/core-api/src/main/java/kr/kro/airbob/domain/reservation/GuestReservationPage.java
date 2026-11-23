package kr.kro.airbob.domain.reservation;

import java.time.Instant;
import java.util.List;

import kr.kro.airbob.core.support.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;

/** 게스트 예약 목록 한 페이지. 상태는 조회 시각 기준이라 만료된 결제 대기는 정리 전에도 EXPIRED다. */
public record GuestReservationPage(
	List<Item> reservations,
	CursorResponse.PageInfo pageInfo
) {

	public record Item(GuestReservationListProjection reservation, ReservationStatus status) {

		public static Item of(GuestReservationListProjection reservation, Instant serverTime) {
			return new Item(reservation,
				Reservation.effectiveStatus(reservation.status(), reservation.expiresAt(), serverTime));
		}
	}
}
