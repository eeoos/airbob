package kr.kro.airbob.domain.payment.messaging.kafka;

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

import kr.kro.airbob.domain.payment.service.PaymentOperationAlertService;
import kr.kro.airbob.domain.payment.service.PaymentOperationExecutor;
import kr.kro.airbob.domain.payment.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOperationEventsConsumer {

	private static final String PROCESSING_FAILURE = "processing failure";
	private static final String FAILURE_UNAVAILABLE = "failure unavailable";

	private final IntegrationEventCodec codec;
	private final PaymentOperationExecutor executor;
	private final PaymentOperationAlertService alertService;

	@RetryableTopic(
		attempts = "${payment.operation.kafka.attempts:4}",
		backoff = @Backoff(delayExpression = "${payment.operation.kafka.backoff-ms:30000}"),
		kafkaTemplate = "paymentOperationRetryKafkaTemplate",
		listenerContainerFactory = "paymentOperationKafkaListenerContainerFactory",
		retryTopicSuffix = ".RETRY",
		dltTopicSuffix = ".DLT",
		sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
		dltStrategy = DltStrategy.FAIL_ON_ERROR,
		autoCreateTopics = "false"
	)
	@KafkaListener(
		topics = PaymentOperationExecutionRequestedV1.TOPIC,
		groupId = "${payment.operation.kafka.group:payment-operation-execution-group}",
		containerFactory = "paymentOperationKafkaListenerContainerFactory"
	)
	public void handle(@Payload String message, Acknowledgment ack) {
		PaymentOperationExecutionRequestedV1 event = decode(message).payload();
		executor.execute(event.operationUid(), event.dispatchGeneration());
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
		handleDlt(record.value(), topic, partition, offset, PROCESSING_FAILURE, ack);
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

	private EventEnvelope<PaymentOperationExecutionRequestedV1> decode(String message) {
		return codec.decode(
			message,
			PaymentOperationExecutionRequestedV1.DESCRIPTOR,
			PaymentOperationExecutionRequestedV1.class
		);
	}

	private Optional<UUID> tryReadOperationUid(String message) {
		try {
			return Optional.of(decode(message).payload().operationUid());
		} catch (RuntimeException invalidEvent) {
			return Optional.empty();
		}
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

	private String sanitize(String error) {
		return error == null || error.isBlank() ? FAILURE_UNAVAILABLE : PROCESSING_FAILURE;
	}
}
