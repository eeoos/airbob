package kr.kro.airbob.domain.accommodation.cache.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.domain.accommodation.cache.messaging.event.AccommodationDetailCacheInvalidationRequestedV1;
import kr.kro.airbob.messaging.alert.application.OperatorAlertEnqueueService;
import kr.kro.airbob.messaging.alert.application.OperatorAlertRequest;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.infrastructure.kafka.MessagingKafkaConfiguration;
import kr.kro.airbob.messaging.infrastructure.kafka.SanitizingRetryKafkaTemplateFactory;

@SpringJUnitConfig(AccommodationDetailCacheKafkaIntegrationTest.KafkaTestConfiguration.class)
@TestPropertySource(properties = {
	"spring.kafka.consumer.auto-offset-reset=earliest",
	"spring.kafka.consumer.enable-auto-commit=false",
	"spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
	"spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
	"spring.kafka.listener.ack-mode=manual",
	"spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
	"spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
	"spring.jackson.property-naming-strategy=SNAKE_CASE",
	"accommodation.detail-cache.invalidation.kafka.group="
		+ AccommodationDetailCacheKafkaIntegrationTest.CACHE_GROUP,
	"accommodation.detail-cache.invalidation.kafka.attempts=2",
	"accommodation.detail-cache.invalidation.kafka.backoff-ms=100",
	"accommodation.detail-cache.invalidation.kafka.auto-startup=true"
})
@EmbeddedKafka(
	partitions = 1,
	topics = {
		AccommodationDetailCacheKafkaIntegrationTest.CACHE_TOPIC,
		AccommodationDetailCacheKafkaIntegrationTest.CACHE_RETRY_TOPIC,
		AccommodationDetailCacheKafkaIntegrationTest.CACHE_DLT_TOPIC,
		"PAYMENT_OPERATION.events",
		"PAYMENT_OPERATION.events.RETRY",
		"PAYMENT_OPERATION.events.DLT",
		"ACCOMMODATION_INDEX.events",
		"ACCOMMODATION_INDEX.events.RETRY",
		"ACCOMMODATION_INDEX.events.DLT",
		"OPERATOR_ALERT.events",
		"OPERATOR_ALERT.events.RETRY",
		"OPERATOR_ALERT.events.DLT"
	},
	bootstrapServersProperty = "spring.kafka.bootstrap-servers",
	brokerProperties = "auto.create.topics.enable=false"
)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("숙소 상세 캐시 무효화 Kafka 격리 통합 테스트")
class AccommodationDetailCacheKafkaIntegrationTest {

	static final String CACHE_TOPIC = "ACCOMMODATION_CACHE.events";
	static final String CACHE_RETRY_TOPIC = "ACCOMMODATION_CACHE.events.RETRY";
	static final String CACHE_DLT_TOPIC = "ACCOMMODATION_CACHE.events.DLT";
	static final String CACHE_GROUP = "accommodation-cache-integration-group";
	private static final long ACCOMMODATION_ID = 41L;
	private static final UUID EVENT_ID =
		UUID.fromString("d23eff16-b83e-4fbf-acd6-34b71cf33cc7");
	private static final String RAW_SECRET = "cache-poison-secret-019ffe7e-d014";
	private static final String RESERVED_HEADER_SECRET =
		"cache-reserved-header-secret-019ffe7e-d014";
	private static final String CUSTOM_SECRET_HEADER = "x-cache-provider-secret";
	private static final long ATTACKER_BACKOFF_DELAY_MS = 30_000L;
	private static final String SANITIZED_POISON =
		SanitizingRetryKafkaTemplateFactory.SANITIZED_POISON;
	private static final Set<String> SAFE_RETRY_DLT_HEADERS = Set.of(
		RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS,
		RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP,
		RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP,
		KafkaHeaders.ORIGINAL_TOPIC,
		KafkaHeaders.ORIGINAL_PARTITION,
		KafkaHeaders.ORIGINAL_OFFSET
	);
	private static final String[] OTHER_STREAM_TOPICS = {
		"PAYMENT_OPERATION.events",
		"PAYMENT_OPERATION.events.RETRY",
		"PAYMENT_OPERATION.events.DLT",
		"ACCOMMODATION_INDEX.events",
		"ACCOMMODATION_INDEX.events.RETRY",
		"ACCOMMODATION_INDEX.events.DLT",
		"OPERATOR_ALERT.events",
		"OPERATOR_ALERT.events.RETRY",
		"OPERATOR_ALERT.events.DLT"
	};

	private final EmbeddedKafkaBroker broker;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final AccommodationDetailCache cache;
	private final OperatorAlertEnqueueService alertEnqueueService;
	private final IntegrationEventCodec codec;
	private Consumer<String, String> retryConsumer;
	private Consumer<String, String> dltConsumer;
	private Consumer<String, String> otherStreamsConsumer;

	@Autowired
	AccommodationDetailCacheKafkaIntegrationTest(
		EmbeddedKafkaBroker broker,
		@Qualifier(MessagingKafkaConfiguration.KAFKA_TEMPLATE)
		KafkaTemplate<String, String> kafkaTemplate,
		AccommodationDetailCache cache,
		OperatorAlertEnqueueService alertEnqueueService,
		IntegrationEventCodec codec
	) {
		this.broker = broker;
		this.kafkaTemplate = kafkaTemplate;
		this.cache = cache;
		this.alertEnqueueService = alertEnqueueService;
		this.codec = codec;
	}

	@BeforeEach
	void setUp() {
		retryConsumer = consumer("accommodation-cache-retry-assertion");
		dltConsumer = consumer("accommodation-cache-dlt-assertion");
		otherStreamsConsumer = consumer("accommodation-cache-cross-stream-assertion");
		broker.consumeFromAnEmbeddedTopic(retryConsumer, CACHE_RETRY_TOPIC);
		broker.consumeFromAnEmbeddedTopic(dltConsumer, CACHE_DLT_TOPIC);
		broker.consumeFromEmbeddedTopics(otherStreamsConsumer, OTHER_STREAM_TOPICS);
		reset(cache, alertEnqueueService);
	}

	@AfterEach
	void tearDown() {
		retryConsumer.close();
		dltConsumer.close();
		otherStreamsConsumer.close();
	}

	@Test
	@DisplayName("정상 이벤트는 커밋하고 failure와 poison은 캐시 전용 retry/DLT로 격리한다")
	void commitsValidEventAndIsolatesFailuresWithoutLeakingSecrets(
		CapturedOutput output
	) throws Exception {
		String validMessage = validMessage();
		long validOffset = kafkaTemplate.send(
			CACHE_TOPIC, "untrusted-key", validMessage)
			.get(10, TimeUnit.SECONDS)
			.getRecordMetadata().offset();

		verify(cache, timeout(15_000)).evictOrThrow(
			ACCOMMODATION_ID, AccommodationDetailCacheInvalidationReason.IMAGE);
		awaitCommittedOffset(validOffset + 1);
		assertThat(KafkaTestUtils.getRecords(retryConsumer, Duration.ofMillis(300)).isEmpty())
			.isTrue();
		assertThat(KafkaTestUtils.getRecords(dltConsumer, Duration.ofMillis(300)).isEmpty())
			.isTrue();
		verifyNoInteractions(alertEnqueueService);

		reset(cache, alertEnqueueService);
		doThrow(new IllegalStateException("redis unavailable"))
			.when(cache).evictOrThrow(
				ACCOMMODATION_ID, AccommodationDetailCacheInvalidationReason.IMAGE);
		long failureOffset = kafkaTemplate.send(
			CACHE_TOPIC, "untrusted-key", validMessage)
			.get(10, TimeUnit.SECONDS)
			.getRecordMetadata().offset();

		ConsumerRecord<String, String> failedRetry = KafkaTestUtils.getSingleRecord(
			retryConsumer, CACHE_RETRY_TOPIC, Duration.ofSeconds(15));
		ConsumerRecord<String, String> failedDlt = KafkaTestUtils.getSingleRecord(
			dltConsumer, CACHE_DLT_TOPIC, Duration.ofSeconds(15));
		assertThat(failedRetry.value()).isEqualTo(validMessage);
		assertThat(failedDlt.value()).isEqualTo(validMessage);
		assertThat(failedRetry.key()).isEqualTo(Long.toString(ACCOMMODATION_ID));
		assertThat(failedDlt.key()).isEqualTo(Long.toString(ACCOMMODATION_ID));
		verify(cache, timeout(15_000).times(2)).evictOrThrow(
			ACCOMMODATION_ID, AccommodationDetailCacheInvalidationReason.IMAGE);
		verify(alertEnqueueService, timeout(15_000)).enqueue(
			OperatorAlertRequest.accommodationCacheQuarantined(
				new OperatorAlertSourcePosition(CACHE_TOPIC, 0, failureOffset)));
		verifyNoMoreInteractions(alertEnqueueService);

		reset(cache, alertEnqueueService);
		String malformed = "not-json cacheToken=" + RAW_SECRET;
		ProducerRecord<String, String> poison = new ProducerRecord<>(
			CACHE_TOPIC, 0, "secret-key-" + RAW_SECRET, malformed);
		poison.headers().add(
			CUSTOM_SECRET_HEADER, RAW_SECRET.getBytes(StandardCharsets.UTF_8));
		poison.headers().add(
			KafkaHeaders.EXCEPTION_MESSAGE,
			RAW_SECRET.getBytes(StandardCharsets.UTF_8));
		long attackerBackoff = System.currentTimeMillis() + ATTACKER_BACKOFF_DELAY_MS;
		addSpoofedReservedHeaders(poison, attackerBackoff);
		long poisonOffset = kafkaTemplate.send(poison)
			.get(10, TimeUnit.SECONDS)
			.getRecordMetadata().offset();

		ConsumerRecord<String, String> poisonRetry = KafkaTestUtils.getSingleRecord(
			retryConsumer, CACHE_RETRY_TOPIC, Duration.ofSeconds(15));
		ConsumerRecord<String, String> poisonDlt = KafkaTestUtils.getSingleRecord(
			dltConsumer, CACHE_DLT_TOPIC, Duration.ofSeconds(15));
		assertSafePoison(poisonRetry);
		assertSafePoison(poisonDlt);
		assertThat(readStringHeader(poisonDlt, KafkaHeaders.ORIGINAL_TOPIC))
			.isEqualTo(CACHE_TOPIC);
		assertThat(readIntHeader(poisonDlt, KafkaHeaders.ORIGINAL_PARTITION)).isZero();
		assertThat(readLongHeader(poisonDlt, KafkaHeaders.ORIGINAL_OFFSET))
			.isEqualTo(poisonOffset);
		assertThat(readIntHeader(
			poisonRetry, RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS)).isEqualTo(2);
		assertThat(readTimestampHeader(
			poisonRetry, RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP))
			.isLessThan(attackerBackoff);
		verifyNoInteractions(cache);
		verify(alertEnqueueService, timeout(15_000)).enqueue(
			OperatorAlertRequest.accommodationCacheQuarantined(
				new OperatorAlertSourcePosition(CACHE_TOPIC, 0, poisonOffset)));
		verifyNoMoreInteractions(alertEnqueueService);
		assertThat(KafkaTestUtils.getRecords(
			otherStreamsConsumer, Duration.ofSeconds(1)).isEmpty()).isTrue();
		assertThat(output).doesNotContain(
			RAW_SECRET, RESERVED_HEADER_SECRET, malformed);
	}

	private String validMessage() {
		return codec.encode(EventEnvelope.of(
			EVENT_ID,
			Instant.parse("2026-08-18T04:30:00Z"),
			new AccommodationDetailCacheInvalidationRequestedV1(
				ACCOMMODATION_ID, AccommodationDetailCacheInvalidationReason.IMAGE)));
	}

	private void awaitCommittedOffset(long expectedOffset) throws Exception {
		TopicPartition partition = new TopicPartition(CACHE_TOPIC, 0);
		long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
		Exception lastFailure = null;
		try (AdminClient admin = AdminClient.create(Map.of(
			AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
			while (System.nanoTime() < deadline) {
				try {
					Map<TopicPartition, OffsetAndMetadata> offsets = admin
						.listConsumerGroupOffsets(CACHE_GROUP)
						.partitionsToOffsetAndMetadata()
						.get(1, TimeUnit.SECONDS);
					OffsetAndMetadata committed = offsets.get(partition);
					if (committed != null && committed.offset() >= expectedOffset) {
						return;
					}
				} catch (Exception exception) {
					lastFailure = exception;
				}
				Thread.sleep(50);
			}
		}
		throw new AssertionError(
			"valid cache invalidation was not acknowledged at offset " + expectedOffset,
			lastFailure);
	}

	private void addSpoofedReservedHeaders(
		ProducerRecord<String, String> record,
		long attackerBackoff
	) {
		for (String headerName : SAFE_RETRY_DLT_HEADERS) {
			record.headers().add(
				headerName, RESERVED_HEADER_SECRET.getBytes(StandardCharsets.UTF_8));
		}
		record.headers().add(
			RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS, intBytes(999));
		record.headers().add(
			RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP,
			BigInteger.valueOf(attackerBackoff).toByteArray());
		record.headers().add(
			RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP,
			BigInteger.ONE.toByteArray());
		record.headers().add(
			KafkaHeaders.ORIGINAL_TOPIC,
			"ACCOMMODATION_INDEX.events".getBytes(StandardCharsets.UTF_8));
		record.headers().add(KafkaHeaders.ORIGINAL_PARTITION, intBytes(77));
		record.headers().add(KafkaHeaders.ORIGINAL_OFFSET, longBytes(999L));
	}

	private void assertSafePoison(ConsumerRecord<String, String> record) {
		assertThat(record.value()).isEqualTo(SANITIZED_POISON);
		assertThat(record.key()).isNull();
		assertThat(headerNames(record)).isSubsetOf(SAFE_RETRY_DLT_HEADERS)
			.doesNotHaveDuplicates();
		assertThat(record.headers().lastHeader(CUSTOM_SECRET_HEADER)).isNull();
		assertThat(record.headers().lastHeader(KafkaHeaders.EXCEPTION_MESSAGE)).isNull();
		assertThat(String.valueOf(record.key())).doesNotContain(RAW_SECRET);
		assertThat(record.value()).doesNotContain(RAW_SECRET, RESERVED_HEADER_SECRET);
		assertThat(headerNames(record)).noneMatch(name ->
			name.contains(RAW_SECRET) || name.contains(RESERVED_HEADER_SECRET));
		assertThat(headerValues(record)).noneMatch(value ->
			value.contains(RAW_SECRET) || value.contains(RESERVED_HEADER_SECRET));
	}

	private List<String> headerNames(ConsumerRecord<String, String> record) {
		List<String> names = new ArrayList<>();
		record.headers().forEach(header -> names.add(header.key()));
		return names;
	}

	private List<String> headerValues(ConsumerRecord<String, String> record) {
		List<String> values = new ArrayList<>();
		record.headers().forEach(header -> values.add(
			new String(header.value(), StandardCharsets.UTF_8)));
		return values;
	}

	private String readStringHeader(ConsumerRecord<String, String> record, String name) {
		return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
	}

	private int readIntHeader(ConsumerRecord<String, String> record, String name) {
		return ByteBuffer.wrap(record.headers().lastHeader(name).value()).getInt();
	}

	private long readLongHeader(ConsumerRecord<String, String> record, String name) {
		return ByteBuffer.wrap(record.headers().lastHeader(name).value()).getLong();
	}

	private long readTimestampHeader(ConsumerRecord<String, String> record, String name) {
		return new BigInteger(record.headers().lastHeader(name).value()).longValueExact();
	}

	private byte[] intBytes(int value) {
		return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
	}

	private byte[] longBytes(long value) {
		return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
	}

	private Consumer<String, String> consumer(String groupId) {
		Map<String, Object> properties = KafkaTestUtils.consumerProps(groupId, "false", broker);
		properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		return new DefaultKafkaConsumerFactory<>(
			properties, new StringDeserializer(), new StringDeserializer()).createConsumer();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableKafka
	@Import({
		JacksonAutoConfiguration.class,
		KafkaAutoConfiguration.class,
		MessagingKafkaConfiguration.class,
		IntegrationEventCodec.class,
		AccommodationDetailCacheKafkaConsumerConfiguration.class,
		AccommodationDetailCacheKafkaRetryPublisherConfiguration.class,
		AccommodationDetailCacheInvalidationKafkaListener.class
	})
	static class KafkaTestConfiguration {

		@Bean
		AccommodationDetailCache accommodationDetailCache() {
			return org.mockito.Mockito.mock(AccommodationDetailCache.class);
		}

		@Bean
		OperatorAlertEnqueueService operatorAlertEnqueueService() {
			return org.mockito.Mockito.mock(OperatorAlertEnqueueService.class);
		}
	}
}
