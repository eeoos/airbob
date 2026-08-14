package kr.kro.airbob.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.support.KafkaHeaders;

@DisplayName("payment-operation Kafka 소비 헤더 인터셉터 테스트")
class PaymentOperationKafkaHeaderConsumerInterceptorTest {

	private static final String PRIMARY_TOPIC = "PAYMENT_OPERATION.events";
	private static final String RETRY_TOPIC = "PAYMENT_OPERATION.events.RETRY";
	private static final String ATTACKER_SECRET = "attacker-reserved-header-secret";
	private static final List<String> FRAMEWORK_OWNED_HEADERS = List.of(
		RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS,
		RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP,
		RetryTopicHeaders.DEFAULT_HEADER_ORIGINAL_TIMESTAMP,
		KafkaHeaders.ORIGINAL_TOPIC,
		KafkaHeaders.ORIGINAL_PARTITION,
		KafkaHeaders.ORIGINAL_OFFSET
	);

	private PaymentOperationKafkaHeaderConsumerInterceptor interceptor;

	@BeforeEach
	void setUp() {
		interceptor = new PaymentOperationKafkaHeaderConsumerInterceptor();
		interceptor.configure(Map.of(
			PaymentOperationKafkaHeaderConsumerInterceptor.PRIMARY_TOPIC_CONFIG,
			PRIMARY_TOPIC
		));
	}

	@Test
	@DisplayName("poll 직후 원본 토픽의 프레임워크 예약 헤더만 제거한다")
	void stripsFrameworkOwnedHeadersFromPrimaryRecordsImmediatelyAfterPoll() {
		ConsumerRecord<String, String> primary = record(PRIMARY_TOPIC);
		for (String name : FRAMEWORK_OWNED_HEADERS) {
			primary.headers().add(name, ATTACKER_SECRET.getBytes(StandardCharsets.UTF_8));
		}
		primary.headers().add("safe-trace", "trace-id".getBytes(StandardCharsets.UTF_8));

		ConsumerRecords<String, String> records = records(primary);

		assertThat(interceptor.onConsume(records)).isSameAs(records);
		assertThat(primary.headers()).noneMatch(header ->
			FRAMEWORK_OWNED_HEADERS.contains(header.key()));
		assertThat(primary.headers().lastHeader("safe-trace")).isNotNull();
	}

	@Test
	@DisplayName("재시도 토픽의 신뢰된 Spring Kafka 상태 헤더는 유지한다")
	void retainsFrameworkOwnedHeadersFromRetryRecords() {
		ConsumerRecord<String, String> retry = record(RETRY_TOPIC);
		byte[] trustedBackoff = "trusted-backoff".getBytes(StandardCharsets.UTF_8);
		retry.headers().add(
			RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP, trustedBackoff);

		ConsumerRecords<String, String> records = records(retry);

		assertThat(interceptor.onConsume(records)).isSameAs(records);
		assertThat(retry.headers()
			.lastHeader(RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP)
			.value()).containsExactly(trustedBackoff);
	}

	private ConsumerRecord<String, String> record(String topic) {
		return new ConsumerRecord<>(topic, 0, 1L, "key", "value");
	}

	private ConsumerRecords<String, String> records(ConsumerRecord<String, String> record) {
		return new ConsumerRecords<>(Map.of(
			new TopicPartition(record.topic(), record.partition()), List.of(record)));
	}
}
