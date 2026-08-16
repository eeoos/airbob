package kr.kro.airbob.kafka.consumer;

import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.service.PaymentCancellationProcessor;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventPayload;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import kr.kro.airbob.outbox.SlackNotificationService;
import kr.kro.airbob.outbox.exception.DebeziumEventParsingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DlqConsumer {
	private static final String ALERT_MESSAGE = """
		🚨 *[DLQ-FATAL]* 🚨
		DLQ 메시지 자동 보상 처리 중 최종 실패! *수동 개입*이 필요합니다.
		                
		• *EventType*: `%s`
		• *Exception*: `%s`
		• *Error Message*: `%s`
		• *Original Message*: ```%s```
		""";

	private final DebeziumEventParser debeziumEventParser;
	private final SlackNotificationService slackNotificationService;
	private final PaymentCancellationProcessor paymentCancellationProcessor;
	private final ReservationService reservationService;
	private final OutboxEventPublisher outboxEventPublisher;
	private final PaymentCancellationGatewayWorker paymentCancellationGatewayWorker;

	@RetryableTopic(
		attempts = "4",
		backoff = @Backoff(delay = 30_000),
		kafkaTemplate = "deadLetterKafkaTemplate",
		retryTopicSuffix = ".RETRY",
		dltTopicSuffix = ".PARKING",
		exclude = {
			InvalidInputException.class,
			ReservationNotFoundException.class
		},
		sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
		dltStrategy = DltStrategy.FAIL_ON_ERROR
	)
	@KafkaListener(
		topics = "${spring.kafka.consumer.properties.spring.kafka.dead-letter-publishing.topic-name}",
		groupId = "dlq-group"
	)
	public void consumeDlqEvents(@Payload String message, Acknowledgment ack) {
		log.warn("[DLQ-CONSUME] DLQ 메시지 수신: {}", message);

		EventType eventType = EventType.UNKNOWN;
		try {
			eventType = EventType.from(debeziumEventParser.getEventType(message));

			recoverEvent(eventType, message);

			ack.acknowledge();
			log.info("[DLQ-ACK] 메시지 처리 성공. DLQ에서 메시지 제거.");

		} catch (DebeziumEventParsingException e) {
			handleFailureAlert(eventType.name(), message, e);
			ack.acknowledge();
			log.warn("[DLQ-ACK] 파싱할 수 없는 메시지를 무한 재시도하지 않고 제거합니다.");
		} catch (RuntimeException e) {
			if (eventType != EventType.UNKNOWN) {
				log.warn("[DLQ-FORWARD] 자동 복구 실패. 비차단 재시도 토픽으로 전달합니다. EventType: {}", eventType, e);
				throw e;
			}

			handleFailureAlert(eventType.name(), message, e);
			ack.acknowledge();
			log.warn("[DLQ-ACK] 지원하지 않는 이벤트의 처리 실패를 무한 재시도하지 않고 제거합니다.");
		}
	}

	@DltHandler
	public void handleExhaustedRecovery(
		@Payload String message,
		@Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String errorMessage,
		Acknowledgment ack
	) {
		String eventType = EventType.UNKNOWN.name();
		Exception failure = new IllegalStateException(
			errorMessage != null ? errorMessage : "DLQ 자동 복구 횟수를 초과했습니다."
		);

		try {
			eventType = debeziumEventParser.getEventType(message);
		} catch (Exception parsingException) {
			failure = parsingException;
		}

		try {
			handleFailureAlert(eventType, message, failure);
		} finally {
			ack.acknowledge();
			log.warn("[DLQ-PARKED] 자동 복구를 소진한 메시지를 보관 토픽에 남깁니다. EventType: {}", eventType);
		}
	}

	private void recoverEvent(EventType eventType, String message) {
		switch (eventType) {
			case PG_CANCEL_CALL_SUCCEEDED -> paymentCancellationProcessor.processSuccess(
				parsePayload(message, PaymentEvent.PgCancelCallSucceededEvent.class));
			case PG_CANCEL_CALL_FAILED -> paymentCancellationProcessor.processFailure(
				parsePayload(message, PaymentEvent.PgCancelCallFailedEvent.class));
			case RESERVATION_CANCELLATION_REQUESTED -> {
				ReservationEvent.ReservationCancellationRequestedEvent event = parsePayload(
					message, ReservationEvent.ReservationCancellationRequestedEvent.class);
				publishPaymentCancellationRequest(
					event.reservationUid(), event.cancelReason(), event.cancelAmount());
			}
			case RESERVATION_CANCELLED -> {
				ReservationEvent.ReservationCancelledEvent event = parsePayload(
					message, ReservationEvent.ReservationCancelledEvent.class);
				publishPaymentCancellationRequest(
					event.reservationUid(), event.cancelReason(), event.cancelAmount());
			}
			case PG_CANCEL_CALL_REQUESTED -> paymentCancellationGatewayWorker.processCancelRequest(
				parsePayload(message, PaymentEvent.PaymentCancellationRequestedEvent.class));
			case PAYMENT_CANCELLATION_COMPLETED -> {
				PaymentEvent.PaymentCancellationCompletedEvent event = parsePayload(
					message, PaymentEvent.PaymentCancellationCompletedEvent.class);
				reservationService.completeCancellation(
					new ReservationEvent.ReservationCancellationCompleteRequestedEvent(event.reservationUid()));
			}
			case RESERVATION_CANCELLATION_COMPLETE_REQUESTED -> reservationService.completeCancellation(
				parsePayload(message, ReservationEvent.ReservationCancellationCompleteRequestedEvent.class));
			case PAYMENT_CANCELLATION_FAILED -> {
				PaymentEvent.PaymentCancellationFailedEvent event = parsePayload(
					message, PaymentEvent.PaymentCancellationFailedEvent.class);
				reservationService.revertCancellation(
					new ReservationEvent.ReservationCancellationRevertRequestedEvent(
						event.reservationUid(), event.reason()));
			}
			case RESERVATION_CANCELLATION_REVERT_REQUESTED -> reservationService.revertCancellation(
				parsePayload(message, ReservationEvent.ReservationCancellationRevertRequestedEvent.class));
			default -> log.warn("[DLQ-IGNORE] 처리 로직이 존재하지 않는 DLQ 메시지. EventType: {}", eventType);
		}
	}

	private <T extends EventPayload> T parsePayload(String message, Class<T> payloadType) {
		return debeziumEventParser.parse(message, payloadType).payload();
	}

	private void publishPaymentCancellationRequest(String reservationUid, String cancelReason, Long cancelAmount) {
		outboxEventPublisher.save(
			EventType.PG_CANCEL_CALL_REQUESTED,
			new PaymentEvent.PaymentCancellationRequestedEvent(reservationUid, cancelReason, cancelAmount)
		);
	}

	private void handleFailureAlert(String eventType, String message, Exception e) {
		log.error("[DLQ-FATAL] DLQ 메시지 자동 처리 중 심각한 오류 발생. 수동 개입 필요. Message: {}", message, e);

		String alertMessage = String.format(ALERT_MESSAGE,
			eventType,
			e.getClass().getSimpleName(),
			e.getMessage(),
			message);
		try {
			slackNotificationService.sendAlert(alertMessage);
		} catch (Exception alertException) {
			log.error("[DLQ-ALERT-FAILED] DLQ 처리 실패 알림 전송 실패.", alertException);
		}
	}
}
