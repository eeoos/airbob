package kr.kro.airbob.search.messaging.kafka;

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

import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.event.InvalidIntegrationEventException;
import kr.kro.airbob.messaging.infrastructure.kafka.KafkaRetryHeaders;
import kr.kro.airbob.messaging.alert.application.OperatorAlertEnqueueService;
import kr.kro.airbob.messaging.alert.application.OperatorAlertRequest;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.search.messaging.event.AccommodationSearchRefreshRequestedV1;
import kr.kro.airbob.search.service.AccommodationIndexingService;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AccommodationSearchRefreshListener {

	public static final String LISTENER_ID = "accommodation-search-refresh";

	private final IntegrationEventCodec eventCodec;
	private final AccommodationIndexingService indexingService;
	private final OperatorAlertEnqueueService alertEnqueueService;

	@RetryableTopic(
		attempts = "${accommodation.indexing.kafka.attempts:4}",
		backoff = @Backoff(delayExpression = "${accommodation.indexing.kafka.backoff-ms:30000}"),
		kafkaTemplate = "accommodationSearchRetryKafkaTemplate",
		listenerContainerFactory = AccommodationSearchKafkaConsumerConfiguration.CONTAINER_FACTORY,
		retryTopicSuffix = ".RETRY",
		dltTopicSuffix = ".DLT",
		sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
		dltStrategy = DltStrategy.FAIL_ON_ERROR,
		autoCreateTopics = "false"
	)
	@KafkaListener(
		id = LISTENER_ID,
		topics = AccommodationSearchRefreshRequestedV1.TOPIC,
		groupId = "${accommodation.indexing.kafka.group:accommodation-indexing-group}",
		containerFactory = AccommodationSearchKafkaConsumerConfiguration.CONTAINER_FACTORY,
		autoStartup = "#{@accommodationIndexAliasReadiness.shouldAutoStart()}"
	)
	public void handle(@Payload String message, Acknowledgment acknowledgment) {
		EventEnvelope<AccommodationSearchRefreshRequestedV1> envelope = eventCodec.decode(
			message,
			AccommodationSearchRefreshRequestedV1.DESCRIPTOR,
			AccommodationSearchRefreshRequestedV1.class
		);
		indexingService.refreshAccommodationIndex(envelope.payload().accommodationUid());
		acknowledgment.acknowledge();
	}

	@DltHandler
	public void handleDlt(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
		UUID accommodationUid = tryReadAccommodationUid(record.value()).orElse(null);
		alertEnqueueService.enqueue(OperatorAlertRequest.accommodationIndexQuarantined(
			accommodationUid, sourcePosition(record)));
		acknowledgment.acknowledge();
	}

	private Optional<UUID> tryReadAccommodationUid(String message) {
		try {
			return Optional.of(eventCodec.decode(
				message,
				AccommodationSearchRefreshRequestedV1.DESCRIPTOR,
				AccommodationSearchRefreshRequestedV1.class
			).payload().accommodationUid());
		} catch (InvalidIntegrationEventException ignored) {
			return Optional.empty();
		}
	}

	private OperatorAlertSourcePosition sourcePosition(ConsumerRecord<String, String> record) {
		String trustedTopic = AccommodationSearchRefreshRequestedV1.DESCRIPTOR.destination();
		String observedOriginalTopic = KafkaRetryHeaders.readStringHeader(
			record.headers(), KafkaHeaders.ORIGINAL_TOPIC).orElse(null);
		KafkaRetryHeaders.RecordCoordinates coordinates =
			KafkaRetryHeaders.canonicalSourceCoordinates(
				record, trustedTopic);
		return OperatorAlertSourcePosition.from(
			AccommodationSearchRefreshRequestedV1.DESCRIPTOR,
			observedOriginalTopic,
			coordinates.partition(),
			coordinates.offset());
	}
}
