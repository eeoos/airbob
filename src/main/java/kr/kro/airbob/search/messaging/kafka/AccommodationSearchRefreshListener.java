package kr.kro.airbob.search.messaging.kafka;

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

import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.event.InvalidIntegrationEventException;
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
		listenerContainerFactory = AccommodationSearchKafkaConsumerConfig.CONTAINER_FACTORY,
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
		containerFactory = AccommodationSearchKafkaConsumerConfig.CONTAINER_FACTORY,
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

	private OperatorAlertSourcePosition sourcePosition(ConsumerRecord<String, String> record) {
		boolean canonicalTopic = readStringHeader(record, KafkaHeaders.ORIGINAL_TOPIC)
			.filter(AccommodationSearchRefreshRequestedV1.TOPIC::equals)
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
			AccommodationSearchRefreshRequestedV1.TOPIC, partition, offset);
	}
}
