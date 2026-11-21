package kr.kro.airbob.domain.accommodation.cache.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.domain.accommodation.cache.messaging.event.AccommodationDetailCacheInvalidationRequestedV1;
import kr.kro.airbob.messaging.alert.application.OperatorAlertEnqueueService;
import kr.kro.airbob.messaging.alert.event.OperatorAlertKind;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.event.InvalidIntegrationEventException;

@ExtendWith(MockitoExtension.class)
class AccommodationDetailCacheInvalidationKafkaListenerTest {

	private static final String TOPIC = AccommodationDetailCacheInvalidationRequestedV1.TOPIC;
	private static final UUID EVENT_ID =
		UUID.fromString("12f4c680-6c60-4b60-a1c8-8ef1ea285207");

	@Mock private AccommodationDetailCache cache;
	@Mock private OperatorAlertEnqueueService alertEnqueueService;
	@Mock private Acknowledgment acknowledgment;

	private AccommodationDetailCacheInvalidationKafkaListener listener;
	private String message;

	@BeforeEach
	void setUp() {
		IntegrationEventCodec codec = new IntegrationEventCodec(new ObjectMapper()
			.findAndRegisterModules()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
		listener = new AccommodationDetailCacheInvalidationKafkaListener(
			codec, cache, alertEnqueueService);
		message = codec.encode(EventEnvelope.of(
			EVENT_ID,
			Instant.parse("2026-08-16T05:30:00Z"),
			new AccommodationDetailCacheInvalidationRequestedV1(
				1L, AccommodationDetailCacheInvalidationReason.IMAGE)));
	}

	@Test
	void acknowledgesOnlyAfterDurableEvictionSucceeds() {
		listener.handle(message, acknowledgment);

		InOrder order = inOrder(cache, acknowledgment);
		order.verify(cache).evictOrThrow(1L, AccommodationDetailCacheInvalidationReason.IMAGE);
		order.verify(acknowledgment).acknowledge();
	}

	@Test
	void evictionFailurePropagatesWithoutAcknowledgment() {
		willThrow(new IllegalStateException("redis unavailable"))
			.given(cache).evictOrThrow(1L, AccommodationDetailCacheInvalidationReason.IMAGE);

		assertThatThrownBy(() -> listener.handle(message, acknowledgment))
			.isInstanceOf(IllegalStateException.class);
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	void rejectsAnotherDescriptorWithoutEvictingOrAcknowledging() {
		String wrongDescriptor = message.replace(
			"ACCOMMODATION_DETAIL_CACHE_INVALIDATION_REQUESTED",
			"ACCOMMODATION_SEARCH_REFRESH_REQUESTED");

		assertThatThrownBy(() -> listener.handle(wrongDescriptor, acknowledgment))
			.isInstanceOf(InvalidIntegrationEventException.class);
		then(cache).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	void quarantinesWithTrustedSourceCoordinatesThenAcknowledges() {
		ConsumerRecord<String, String> record = dltRecord(message, TOPIC);

		listener.handleDlt(record, acknowledgment);

		then(alertEnqueueService).should().enqueue(org.mockito.ArgumentMatchers.argThat(request ->
			request.kind() == OperatorAlertKind.ACCOMMODATION_CACHE_QUARANTINED
				&& request.sourcePosition().topic().equals(TOPIC)
				&& request.sourcePosition().partition() == 2
				&& request.sourcePosition().offset() == 41L));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	void rejectsForgedOriginalTopicBeforeAlertingOrAcknowledging() {
		ConsumerRecord<String, String> record = dltRecord(message, "EVIL.events");

		assertThatThrownBy(() -> listener.handleDlt(record, acknowledgment))
			.isInstanceOf(IllegalArgumentException.class);
		then(alertEnqueueService).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	void usesDedicatedCanonicalRetryAndDltInfrastructure() throws NoSuchMethodException {
		var method = AccommodationDetailCacheInvalidationKafkaListener.class
			.getMethod("handle", String.class, Acknowledgment.class);
		RetryableTopic retryableTopic = method.getAnnotation(RetryableTopic.class);
		KafkaListener kafkaListener = method.getAnnotation(KafkaListener.class);

		assertThat(retryableTopic.kafkaTemplate())
			.isEqualTo("accommodationDetailCacheRetryKafkaTemplate");
		assertThat(retryableTopic.listenerContainerFactory())
			.isEqualTo(AccommodationDetailCacheKafkaConsumerConfiguration.CONTAINER_FACTORY);
		assertThat(retryableTopic.retryTopicSuffix()).isEqualTo(".RETRY");
		assertThat(retryableTopic.dltTopicSuffix()).isEqualTo(".DLT");
		assertThat(retryableTopic.sameIntervalTopicReuseStrategy())
			.isEqualTo(SameIntervalTopicReuseStrategy.SINGLE_TOPIC);
		assertThat(retryableTopic.dltStrategy()).isEqualTo(DltStrategy.FAIL_ON_ERROR);
		assertThat(retryableTopic.autoCreateTopics()).isEqualTo("false");
		assertThat(kafkaListener.topics()).containsExactly(TOPIC);
	}

	private ConsumerRecord<String, String> dltRecord(String value, String originalTopic) {
		ConsumerRecord<String, String> record = new ConsumerRecord<>(
			TOPIC + ".DLT", 0, 7L, "1", value);
		record.headers().add(
			KafkaHeaders.ORIGINAL_TOPIC,
			originalTopic.getBytes(StandardCharsets.UTF_8));
		record.headers().add(
			KafkaHeaders.ORIGINAL_PARTITION,
			ByteBuffer.allocate(Integer.BYTES).putInt(2).array());
		record.headers().add(
			KafkaHeaders.ORIGINAL_OFFSET,
			ByteBuffer.allocate(Long.BYTES).putLong(41L).array());
		return record;
	}
}
