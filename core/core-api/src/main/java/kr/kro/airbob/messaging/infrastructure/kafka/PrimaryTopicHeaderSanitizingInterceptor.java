package kr.kro.airbob.messaging.infrastructure.kafka;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigException;

public final class PrimaryTopicHeaderSanitizingInterceptor
	implements ConsumerInterceptor<String, String> {

	public static final String PRIMARY_TOPIC_CONFIG = "airbob.messaging.kafka.primary-topic";

	private String primaryTopic;

	@Override
	public void configure(Map<String, ?> configs) {
		Object configuredTopic = configs.get(PRIMARY_TOPIC_CONFIG);
		if (!(configuredTopic instanceof String topic) || topic.isBlank()) {
			throw new ConfigException(PRIMARY_TOPIC_CONFIG, configuredTopic, "must be a topic name");
		}
		primaryTopic = topic;
	}

	@Override
	public ConsumerRecords<String, String> onConsume(ConsumerRecords<String, String> records) {
		for (var record : records) {
			if (record.topic().equals(primaryTopic)) {
				KafkaRetryHeaders.stripFrameworkOwned(record.headers());
			}
		}
		return records;
	}

	@Override
	public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
	}

	@Override
	public void close() {
	}
}
