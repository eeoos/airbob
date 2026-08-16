package kr.kro.airbob.domain.payment.event;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import kr.kro.airbob.outbox.EventPayload;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentOperationEvent {

	public record PaymentExecutionRequestedV1(UUID operationUid, UUID reservationUid) implements EventPayload {
		@Override
		@JsonIgnore
		public String getId() {
			return reservationUid.toString();
		}
	}
}
