package kr.kro.airbob.domain.payment.event;

import kr.kro.airbob.outbox.EventPayload;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentEvent {

	public record PaymentSucceededEvent(String reservationUid) implements EventPayload {
		@Override
		public String getId() {
			return this.reservationUid;
		}
	}

	public record PaymentFailedEvent(String reservationUid, String reason) implements EventPayload{
		@Override
		public String getId() {
			return this.reservationUid;
		}
	}

	public record PaymentCancellationFailedEvent(String reservationUid, String reason) implements EventPayload{
		@Override
		public String getId() {
			return this.reservationUid;
		}
	}

}
