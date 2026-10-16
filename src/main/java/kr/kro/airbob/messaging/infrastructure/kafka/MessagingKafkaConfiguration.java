package kr.kro.airbob.messaging.infrastructure.kafka;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import kr.kro.airbob.messaging.event.IntegrationEventCodec;

@Configuration(proxyBeanMethods = false)
@Profile("!traffic-benchmark")
@EnableKafkaRetryTopic
public class MessagingKafkaConfiguration {

	public static final String PRODUCER_FACTORY = "integrationEventProducerFactory";
	public static final String KAFKA_TEMPLATE = "integrationEventKafkaTemplate";

	@Bean
	public RetryTopicSchedulerWrapper kafkaRetryTopicScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("kafka-retry-");
		scheduler.setRemoveOnCancelPolicy(true);
		return new RetryTopicSchedulerWrapper(scheduler);
	}

	@Bean(name = PRODUCER_FACTORY)
	public ProducerFactory<String, String> integrationEventProducerFactory(
		KafkaProperties kafkaProperties
	) {
		Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
		properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return new DefaultKafkaProducerFactory<>(properties);
	}

	@Bean(name = KAFKA_TEMPLATE)
	public KafkaTemplate<String, String> integrationEventKafkaTemplate(
		@Qualifier(PRODUCER_FACTORY) ProducerFactory<String, String> producerFactory
	) {
		return new KafkaTemplate<>(producerFactory);
	}

	@Bean
	public IntegrationEventKafkaListenerContainerFactoryBuilder
		integrationEventKafkaListenerContainerFactoryBuilder(
			ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
			KafkaProperties kafkaProperties
		) {
		return new IntegrationEventKafkaListenerContainerFactoryBuilder(
			configurer, kafkaProperties);
	}

	@Bean
	public SanitizingRetryKafkaTemplateFactory sanitizingRetryKafkaTemplateFactory(
		@Qualifier(PRODUCER_FACTORY) ProducerFactory<String, String> producerFactory,
		IntegrationEventCodec codec
	) {
		return new SanitizingRetryKafkaTemplateFactory(producerFactory, codec);
	}
}
