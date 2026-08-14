package kr.kro.airbob.kafka.consumer;

import static kr.kro.airbob.outbox.EventType.PAYMENT_EXECUTION_REQUESTED_V1;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
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
		kafkaTemplate = "paymentOperationRetryKafkaTemplate",
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
	public void handleDlt(ConsumerRecord<String, String> record, Acknowledgment ack) {
		String topic = readStringHeader(record, KafkaHeaders.ORIGINAL_TOPIC)
			.orElse(record.topic());
		int partition = readIntHeader(record, KafkaHeaders.ORIGINAL_PARTITION)
			.orElse(record.partition());
		long offset = readLongHeader(record, KafkaHeaders.ORIGINAL_OFFSET)
			.orElse(record.offset());
		String error = record.headers().lastHeader(KafkaHeaders.EXCEPTION_FQCN) == null
			? null
			: PROCESSING_FAILURE;
		handleDlt(record.value(), topic, partition, offset, error, ack);
	}

	public void handleDlt(
		@Payload String message,
		String topic,
		int partition,
		long offset,
		String error,
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

	private Optional<String> readStringHeader(ConsumerRecord<String, String> record, String name) {
		return Optional.ofNullable(record.headers().lastHeader(name))
			.map(Header::value)
			.map(value -> new String(value, StandardCharsets.UTF_8));
	}

	private Optional<Integer> readIntHeader(ConsumerRecord<String, String> record, String name) {
		return Optional.ofNullable(record.headers().lastHeader(name))
			.map(Header::value)
			.filter(value -> value.length == Integer.BYTES)
			.map(value -> ByteBuffer.wrap(value).getInt());
	}

	private Optional<Long> readLongHeader(ConsumerRecord<String, String> record, String name) {
		return Optional.ofNullable(record.headers().lastHeader(name))
			.map(Header::value)
			.filter(value -> value.length == Long.BYTES)
			.map(value -> ByteBuffer.wrap(value).getLong());
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
