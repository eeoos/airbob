package kr.kro.airbob.kafka.consumer;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.service.PaymentApprovalService;
import kr.kro.airbob.domain.payment.service.PaymentCancellationProcessor;
import kr.kro.airbob.domain.payment.service.PaymentConfirmationProcessor;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventEnvelope;
import kr.kro.airbob.outbox.EventType;

@ExtendWith(MockitoExtension.class)
@DisplayName("결제 이벤트 컨슈머 테스트")
class PaymentEventsConsumerTest {

	@Mock private DebeziumEventParser debeziumEventParser;
	@Mock private PaymentApprovalService paymentApprovalService;
	@Mock private PaymentConfirmationProcessor confirmationProcessor;
	@Mock private PaymentCancellationProcessor cancellationProcessor;
	@Mock private Acknowledgment acknowledgment;

	@InjectMocks private PaymentEventsConsumer consumer;

	@Test
	@DisplayName("결제 승인 요청은 예약 선점 서비스에 위임하고 성공 후 ACK한다")
	void delegatesConfirmationRequestToAtomicClaimService() {
		String message = "payment-confirm-requested";
		UUID reservationUid = UUID.randomUUID();
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", reservationUid.toString(), 100_000);
		EventEnvelope<PaymentRequest.Confirm> envelope = EventEnvelope.of(
			EventType.PAYMENT_CONFIRM_REQUESTED, request, Instant.EPOCH);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PAYMENT_CONFIRM_REQUESTED.name());
		given(debeziumEventParser.parse(message, PaymentRequest.Confirm.class))
			.willReturn(envelope);
		given(paymentApprovalService.preparePgCall(request)).willReturn(true);

		consumer.handlePaymentEvents(message, acknowledgment);

		then(paymentApprovalService).should().preparePgCall(request);
		then(acknowledgment).should().acknowledge();
	}
}
