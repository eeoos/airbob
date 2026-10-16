package kr.kro.airbob.messaging.alert.infrastructure.kafka;

import java.util.Optional;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
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

import kr.kro.airbob.messaging.alert.application.OperatorAlertGateway;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.messaging.alert.monitoring.OperatorAlertMetrics;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.infrastructure.kafka.KafkaRetryHeaders;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OperatorAlertKafkaListener {

	private final IntegrationEventCodec codec;
	private final OperatorAlertGateway gateway;
	private final OperatorAlertMetrics metrics;

	public OperatorAlertKafkaListener(
		IntegrationEventCodec codec,
		OperatorAlertGateway gateway,
		OperatorAlertMetrics metrics
	) {
		this.codec = codec;
		this.gateway = gateway;
		this.metrics = metrics;
	}

	@RetryableTopic(
		attempts = "${operator-alert.kafka.attempts:4}",
		backoff = @Backoff(delayExpression = "${operator-alert.kafka.backoff-ms:30000}"),
		kafkaTemplate = "operatorAlertRetryKafkaTemplate",
		listenerContainerFactory = OperatorAlertKafkaConsumerConfiguration.CONTAINER_FACTORY,
		retryTopicSuffix = ".RETRY",
		dltTopicSuffix = ".DLT",
		sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
		dltStrategy = DltStrategy.FAIL_ON_ERROR,
		autoCreateTopics = "false"
	)
	@KafkaListener(
		topics = OperatorAlertRequestedV1.TOPIC,
		groupId = "${operator-alert.kafka.group:operator-alert-delivery-group}",
		containerFactory = OperatorAlertKafkaConsumerConfiguration.CONTAINER_FACTORY,
		autoStartup = "${operator-alert.kafka.auto-startup:true}"
	)
	public void handle(@Payload String message, Acknowledgment acknowledgment) {
		try {
			OperatorAlertRequestedV1 alert = decode(message).payload();
			gateway.deliver(alert);
			metrics.delivered();
			acknowledgment.acknowledge();
		} catch (RuntimeException failure) {
			metrics.failed();
			throw failure;
		}
	}

	@DltHandler
	public void handleDlt(
		ConsumerRecord<String, String> record,
		Acknowledgment acknowledgment
	) {
		String topic = KafkaRetryHeaders.readStringHeader(
			record.headers(), KafkaHeaders.ORIGINAL_TOPIC)
			.orElse(record.topic());
		int partition = KafkaRetryHeaders.readIntHeader(
			record.headers(), KafkaHeaders.ORIGINAL_PARTITION)
			.orElse(record.partition());
		long offset = KafkaRetryHeaders.readLongHeader(
			record.headers(), KafkaHeaders.ORIGINAL_OFFSET)
			.orElse(record.offset());
		handleDlt(record.value(), topic, partition, offset, acknowledgment);
	}

	public void handleDlt(
		String message,
		String topic,
		int partition,
		long offset,
		Acknowledgment acknowledgment
	) {
		UUID alertUid = tryReadAlertUid(message).orElse(null);
		metrics.dlt();
		log.error(
			"operator alert retained in DLT. alertUid={}, topic={}, partition={}, offset={}",
			alertUid,
			topic,
			partition,
			offset
		);
		acknowledgment.acknowledge();
	}

	private EventEnvelope<OperatorAlertRequestedV1> decode(String message) {
		return codec.decode(
			message,
			OperatorAlertRequestedV1.DESCRIPTOR,
			OperatorAlertRequestedV1.class
		);
	}

	private Optional<UUID> tryReadAlertUid(String message) {
		try {
			return Optional.of(decode(message).payload().alertUid());
		} catch (RuntimeException invalidEvent) {
			return Optional.empty();
		}
	}

}
