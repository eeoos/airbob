package kr.kro.airbob.domain.payment.messaging.kafka;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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

import kr.kro.airbob.domain.payment.service.PaymentOperationDltIncidentService;
import kr.kro.airbob.domain.payment.service.PaymentOperationExecutor;
import kr.kro.airbob.domain.payment.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentOperationEventsConsumer {

	private final IntegrationEventCodec codec;
	private final PaymentOperationExecutor executor;
	private final PaymentOperationDltIncidentService dltIncidentService;

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
		dltIncidentService.record(record.value(), sourcePosition(record));
		ack.acknowledge();
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

	private OperatorAlertSourcePosition sourcePosition(ConsumerRecord<String, String> record) {
		boolean canonicalTopic = readStringHeader(record, KafkaHeaders.ORIGINAL_TOPIC)
			.filter(PaymentOperationExecutionRequestedV1.TOPIC::equals)
			.isPresent();
		int partition = canonicalTopic
			? readIntHeader(record, KafkaHeaders.ORIGINAL_PARTITION)
				.filter(value -> value >= 0)
				.orElse(record.partition())
			: record.partition();
		long offset = canonicalTopic
			? readLongHeader(record, KafkaHeaders.ORIGINAL_OFFSET)
				.filter(value -> value >= 0)
				.orElse(record.offset())
			: record.offset();
		return new OperatorAlertSourcePosition(
			PaymentOperationExecutionRequestedV1.TOPIC, partition, offset);
	}
}
