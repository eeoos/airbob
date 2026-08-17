package kr.kro.airbob.kafka.consumer;

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

import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.domain.accommodation.cache.invalidation.AccommodationDetailCacheInvalidationRequestedEvent;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventEnvelope;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.SlackNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccommodationDetailCacheInvalidationConsumer {

	private static final String ALERT_MESSAGE = """
		[accommodation-detail-cache-invalidation-quarantined]
		eventType=%s
		topic=%s
		partition=%d
		offset=%d
		accommodationId=%s
		reason=%s
		""";

	private final DebeziumEventParser debeziumEventParser;
	private final AccommodationDetailCache cache;
	private final SlackNotificationService slackNotificationService;

	@RetryableTopic(
		attempts = "${accommodation.detail-cache.invalidation.kafka.attempts:4}",
		backoff = @Backoff(
			delayExpression = "${accommodation.detail-cache.invalidation.kafka.backoff-ms:30000}"),
		kafkaTemplate = "deadLetterKafkaTemplate",
		retryTopicSuffix = ".RETRY",
		dltTopicSuffix = ".DLT",
		sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
		dltStrategy = DltStrategy.FAIL_ON_ERROR
	)
	@KafkaListener(
		topics = "${accommodation.detail-cache.invalidation.kafka.topic:ACCOMMODATION_CACHE.events}",
		groupId = "${accommodation.detail-cache.invalidation.kafka.group:accommodation-detail-cache-invalidation-group}",
		autoStartup = "${accommodation.detail-cache.invalidation.kafka.auto-startup:true}"
	)
	public void handle(@Payload String message, Acknowledgment acknowledgment) {
		AccommodationDetailCacheInvalidationRequestedEvent event = parse(message);
		cache.evictOrThrow(event.accommodationId(), event.reason());
		acknowledgment.acknowledge();
	}

	@DltHandler
	public void handleDlt(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
		String topic = readStringHeader(record, KafkaHeaders.ORIGINAL_TOPIC)
			.orElse(record.topic());
		int partition = readIntHeader(record, KafkaHeaders.ORIGINAL_PARTITION)
			.orElse(record.partition());
		long offset = readLongHeader(record, KafkaHeaders.ORIGINAL_OFFSET)
			.orElse(record.offset());
		Optional<AccommodationDetailCacheInvalidationRequestedEvent> event = tryParse(record.value());

		try {
			slackNotificationService.sendAlert(ALERT_MESSAGE.formatted(
				EventType.CACHE_INVALIDATION_REQUESTED.name(),
				topic,
				partition,
				offset,
				event.map(AccommodationDetailCacheInvalidationRequestedEvent::accommodationId)
					.map(String::valueOf)
					.orElse("unavailable"),
				event.map(AccommodationDetailCacheInvalidationRequestedEvent::reason)
					.map(Enum::name)
					.orElse("unavailable")
			));
		} catch (RuntimeException alertFailure) {
			log.error(
				"숙소 상세 캐시 무효화 DLT 알림 전송 실패. topic={}, partition={}, offset={}",
				topic,
				partition,
				offset,
				alertFailure
			);
		} finally {
			acknowledgment.acknowledge();
		}
	}

	private AccommodationDetailCacheInvalidationRequestedEvent parse(String message) {
		EventEnvelope<AccommodationDetailCacheInvalidationRequestedEvent> envelope;
		try {
			envelope = debeziumEventParser.parse(
				message, AccommodationDetailCacheInvalidationRequestedEvent.class);
		} catch (RuntimeException exception) {
			throw new AccommodationDetailCacheInvalidationEventParsingException(exception);
		}

		if (envelope == null
			|| !EventType.CACHE_INVALIDATION_REQUESTED.name().equals(envelope.eventType())
			|| envelope.payload() == null) {
			throw new AccommodationDetailCacheInvalidationEventParsingException();
		}
		return envelope.payload();
	}

	private Optional<AccommodationDetailCacheInvalidationRequestedEvent> tryParse(String message) {
		try {
			return Optional.of(parse(message));
		} catch (AccommodationDetailCacheInvalidationEventParsingException ignored) {
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
}
