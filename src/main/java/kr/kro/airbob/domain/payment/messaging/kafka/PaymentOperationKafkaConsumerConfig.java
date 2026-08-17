package kr.kro.airbob.domain.payment.messaging.kafka;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

@Configuration(proxyBeanMethods = false)
public class PaymentOperationKafkaConsumerConfig {

	public static final String CONTAINER_FACTORY =
		"paymentOperationKafkaListenerContainerFactory";

	@Bean(name = CONTAINER_FACTORY)
	public ConcurrentKafkaListenerContainerFactory<Object, Object>
		paymentOperationKafkaListenerContainerFactory(
			ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
			KafkaProperties kafkaProperties,
			@Value("${payment.operation.kafka.topic:PAYMENT_OPERATION.events}") String primaryTopic
		) {
		Map<String, Object> consumerProperties = new HashMap<>(
			kafkaProperties.buildConsumerProperties());
		consumerProperties.put(
			ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG,
			PaymentOperationKafkaHeaderConsumerInterceptor.class.getName()
		);
		consumerProperties.put(
			PaymentOperationKafkaHeaderConsumerInterceptor.PRIMARY_TOPIC_CONFIG,
			primaryTopic
		);

		DefaultKafkaConsumerFactory<Object, Object> consumerFactory =
			new DefaultKafkaConsumerFactory<>(consumerProperties);
		ConcurrentKafkaListenerContainerFactory<Object, Object> containerFactory =
			new ConcurrentKafkaListenerContainerFactory<>();
		configurer.configure(containerFactory, consumerFactory);
		return containerFactory;
	}
}
