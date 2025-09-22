package kr.kro.airbob.domain.reservation.event;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReservationEvent {

	public record ReservationPendingEvent(
		Long reservationId,
		Integer totalPrice
	) {
	}
}
