package kr.kro.airbob.kafka.consumer;

import static kr.kro.airbob.outbox.EventType.PAYMENT_EXECUTION_REQUESTED_V1;

import java.util.Optional;
import java.util.UUID;

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

import kr.kro.airbob.domain.payment.event.PaymentOperationEvent.PaymentExecutionRequestedV1;
import kr.kro.airbob.domain.payment.service.PaymentOperationAlertService;
import kr.kro.airbob.domain.payment.service.PaymentOperationExecutor;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOperationEventsConsumer {

	private static final String PROCESSING_FAILURE = "processing failure";
	private static final String FAILURE_UNAVAILABLE = "failure unavailable";

	private final DebeziumEventParser parser;
	private final PaymentOperationExecutor executor;
	private final PaymentOperationAlertService alertService;

	@RetryableTopic(
		attempts = "${payment.operation.kafka.attempts:4}",
		backoff = @Backoff(delayExpression = "${payment.operation.kafka.backoff-ms:30000}"),
		kafkaTemplate = "deadLetterKafkaTemplate",
		retryTopicSuffix = ".RETRY",
		dltTopicSuffix = ".DLT",
		sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
		dltStrategy = DltStrategy.FAIL_ON_ERROR
	)
	@KafkaListener(
		topics = "${payment.operation.kafka.topic:PAYMENT_OPERATION.events}",
		groupId = "${payment.operation.kafka.group:payment-operation-execution-group}"
	)
	public void handle(@Payload String message, Acknowledgment ack) {
		String type = parser.getEventType(message);
		if (EventType.from(type) != PAYMENT_EXECUTION_REQUESTED_V1) {
			throw new IllegalArgumentException("지원하지 않는 payment-operation 이벤트: " + type);
		}

		PaymentExecutionRequestedV1 event = parser
			.parse(message, PaymentExecutionRequestedV1.class)
			.payload();
		executor.execute(event.operationUid());
		ack.acknowledge();
	}

	@DltHandler
	public void handleDlt(
		@Payload String message,
		@Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
		@Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
		@Header(KafkaHeaders.OFFSET) long offset,
		@Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String error,
		Acknowledgment ack
	) {
		UUID operationUid = tryReadOperationUid(message).orElse(null);
		try {
			alertService.alertQuarantined(
				topic, partition, offset, operationUid, sanitize(error));
		} catch (RuntimeException alertFailure) {
			log.error(
				"payment-operation DLT 알림 전송 실패. topic={}, partition={}, offset={}",
				topic,
				partition,
				offset
			);
		} finally {
			ack.acknowledge();
		}
	}

	private Optional<UUID> tryReadOperationUid(String message) {
		try {
			if (EventType.from(parser.getEventType(message)) != PAYMENT_EXECUTION_REQUESTED_V1) {
				return Optional.empty();
			}
			PaymentExecutionRequestedV1 event = parser
				.parse(message, PaymentExecutionRequestedV1.class)
				.payload();
			return Optional.ofNullable(event.operationUid());
		} catch (RuntimeException ignored) {
			return Optional.empty();
		}
	}

	private String sanitize(String error) {
		return error == null || error.isBlank() ? FAILURE_UNAVAILABLE : PROCESSING_FAILURE;
	}
}
