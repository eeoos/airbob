package kr.kro.airbob.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.config.KafkaConfig;
import kr.kro.airbob.domain.payment.service.PaymentOperationAlertService;
import kr.kro.airbob.domain.payment.service.PaymentOperationExecutor;
import kr.kro.airbob.outbox.DebeziumEventParser;

@SpringJUnitConfig(PaymentOperationKafkaIntegrationTest.KafkaTestConfiguration.class)
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
	"payment.operation.kafka.topic=PAYMENT_OPERATION.events",
	"payment.operation.kafka.group=payment-operation-execution-group",
	"payment.operation.kafka.attempts=2",
	"payment.operation.kafka.backoff-ms=0"
})
@EmbeddedKafka(
	partitions = 1,
	topics = {
		PaymentOperationKafkaIntegrationTest.OPERATION_TOPIC,
		PaymentOperationKafkaIntegrationTest.OPERATION_RETRY_TOPIC,
		PaymentOperationKafkaIntegrationTest.OPERATION_DLT_TOPIC,
		PaymentOperationKafkaIntegrationTest.GLOBAL_PAYMENT_DLT_TOPIC
	},
	bootstrapServersProperty = "spring.kafka.bootstrap-servers",
	brokerProperties = "auto.create.topics.enable=false"
)
@DisplayName("payment-operation Kafka 격리 통합 테스트")
class PaymentOperationKafkaIntegrationTest {

	static final String OPERATION_TOPIC = "PAYMENT_OPERATION.events";
	static final String OPERATION_RETRY_TOPIC = "PAYMENT_OPERATION.events.RETRY";
	static final String OPERATION_DLT_TOPIC = "PAYMENT_OPERATION.events.DLT";
	static final String GLOBAL_PAYMENT_DLT_TOPIC = "PAYMENT.events.DLT";
	private static final UUID OPERATION_UID = UUID.fromString("7e19fa7d-a8dc-4096-8c75-e84f43e5b639");
	private static final String VALID_MESSAGE = """
		{
		  "event_id": "710470d6-8bb8-4fd4-8249-5f2f52a1afcc",
		  "trace_id": "ac3921de-5f64-4d73-829d-a49c32321950",
		  "event_type": "PAYMENT_EXECUTION_REQUESTED_V1",
		  "event_version": "1.0",
		  "timestamp": "2026-08-14T00:00:00Z",
		  "payload": {
		    "operation_uid": "7e19fa7d-a8dc-4096-8c75-e84f43e5b639",
		    "reservation_uid": "ac3921de-5f64-4d73-829d-a49c32321950"
		  }
		}
		""";

	private final EmbeddedKafkaBroker broker;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final PaymentOperationExecutor executor;
	private final DebeziumEventParser parser;
	private Consumer<String, String> operationDltConsumer;
	private Consumer<String, String> globalPaymentDltConsumer;

	@Autowired
	PaymentOperationKafkaIntegrationTest(
		EmbeddedKafkaBroker broker,
		KafkaTemplate<String, String> kafkaTemplate,
		PaymentOperationExecutor executor,
		DebeziumEventParser parser
	) {
		this.broker = broker;
		this.kafkaTemplate = kafkaTemplate;
		this.executor = executor;
		this.parser = parser;
	}

	@BeforeEach
	void setUp() {
		operationDltConsumer = consumer("payment-operation-dlt-assertion");
		globalPaymentDltConsumer = consumer("global-payment-dlt-assertion");
		broker.consumeFromAnEmbeddedTopic(operationDltConsumer, OPERATION_DLT_TOPIC);
		broker.consumeFromAnEmbeddedTopic(globalPaymentDltConsumer, GLOBAL_PAYMENT_DLT_TOPIC);
		reset(executor);
		clearInvocations(parser);
	}

	@AfterEach
	void tearDown() {
		operationDltConsumer.close();
		globalPaymentDltConsumer.close();
	}

	@Test
	@DisplayName("poison message는 전용 DLT로만 가고 정상 중복 메시지는 경계에서 두 번 실행된다")
	void isolatesPoisonMessageAndDeliversValidDuplicatesAtBoundary() throws Exception {
		String malformed = "paymentKey=must-not-enter-global-dlt";

		kafkaTemplate.send(OPERATION_TOPIC, malformed).get(10, TimeUnit.SECONDS);

		ConsumerRecord<String, String> quarantined = KafkaTestUtils.getSingleRecord(
			operationDltConsumer, OPERATION_DLT_TOPIC, Duration.ofSeconds(15));
		assertThat(quarantined.topic()).isEqualTo(OPERATION_DLT_TOPIC);
		assertThat(quarantined.value()).isEqualTo(malformed);
		assertThat(KafkaTestUtils.getRecords(globalPaymentDltConsumer, Duration.ofSeconds(2)))
			.isEmpty();
		verify(parser, timeout(15_000).times(3)).getEventType(malformed);
		verifyNoInteractions(executor);

		kafkaTemplate.send(OPERATION_TOPIC, VALID_MESSAGE).get(10, TimeUnit.SECONDS);
		kafkaTemplate.send(OPERATION_TOPIC, VALID_MESSAGE).get(10, TimeUnit.SECONDS);

		verify(executor, timeout(15_000).times(2)).execute(OPERATION_UID);
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
		PaymentOperationEventsConsumer.class
	})
	static class KafkaTestConfiguration {

		@Bean
		DebeziumEventParser debeziumEventParser(ObjectMapper objectMapper) {
			return spy(new DebeziumEventParser(objectMapper));
		}

		@Bean
		PaymentOperationExecutor paymentOperationExecutor() {
			return org.mockito.Mockito.mock(PaymentOperationExecutor.class);
		}

		@Bean
		PaymentOperationAlertService paymentOperationAlertService() {
			return org.mockito.Mockito.mock(PaymentOperationAlertService.class);
		}
	}
}
