package kr.kro.airbob.domain.payment.event;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.outbox.EventPayload;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentEvent {

	public record PaymentCompletedEvent(String reservationUid) implements EventPayload {
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

	public record PaymentCancellationRequestedEvent(
		String reservationUid,
		String cancelReason,
		Long cancelAmount
	) implements EventPayload {
		@Override
		public String getId() { return reservationUid; }
	}

	public record PgCallSucceededEvent(
		TossPaymentResponse response,
		String reservationUid
	) implements EventPayload {
		@Override
		public String getId() {
			return this.reservationUid;
		}
	}

	public record PgCallFailedEvent(
		PaymentRequest.Confirm request, // 원래 요청 정보
		String reservationUid,
		String errorCode,
		String errorMessage
	) implements EventPayload {
		@Override
		public String getId() {
			return this.reservationUid;
		}
	}

	public record PgCancelCallSucceededEvent(
		TossPaymentResponse response,
		String reservationUid
	) implements EventPayload {
		@Override
		public String getId() {
			return this.reservationUid;
		}
	}

	public record PgCancelCallFailedEvent(
		PaymentCancellationRequestedEvent request,
		String reservationUid,
		String errorCode,
		String errorMessage
	) implements EventPayload {
		@Override
		public String getId() {
			return this.reservationUid;
		}
	}
}
