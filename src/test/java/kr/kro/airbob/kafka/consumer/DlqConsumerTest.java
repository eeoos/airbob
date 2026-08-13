package kr.kro.airbob.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.support.Acknowledgment;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.config.KafkaConfig;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.service.PaymentApprovalService;
import kr.kro.airbob.domain.payment.service.PaymentCancellationProcessor;
import kr.kro.airbob.domain.payment.service.PaymentCompensationService;
import kr.kro.airbob.domain.payment.service.PaymentConfirmationProcessor;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventEnvelope;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import kr.kro.airbob.outbox.SlackNotificationService;
import kr.kro.airbob.outbox.exception.DebeziumEventParsingException;

@ExtendWith(MockitoExtension.class)
@DisplayName("DLQ 소비자 테스트")
class DlqConsumerTest {

	@Mock private DebeziumEventParser debeziumEventParser;
	@Mock private SlackNotificationService slackNotificationService;
	@Mock private PaymentCompensationService paymentCompensationService;
	@Mock private PaymentApprovalService paymentApprovalService;
	@Mock private PaymentCancellationProcessor paymentCancellationProcessor;
	@Mock private PaymentConfirmationProcessor paymentConfirmationProcessor;
	@Mock private ReservationService reservationService;
	@Mock private OutboxEventPublisher outboxEventPublisher;
	@Mock private PaymentGatewayWorker paymentGatewayWorker;
	@Mock private Acknowledgment acknowledgment;
	@InjectMocks private DlqConsumer consumer;

	@Test
	@DisplayName("레거시 예약 취소 이벤트가 DLQ에 도착하면 PG 취소 요청으로 복구한다")
	void recoversLegacyReservationCancellation() {
		String message = "reservation-cancelled-dlt";
		ReservationEvent.ReservationCancelledEvent payload =
			new ReservationEvent.ReservationCancelledEvent(
				"reservation-uid", "사용자 요청", null);
		EventEnvelope<ReservationEvent.ReservationCancelledEvent> envelope =
			EventEnvelope.of(EventType.RESERVATION_CANCELLED, payload, java.time.Instant.EPOCH);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.RESERVATION_CANCELLED.name());
		given(debeziumEventParser.parse(message, ReservationEvent.ReservationCancelledEvent.class))
			.willReturn(envelope);

		consumer.consumeDlqEvents(message, acknowledgment);

		then(outboxEventPublisher).should().save(
			eq(EventType.PG_CANCEL_CALL_REQUESTED),
			argThat(event -> event instanceof PaymentEvent.PaymentCancellationRequestedEvent requested
				&& requested.reservationUid().equals("reservation-uid")
				&& requested.cancelReason().equals("사용자 요청")
				&& requested.cancelAmount() == null));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("레거시 예약 취소 복구 실패는 비차단 재시도 토픽으로 전달한다")
	void forwardsFailedLegacyCancellationRecoveryToRetryTopic() {
		String message = "reservation-cancelled-dlt";
		ReservationEvent.ReservationCancelledEvent payload =
			new ReservationEvent.ReservationCancelledEvent(
				"reservation-uid", "사용자 요청", null);
		EventEnvelope<ReservationEvent.ReservationCancelledEvent> envelope =
			EventEnvelope.of(EventType.RESERVATION_CANCELLED, payload, java.time.Instant.EPOCH);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.RESERVATION_CANCELLED.name());
		given(debeziumEventParser.parse(message, ReservationEvent.ReservationCancelledEvent.class))
			.willReturn(envelope);
		willThrow(new IllegalStateException("outbox unavailable"))
			.given(outboxEventPublisher)
			.save(eq(EventType.PG_CANCEL_CALL_REQUESTED), argThat(event -> true));

		assertThatThrownBy(() -> consumer.consumeDlqEvents(message, acknowledgment))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("outbox unavailable");

		then(outboxEventPublisher).should().save(
			eq(EventType.PG_CANCEL_CALL_REQUESTED), argThat(event -> true));
		then(slackNotificationService).shouldHaveNoInteractions();
		then(acknowledgment).should(never()).acknowledge();
		then(acknowledgment).should(never()).nack(any(Duration.class));
	}

	@Test
	@DisplayName("예약 확정 요청이 최종 실패하면 이미 승인된 결제를 보상 취소한다")
	void compensatesFailedReservationConfirmation() {
		String message = "reservation-confirm-requested-dlt";
		PaymentEvent.PaymentCompletedEvent payload =
			new PaymentEvent.PaymentCompletedEvent("reservation-uid");
		EventEnvelope<PaymentEvent.PaymentCompletedEvent> envelope =
			EventEnvelope.of(
				EventType.RESERVATION_CONFIRM_REQUESTED,
				payload,
				java.time.Instant.parse("2026-08-12T00:00:00Z")
			);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.RESERVATION_CONFIRM_REQUESTED.name());
		given(debeziumEventParser.parse(message, PaymentEvent.PaymentCompletedEvent.class))
			.willReturn(envelope);

		consumer.consumeDlqEvents(message, acknowledgment);

		then(paymentCompensationService).should().compensate("reservation-uid");
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("결제 보상 처리 실패는 비차단 재시도 토픽으로 전달한다")
	void forwardsFailedPaymentCompensationToRetryTopic() {
		String message = "payment-completed-dlt";
		PaymentEvent.PaymentCompletedEvent payload =
			new PaymentEvent.PaymentCompletedEvent("reservation-uid");
		EventEnvelope<PaymentEvent.PaymentCompletedEvent> envelope =
			EventEnvelope.of(EventType.PAYMENT_COMPLETED, payload, java.time.Instant.EPOCH);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PAYMENT_COMPLETED.name());
		given(debeziumEventParser.parse(message, PaymentEvent.PaymentCompletedEvent.class))
			.willReturn(envelope);
		willThrow(new IllegalStateException("compensation unavailable"))
			.given(paymentCompensationService)
			.compensate("reservation-uid");

		assertThatThrownBy(() -> consumer.consumeDlqEvents(message, acknowledgment))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("compensation unavailable");

		then(paymentCompensationService).should().compensate("reservation-uid");
		then(slackNotificationService).shouldHaveNoInteractions();
		then(acknowledgment).should(never()).acknowledge();
		then(acknowledgment).should(never()).nack(any(Duration.class));
	}

	@Test
	@DisplayName("PG 환불 뒤 예약 취소 완료 반영이 최종 실패하면 DLQ에서 다시 완료 처리한다")
	void retriesFailedCancellationCompletion() {
		String message = "reservation-cancellation-complete-requested-dlt";
		ReservationEvent.ReservationCancellationCompleteRequestedEvent payload =
			new ReservationEvent.ReservationCancellationCompleteRequestedEvent("reservation-uid");
		EventEnvelope<ReservationEvent.ReservationCancellationCompleteRequestedEvent> envelope =
			EventEnvelope.of(
				EventType.RESERVATION_CANCELLATION_COMPLETE_REQUESTED,
				payload,
				java.time.Instant.parse("2026-08-12T00:00:00Z")
			);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.RESERVATION_CANCELLATION_COMPLETE_REQUESTED.name());
		given(debeziumEventParser.parse(
			message, ReservationEvent.ReservationCancellationCompleteRequestedEvent.class))
			.willReturn(envelope);

		consumer.consumeDlqEvents(message, acknowledgment);

		then(reservationService).should().completeCancellation(payload);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PG 취소 호출 요청이 최종 실패해도 멱등키가 적용되는 워커로 한 번 더 복구한다")
	void retriesFailedPgCancellationRequest() {
		String message = "pg-cancel-call-requested-dlt";
		PaymentEvent.PaymentCancellationRequestedEvent payload =
			new PaymentEvent.PaymentCancellationRequestedEvent(
				"reservation-uid", "사용자 요청", null);
		EventEnvelope<PaymentEvent.PaymentCancellationRequestedEvent> envelope =
			EventEnvelope.of(EventType.PG_CANCEL_CALL_REQUESTED, payload, java.time.Instant.EPOCH);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PG_CANCEL_CALL_REQUESTED.name());
		given(debeziumEventParser.parse(
			message, PaymentEvent.PaymentCancellationRequestedEvent.class))
			.willReturn(envelope);

		consumer.consumeDlqEvents(message, acknowledgment);

		then(paymentGatewayWorker).should().processCancelRequest(payload);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PG 승인 요청이 최종 실패해도 같은 멱등키 워커로 승인 결과를 복구한다")
	void retriesFailedPgConfirmRequest() {
		String message = "pg-call-requested-dlt";
		PaymentRequest.Confirm payload = new PaymentRequest.Confirm(
			"payment-key", "reservation-uid", 100_000);
		EventEnvelope<PaymentRequest.Confirm> envelope =
			EventEnvelope.of(EventType.PG_CALL_REQUESTED, payload, java.time.Instant.EPOCH);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PG_CALL_REQUESTED.name());
		given(debeziumEventParser.parse(message, PaymentRequest.Confirm.class))
			.willReturn(envelope);

		consumer.consumeDlqEvents(message, acknowledgment);

		then(paymentGatewayWorker).should().processConfirmRequest(payload);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("결제 승인 요청이 DLQ에 도착하면 승인 선점 로직을 다시 실행한다")
	void recoversPaymentConfirmRequest() {
		String message = "payment-confirm-requested-dlt";
		PaymentRequest.Confirm payload = new PaymentRequest.Confirm(
			"payment-key", "reservation-uid", 100_000);
		EventEnvelope<PaymentRequest.Confirm> envelope = EventEnvelope.of(
			EventType.PAYMENT_CONFIRM_REQUESTED, payload, java.time.Instant.EPOCH);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PAYMENT_CONFIRM_REQUESTED.name());
		given(debeziumEventParser.parse(message, PaymentRequest.Confirm.class))
			.willReturn(envelope);

		consumer.consumeDlqEvents(message, acknowledgment);

		then(paymentApprovalService).should().preparePgCall(payload);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("결제 승인 선점의 일시 오류는 비차단 재시도 토픽으로 전달한다")
	void retriesTransientPaymentConfirmFailure() {
		String message = "payment-confirm-requested-dlt";
		PaymentRequest.Confirm payload = new PaymentRequest.Confirm(
			"payment-key", "reservation-uid", 100_000);
		EventEnvelope<PaymentRequest.Confirm> envelope = EventEnvelope.of(
			EventType.PAYMENT_CONFIRM_REQUESTED, payload, java.time.Instant.EPOCH);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PAYMENT_CONFIRM_REQUESTED.name());
		given(debeziumEventParser.parse(message, PaymentRequest.Confirm.class))
			.willReturn(envelope);
		willThrow(new IllegalStateException("database unavailable"))
			.given(paymentApprovalService)
			.preparePgCall(payload);

		assertThatThrownBy(() -> consumer.consumeDlqEvents(message, acknowledgment))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("database unavailable");

		then(acknowledgment).should(never()).acknowledge();
		then(slackNotificationService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("PG 승인 복구 호출 실패는 비차단 재시도 토픽으로 전달한다")
	void forwardsFailedPgConfirmRecoveryToRetryTopic() {
		String message = "pg-call-requested-dlt";
		PaymentRequest.Confirm payload = new PaymentRequest.Confirm(
			"payment-key", "reservation-uid", 100_000);
		EventEnvelope<PaymentRequest.Confirm> envelope =
			EventEnvelope.of(EventType.PG_CALL_REQUESTED, payload, java.time.Instant.EPOCH);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PG_CALL_REQUESTED.name());
		given(debeziumEventParser.parse(message, PaymentRequest.Confirm.class))
			.willReturn(envelope);
		willThrow(new IllegalStateException("pg unavailable"))
			.given(paymentGatewayWorker)
			.processConfirmRequest(payload);

		assertThatThrownBy(() -> consumer.consumeDlqEvents(message, acknowledgment))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("pg unavailable");

		then(paymentGatewayWorker).should().processConfirmRequest(payload);
		then(slackNotificationService).shouldHaveNoInteractions();
		then(acknowledgment).should(never()).acknowledge();
		then(acknowledgment).should(never()).nack(any(Duration.class));
	}

	@Test
	@DisplayName("DLQ 복구는 세 번 재시도한 뒤 보관 토픽으로 이동한다")
	void configuresBoundedNonBlockingRetry() throws NoSuchMethodException {
		RetryableTopic retryableTopic = DlqConsumer.class
			.getMethod("consumeDlqEvents", String.class, Acknowledgment.class)
			.getAnnotation(RetryableTopic.class);

		assertThat(retryableTopic).isNotNull();
		assertThat(retryableTopic.attempts()).isEqualTo("4");
		assertThat(retryableTopic.backoff().delay()).isEqualTo(30_000L);
		assertThat(retryableTopic.kafkaTemplate()).isEqualTo("deadLetterKafkaTemplate");
		assertThat(retryableTopic.retryTopicSuffix()).isEqualTo(".RETRY");
		assertThat(retryableTopic.dltTopicSuffix()).isEqualTo(".PARKING");
		assertThat(retryableTopic.sameIntervalTopicReuseStrategy())
			.isEqualTo(SameIntervalTopicReuseStrategy.SINGLE_TOPIC);
		assertThat(retryableTopic.dltStrategy()).isEqualTo(DltStrategy.FAIL_ON_ERROR);
		assertThat(retryableTopic.exclude())
			.containsExactlyInAnyOrder(
				InvalidInputException.class,
				ReservationNotFoundException.class
			);
		assertThat(KafkaConfig.class.isAnnotationPresent(EnableKafkaRetryTopic.class)).isTrue();
	}

	@Test
	@DisplayName("재시도를 모두 소진한 메시지는 한 번 알리고 보관 토픽 오프셋을 커밋한다")
	void alertsOnceWhenRetriesAreExhausted() {
		String message = "payment-completed-parking";
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PAYMENT_COMPLETED.name());
		consumer.handleExhaustedRecovery(message, "database unavailable", acknowledgment);

		then(slackNotificationService).should().sendAlert(argThat(alert ->
			alert.contains(EventType.PAYMENT_COMPLETED.name())
				&& alert.contains("database unavailable")));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PG 승인 성공 이벤트의 DB 반영이 최종 실패하면 DLQ에서 다시 반영한다")
	void retriesFailedPgCallSucceeded() {
		String message = "pg-call-succeeded-dlt";
		PaymentEvent.PgCallSucceededEvent payload =
			new PaymentEvent.PgCallSucceededEvent(null, "reservation-uid");
		EventEnvelope<PaymentEvent.PgCallSucceededEvent> envelope =
			EventEnvelope.of(EventType.PG_CALL_SUCCEEDED, payload, java.time.Instant.EPOCH);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PG_CALL_SUCCEEDED.name());
		given(debeziumEventParser.parse(message, PaymentEvent.PgCallSucceededEvent.class))
			.willReturn(envelope);

		consumer.consumeDlqEvents(message, acknowledgment);

		then(paymentConfirmationProcessor).should().processSuccess(payload);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PG 승인 실패 이벤트의 DB 반영이 최종 실패하면 DLQ에서 다시 반영한다")
	void retriesFailedPgCallFailure() {
		String message = "pg-call-failed-dlt";
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", "reservation-uid", 100_000);
		PaymentEvent.PgCallFailedEvent payload =
			new PaymentEvent.PgCallFailedEvent(
				request, "reservation-uid", "REJECTED", "결제 거절");
		EventEnvelope<PaymentEvent.PgCallFailedEvent> envelope =
			EventEnvelope.of(EventType.PG_CALL_FAILED, payload, java.time.Instant.EPOCH);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PG_CALL_FAILED.name());
		given(debeziumEventParser.parse(message, PaymentEvent.PgCallFailedEvent.class))
			.willReturn(envelope);

		consumer.consumeDlqEvents(message, acknowledgment);

		then(paymentConfirmationProcessor).should().processFailure(payload);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("결제 실패 이벤트 번역이 최종 실패하면 DLQ에서 예약을 만료 처리한다")
	void expiresReservationForFailedPayment() {
		String message = "payment-failed-dlt";
		PaymentEvent.PaymentFailedEvent payload =
			new PaymentEvent.PaymentFailedEvent("reservation-uid", "결제 거절");
		EventEnvelope<PaymentEvent.PaymentFailedEvent> envelope =
			EventEnvelope.of(EventType.PAYMENT_FAILED, payload, java.time.Instant.EPOCH);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PAYMENT_FAILED.name());
		given(debeziumEventParser.parse(message, PaymentEvent.PaymentFailedEvent.class))
			.willReturn(envelope);

		consumer.consumeDlqEvents(message, acknowledgment);

		then(reservationService).should().expireReservation(payload);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("예약 만료 요청이 최종 실패하면 DLQ에서 예약을 다시 만료 처리한다")
	void retriesFailedReservationExpiration() {
		String message = "reservation-expire-requested-dlt";
		PaymentEvent.PaymentFailedEvent payload =
			new PaymentEvent.PaymentFailedEvent("reservation-uid", "결제 거절");
		EventEnvelope<PaymentEvent.PaymentFailedEvent> envelope =
			EventEnvelope.of(EventType.RESERVATION_EXPIRE_REQUESTED, payload, java.time.Instant.EPOCH);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.RESERVATION_EXPIRE_REQUESTED.name());
		given(debeziumEventParser.parse(message, PaymentEvent.PaymentFailedEvent.class))
			.willReturn(envelope);

		consumer.consumeDlqEvents(message, acknowledgment);

		then(reservationService).should().expireReservation(payload);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("지원하지 않는 이벤트는 성공적으로 무시하고 DLQ에서 제거한다")
	void acknowledgesSuccessfullyIgnoredUnsupportedEvent() {
		String message = "accommodation-updated-dlt";
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.ACCOMMODATION_UPDATED.name());

		consumer.consumeDlqEvents(message, acknowledgment);

		then(acknowledgment).should().acknowledge();
		then(acknowledgment).should(never()).nack(any(Duration.class));
	}

	@Test
	@DisplayName("지원하는 이벤트라도 페이로드를 파싱할 수 없으면 무한 재시도하지 않는다")
	void acknowledgesPoisonPayload() {
		String message = "malformed-payment-completed-dlt";
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.PAYMENT_COMPLETED.name());
		given(debeziumEventParser.parse(message, PaymentEvent.PaymentCompletedEvent.class))
			.willThrow(new DebeziumEventParsingException(new IOException("malformed payload")));

		consumer.consumeDlqEvents(message, acknowledgment);

		then(acknowledgment).should().acknowledge();
		then(acknowledgment).should(never()).nack(any(Duration.class));
	}
}
