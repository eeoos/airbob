package kr.kro.airbob.messaging.infrastructure.kafka;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

public class IntegrationEventKafkaListenerContainerFactoryBuilder {

	private final ConcurrentKafkaListenerContainerFactoryConfigurer configurer;
	private final KafkaProperties kafkaProperties;

	public IntegrationEventKafkaListenerContainerFactoryBuilder(
		ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
		KafkaProperties kafkaProperties
	) {
		this.configurer = configurer;
		this.kafkaProperties = kafkaProperties;
	}

	public ConcurrentKafkaListenerContainerFactory<Object, Object> build(String primaryTopic) {
		Map<String, Object> properties = new HashMap<>(kafkaProperties.buildConsumerProperties());
		properties.put(
			ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG,
			PrimaryTopicHeaderSanitizingInterceptor.class.getName());
		properties.put(PrimaryTopicHeaderSanitizingInterceptor.PRIMARY_TOPIC_CONFIG, primaryTopic);

		DefaultKafkaConsumerFactory<Object, Object> consumerFactory =
			new DefaultKafkaConsumerFactory<>(properties);
		ConcurrentKafkaListenerContainerFactory<Object, Object> containerFactory =
			new ConcurrentKafkaListenerContainerFactory<>();
		configurer.configure(containerFactory, consumerFactory);
		return containerFactory;
	}
}
