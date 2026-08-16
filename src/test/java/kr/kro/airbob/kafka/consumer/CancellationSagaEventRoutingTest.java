package kr.kro.airbob.kafka.consumer;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.service.ReservationHoldService;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventEnvelope;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("예약 취소 Saga 이벤트 라우팅 테스트")
class CancellationSagaEventRoutingTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-08-12T00:00:00Z");

	@Mock private DebeziumEventParser parser;
	@Mock private OutboxEventPublisher outboxEventPublisher;
	@Mock private ReservationService reservationService;
	@Mock private ReservationHoldService reservationHoldService;
	@Mock private Acknowledgment acknowledgment;

	@Test
	@DisplayName("예약 취소 요청은 PG 취소 호출 요청으로 번역한다")
	void translatesReservationCancellationRequest() {
		String message = "reservation-cancellation-requested";
		ReservationEvent.ReservationCancellationRequestedEvent payload =
			new ReservationEvent.ReservationCancellationRequestedEvent(
				"reservation-uid", "고객 요청", null);
		EventEnvelope<ReservationEvent.ReservationCancellationRequestedEvent> envelope =
			EventEnvelope.of(EventType.RESERVATION_CANCELLATION_REQUESTED, payload, OCCURRED_AT);
		given(parser.getEventType(message))
			.willReturn(EventType.RESERVATION_CANCELLATION_REQUESTED.name());
		given(parser.parse(message, ReservationEvent.ReservationCancellationRequestedEvent.class))
			.willReturn(envelope);

		new ReservationEventTranslator(parser, outboxEventPublisher)
			.translateReservationEvents(message, acknowledgment);

		then(outboxEventPublisher).should().save(
			eq(EventType.PG_CANCEL_CALL_REQUESTED),
			argThat(event -> event instanceof PaymentEvent.PaymentCancellationRequestedEvent requested
				&& requested.reservationUid().equals("reservation-uid")
				&& requested.cancelReason().equals("고객 요청")
				&& requested.cancelAmount() == null));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("배포 전 발행된 RESERVATION_CANCELLED도 PG 취소 요청으로 변환한다")
	void translatesLegacyReservationCancelled() {
		String message = "legacy-reservation-cancelled";
		ReservationEvent.ReservationCancelledEvent payload =
			new ReservationEvent.ReservationCancelledEvent(
				"reservation-uid", "사용자 요청", null);
		EventEnvelope<ReservationEvent.ReservationCancelledEvent> envelope =
			EventEnvelope.of(EventType.RESERVATION_CANCELLED, payload, OCCURRED_AT);
		given(parser.getEventType(message))
			.willReturn(EventType.RESERVATION_CANCELLED.name());
		given(parser.parse(message, ReservationEvent.ReservationCancelledEvent.class))
			.willReturn(envelope);

		new ReservationEventTranslator(parser, outboxEventPublisher)
			.translateReservationEvents(message, acknowledgment);

		then(outboxEventPublisher).should().save(
			eq(EventType.PG_CANCEL_CALL_REQUESTED),
			argThat(event -> event instanceof PaymentEvent.PaymentCancellationRequestedEvent requested
				&& requested.reservationUid().equals("reservation-uid")
				&& requested.cancelReason().equals("사용자 요청"))
		);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("결제 취소 완료는 예약 취소 완료 반영 요청으로 번역한다")
	void translatesPaymentCancellationCompletion() {
		String message = "payment-cancellation-completed";
		PaymentEvent.PaymentCancellationCompletedEvent payload =
			new PaymentEvent.PaymentCancellationCompletedEvent("reservation-uid");
		EventEnvelope<PaymentEvent.PaymentCancellationCompletedEvent> envelope =
			EventEnvelope.of(EventType.PAYMENT_CANCELLATION_COMPLETED, payload, OCCURRED_AT);
		given(parser.getEventType(message))
			.willReturn(EventType.PAYMENT_CANCELLATION_COMPLETED.name());
		given(parser.parse(message, PaymentEvent.PaymentCancellationCompletedEvent.class))
			.willReturn(envelope);

		new PaymentCancellationEventTranslator(parser, outboxEventPublisher)
			.translatePaymentEvents(message, acknowledgment);

		then(outboxEventPublisher).should().save(
			eq(EventType.RESERVATION_CANCELLATION_COMPLETE_REQUESTED),
			argThat(event -> event instanceof ReservationEvent.ReservationCancellationCompleteRequestedEvent requested
				&& requested.reservationUid().equals("reservation-uid")));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("예약 취소 완료 반영 요청은 예약 서비스의 잠금 트랜잭션으로 전달한다")
	void routesReservationCancellationCompletion() {
		String message = "reservation-cancellation-complete-requested";
		ReservationEvent.ReservationCancellationCompleteRequestedEvent payload =
			new ReservationEvent.ReservationCancellationCompleteRequestedEvent("reservation-uid");
		EventEnvelope<ReservationEvent.ReservationCancellationCompleteRequestedEvent> envelope =
			EventEnvelope.of(EventType.RESERVATION_CANCELLATION_COMPLETE_REQUESTED, payload, OCCURRED_AT);
		given(parser.getEventType(message))
			.willReturn(EventType.RESERVATION_CANCELLATION_COMPLETE_REQUESTED.name());
		given(parser.parse(message, ReservationEvent.ReservationCancellationCompleteRequestedEvent.class))
			.willReturn(envelope);

		new ReservationEventsConsumer(reservationService, reservationHoldService, parser)
			.handleReservationEvents(message, acknowledgment);

		then(reservationService).should().completeCancellation(payload);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("결제 취소 실패는 예약 취소 실패 반영 요청으로 번역한다")
	void translatesPaymentCancellationFailure() {
		String message = "payment-cancellation-failed";
		PaymentEvent.PaymentCancellationFailedEvent payload =
			new PaymentEvent.PaymentCancellationFailedEvent("reservation-uid", "PG 취소 실패");
		EventEnvelope<PaymentEvent.PaymentCancellationFailedEvent> envelope =
			EventEnvelope.of(EventType.PAYMENT_CANCELLATION_FAILED, payload, OCCURRED_AT);
		given(parser.getEventType(message))
			.willReturn(EventType.PAYMENT_CANCELLATION_FAILED.name());
		given(parser.parse(message, PaymentEvent.PaymentCancellationFailedEvent.class))
			.willReturn(envelope);

		new PaymentCancellationEventTranslator(parser, outboxEventPublisher)
			.translatePaymentEvents(message, acknowledgment);

		then(outboxEventPublisher).should().save(
			eq(EventType.RESERVATION_CANCELLATION_REVERT_REQUESTED),
			argThat(event -> event instanceof ReservationEvent.ReservationCancellationRevertRequestedEvent requested
				&& requested.reservationUid().equals("reservation-uid")
				&& requested.reason().equals("PG 취소 실패")));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("예약 취소 실패 반영 요청은 예약 서비스의 잠금 트랜잭션으로 전달한다")
	void routesReservationCancellationFailure() {
		String message = "reservation-cancellation-revert-requested";
		ReservationEvent.ReservationCancellationRevertRequestedEvent payload =
			new ReservationEvent.ReservationCancellationRevertRequestedEvent(
				"reservation-uid", "PG 취소 실패");
		EventEnvelope<ReservationEvent.ReservationCancellationRevertRequestedEvent> envelope =
			EventEnvelope.of(EventType.RESERVATION_CANCELLATION_REVERT_REQUESTED, payload, OCCURRED_AT);
		given(parser.getEventType(message))
			.willReturn(EventType.RESERVATION_CANCELLATION_REVERT_REQUESTED.name());
		given(parser.parse(message, ReservationEvent.ReservationCancellationRevertRequestedEvent.class))
			.willReturn(envelope);

		new ReservationEventsConsumer(reservationService, reservationHoldService, parser)
			.handleReservationEvents(message, acknowledgment);

		then(reservationService).should().revertCancellation(payload);
		then(acknowledgment).should().acknowledge();
	}
}
