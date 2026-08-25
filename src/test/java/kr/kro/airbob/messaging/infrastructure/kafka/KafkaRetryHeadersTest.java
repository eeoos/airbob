package kr.kro.airbob.messaging.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.support.KafkaHeaders;

@DisplayName("공통 Kafka retry 헤더 경계")
class KafkaRetryHeadersTest {

	private static final String PRIMARY_TOPIC = "PAYMENT_OPERATION.events";
	private static final String RETRY_TOPIC = PRIMARY_TOPIC + ".RETRY";
	private static final byte[] SECRET = "reserved-header-secret".getBytes(StandardCharsets.UTF_8);

	private PrimaryTopicHeaderSanitizingInterceptor interceptor;

	@BeforeEach
	void setUp() {
		interceptor = new PrimaryTopicHeaderSanitizingInterceptor();
		interceptor.configure(Map.of(
			PrimaryTopicHeaderSanitizingInterceptor.PRIMARY_TOPIC_CONFIG,
			PRIMARY_TOPIC));
	}

	@Test
	void stripsEveryReservedFrameworkHeaderFromPrimaryRecords() {
		ConsumerRecord<String, String> primary = record(PRIMARY_TOPIC);
		primary.headers().add(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS, SECRET);
		primary.headers().add(KafkaHeaders.ORIGINAL_TOPIC, SECRET);
		primary.headers().add(KafkaHeaders.EXCEPTION_MESSAGE, SECRET);
		primary.headers().add(KafkaHeaders.DLT_EXCEPTION_STACKTRACE, SECRET);
		primary.headers().add(KafkaHeaders.DELIVERY_ATTEMPT, SECRET);
		primary.headers().add("safe-trace", "trace-id".getBytes(StandardCharsets.UTF_8));

		ConsumerRecords<String, String> records = records(primary);

		assertThat(interceptor.onConsume(records)).isSameAs(records);
		assertThat(primary.headers()).extracting(header -> header.key())
			.containsExactly("safe-trace");
	}

	@Test
	void retainsFrameworkHeadersOnFrameworkCreatedRetryRecords() {
		ConsumerRecord<String, String> retry = record(RETRY_TOPIC);
		retry.headers().add(RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP, SECRET);

		interceptor.onConsume(records(retry));

		assertThat(retry.headers().lastHeader(
			RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP)).isNotNull();
	}

	@Test
	void rejectsMissingPrimaryTopicConfiguration() {
		assertThatThrownBy(() -> new PrimaryTopicHeaderSanitizingInterceptor().configure(Map.of()))
			.isInstanceOf(ConfigException.class);
	}

	@Test
	void copiesOnlyValidatedRetryCoordinatesAndDropsSecretsAndCustomHeaders() {
		Headers source = new RecordHeaders()
			.add(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS, intBytes(2))
			.add(RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP, positiveBigIntegerBytes(42L))
			.add(RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP, positiveBigIntegerBytes(7L))
			.add(KafkaHeaders.ORIGINAL_TOPIC, PRIMARY_TOPIC.getBytes(StandardCharsets.UTF_8))
			.add(KafkaHeaders.ORIGINAL_PARTITION, intBytes(1))
			.add(KafkaHeaders.ORIGINAL_OFFSET, longBytes(19L))
			.add(KafkaHeaders.EXCEPTION_MESSAGE, SECRET)
			.add("custom-secret", SECRET);

		Headers copied = KafkaRetryHeaders.copyValidatedFrameworkOwned(
			source, RETRY_TOPIC, 1);

		assertThat(copied).extracting(header -> header.key()).containsExactlyInAnyOrder(
			RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS,
			RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP,
			RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP,
			KafkaHeaders.ORIGINAL_TOPIC,
			KafkaHeaders.ORIGINAL_PARTITION,
			KafkaHeaders.ORIGINAL_OFFSET);
		assertThat(copied.lastHeader(KafkaHeaders.EXCEPTION_MESSAGE)).isNull();
		assertThat(copied.lastHeader("custom-secret")).isNull();
	}

	@Test
	void rejectsSpoofedTopicAndPartitionCoordinates() {
		Headers source = new RecordHeaders()
			.add(KafkaHeaders.ORIGINAL_TOPIC, "OTHER.events".getBytes(StandardCharsets.UTF_8))
			.add(KafkaHeaders.ORIGINAL_PARTITION, intBytes(9))
			.add(KafkaHeaders.ORIGINAL_OFFSET, longBytes(19L));

		Headers copied = KafkaRetryHeaders.copyValidatedFrameworkOwned(
			source, RETRY_TOPIC, 1);

		assertThat(copied.lastHeader(KafkaHeaders.ORIGINAL_TOPIC)).isNull();
		assertThat(copied.lastHeader(KafkaHeaders.ORIGINAL_PARTITION)).isNull();
		assertThat(copied.lastHeader(KafkaHeaders.ORIGINAL_OFFSET)).isNotNull();
	}

	@Test
	void usesOriginalCoordinatesOnlyForTheCanonicalTopic() {
		ConsumerRecord<String, String> canonical = new ConsumerRecord<>(
			RETRY_TOPIC, 5, 99L, "key", "value");
		canonical.headers()
			.add(KafkaHeaders.ORIGINAL_TOPIC, PRIMARY_TOPIC.getBytes(StandardCharsets.UTF_8))
			.add(KafkaHeaders.ORIGINAL_PARTITION, intBytes(1))
			.add(KafkaHeaders.ORIGINAL_OFFSET, longBytes(19L));
		ConsumerRecord<String, String> foreign = new ConsumerRecord<>(
			RETRY_TOPIC, 5, 99L, "key", "value");
		foreign.headers()
			.add(KafkaHeaders.ORIGINAL_TOPIC, "OTHER.events".getBytes(StandardCharsets.UTF_8))
			.add(KafkaHeaders.ORIGINAL_PARTITION, intBytes(1))
			.add(KafkaHeaders.ORIGINAL_OFFSET, longBytes(19L));

		assertThat(KafkaRetryHeaders.canonicalSourceCoordinates(canonical, PRIMARY_TOPIC))
			.isEqualTo(new KafkaRetryHeaders.RecordCoordinates(1, 19L));
		assertThat(KafkaRetryHeaders.canonicalSourceCoordinates(foreign, PRIMARY_TOPIC))
			.isEqualTo(new KafkaRetryHeaders.RecordCoordinates(5, 99L));
	}

	private ConsumerRecord<String, String> record(String topic) {
		return new ConsumerRecord<>(topic, 0, 1L, "key", "value");
	}

	private ConsumerRecords<String, String> records(ConsumerRecord<String, String> record) {
		return new ConsumerRecords<>(Map.of(
			new TopicPartition(record.topic(), record.partition()), List.of(record)));
	}

	private byte[] intBytes(int value) {
		return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
	}

	private byte[] longBytes(long value) {
		return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
	}

	private byte[] positiveBigIntegerBytes(long value) {
		return BigInteger.valueOf(value).toByteArray();
	}
}
