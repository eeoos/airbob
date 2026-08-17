package kr.kro.airbob.search.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.junit.jupiter.api.extension.ExtendWith;

import kr.kro.airbob.config.KafkaConfig;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.search.messaging.event.AccommodationSearchRefreshRequestedV1;
import kr.kro.airbob.search.service.AccommodationIndexingAlertService;
import kr.kro.airbob.search.service.AccommodationIndexingService;

@SpringJUnitConfig(AccommodationSearchRefreshKafkaIntegrationTest.KafkaTestConfiguration.class)
@TestPropertySource(properties = {
	"spring.kafka.consumer.auto-offset-reset=earliest",
	"spring.kafka.consumer.enable-auto-commit=false",
	"spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
	"spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
	"spring.kafka.listener.ack-mode=manual",
	"spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
	"spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
	"spring.jackson.property-naming-strategy=SNAKE_CASE",
	"accommodation.indexing.kafka.topic=ACCOMMODATION_INDEX.events",
	"accommodation.indexing.kafka.group=accommodation-indexing-group",
	"accommodation.indexing.kafka.attempts=2",
	"accommodation.indexing.kafka.backoff-ms=100",
	"accommodation.indexing.kafka.auto-startup=true"
})
@EmbeddedKafka(
	partitions = 1,
	topics = {
		AccommodationSearchRefreshKafkaIntegrationTest.INDEXING_TOPIC,
		AccommodationSearchRefreshKafkaIntegrationTest.INDEXING_RETRY_TOPIC,
		AccommodationSearchRefreshKafkaIntegrationTest.INDEXING_DLT_TOPIC,
		AccommodationSearchRefreshKafkaIntegrationTest.GLOBAL_PAYMENT_DLT_TOPIC
	},
	bootstrapServersProperty = "spring.kafka.bootstrap-servers",
	brokerProperties = "auto.create.topics.enable=false"
)
@DisplayName("숙소 검색 refresh Kafka 격리 통합 테스트")
@ExtendWith(OutputCaptureExtension.class)
class AccommodationSearchRefreshKafkaIntegrationTest {

	static final String INDEXING_TOPIC = "ACCOMMODATION_INDEX.events";
	static final String INDEXING_RETRY_TOPIC = "ACCOMMODATION_INDEX.events.RETRY";
	static final String INDEXING_DLT_TOPIC = "ACCOMMODATION_INDEX.events.DLT";
	static final String GLOBAL_PAYMENT_DLT_TOPIC = "PAYMENT.events.DLT";
	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("109cc081-b87d-4502-9a5e-7d7b65993056");
	private static final String RAW_SECRET = "search-poison-secret-019ffe7e";
	private static final String CUSTOM_SECRET_HEADER = "x-search-provider-secret";
	private static final String SANITIZED_POISON =
		AccommodationSearchKafkaPublisherConfig.SANITIZED_POISON;
	private static final Set<String> SAFE_RETRY_DLT_HEADERS = Set.of(
		RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS,
		RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP,
		RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP,
		KafkaHeaders.ORIGINAL_TOPIC,
		KafkaHeaders.ORIGINAL_PARTITION,
		KafkaHeaders.ORIGINAL_OFFSET
	);
	private static final String MESSAGE = """
		{
		  "event_id": "a479d1bb-6cc6-4ad4-864d-01bd8b4dc63a",
		  "event_type": "ACCOMMODATION_SEARCH_REFRESH_REQUESTED",
		  "event_version": "1",
		  "occurred_at": "2026-08-17T08:00:00Z",
		  "payload": {"accommodation_uid": "109cc081-b87d-4502-9a5e-7d7b65993056"}
		}
		""";

	private final EmbeddedKafkaBroker broker;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final AccommodationIndexingService indexingService;
	private final AccommodationIndexingAlertService alertService;
	private final IntegrationEventCodec eventCodec;
	private Consumer<String, String> retryConsumer;
	private Consumer<String, String> dltConsumer;
	private Consumer<String, String> globalPaymentDltConsumer;

	@Autowired
	AccommodationSearchRefreshKafkaIntegrationTest(
		EmbeddedKafkaBroker broker,
		@Qualifier("deadLetterKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
		AccommodationIndexingService indexingService,
		AccommodationIndexingAlertService alertService,
		IntegrationEventCodec eventCodec
	) {
		this.broker = broker;
		this.kafkaTemplate = kafkaTemplate;
		this.indexingService = indexingService;
		this.alertService = alertService;
		this.eventCodec = eventCodec;
	}

	@BeforeEach
	void setUp() {
		retryConsumer = consumer("accommodation-indexing-retry-assertion");
		dltConsumer = consumer("accommodation-indexing-dlt-assertion");
		globalPaymentDltConsumer = consumer("global-payment-dlt-assertion-for-indexing");
		broker.consumeFromAnEmbeddedTopic(retryConsumer, INDEXING_RETRY_TOPIC);
		broker.consumeFromAnEmbeddedTopic(dltConsumer, INDEXING_DLT_TOPIC);
		broker.consumeFromAnEmbeddedTopic(globalPaymentDltConsumer, GLOBAL_PAYMENT_DLT_TOPIC);
		reset(indexingService, alertService);
	}

	@AfterEach
	void tearDown() {
		retryConsumer.close();
		dltConsumer.close();
		globalPaymentDltConsumer.close();
	}

	@Test
	@DisplayName("ES 장애는 색인 전용 retry/DLT로만 격리한다")
	void routesIndexingFailureOnlyToDedicatedRetryAndDlt(CapturedOutput output) throws Exception {
		doThrow(new IllegalStateException("elasticsearch unavailable"))
			.when(indexingService).refreshAccommodationIndex(ACCOMMODATION_UID);

		kafkaTemplate.send(INDEXING_TOPIC, ACCOMMODATION_UID.toString(), MESSAGE)
			.get(10, TimeUnit.SECONDS);

		ConsumerRecord<String, String> retried = KafkaTestUtils.getSingleRecord(
			retryConsumer, INDEXING_RETRY_TOPIC, Duration.ofSeconds(15));
		ConsumerRecord<String, String> quarantined = KafkaTestUtils.getSingleRecord(
			dltConsumer, INDEXING_DLT_TOPIC, Duration.ofSeconds(15));
		String canonicalMessage = eventCodec.encode(eventCodec.decode(
			MESSAGE,
			AccommodationSearchRefreshRequestedV1.DESCRIPTOR,
			AccommodationSearchRefreshRequestedV1.class));
		assertThat(retried.value()).isEqualTo(canonicalMessage);
		assertThat(quarantined.value()).isEqualTo(canonicalMessage);
		assertThat(retried.key()).isEqualTo(ACCOMMODATION_UID.toString());
		assertThat(quarantined.key()).isEqualTo(ACCOMMODATION_UID.toString());
		assertThat(KafkaTestUtils.getRecords(
			globalPaymentDltConsumer, Duration.ofSeconds(1))).isEmpty();
		verify(indexingService, timeout(15_000).times(2))
			.refreshAccommodationIndex(ACCOMMODATION_UID);
		verify(alertService, timeout(15_000)).alertQuarantined(
			INDEXING_TOPIC, 0, 0L, ACCOMMODATION_UID);

		String malicious = MESSAGE.replace(
			"\"accommodation_uid\": \"" + ACCOMMODATION_UID + "\"",
			"\"accommodation_uid\": \"" + ACCOMMODATION_UID + "\","
				+ "\"provider_secret\":\"" + RAW_SECRET + "\"");
		ProducerRecord<String, String> poisoned = new ProducerRecord<>(
			INDEXING_TOPIC, 0, RAW_SECRET, malicious);
		poisoned.headers().add(
			CUSTOM_SECRET_HEADER, RAW_SECRET.getBytes(StandardCharsets.UTF_8));
		poisoned.headers().add(
			RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS, intBytes(999));
		poisoned.headers().add(
			KafkaHeaders.ORIGINAL_TOPIC, RAW_SECRET.getBytes(StandardCharsets.UTF_8));
		poisoned.headers().add(
			KafkaHeaders.ORIGINAL_PARTITION, intBytes(999));
		poisoned.headers().add(
			KafkaHeaders.ORIGINAL_OFFSET, longBytes(999L));
		kafkaTemplate.send(poisoned).get(10, TimeUnit.SECONDS);

		ConsumerRecord<String, String> sanitizedRetry = KafkaTestUtils.getSingleRecord(
			retryConsumer, INDEXING_RETRY_TOPIC, Duration.ofSeconds(15));
		ConsumerRecord<String, String> sanitizedDlt = KafkaTestUtils.getSingleRecord(
			dltConsumer, INDEXING_DLT_TOPIC, Duration.ofSeconds(15));
		assertSafePoisonRecord(sanitizedRetry);
		assertSafePoisonRecord(sanitizedDlt);
		assertThat(readStringHeader(sanitizedDlt, KafkaHeaders.ORIGINAL_TOPIC))
			.isEqualTo(INDEXING_TOPIC);
		assertThat(readIntHeader(sanitizedDlt, KafkaHeaders.ORIGINAL_PARTITION)).isZero();
		long originalPoisonOffset = readLongHeader(
			sanitizedDlt, KafkaHeaders.ORIGINAL_OFFSET);
		assertThat(originalPoisonOffset).isNotNegative();
		assertThat(readIntHeader(sanitizedRetry, RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS))
			.isEqualTo(2);
		verify(alertService, timeout(15_000)).alertQuarantined(
			INDEXING_TOPIC, 0, originalPoisonOffset, null);
		assertThat(output).doesNotContain(RAW_SECRET, malicious);
	}

	private void assertSafePoisonRecord(ConsumerRecord<String, String> record) {
		assertThat(record.value()).isEqualTo(SANITIZED_POISON);
		assertThat(record.key()).isNull();
		assertThat(headerNames(record)).isSubsetOf(SAFE_RETRY_DLT_HEADERS);
		assertThat(headerNames(record)).doesNotHaveDuplicates();
		assertThat(record.headers().lastHeader(CUSTOM_SECRET_HEADER)).isNull();
		assertThat(record.value()).doesNotContain(RAW_SECRET);
		assertThat(headerValues(record)).noneMatch(value -> value.contains(RAW_SECRET));
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
		KafkaConfig.class,
		IntegrationEventCodec.class,
		AccommodationSearchKafkaConsumerConfig.class,
		AccommodationSearchKafkaPublisherConfig.class,
		AccommodationSearchRefreshListener.class
	})
	static class KafkaTestConfiguration {

		@Bean
		AccommodationIndexingService accommodationIndexingService() {
			return org.mockito.Mockito.mock(AccommodationIndexingService.class);
		}

		@Bean
		AccommodationIndexingAlertService accommodationIndexingAlertService() {
			return org.mockito.Mockito.mock(AccommodationIndexingAlertService.class);
		}

		@Bean("accommodationIndexAliasReadiness")
		TestAliasReadiness accommodationIndexAliasReadiness(
			@Value("${accommodation.indexing.kafka.auto-startup:true}") boolean autoStartup
		) {
			return new TestAliasReadiness(autoStartup);
		}
	}

	public record TestAliasReadiness(boolean autoStartup) {
		public boolean shouldAutoStart() {
			return autoStartup;
		}
	}
}
