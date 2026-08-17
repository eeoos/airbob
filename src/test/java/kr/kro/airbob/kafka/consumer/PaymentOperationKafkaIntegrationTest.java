package kr.kro.airbob.kafka.consumer;

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
import org.slf4j.LoggerFactory;
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
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;

import kr.kro.airbob.config.KafkaConfig;
import kr.kro.airbob.config.PaymentOperationKafkaConsumerConfig;
import kr.kro.airbob.config.PaymentOperationKafkaPublisherConfig;
import kr.kro.airbob.domain.payment.event.PaymentOperationEvent.PaymentExecutionRequestedV1;
import kr.kro.airbob.domain.payment.service.PaymentOperationAlertService;
import kr.kro.airbob.domain.payment.service.PaymentOperationExecutor;
import kr.kro.airbob.outbox.EventEnvelope;
import kr.kro.airbob.outbox.EventType;

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
	"payment.operation.kafka.backoff-ms=500"
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
	private static final UUID RESERVATION_UID = UUID.fromString("ac3921de-5f64-4d73-829d-a49c32321950");
	private static final String RAW_SECRET = "raw-provider-secret-019ffe7e-d014";
	private static final String RESERVED_HEADER_SECRET = "reserved-header-secret-019ffe7e-d014";
	private static final String CUSTOM_SENSITIVE_HEADER = "x-provider-authorization";
	private static final String SPOOFED_TOPIC = "SPOOFED_PAYMENT.events";
	private static final int SPOOFED_PARTITION = 77;
	private static final long SPOOFED_OFFSET = 999L;
	private static final long SPOOFED_ORIGINAL_TIMESTAMP = 1L;
	private static final long ATTACKER_BACKOFF_DELAY_MS = 30_000L;
	private static final long PROMPT_DELIVERY_TIMEOUT_MS = 2_000L;
	private static final String SANITIZED_POISON = "{\"event_type\":\"UNKNOWN\",\"payload\":{}}";
	private static final String SANITIZED_EXECUTION_REQUEST =
		"{\"event_type\":\"PAYMENT_EXECUTION_REQUESTED_V1\",\"payload\":{\"operation_uid\":\""
			+ OPERATION_UID + "\"}}";
	private static final List<String> SAFE_RETRY_DLT_HEADERS = List.of(
		RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS,
		RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP,
		RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP,
		KafkaHeaders.ORIGINAL_TOPIC,
		KafkaHeaders.ORIGINAL_PARTITION,
		KafkaHeaders.ORIGINAL_OFFSET
	);
	private final EmbeddedKafkaBroker broker;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final PaymentOperationExecutor executor;
	private final PaymentOperationAlertService alertService;
	private final ObjectMapper objectMapper;
	private Logger rootLogger;
	private ListAppender<ILoggingEvent> logAppender;
	private Consumer<String, String> operationRetryConsumer;
	private Consumer<String, String> operationDltConsumer;
	private Consumer<String, String> globalPaymentDltConsumer;

	@Autowired
	PaymentOperationKafkaIntegrationTest(
		EmbeddedKafkaBroker broker,
		@Qualifier("deadLetterKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
		PaymentOperationExecutor executor,
		PaymentOperationAlertService alertService,
		ObjectMapper objectMapper
	) {
		this.broker = broker;
		this.kafkaTemplate = kafkaTemplate;
		this.executor = executor;
		this.alertService = alertService;
		this.objectMapper = objectMapper;
	}

	@BeforeEach
	void setUp() {
		operationRetryConsumer = consumer("payment-operation-retry-assertion");
		operationDltConsumer = consumer("payment-operation-dlt-assertion");
		globalPaymentDltConsumer = consumer("global-payment-dlt-assertion");
		broker.consumeFromAnEmbeddedTopic(operationRetryConsumer, OPERATION_RETRY_TOPIC);
		broker.consumeFromAnEmbeddedTopic(operationDltConsumer, OPERATION_DLT_TOPIC);
		broker.consumeFromAnEmbeddedTopic(globalPaymentDltConsumer, GLOBAL_PAYMENT_DLT_TOPIC);
		reset(executor);
		reset(alertService);
		rootLogger = (Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		logAppender = new ListAppender<>();
		logAppender.start();
		rootLogger.addAppender(logAppender);
	}

	@AfterEach
	void tearDown() {
		rootLogger.detachAppender(logAppender);
		logAppender.stop();
		operationRetryConsumer.close();
		operationDltConsumer.close();
		globalPaymentDltConsumer.close();
	}

	@Test
	@DisplayName("원본의 미래 backoff 헤더는 같은 파티션을 막지 않고 poison만 전용 DLT로 격리한다")
	void isolatesPoisonMessageAndDeliversValidDuplicatesAtBoundary() throws Exception {
		String validMessage = productionMessage();
		String malformed = "not-json paymentKey=" + RAW_SECRET;
		assertThat(validMessage).contains("\"timestamp\":\"2026-08-14T00:00:00\"");

		kafkaTemplate.send(OPERATION_TOPIC, validMessage).get(10, TimeUnit.SECONDS);
		verify(executor, timeout(15_000)).execute(OPERATION_UID);
		org.mockito.Mockito.clearInvocations(executor, alertService);

		ProducerRecord<String, String> poison = new ProducerRecord<>(
			OPERATION_TOPIC, "sensitive-key-" + RAW_SECRET, malformed);
		poison.headers().add(
			CUSTOM_SENSITIVE_HEADER, RAW_SECRET.getBytes(StandardCharsets.UTF_8));
		long attackerBackoffTimestamp = System.currentTimeMillis() + ATTACKER_BACKOFF_DELAY_MS;
		addSpoofedReservedHeaders(poison, attackerBackoffTimestamp);
		kafkaTemplate.send(poison)
			.get(10, TimeUnit.SECONDS);
		ProducerRecord<String, String> validFollower = new ProducerRecord<>(
			OPERATION_TOPIC, 0, RESERVATION_UID.toString(), validMessage);
		kafkaTemplate.send(validFollower).get(10, TimeUnit.SECONDS);
		verify(executor, timeout(PROMPT_DELIVERY_TIMEOUT_MS)).execute(OPERATION_UID);
		org.mockito.Mockito.clearInvocations(executor);

		ConsumerRecord<String, String> retryRecord = KafkaTestUtils.getSingleRecord(
			operationRetryConsumer, OPERATION_RETRY_TOPIC, Duration.ofSeconds(15));
		ConsumerRecord<String, String> quarantined = KafkaTestUtils.getSingleRecord(
			operationDltConsumer, OPERATION_DLT_TOPIC, Duration.ofSeconds(15));
		long dltObservedAt = System.currentTimeMillis();
		List<String> retryHeaderNames = headerNames(retryRecord);
		List<String> dltHeaderNames = headerNames(quarantined);
		assertThat(dltHeaderNames).contains(
			KafkaHeaders.ORIGINAL_TOPIC,
			KafkaHeaders.ORIGINAL_PARTITION,
			KafkaHeaders.ORIGINAL_OFFSET);
		assertThat(retryHeaderNames).isSubsetOf(SAFE_RETRY_DLT_HEADERS);
		assertThat(dltHeaderNames).isSubsetOf(SAFE_RETRY_DLT_HEADERS);
		assertThat(retryHeaderNames).doesNotHaveDuplicates();
		assertThat(dltHeaderNames).doesNotHaveDuplicates();
		assertThat(retryRecord.headers().lastHeader(CUSTOM_SENSITIVE_HEADER)).isNull();
		assertThat(quarantined.headers().lastHeader(CUSTOM_SENSITIVE_HEADER)).isNull();
		assertThat(readIntHeader(retryRecord, RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS))
			.isEqualTo(2);
		long originalTimestamp = readTimestampHeader(
			retryRecord, RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP);
		long backoffTimestamp = readTimestampHeader(
			retryRecord, RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP);
		assertThat(originalTimestamp).isGreaterThan(SPOOFED_ORIGINAL_TIMESTAMP);
		assertThat(backoffTimestamp).isGreaterThanOrEqualTo(originalTimestamp + 500L);
		assertThat(backoffTimestamp).isLessThan(attackerBackoffTimestamp);
		assertThat(dltObservedAt).isGreaterThanOrEqualTo(backoffTimestamp);
		assertThat(readStringHeader(quarantined, KafkaHeaders.ORIGINAL_TOPIC))
			.isEqualTo(OPERATION_TOPIC);
		assertThat(readIntHeader(quarantined, KafkaHeaders.ORIGINAL_PARTITION)).isZero();
		assertThat(readLongHeader(quarantined, KafkaHeaders.ORIGINAL_OFFSET)).isEqualTo(1L);
		verify(alertService, timeout(15_000)).alertQuarantined(
			OPERATION_TOPIC, 0, 1L, null, "processing failure");
		verifyNoMoreInteractions(alertService);
		assertThat(retryRecord.value()).isEqualTo(SANITIZED_POISON);
		assertThat(retryRecord.key()).isNull();
		assertThat(quarantined.topic()).isEqualTo(OPERATION_DLT_TOPIC);
		assertThat(quarantined.value()).isEqualTo(SANITIZED_POISON);
		assertThat(quarantined.key()).isNull();
		assertRecordDoesNotContainSecret(retryRecord);
		assertRecordDoesNotContainSecret(quarantined);
		assertThat(capturedLogs()).doesNotContain(
			RAW_SECRET, RESERVED_HEADER_SECRET, malformed);
		assertThat(KafkaTestUtils.getRecords(globalPaymentDltConsumer, Duration.ofSeconds(2)))
			.isEmpty();
		verifyNoInteractions(executor);

		reset(executor);
		doThrow(new IllegalStateException("database unavailable"))
			.doNothing()
			.when(executor).execute(OPERATION_UID);
		kafkaTemplate.send(OPERATION_TOPIC, RESERVATION_UID.toString(), validMessage)
			.get(10, TimeUnit.SECONDS);
		ConsumerRecord<String, String> validRetry = KafkaTestUtils.getSingleRecord(
			operationRetryConsumer, OPERATION_RETRY_TOPIC, Duration.ofSeconds(15));
		assertThat(validRetry.value()).isEqualTo(validMessage);
		assertThat(validRetry.key()).isEqualTo(RESERVATION_UID.toString());
		assertThat(validRetry.partition()).isZero();
		verify(executor, timeout(15_000).times(2)).execute(OPERATION_UID);

		reset(executor);
		doThrow(new IllegalStateException("database unavailable"))
			.doNothing()
			.when(executor).execute(OPERATION_UID);
		kafkaTemplate.send(OPERATION_TOPIC, "untrusted-incoming-key", validMessage)
			.get(10, TimeUnit.SECONDS);
		ConsumerRecord<String, String> rekeyedRetry = KafkaTestUtils.getSingleRecord(
			operationRetryConsumer, OPERATION_RETRY_TOPIC, Duration.ofSeconds(15));
		assertThat(rekeyedRetry.key()).isEqualTo(RESERVATION_UID.toString());
		verify(executor, timeout(15_000).times(2)).execute(OPERATION_UID);

		reset(executor);
		doThrow(new IllegalStateException("database unavailable"))
			.when(executor).execute(OPERATION_UID);
		String extraField = validMessage.replace(
			"\"reservation_uid\":\"" + RESERVATION_UID + "\"",
			"\"reservation_uid\":\"" + RESERVATION_UID + "\","
				+ "\"provider_secret\":\"" + RAW_SECRET + "\"");
		kafkaTemplate.send(OPERATION_TOPIC, RESERVATION_UID.toString(), extraField)
			.get(10, TimeUnit.SECONDS);
		ConsumerRecord<String, String> sanitizedRetry = KafkaTestUtils.getSingleRecord(
			operationRetryConsumer, OPERATION_RETRY_TOPIC, Duration.ofSeconds(15));
		ConsumerRecord<String, String> sanitizedDlt = KafkaTestUtils.getSingleRecord(
			operationDltConsumer, OPERATION_DLT_TOPIC, Duration.ofSeconds(15));
		assertThat(sanitizedRetry.value()).isEqualTo(SANITIZED_EXECUTION_REQUEST);
		assertThat(sanitizedRetry.key()).isNull();
		assertThat(sanitizedDlt.value()).isEqualTo(SANITIZED_EXECUTION_REQUEST);
		assertRecordDoesNotContainSecret(sanitizedRetry);
		assertRecordDoesNotContainSecret(sanitizedDlt);
		verify(executor, timeout(15_000).times(2)).execute(OPERATION_UID);

		String duplicateKey = validMessage.replace(
			"\"operation_uid\":\"" + OPERATION_UID + "\"",
			"\"operation_uid\":\"" + RAW_SECRET + "\","
				+ "\"operation_uid\":\"" + OPERATION_UID + "\"");
		assertSmuggledPayloadBecomesPoison(duplicateKey);
		assertSmuggledPayloadBecomesPoison(
			validMessage + "{\"provider_secret\":\"" + RAW_SECRET + "\"}");

		reset(executor);
		kafkaTemplate.send(OPERATION_TOPIC, validMessage).get(10, TimeUnit.SECONDS);
		kafkaTemplate.send(OPERATION_TOPIC, validMessage).get(10, TimeUnit.SECONDS);

		verify(executor, timeout(15_000).times(2)).execute(OPERATION_UID);
		assertThat(capturedLogs()).doesNotContain(RAW_SECRET, RESERVED_HEADER_SECRET);
	}

	private void assertSmuggledPayloadBecomesPoison(String smuggled) throws Exception {
		reset(executor);
		doThrow(new IllegalStateException("database unavailable"))
			.when(executor).execute(OPERATION_UID);

		kafkaTemplate.send(OPERATION_TOPIC, RESERVATION_UID.toString(), smuggled)
			.get(10, TimeUnit.SECONDS);
		ConsumerRecord<String, String> retry = KafkaTestUtils.getSingleRecord(
			operationRetryConsumer, OPERATION_RETRY_TOPIC, Duration.ofSeconds(15));
		ConsumerRecord<String, String> dlt = KafkaTestUtils.getSingleRecord(
			operationDltConsumer, OPERATION_DLT_TOPIC, Duration.ofSeconds(15));

		assertThat(retry.value()).isEqualTo(SANITIZED_POISON);
		assertThat(retry.key()).isNull();
		assertThat(dlt.value()).isEqualTo(SANITIZED_POISON);
		assertThat(dlt.key()).isNull();
		assertRecordDoesNotContainSecret(retry);
		assertRecordDoesNotContainSecret(dlt);
		assertThat(capturedLogs()).doesNotContain(smuggled);
		verifyNoInteractions(executor);
	}

	private String productionMessage() throws Exception {
		return objectMapper.writeValueAsString(EventEnvelope.of(
			EventType.PAYMENT_EXECUTION_REQUESTED_V1,
			new PaymentExecutionRequestedV1(OPERATION_UID, RESERVATION_UID),
			Instant.parse("2026-08-14T00:00:00Z")
		));
	}

	private List<String> headerNames(ConsumerRecord<String, String> record) {
		List<String> names = new ArrayList<>();
		record.headers().forEach(header -> names.add(header.key()));
		return names;
	}

	private void addSpoofedReservedHeaders(
		ProducerRecord<String, String> record,
		long attackerBackoffTimestamp
	) {
		for (String headerName : SAFE_RETRY_DLT_HEADERS) {
			record.headers().add(
				headerName, RESERVED_HEADER_SECRET.getBytes(StandardCharsets.UTF_8));
		}
		record.headers().add(
			RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS, intBytes(1));
		record.headers().add(
			RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP,
			BigInteger.valueOf(attackerBackoffTimestamp).toByteArray());
		record.headers().add(
			RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP,
			BigInteger.valueOf(SPOOFED_ORIGINAL_TIMESTAMP).toByteArray());
		record.headers().add(
			KafkaHeaders.ORIGINAL_TOPIC, SPOOFED_TOPIC.getBytes(StandardCharsets.UTF_8));
		record.headers().add(
			KafkaHeaders.ORIGINAL_PARTITION, intBytes(SPOOFED_PARTITION));
		record.headers().add(
			KafkaHeaders.ORIGINAL_OFFSET, longBytes(SPOOFED_OFFSET));
	}

	private void assertRecordDoesNotContainSecret(ConsumerRecord<String, String> record) {
		assertThat(String.valueOf(record.key())).doesNotContain(RAW_SECRET);
		assertThat(record.value()).doesNotContain(RAW_SECRET);
		assertThat(headerNames(record)).noneMatch(name ->
			name.contains(RAW_SECRET) || name.contains(RESERVED_HEADER_SECRET));
		assertThat(headerValues(record)).noneMatch(value ->
			value.contains(RAW_SECRET) || value.contains(RESERVED_HEADER_SECRET));
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

	private String capturedLogs() {
		StringBuilder logs = new StringBuilder();
		for (ILoggingEvent event : logAppender.list) {
			logs.append(event.getFormattedMessage());
			if (event.getThrowableProxy() != null) {
				logs.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
			}
		}
		return logs.toString();
	}

	private List<String> headerValues(ConsumerRecord<String, String> record) {
		List<String> values = new ArrayList<>();
		record.headers().forEach(header ->
			values.add(new String(header.value(), StandardCharsets.UTF_8)));
		return values;
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
		PaymentOperationKafkaConsumerConfig.class,
		PaymentOperationKafkaPublisherConfig.class,
		PaymentOperationEventsConsumer.class
	})
	static class KafkaTestConfiguration {

		@Bean
		PaymentOperationEventParser paymentOperationEventParser(ObjectMapper objectMapper) {
			return new PaymentOperationEventParser(objectMapper);
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
