package kr.kro.airbob.domain.payment.event;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentEvent {

	public record PaymentSucceededEvent(Long reservationId) {
	}

	public record PaymentFailedEvent(Long reservationId, String reason) {
	}

}
