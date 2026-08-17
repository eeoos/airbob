package kr.kro.airbob.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import kr.kro.airbob.config.KafkaConfig;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.search.service.AccommodationIndexingAlertService;
import kr.kro.airbob.search.service.AccommodationIndexingService;

@SpringJUnitConfig(AccommodationIndexingKafkaIntegrationTest.KafkaTestConfiguration.class)
@TestPropertySource(properties = {
	"spring.kafka.consumer.auto-offset-reset=earliest",
	"spring.kafka.consumer.enable-auto-commit=false",
	"spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
	"spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
	"spring.kafka.consumer.properties.spring.kafka.dead-letter-publishing.topic-name=PAYMENT.events.DLT",
	"spring.kafka.listener.ack-mode=manual",
	"spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
	"spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
	"spring.jackson.property-naming-strategy=SNAKE_CASE",
	"accommodation.indexing.kafka.topic=ACCOMMODATION.events",
	"accommodation.indexing.kafka.group=accommodation-indexing-group",
	"accommodation.indexing.kafka.attempts=2",
	"accommodation.indexing.kafka.backoff-ms=100"
})
@EmbeddedKafka(
	partitions = 1,
	topics = {
		AccommodationIndexingKafkaIntegrationTest.INDEXING_TOPIC,
		AccommodationIndexingKafkaIntegrationTest.INDEXING_RETRY_TOPIC,
		AccommodationIndexingKafkaIntegrationTest.INDEXING_DLT_TOPIC,
		AccommodationIndexingKafkaIntegrationTest.GLOBAL_PAYMENT_DLT_TOPIC
	},
	bootstrapServersProperty = "spring.kafka.bootstrap-servers",
	brokerProperties = "auto.create.topics.enable=false"
)
@DisplayName("숙소 색인 Kafka 격리 통합 테스트")
class AccommodationIndexingKafkaIntegrationTest {

	static final String INDEXING_TOPIC = "ACCOMMODATION.events";
	static final String INDEXING_RETRY_TOPIC = "ACCOMMODATION.events.RETRY";
	static final String INDEXING_DLT_TOPIC = "ACCOMMODATION.events.DLT";
	static final String GLOBAL_PAYMENT_DLT_TOPIC = "PAYMENT.events.DLT";
	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("109cc081-b87d-4502-9a5e-7d7b65993056");
	private static final String MESSAGE = """
		{
		  "event_type": "ACCOMMODATION_UPDATED",
		  "payload": {"accommodation_uid": "109cc081-b87d-4502-9a5e-7d7b65993056"}
		}
		""";

	private final EmbeddedKafkaBroker broker;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final AccommodationIndexingService indexingService;
	private final AccommodationIndexingAlertService alertService;
	private Consumer<String, String> retryConsumer;
	private Consumer<String, String> dltConsumer;
	private Consumer<String, String> globalPaymentDltConsumer;

	@Autowired
	AccommodationIndexingKafkaIntegrationTest(
		EmbeddedKafkaBroker broker,
		@Qualifier("deadLetterKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
		AccommodationIndexingService indexingService,
		AccommodationIndexingAlertService alertService
	) {
		this.broker = broker;
		this.kafkaTemplate = kafkaTemplate;
		this.indexingService = indexingService;
		this.alertService = alertService;
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
	@DisplayName("ES 장애는 숙소 전용 retry와 DLT로 격리하고 결제 DLT에는 보내지 않는다")
	void routesIndexingFailureOnlyToDedicatedRetryAndDlt() throws Exception {
		doThrow(new IllegalStateException("elasticsearch unavailable"))
			.when(indexingService).refreshAccommodationIndex(ACCOMMODATION_UID);

		kafkaTemplate.send(INDEXING_TOPIC, ACCOMMODATION_UID.toString(), MESSAGE)
			.get(10, TimeUnit.SECONDS);

		ConsumerRecord<String, String> retried = KafkaTestUtils.getSingleRecord(
			retryConsumer, INDEXING_RETRY_TOPIC, Duration.ofSeconds(15));
		ConsumerRecord<String, String> quarantined = KafkaTestUtils.getSingleRecord(
			dltConsumer, INDEXING_DLT_TOPIC, Duration.ofSeconds(15));
		assertThat(retried.value()).isEqualTo(MESSAGE);
		assertThat(quarantined.value()).isEqualTo(MESSAGE);
		assertThat(KafkaTestUtils.getRecords(
			globalPaymentDltConsumer, Duration.ofSeconds(1))).isEmpty();
		verify(indexingService, timeout(15_000).times(2))
			.refreshAccommodationIndex(ACCOMMODATION_UID);
		verify(alertService, timeout(15_000)).alertQuarantined(
			INDEXING_TOPIC,
			0,
			0L,
			EventType.ACCOMMODATION_UPDATED,
			ACCOMMODATION_UID
		);
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
		AccommodationIndexingEventParser.class,
		AccommodationIndexingConsumer.class
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
	}
}
