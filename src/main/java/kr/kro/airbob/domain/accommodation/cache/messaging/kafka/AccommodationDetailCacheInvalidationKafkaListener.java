package kr.kro.airbob.domain.accommodation.cache.messaging.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Profile;
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

import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.domain.accommodation.cache.messaging.event.AccommodationDetailCacheInvalidationRequestedV1;
import kr.kro.airbob.messaging.alert.application.OperatorAlertEnqueueService;
import kr.kro.airbob.messaging.alert.application.OperatorAlertRequest;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.infrastructure.kafka.KafkaRetryHeaders;
import lombok.RequiredArgsConstructor;

@Component
@Profile("!traffic-benchmark")
@RequiredArgsConstructor
public class AccommodationDetailCacheInvalidationKafkaListener {

	private final IntegrationEventCodec codec;
	private final AccommodationDetailCache cache;
	private final OperatorAlertEnqueueService alertEnqueueService;

	@RetryableTopic(
		attempts = "${accommodation.detail-cache.invalidation.kafka.attempts:4}",
		backoff = @Backoff(
			delayExpression = "${accommodation.detail-cache.invalidation.kafka.backoff-ms:30000}"),
		kafkaTemplate = "accommodationDetailCacheRetryKafkaTemplate",
		listenerContainerFactory = AccommodationDetailCacheKafkaConsumerConfiguration.CONTAINER_FACTORY,
		retryTopicSuffix = ".RETRY",
		dltTopicSuffix = ".DLT",
		sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
		dltStrategy = DltStrategy.FAIL_ON_ERROR,
		autoCreateTopics = "false"
	)
	@KafkaListener(
		topics = AccommodationDetailCacheInvalidationRequestedV1.TOPIC,
		groupId = "${accommodation.detail-cache.invalidation.kafka.group:accommodation-detail-cache-invalidation-group}",
		containerFactory = AccommodationDetailCacheKafkaConsumerConfiguration.CONTAINER_FACTORY,
		autoStartup = "${accommodation.detail-cache.invalidation.kafka.auto-startup:true}"
	)
	public void handle(@Payload String message, Acknowledgment acknowledgment) {
		AccommodationDetailCacheInvalidationRequestedV1 event = decode(message).payload();
		cache.evictOrThrow(event.accommodationId(), event.reason());
		acknowledgment.acknowledge();
	}

	@DltHandler
	public void handleDlt(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
		alertEnqueueService.enqueue(OperatorAlertRequest.accommodationCacheQuarantined(
			sourcePosition(record)));
		acknowledgment.acknowledge();
	}

	private EventEnvelope<AccommodationDetailCacheInvalidationRequestedV1> decode(String message) {
		return codec.decode(
			message,
			AccommodationDetailCacheInvalidationRequestedV1.DESCRIPTOR,
			AccommodationDetailCacheInvalidationRequestedV1.class);
	}

	private OperatorAlertSourcePosition sourcePosition(ConsumerRecord<String, String> record) {
		String trustedTopic = AccommodationDetailCacheInvalidationRequestedV1.DESCRIPTOR.destination();
		String observedOriginalTopic = KafkaRetryHeaders.readStringHeader(
			record.headers(), KafkaHeaders.ORIGINAL_TOPIC).orElse(null);
		KafkaRetryHeaders.RecordCoordinates coordinates =
			KafkaRetryHeaders.canonicalSourceCoordinates(record, trustedTopic);
		return OperatorAlertSourcePosition.from(
			AccommodationDetailCacheInvalidationRequestedV1.DESCRIPTOR,
			observedOriginalTopic,
			coordinates.partition(),
			coordinates.offset());
	}
}
