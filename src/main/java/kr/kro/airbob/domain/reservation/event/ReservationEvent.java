package kr.kro.airbob.domain.reservation.event;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReservationEvent {

	public record ReservationPendingEvent(
		Integer amount,
		String paymentKey,   // pg 결제 키
		String orderId       // reservation_uid
	) {
	}

	public record ReservationCancelledEvent(
		String reservationUid,
		String cancelReason,
		Long cancelAmount // 전체 취소 시 null | 전체 금액
	) {
	}
}
