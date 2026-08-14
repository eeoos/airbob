package kr.kro.airbob.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.service.TossPaymentsAdapter;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventEnvelope;
import kr.kro.airbob.outbox.EventPayload;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("PG 취소 호출 워커 테스트")
class PaymentCancellationGatewayWorkerTest {

	@Mock private DebeziumEventParser debeziumEventParser;
	@Mock private TossPaymentsAdapter tossPaymentsAdapter;
	@Mock private OutboxEventPublisher outboxEventPublisher;
	@Mock private PaymentRepository paymentRepository;
	@Mock private Acknowledgment acknowledgment;

	private PaymentCancellationGatewayWorker worker;

	@BeforeEach
	void setUp() {
		worker = new PaymentCancellationGatewayWorker(
			debeziumEventParser,
			tossPaymentsAdapter,
			outboxEventPublisher,
			paymentRepository
		);
	}

	@Test
	@DisplayName("PG 취소 성공 후 성공 이벤트 저장이 실패하면 취소 실패 이벤트로 바꾸지 않는다")
	void doesNotConvertSuccessfulPgCancellationIntoFailure() {
		String message = "pg-cancel-request";
		UUID reservationUid = UUID.randomUUID();
		PaymentEvent.PaymentCancellationRequestedEvent request =
			new PaymentEvent.PaymentCancellationRequestedEvent(
				reservationUid.toString(), "사용자 요청", null);
		EventEnvelope<PaymentEvent.PaymentCancellationRequestedEvent> envelope =
			EventEnvelope.of(EventType.PG_CANCEL_CALL_REQUESTED, request, Instant.EPOCH);
		Payment payment = mock(Payment.class);
		TossPaymentResponse response = TossPaymentResponse.builder()
			.paymentKey("payment-key")
			.orderId(reservationUid.toString())
			.status("CANCELED")
			.balanceAmount(0L)
			.build();

		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PG_CANCEL_CALL_REQUESTED.name());
		given(debeziumEventParser.parse(
			message, PaymentEvent.PaymentCancellationRequestedEvent.class))
			.willReturn(envelope);
		given(paymentRepository.findByReservationReservationUid(reservationUid))
			.willReturn(Optional.of(payment));
		given(payment.getPaymentKey()).willReturn("payment-key");
		given(tossPaymentsAdapter.cancelPayment("payment-key", "사용자 요청", null))
			.willReturn(response);
		doThrow(new RuntimeException("outbox 저장 실패"))
			.when(outboxEventPublisher)
			.save(eq(EventType.PG_CANCEL_CALL_SUCCEEDED), any(EventPayload.class));

		assertThatThrownBy(() -> worker.handlePgCallRequest(message, acknowledgment))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("outbox 저장 실패");

		verify(outboxEventPublisher, never())
			.save(eq(EventType.PG_CANCEL_CALL_FAILED), any(EventPayload.class));
		verify(acknowledgment, never()).acknowledge();
	}

	@Test
	@DisplayName("결과를 알 수 없는 PG 통신 오류는 실패로 확정하지 않고 Kafka 재시도로 넘긴다")
	void retriesUnknownPgCancellationResult() {
		String message = "pg-cancel-request";
		UUID reservationUid = UUID.randomUUID();
		PaymentEvent.PaymentCancellationRequestedEvent request =
			new PaymentEvent.PaymentCancellationRequestedEvent(
				reservationUid.toString(), "사용자 요청", null);
		EventEnvelope<PaymentEvent.PaymentCancellationRequestedEvent> envelope =
			EventEnvelope.of(EventType.PG_CANCEL_CALL_REQUESTED, request, Instant.EPOCH);
		Payment payment = mock(Payment.class);

		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PG_CANCEL_CALL_REQUESTED.name());
		given(debeziumEventParser.parse(
			message, PaymentEvent.PaymentCancellationRequestedEvent.class))
			.willReturn(envelope);
		given(paymentRepository.findByReservationReservationUid(reservationUid))
			.willReturn(Optional.of(payment));
		given(payment.getPaymentKey()).willReturn("payment-key");
		given(tossPaymentsAdapter.cancelPayment("payment-key", "사용자 요청", null))
			.willThrow(new RuntimeException("응답 유실"));

		assertThatThrownBy(() -> worker.handlePgCallRequest(message, acknowledgment))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("응답 유실");

		verify(outboxEventPublisher, never())
			.save(eq(EventType.PG_CANCEL_CALL_FAILED), any(EventPayload.class));
	}
}
