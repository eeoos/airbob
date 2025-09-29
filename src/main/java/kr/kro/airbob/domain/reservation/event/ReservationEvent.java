package kr.kro.airbob.domain.reservation.event;

import kr.kro.airbob.outbox.EventPayload;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReservationEvent {

	public record ReservationPendingEvent(
		Integer amount,
		String paymentKey,   // pg 결제 키
		String orderId       // reservation_uid
	) implements EventPayload {
		@Override
		public String getId() {
			return this.orderId;
		}
	}

	public record ReservationCancelledEvent(
		String reservationUid,
		String cancelReason,
		Long cancelAmount // 전체 취소 시 null | 전체 금액
	) implements EventPayload {
		@Override
		public String getId() {
			return this.reservationUid;
		}
	}

	public record ReservationConfirmationFailedEvent(
		String reservationUid,
		String reason
	) implements EventPayload {
		@Override
		public String getId() {
			return this.reservationUid;
		}
	}
}
