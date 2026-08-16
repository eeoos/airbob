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

import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.search.service.AccommodationIndexingAlertService;
import kr.kro.airbob.search.service.AccommodationIndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccommodationIndexingConsumer {

	private final AccommodationIndexingEventParser parser;
	private final AccommodationIndexingService indexingService;
	private final AccommodationIndexingAlertService alertService;

	@RetryableTopic(
		attempts = "${accommodation.indexing.kafka.attempts:4}",
		backoff = @Backoff(delayExpression = "${accommodation.indexing.kafka.backoff-ms:30000}"),
		kafkaTemplate = "deadLetterKafkaTemplate",
		retryTopicSuffix = ".RETRY",
		dltTopicSuffix = ".DLT",
		sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
		dltStrategy = DltStrategy.FAIL_ON_ERROR
	)
	@KafkaListener(
		topics = "${accommodation.indexing.kafka.topic:ACCOMMODATION.events}",
		groupId = "${accommodation.indexing.kafka.group:accommodation-indexing-group}"
	)
	public void handle(@Payload String message, Acknowledgment ack) {
		AccommodationIndexingCommand command = parser.parse(message);
		if (command.eventType() == EventType.ACCOMMODATION_DELETED) {
			indexingService.deleteAccommodationIndex(command.accommodationUid());
		} else {
			indexingService.refreshAccommodationIndex(command.accommodationUid());
		}
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
		Optional<AccommodationIndexingCommand> command = parser.tryParse(record.value());
		try {
			alertService.alertQuarantined(
				topic,
				partition,
				offset,
				command.map(AccommodationIndexingCommand::eventType).orElse(null),
				command.map(AccommodationIndexingCommand::accommodationUid).orElse(null)
			);
		} catch (RuntimeException alertFailure) {
			log.error(
				"숙소 색인 DLT 알림 전송 실패. topic={}, partition={}, offset={}",
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
}
