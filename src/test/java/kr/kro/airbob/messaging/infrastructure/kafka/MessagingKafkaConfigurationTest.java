package kr.kro.airbob.messaging.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@DisplayName("공통 integration-event Kafka 설정")
class MessagingKafkaConfigurationTest {

	private final MessagingKafkaConfiguration configuration =
		new MessagingKafkaConfiguration();

	@Test
	void usesStringSerializersForCanonicalEventJson() {
		DefaultKafkaProducerFactory<String, String> producerFactory =
			(DefaultKafkaProducerFactory<String, String>)configuration
				.integrationEventProducerFactory(new KafkaProperties());

		assertThat(producerFactory.getConfigurationProperties())
			.containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
			.containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
	}

	@Test
	void providesDedicatedRetryTopicScheduler() {
		RetryTopicSchedulerWrapper schedulerWrapper = configuration.kafkaRetryTopicScheduler();

		assertThat(schedulerWrapper.getScheduler()).isInstanceOf(ThreadPoolTaskScheduler.class);
		assertThat(((ThreadPoolTaskScheduler)schedulerWrapper.getScheduler()).getThreadNamePrefix())
			.isEqualTo("kafka-retry-");
	}
}
