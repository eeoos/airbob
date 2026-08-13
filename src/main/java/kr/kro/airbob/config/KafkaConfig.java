package kr.kro.airbob.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
@EnableKafkaRetryTopic
public class KafkaConfig {

	@Bean
	public RetryTopicSchedulerWrapper retryTopicScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("kafka-retry-");
		scheduler.setRemoveOnCancelPolicy(true);
		return new RetryTopicSchedulerWrapper(scheduler);
	}

	@Bean
	public ProducerFactory<String, String> deadLetterProducerFactory(
		KafkaProperties kafkaProperties
	) {
		Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
		properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return new DefaultKafkaProducerFactory<>(properties);
	}

	@Bean
	public KafkaTemplate<String, String> deadLetterKafkaTemplate(
		@Qualifier("deadLetterProducerFactory") ProducerFactory<String, String> producerFactory
	) {
		return new KafkaTemplate<>(producerFactory);
	}

	@Bean
	public DefaultErrorHandler errorHandler(
		@Value("${spring.kafka.consumer.properties.spring.kafka.dead-letter-publishing.topic-name}")
		String deadLetterTopic,
		@Qualifier("deadLetterKafkaTemplate") KafkaTemplate<String, String> deadLetterKafkaTemplate
	) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(deadLetterKafkaTemplate,
			(consumerRecord, exception) -> new TopicPartition(deadLetterTopic, -1)
		);

		ExponentialBackOff backOff = new ExponentialBackOff(2000L, 2.0);
		backOff.setMaxInterval(10000L);
		backOff.setMaxElapsedTime(60000L);

		return new DefaultErrorHandler(recoverer, backOff);
	}
}
