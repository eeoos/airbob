package kr.kro.airbob.search.messaging.kafka;

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

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.event.InvalidIntegrationEventException;
import kr.kro.airbob.messaging.alert.application.OperatorAlertEnqueueService;
import kr.kro.airbob.messaging.alert.application.OperatorAlertRequest;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.search.messaging.event.AccommodationSearchRefreshRequestedV1;
import kr.kro.airbob.search.service.AccommodationIndexingService;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 검색 refresh Kafka listener")
class AccommodationSearchRefreshListenerTest {

	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("109cc081-b87d-4502-9a5e-7d7b65993056");

	@Mock private AccommodationIndexingService indexingService;
	@Mock private OperatorAlertEnqueueService alertEnqueueService;
	@Mock private Acknowledgment acknowledgment;

	private IntegrationEventCodec eventCodec;
	private AccommodationSearchRefreshListener listener;

	@BeforeEach
	void setUp() {
		eventCodec = new IntegrationEventCodec(JsonMapper.builder()
			.addModule(new JavaTimeModule())
			.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.build());
		listener = new AccommodationSearchRefreshListener(
			eventCodec, indexingService, alertEnqueueService);
	}

	@Test
	@DisplayName("엄격히 검증한 V1 refresh를 MySQL 최신 상태로 반영한 뒤 ACK한다")
	void refreshesThenAcknowledges() {
		listener.handle(validMessage(), acknowledgment);

		InOrder order = inOrder(indexingService, acknowledgment);
		order.verify(indexingService).refreshAccommodationIndex(ACCOMMODATION_UID);
		order.verify(acknowledgment).acknowledge();
	}

	@Test
	@DisplayName("레거시 이벤트와 poison payload는 전용 retry/DLT를 타도록 ACK하지 않는다")
	void rejectsLegacyAndPoisonMessagesWithoutAck() {
		String legacy = validMessage().replace(
			"ACCOMMODATION_SEARCH_REFRESH_REQUESTED", "ACCOMMODATION_DELETED");
		String poison = validMessage().replace(
			"\"accommodation_uid\":", "\"secret\":\"do-not-leak\",\"accommodation_uid\":");

		assertThatThrownBy(() -> listener.handle(legacy, acknowledgment))
			.isInstanceOf(InvalidIntegrationEventException.class);
		assertThatThrownBy(() -> listener.handle(poison, acknowledgment))
			.isInstanceOf(InvalidIntegrationEventException.class)
			.hasMessage("Invalid integration event.")
			.hasNoCause();
		then(indexingService).shouldHaveNoInteractions();
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("색인 실패는 전파하고 ACK하지 않는다")
	void rethrowsIndexingFailureWithoutAck() {
		willThrow(new IllegalStateException("elasticsearch unavailable"))
			.given(indexingService).refreshAccommodationIndex(ACCOMMODATION_UID);

		assertThatThrownBy(() -> listener.handle(validMessage(), acknowledgment))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("elasticsearch unavailable");
		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("DLT는 원본 좌표와 식별자만 알림한 뒤 ACK한다")
	void alertsWithOriginalCoordinatesAndIdentifier() {
		ConsumerRecord<String, String> record = new ConsumerRecord<>(
			"ACCOMMODATION_INDEX.events.DLT", 0, 7L,
			ACCOMMODATION_UID.toString(), validMessage());
		record.headers().add(
			KafkaHeaders.ORIGINAL_TOPIC,
			"ACCOMMODATION_INDEX.events".getBytes(StandardCharsets.UTF_8));
		record.headers().add(
			KafkaHeaders.ORIGINAL_PARTITION,
			ByteBuffer.allocate(Integer.BYTES).putInt(2).array());
		record.headers().add(
			KafkaHeaders.ORIGINAL_OFFSET,
			ByteBuffer.allocate(Long.BYTES).putLong(41L).array());

		listener.handleDlt(record, acknowledgment);

		OperatorAlertRequest request = OperatorAlertRequest.accommodationIndexQuarantined(
			ACCOMMODATION_UID,
			new OperatorAlertSourcePosition("ACCOMMODATION_INDEX.events", 2, 41L));
		InOrder order = inOrder(alertEnqueueService, acknowledgment);
		order.verify(alertEnqueueService).enqueue(request);
		order.verify(acknowledgment).acknowledge();
	}

	@Test
	@DisplayName("poison DLT는 원문 없이 좌표 기반 subject로 durable alert를 만든다")
	void poisonDltUsesDeterministicCoordinateSubject() {
		ConsumerRecord<String, String> record = dltRecord("not-json paymentKey=secret", 0, 7L);
		OperatorAlertSourcePosition source =
			new OperatorAlertSourcePosition("ACCOMMODATION_INDEX.events", 0, 7L);

		listener.handleDlt(record, acknowledgment);

		then(alertEnqueueService).should().enqueue(
			OperatorAlertRequest.accommodationIndexQuarantined(null, source));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("durable alert enqueue 실패는 전파하고 DLT를 ACK하지 않는다")
	void alertEnqueueFailurePropagatesWithoutAck() {
		ConsumerRecord<String, String> record = dltRecord(validMessage(), 1, 19L);
		OperatorAlertRequest request = OperatorAlertRequest.accommodationIndexQuarantined(
			ACCOMMODATION_UID,
			new OperatorAlertSourcePosition("ACCOMMODATION_INDEX.events", 1, 19L));
		willThrow(new IllegalStateException("operator alert outbox unavailable"))
			.given(alertEnqueueService).enqueue(request);

		assertThatThrownBy(() -> listener.handleDlt(record, acknowledgment))
			.isInstanceOf(IllegalStateException.class);

		then(acknowledgment).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("전용 topic/retry/DLT와 alias readiness 시작 계약을 구성한다")
	void configuresDedicatedRetryDltAndAliasReadiness() throws Exception {
		var method = AccommodationSearchRefreshListener.class
			.getMethod("handle", String.class, Acknowledgment.class);
		RetryableTopic retryableTopic = method.getAnnotation(RetryableTopic.class);
		KafkaListener kafkaListener = method.getAnnotation(KafkaListener.class);

		assertThat(retryableTopic).isNotNull();
		assertThat(retryableTopic.attempts())
			.isEqualTo("${accommodation.indexing.kafka.attempts:4}");
		assertThat(retryableTopic.kafkaTemplate())
			.isEqualTo("accommodationSearchRetryKafkaTemplate");
		assertThat(retryableTopic.listenerContainerFactory())
			.isEqualTo(AccommodationSearchKafkaConsumerConfiguration.CONTAINER_FACTORY);
		assertThat(retryableTopic.retryTopicSuffix()).isEqualTo(".RETRY");
		assertThat(retryableTopic.dltTopicSuffix()).isEqualTo(".DLT");
		assertThat(retryableTopic.autoCreateTopics()).isEqualTo("false");
		assertThat(retryableTopic.sameIntervalTopicReuseStrategy())
			.isEqualTo(SameIntervalTopicReuseStrategy.SINGLE_TOPIC);
		assertThat(retryableTopic.dltStrategy()).isEqualTo(DltStrategy.FAIL_ON_ERROR);
		assertThat(kafkaListener.id()).isEqualTo(AccommodationSearchRefreshListener.LISTENER_ID);
		assertThat(kafkaListener.containerFactory())
			.isEqualTo(AccommodationSearchKafkaConsumerConfiguration.CONTAINER_FACTORY);
		assertThat(kafkaListener.topics()).containsExactly(
			AccommodationSearchRefreshRequestedV1.TOPIC);
		assertThat(kafkaListener.autoStartup())
			.isEqualTo("#{@accommodationIndexAliasReadiness.shouldAutoStart()}");
	}

	private String validMessage() {
		return eventCodec.encode(EventEnvelope.of(
			UUID.fromString("a479d1bb-6cc6-4ad4-864d-01bd8b4dc63a"),
			Instant.parse("2026-08-17T08:00:00Z"),
			new AccommodationSearchRefreshRequestedV1(ACCOMMODATION_UID)
		));
	}

	private ConsumerRecord<String, String> dltRecord(String value, int partition, long offset) {
		ConsumerRecord<String, String> record = new ConsumerRecord<>(
			"ACCOMMODATION_INDEX.events.DLT", 0, 99L, null, value);
		record.headers().add(
			KafkaHeaders.ORIGINAL_TOPIC,
			"ACCOMMODATION_INDEX.events".getBytes(StandardCharsets.UTF_8));
		record.headers().add(
			KafkaHeaders.ORIGINAL_PARTITION,
			ByteBuffer.allocate(Integer.BYTES).putInt(partition).array());
		record.headers().add(
			KafkaHeaders.ORIGINAL_OFFSET,
			ByteBuffer.allocate(Long.BYTES).putLong(offset).array());
		return record;
	}
}
