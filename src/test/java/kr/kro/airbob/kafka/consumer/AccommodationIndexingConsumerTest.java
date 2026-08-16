package kr.kro.airbob.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

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

import kr.kro.airbob.search.service.AccommodationIndexingAlertService;
import kr.kro.airbob.search.service.AccommodationIndexingService;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 색인 Kafka 소비자 테스트")
class AccommodationIndexingConsumerTest {

	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("109cc081-b87d-4502-9a5e-7d7b65993056");
	private static final String UPDATED_MESSAGE = """
		{"event_type":"ACCOMMODATION_UPDATED","payload":{"accommodation_uid":"%s"}}
		""".formatted(ACCOMMODATION_UID);
	private static final String DELETED_MESSAGE = """
		{"event_type":"ACCOMMODATION_DELETED","payload":{"accommodation_uid":"%s"}}
		""".formatted(ACCOMMODATION_UID);

	@Mock private AccommodationIndexingService indexingService;
	@Mock private AccommodationIndexingAlertService alertService;
	@Mock private Acknowledgment acknowledgment;

	private AccommodationIndexingConsumer consumer;

	@BeforeEach
	void setUp() {
		ObjectMapper objectMapper = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		consumer = new AccommodationIndexingConsumer(
			new AccommodationIndexingEventParser(objectMapper), indexingService, alertService);
	}

	@Test
	@DisplayName("MySQL 최신 상태로 색인을 갱신한 뒤에만 ACK한다")
	void refreshesProjectionThenAcknowledges() {
		consumer.handle(UPDATED_MESSAGE, acknowledgment);

		InOrder order = inOrder(indexingService, acknowledgment);
		order.verify(indexingService).refreshAccommodationIndex(ACCOMMODATION_UID);
		order.verify(acknowledgment).acknowledge();
	}

	@Test
	@DisplayName("삭제 이벤트는 문서를 다시 만들지 않고 UID 기준 삭제 후 ACK한다")
	void deletesProjectionThenAcknowledges() {
		consumer.handle(DELETED_MESSAGE, acknowledgment);

		InOrder order = inOrder(indexingService, acknowledgment);
		order.verify(indexingService).deleteAccommodationIndex(ACCOMMODATION_UID);
		order.verify(acknowledgment).acknowledge();
		then(indexingService).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("처리 실패는 전파하고 ACK하지 않는다")
	void rethrowsIndexingFailureWithoutAck() {
		willThrow(new IllegalStateException("elasticsearch unavailable"))
			.given(indexingService).refreshAccommodationIndex(ACCOMMODATION_UID);

		assertThatThrownBy(() -> consumer.handle(UPDATED_MESSAGE, acknowledgment))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("elasticsearch unavailable");
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("파싱 실패도 전용 retry/DLT로 보내기 위해 ACK하지 않는다")
	void rethrowsParsingFailureWithoutAck() {
		assertThatThrownBy(() -> consumer.handle("not-json", acknowledgment))
			.isInstanceOf(AccommodationIndexingEventParsingException.class);
		then(indexingService).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("DLT 알림은 원본 좌표와 식별자만 전달하고 ACK한다")
	void alertsWithOriginalCoordinatesAndIdentifierThenAcknowledges() {
		ConsumerRecord<String, String> record = new ConsumerRecord<>(
			"ACCOMMODATION.events.DLT", 0, 7L, ACCOMMODATION_UID.toString(), UPDATED_MESSAGE);
		record.headers().add(
			KafkaHeaders.ORIGINAL_TOPIC,
			"ACCOMMODATION.events".getBytes(StandardCharsets.UTF_8));
		record.headers().add(
			KafkaHeaders.ORIGINAL_PARTITION,
			ByteBuffer.allocate(Integer.BYTES).putInt(2).array());
		record.headers().add(
			KafkaHeaders.ORIGINAL_OFFSET,
			ByteBuffer.allocate(Long.BYTES).putLong(41L).array());

		consumer.handleDlt(record, acknowledgment);

		InOrder order = inOrder(alertService, acknowledgment);
		order.verify(alertService).alertQuarantined(
			"ACCOMMODATION.events",
			2,
			41L,
			kr.kro.airbob.outbox.EventType.ACCOMMODATION_UPDATED,
			ACCOMMODATION_UID
		);
		order.verify(acknowledgment).acknowledge();
	}

	@Test
	@DisplayName("알림 전송 실패는 이미 격리된 DLT의 ACK를 막지 않는다")
	void acknowledgesDltWhenAlertDeliveryFails() {
		ConsumerRecord<String, String> record = new ConsumerRecord<>(
			"ACCOMMODATION.events.DLT", 0, 7L, null, "not-json");
		willThrow(new IllegalStateException("slack unavailable"))
			.given(alertService).alertQuarantined(
				"ACCOMMODATION.events.DLT", 0, 7L, null, null);

		consumer.handleDlt(record, acknowledgment);

		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("전용 토픽, 그룹, retry 토픽과 DLT 계약을 구성한다")
	void configuresDedicatedRetryAndDlt() throws NoSuchMethodException {
		var method = AccommodationIndexingConsumer.class
			.getMethod("handle", String.class, Acknowledgment.class);
		RetryableTopic retryableTopic = method.getAnnotation(RetryableTopic.class);
		KafkaListener kafkaListener = method.getAnnotation(KafkaListener.class);

		assertThat(retryableTopic).isNotNull();
		assertThat(retryableTopic.attempts())
			.isEqualTo("${accommodation.indexing.kafka.attempts:4}");
		assertThat(retryableTopic.backoff().delayExpression())
			.isEqualTo("${accommodation.indexing.kafka.backoff-ms:30000}");
		assertThat(retryableTopic.kafkaTemplate()).isEqualTo("deadLetterKafkaTemplate");
		assertThat(retryableTopic.retryTopicSuffix()).isEqualTo(".RETRY");
		assertThat(retryableTopic.dltTopicSuffix()).isEqualTo(".DLT");
		assertThat(retryableTopic.sameIntervalTopicReuseStrategy())
			.isEqualTo(SameIntervalTopicReuseStrategy.SINGLE_TOPIC);
		assertThat(retryableTopic.dltStrategy()).isEqualTo(DltStrategy.FAIL_ON_ERROR);
		assertThat(kafkaListener.topics())
			.containsExactly("${accommodation.indexing.kafka.topic:ACCOMMODATION.events}");
		assertThat(kafkaListener.groupId())
			.isEqualTo("${accommodation.indexing.kafka.group:accommodation-indexing-group}");
	}
}
