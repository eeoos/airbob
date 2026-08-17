package kr.kro.airbob.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.SlackNotificationService;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 상세 캐시 무효화 Kafka 소비자 테스트")
class AccommodationDetailCacheInvalidationConsumerTest {

	private static final String MESSAGE = """
		{
		  "event_id":"12f4c680-6c60-4b60-a1c8-8ef1ea285207",
		  "trace_id":"1",
		  "event_type":"CACHE_INVALIDATION_REQUESTED",
		  "event_version":"1.0",
		  "timestamp":"2026-08-16T05:30:00.000000",
		  "payload":{"accommodation_id":1,"reason":"IMAGE"}
		}
		""";

	@Mock private AccommodationDetailCache cache;
	@Mock private SlackNotificationService slackNotificationService;
	@Mock private Acknowledgment acknowledgment;

	private AccommodationDetailCacheInvalidationConsumer consumer;

	@BeforeEach
	void setUp() {
		ObjectMapper objectMapper = new ObjectMapper()
			.findAndRegisterModules()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		consumer = new AccommodationDetailCacheInvalidationConsumer(
			new DebeziumEventParser(objectMapper), cache, slackNotificationService);
	}

	@Test
	@DisplayName("내구성 삭제가 성공한 뒤에만 ACK한다")
	void evictsThenAcknowledges() {
		consumer.handle(MESSAGE, acknowledgment);

		InOrder order = inOrder(cache, acknowledgment);
		order.verify(cache).evictOrThrow(1L, AccommodationDetailCacheInvalidationReason.IMAGE);
		order.verify(acknowledgment).acknowledge();
	}

	@Test
	@DisplayName("Redis 삭제 실패는 retry 토픽으로 전달되도록 전파하고 ACK하지 않는다")
	void rethrowsEvictionFailureWithoutAck() {
		willThrow(new IllegalStateException("redis unavailable"))
			.given(cache).evictOrThrow(1L, AccommodationDetailCacheInvalidationReason.IMAGE);

		assertThatThrownBy(() -> consumer.handle(MESSAGE, acknowledgment))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("redis unavailable");
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("다른 이벤트 타입은 캐시를 삭제하지 않고 실패로 처리한다")
	void rejectsUnsupportedEventType() {
		String message = MESSAGE.replace(
			"CACHE_INVALIDATION_REQUESTED", "ACCOMMODATION_UPDATED");

		assertThatThrownBy(() -> consumer.handle(message, acknowledgment))
			.isInstanceOf(AccommodationDetailCacheInvalidationEventParsingException.class);
		then(cache).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("JSON null은 재시도 대상으로 처리하고 캐시를 삭제하거나 ACK하지 않는다")
	void rejectsNullEnvelopeWithoutAck() {
		assertThatThrownBy(() -> consumer.handle("null", acknowledgment))
			.isInstanceOf(AccommodationDetailCacheInvalidationEventParsingException.class);
		then(cache).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("재시도를 소진한 이벤트는 원본 좌표와 숙소 ID를 알리고 ACK한다")
	void alertsQuarantinedEventThenAcknowledges() {
		ConsumerRecord<String, String> record = new ConsumerRecord<>(
			"ACCOMMODATION_CACHE.events.DLT", 0, 7L, "1", MESSAGE);
		record.headers().add(
			KafkaHeaders.ORIGINAL_TOPIC,
			"ACCOMMODATION_CACHE.events".getBytes(StandardCharsets.UTF_8));
		record.headers().add(
			KafkaHeaders.ORIGINAL_PARTITION,
			ByteBuffer.allocate(Integer.BYTES).putInt(2).array());
		record.headers().add(
			KafkaHeaders.ORIGINAL_OFFSET,
			ByteBuffer.allocate(Long.BYTES).putLong(41L).array());

		consumer.handleDlt(record, acknowledgment);

		then(slackNotificationService).should().sendAlert(org.mockito.ArgumentMatchers.argThat(alert ->
			alert.contains("CACHE_INVALIDATION_REQUESTED")
				&& alert.contains("ACCOMMODATION_CACHE.events")
				&& alert.contains("accommodationId=1")
				&& alert.contains("reason=IMAGE")));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("파싱할 수 없는 null 이벤트는 식별 정보를 unavailable로 알리고 ACK한다")
	void alertsUnavailableMetadataForNullEventThenAcknowledges() {
		ConsumerRecord<String, String> record = new ConsumerRecord<>(
			"ACCOMMODATION_CACHE.events.DLT", 1, 9L, "1", "null");

		consumer.handleDlt(record, acknowledgment);

		then(slackNotificationService).should().sendAlert(org.mockito.ArgumentMatchers.argThat(alert ->
			alert.contains("topic=ACCOMMODATION_CACHE.events.DLT")
				&& alert.contains("partition=1")
				&& alert.contains("offset=9")
				&& alert.contains("accommodationId=unavailable")
				&& alert.contains("reason=unavailable")));
		then(acknowledgment).should(times(1)).acknowledge();
	}

	@Test
	@DisplayName("DLT 알림 전송 실패를 격리하고 ACK는 한 번만 수행한다")
	void isolatesSlackAlertFailureAndAcknowledgesOnce() {
		ConsumerRecord<String, String> record = new ConsumerRecord<>(
			"ACCOMMODATION_CACHE.events.DLT", 0, 7L, "1", MESSAGE);
		willThrow(new IllegalStateException("slack unavailable"))
			.given(slackNotificationService).sendAlert(org.mockito.ArgumentMatchers.anyString());

		consumer.handleDlt(record, acknowledgment);

		then(slackNotificationService).should(times(1))
			.sendAlert(org.mockito.ArgumentMatchers.anyString());
		then(acknowledgment).should(times(1)).acknowledge();
	}

	@Test
	@DisplayName("전용 토픽, 소비자 그룹, retry 토픽과 DLT 계약을 구성한다")
	void configuresDedicatedRetryAndDlt() throws NoSuchMethodException {
		var method = AccommodationDetailCacheInvalidationConsumer.class
			.getMethod("handle", String.class, Acknowledgment.class);
		RetryableTopic retryableTopic = method.getAnnotation(RetryableTopic.class);
		KafkaListener kafkaListener = method.getAnnotation(KafkaListener.class);

		assertThat(retryableTopic).isNotNull();
		assertThat(retryableTopic.attempts())
			.isEqualTo("${accommodation.detail-cache.invalidation.kafka.attempts:4}");
		assertThat(retryableTopic.backoff().delayExpression())
			.isEqualTo("${accommodation.detail-cache.invalidation.kafka.backoff-ms:30000}");
		assertThat(retryableTopic.kafkaTemplate()).isEqualTo("deadLetterKafkaTemplate");
		assertThat(retryableTopic.retryTopicSuffix()).isEqualTo(".RETRY");
		assertThat(retryableTopic.dltTopicSuffix()).isEqualTo(".DLT");
		assertThat(retryableTopic.sameIntervalTopicReuseStrategy())
			.isEqualTo(SameIntervalTopicReuseStrategy.SINGLE_TOPIC);
		assertThat(retryableTopic.dltStrategy()).isEqualTo(DltStrategy.FAIL_ON_ERROR);
		assertThat(kafkaListener.topics()).containsExactly(
			"${accommodation.detail-cache.invalidation.kafka.topic:ACCOMMODATION_CACHE.events}");
		assertThat(kafkaListener.groupId()).isEqualTo(
			"${accommodation.detail-cache.invalidation.kafka.group:accommodation-detail-cache-invalidation-group}");
		assertThat(kafkaListener.autoStartup()).isEqualTo(
			"${accommodation.detail-cache.invalidation.kafka.auto-startup:true}");
	}
}
