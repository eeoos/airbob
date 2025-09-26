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
}
