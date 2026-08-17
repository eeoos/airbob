package kr.kro.airbob.domain.payment.messaging.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.domain.payment.service.PaymentOperationDltIncidentService;
import kr.kro.airbob.domain.payment.service.PaymentOperationExecutor;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.infrastructure.kafka.KafkaRetryHeaders;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentOperationExecutionListener {

	private final IntegrationEventCodec codec;
	private final PaymentOperationExecutor executor;
	private final PaymentOperationDltIncidentService dltIncidentService;

	@RetryableTopic(
		attempts = "${payment.operation.kafka.attempts:4}",
		backoff = @Backoff(delayExpression = "${payment.operation.kafka.backoff-ms:30000}"),
		kafkaTemplate = "paymentOperationRetryKafkaTemplate",
		listenerContainerFactory = PaymentOperationKafkaConsumerConfiguration.CONTAINER_FACTORY,
		retryTopicSuffix = ".RETRY",
		dltTopicSuffix = ".DLT",
		sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
		dltStrategy = DltStrategy.FAIL_ON_ERROR,
		autoCreateTopics = "false"
	)
	@KafkaListener(
		topics = PaymentOperationExecutionRequestedV1.TOPIC,
		groupId = "${payment.operation.kafka.group:payment-operation-execution-group}",
		containerFactory = PaymentOperationKafkaConsumerConfiguration.CONTAINER_FACTORY
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

	private EventEnvelope<PaymentOperationExecutionRequestedV1> decode(String message) {
		return codec.decode(
			message,
			PaymentOperationExecutionRequestedV1.DESCRIPTOR,
			PaymentOperationExecutionRequestedV1.class
		);
	}

	private OperatorAlertSourcePosition sourcePosition(ConsumerRecord<String, String> record) {
		KafkaRetryHeaders.RecordCoordinates coordinates =
			KafkaRetryHeaders.canonicalSourceCoordinates(
				record, PaymentOperationExecutionRequestedV1.TOPIC);
		return new OperatorAlertSourcePosition(
			PaymentOperationExecutionRequestedV1.TOPIC,
			coordinates.partition(),
			coordinates.offset());
	}
}
