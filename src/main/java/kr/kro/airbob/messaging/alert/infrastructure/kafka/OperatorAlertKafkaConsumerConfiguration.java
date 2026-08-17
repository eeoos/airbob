package kr.kro.airbob.messaging.alert.infrastructure.kafka;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;

@Configuration(proxyBeanMethods = false)
public class OperatorAlertKafkaConsumerConfiguration {

	public static final String CONTAINER_FACTORY =
		"operatorAlertKafkaListenerContainerFactory";

	@Bean(name = CONTAINER_FACTORY)
	public ConcurrentKafkaListenerContainerFactory<Object, Object>
		operatorAlertKafkaListenerContainerFactory(
			ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
			KafkaProperties kafkaProperties
		) {
		Map<String, Object> properties = new HashMap<>(
			kafkaProperties.buildConsumerProperties());
		properties.put(
			ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG,
			OperatorAlertKafkaHeaderConsumerInterceptor.class.getName());
		properties.put(
			OperatorAlertKafkaHeaderConsumerInterceptor.PRIMARY_TOPIC_CONFIG,
			OperatorAlertRequestedV1.TOPIC);

		DefaultKafkaConsumerFactory<Object, Object> consumerFactory =
			new DefaultKafkaConsumerFactory<>(properties);
		ConcurrentKafkaListenerContainerFactory<Object, Object> containerFactory =
			new ConcurrentKafkaListenerContainerFactory<>();
		configurer.configure(containerFactory, consumerFactory);
		return containerFactory;
	}
}
