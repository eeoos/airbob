package kr.kro.airbob.domain.payment.event;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentEvent {

	public record PaymentSucceededEvent(String reservationUid) {
	}

	public record PaymentFailedEvent(String reservationUid, String reason) {
	}

	public record PaymentCancellationFailedEvent(String reservationUid, String reason) {
	}

}
