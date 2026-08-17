package kr.kro.airbob.kafka.consumer;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.service.PaymentCancellationProcessor;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventEnvelope;
import kr.kro.airbob.outbox.EventType;

@ExtendWith(MockitoExtension.class)
@DisplayName("결제 이벤트 컨슈머 테스트")
class PaymentCancellationEventsConsumerTest {

	@Mock private DebeziumEventParser debeziumEventParser;
	@Mock private PaymentCancellationProcessor cancellationProcessor;
	@Mock private Acknowledgment acknowledgment;

	@InjectMocks private PaymentCancellationEventsConsumer consumer;

	@Test
	@DisplayName("PG 취소 성공 이벤트는 취소 프로세서에 전달하고 ACK한다")
	void delegatesCancellationSuccess() {
		String message = "pg-cancel-call-succeeded";
		PaymentEvent.PgCancelCallSucceededEvent payload =
			new PaymentEvent.PgCancelCallSucceededEvent(
				TossPaymentResponse.builder().status("CANCELED").balanceAmount(0L).build(),
				"reservation-uid");
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PG_CANCEL_CALL_SUCCEEDED.name());
		given(debeziumEventParser.parse(message, PaymentEvent.PgCancelCallSucceededEvent.class))
			.willReturn(EventEnvelope.of(
				EventType.PG_CANCEL_CALL_SUCCEEDED, payload, Instant.EPOCH));

		consumer.handlePaymentEvents(message, acknowledgment);

		then(cancellationProcessor).should().processSuccess(payload);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PG 취소 실패 이벤트는 취소 프로세서에 전달하고 ACK한다")
	void delegatesCancellationFailure() {
		String message = "pg-cancel-call-failed";
		PaymentEvent.PaymentCancellationRequestedEvent request =
			new PaymentEvent.PaymentCancellationRequestedEvent(
				"reservation-uid", "고객 요청", 100_000L);
		PaymentEvent.PgCancelCallFailedEvent payload =
			new PaymentEvent.PgCancelCallFailedEvent(
				request, "reservation-uid", "CANCEL_FAILED", "취소 실패");
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PG_CANCEL_CALL_FAILED.name());
		given(debeziumEventParser.parse(message, PaymentEvent.PgCancelCallFailedEvent.class))
			.willReturn(EventEnvelope.of(
				EventType.PG_CANCEL_CALL_FAILED, payload, Instant.EPOCH));

		consumer.handlePaymentEvents(message, acknowledgment);

		then(cancellationProcessor).should().processFailure(payload);
		then(acknowledgment).should().acknowledge();
	}
}
