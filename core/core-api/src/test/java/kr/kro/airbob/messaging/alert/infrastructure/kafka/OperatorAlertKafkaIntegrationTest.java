package kr.kro.airbob.messaging.alert.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import kr.kro.airbob.messaging.alert.application.OperatorAlertGateway;
import kr.kro.airbob.messaging.alert.event.OperatorAlertKind;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSummaryCode;
import kr.kro.airbob.messaging.alert.monitoring.OperatorAlertMetrics;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.infrastructure.kafka.MessagingKafkaConfiguration;
import kr.kro.airbob.messaging.infrastructure.kafka.SanitizingRetryKafkaTemplateFactory;

@SpringJUnitConfig(OperatorAlertKafkaIntegrationTest.TestConfiguration.class)
@TestPropertySource(properties = {
	"spring.kafka.consumer.auto-offset-reset=earliest",
	"spring.kafka.consumer.enable-auto-commit=false",
	"spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
	"spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
	"spring.kafka.listener.ack-mode=manual",
	"spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
	"spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
	"spring.jackson.property-naming-strategy=SNAKE_CASE",
	"operator-alert.kafka.group=operator-alert-delivery-group",
	"operator-alert.kafka.auto-startup=true",
	"operator-alert.kafka.attempts=2",
	"operator-alert.kafka.backoff-ms=200"
})
@EmbeddedKafka(
	partitions = 1,
	topics = {
		OperatorAlertKafkaIntegrationTest.ALERT_TOPIC,
		OperatorAlertKafkaIntegrationTest.ALERT_RETRY_TOPIC,
		OperatorAlertKafkaIntegrationTest.ALERT_DLT_TOPIC
	},
	bootstrapServersProperty = "spring.kafka.bootstrap-servers",
	brokerProperties = "auto.create.topics.enable=false"
)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("operator alert Kafka isolation")
class OperatorAlertKafkaIntegrationTest {

	static final String ALERT_TOPIC = "OPERATOR_ALERT.events";
	static final String ALERT_RETRY_TOPIC = "OPERATOR_ALERT.events.RETRY";
	static final String ALERT_DLT_TOPIC = "OPERATOR_ALERT.events.DLT";
	private static final UUID SUBJECT_UID =
		UUID.fromString("18dc773f-055d-4416-a7f0-995772f0fe91");
	private static final UUID ALERT_UID =
		UUID.fromString("37938505-8b78-316f-9162-1a5790b0cf88");
	private static final String RAW_SECRET = "raw-provider-key-operator-alert-secret";
	private static final String CUSTOM_SECRET_HEADER = "x-provider-authorization";
	private static final long FUTURE_ATTACKER_BACKOFF_MILLIS = 30_000L;
	private static final Set<String> SAFE_HEADERS = Set.of(
		RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS,
		RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP,
		RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP,
		KafkaHeaders.ORIGINAL_TOPIC,
		KafkaHeaders.ORIGINAL_PARTITION,
		KafkaHeaders.ORIGINAL_OFFSET
	);

	private final EmbeddedKafkaBroker broker;
	private final KafkaTemplate<String, String> template;
	private final OperatorAlertGateway gateway;
	private final OperatorAlertMetrics metrics;
	private final IntegrationEventCodec codec;
	private Consumer<String, String> retryConsumer;
	private Consumer<String, String> dltConsumer;

	@Autowired
	OperatorAlertKafkaIntegrationTest(
		EmbeddedKafkaBroker broker,
		@Qualifier(MessagingKafkaConfiguration.KAFKA_TEMPLATE)
		KafkaTemplate<String, String> template,
		OperatorAlertGateway gateway,
		OperatorAlertMetrics metrics,
		IntegrationEventCodec codec
	) {
		this.broker = broker;
		this.template = template;
		this.gateway = gateway;
		this.metrics = metrics;
		this.codec = codec;
	}

	@BeforeEach
	void setUp() {
		retryConsumer = consumer("operator-alert-retry-assertion");
		dltConsumer = consumer("operator-alert-dlt-assertion");
		broker.consumeFromAnEmbeddedTopic(retryConsumer, ALERT_RETRY_TOPIC);
		broker.consumeFromAnEmbeddedTopic(dltConsumer, ALERT_DLT_TOPIC);
		reset(gateway, metrics);
	}

	@AfterEach
	void tearDown() {
		retryConsumer.close();
		dltConsumer.close();
	}

	@Test
	void poisonTravelsOnlyMainToDedicatedRetryAndDltWithoutLeakingSecrets(
		CapturedOutput output
	) throws Exception {
		String poisonValue = "not-json paymentKey=" + RAW_SECRET;
		ProducerRecord<String, String> poison = new ProducerRecord<>(
			ALERT_TOPIC, 0, "secret-key-" + RAW_SECRET, poisonValue);
		poison.headers().add(
			CUSTOM_SECRET_HEADER, RAW_SECRET.getBytes(StandardCharsets.UTF_8));
		poison.headers().add(
			KafkaHeaders.EXCEPTION_MESSAGE, RAW_SECRET.getBytes(StandardCharsets.UTF_8));
		long attackerBackoff = System.currentTimeMillis() + FUTURE_ATTACKER_BACKOFF_MILLIS;
		addSpoofedRetryHeaders(poison, attackerBackoff);

		long originalOffset = template.send(poison).get(10, TimeUnit.SECONDS)
			.getRecordMetadata().offset();

		ConsumerRecord<String, String> retry = KafkaTestUtils.getSingleRecord(
			retryConsumer, ALERT_RETRY_TOPIC, Duration.ofSeconds(15));
		ConsumerRecord<String, String> dlt = KafkaTestUtils.getSingleRecord(
			dltConsumer, ALERT_DLT_TOPIC, Duration.ofSeconds(15));

		assertSafePoison(retry);
		assertSafePoison(dlt);
		assertThat(readStringHeader(dlt, KafkaHeaders.ORIGINAL_TOPIC)).isEqualTo(ALERT_TOPIC);
		assertThat(readIntHeader(dlt, KafkaHeaders.ORIGINAL_PARTITION)).isZero();
		assertThat(readLongHeader(dlt, KafkaHeaders.ORIGINAL_OFFSET)).isEqualTo(originalOffset);
		assertThat(readIntHeader(retry, RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS))
			.isEqualTo(2);
		long originalTimestamp = readTimestampHeader(
			retry, RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP);
		long backoffTimestamp = readTimestampHeader(
			retry, RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP);
		assertThat(backoffTimestamp).isGreaterThanOrEqualTo(originalTimestamp + 200L);
		assertThat(backoffTimestamp).isLessThan(attackerBackoff);
		assertThat(output).doesNotContain(RAW_SECRET, poisonValue);
		verifyNoInteractions(gateway);
		verify(metrics, timeout(15_000).times(2)).failed();
		verify(metrics, timeout(15_000)).dlt();
	}

	@Test
	void validFailureIsCanonicalizedAndRetriedBeforeAcknowledgment() throws Exception {
		OperatorAlertRequestedV1 event = event();
		String message = codec.encode(EventEnvelope.of(
			UUID.fromString("343f7f20-3f4b-43df-b896-e95939e40d06"),
			Instant.parse("2026-08-17T00:00:00Z"),
			event));
		doThrow(new IllegalStateException("transport unavailable"))
			.doNothing()
			.when(gateway).deliver(event);

		template.send(ALERT_TOPIC, "untrusted-key", message).get(10, TimeUnit.SECONDS);

		ConsumerRecord<String, String> retry = KafkaTestUtils.getSingleRecord(
			retryConsumer, ALERT_RETRY_TOPIC, Duration.ofSeconds(15));
		assertThat(retry.key()).isEqualTo(SUBJECT_UID.toString());
		assertThat(retry.value()).isEqualTo(message);
		verify(gateway, timeout(15_000).times(2)).deliver(event);
		verify(metrics, timeout(15_000)).failed();
		verify(metrics, timeout(15_000)).delivered();
	}

	private OperatorAlertRequestedV1 event() {
		return new OperatorAlertRequestedV1(
			ALERT_UID,
			OperatorAlertKind.PAYMENT_OPERATION_QUARANTINED,
			SUBJECT_UID,
			"PAYMENT_OPERATION.events",
			0,
			12L,
			OperatorAlertSummaryCode.MESSAGE_PROCESSING_FAILED
		);
	}

	private void assertSafePoison(ConsumerRecord<String, String> record) {
		assertThat(record.value())
			.isEqualTo(SanitizingRetryKafkaTemplateFactory.SANITIZED_POISON)
			.doesNotContain(RAW_SECRET);
		assertThat(record.key()).isNull();
		assertThat(headerNames(record)).isSubsetOf(SAFE_HEADERS).doesNotHaveDuplicates();
		assertThat(headerValues(record)).noneMatch(value -> value.contains(RAW_SECRET));
		assertThat(record.headers().lastHeader(CUSTOM_SECRET_HEADER)).isNull();
		assertThat(record.headers().lastHeader(KafkaHeaders.EXCEPTION_MESSAGE)).isNull();
	}

	private void addSpoofedRetryHeaders(
		ProducerRecord<String, String> record,
		long attackerBackoff
	) {
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
			"SPOOFED.events".getBytes(StandardCharsets.UTF_8));
		record.headers().add(KafkaHeaders.ORIGINAL_PARTITION, intBytes(77));
		record.headers().add(KafkaHeaders.ORIGINAL_OFFSET, longBytes(999L));
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
		OperatorAlertKafkaConsumerConfiguration.class,
		OperatorAlertKafkaPublisherConfiguration.class,
		OperatorAlertKafkaListener.class
	})
	static class TestConfiguration {
		@Bean
		OperatorAlertGateway gateway() {
			return org.mockito.Mockito.mock(OperatorAlertGateway.class);
		}

		@Bean
		OperatorAlertMetrics metrics() {
			return org.mockito.Mockito.mock(OperatorAlertMetrics.class);
		}
	}
}
