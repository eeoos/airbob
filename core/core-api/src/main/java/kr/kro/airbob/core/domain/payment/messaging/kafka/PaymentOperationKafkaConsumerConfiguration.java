package kr.kro.airbob.core.domain.payment.messaging.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

import kr.kro.airbob.core.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.core.support.messaging.kafka.IntegrationEventKafkaListenerContainerFactoryBuilder;

@Configuration(proxyBeanMethods = false)
public class PaymentOperationKafkaConsumerConfiguration {

	public static final String CONTAINER_FACTORY = "paymentOperationKafkaListenerContainerFactory";

	@Bean(name = CONTAINER_FACTORY)
	public ConcurrentKafkaListenerContainerFactory<Object, Object> paymentOperationKafkaListenerContainerFactory(
		IntegrationEventKafkaListenerContainerFactoryBuilder factoryBuilder) {
		return factoryBuilder.build(PaymentOperationExecutionRequestedV1.TOPIC);
	}
}
