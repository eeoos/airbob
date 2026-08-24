package kr.kro.airbob.domain.payment.messaging.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.messaging.infrastructure.kafka.IntegrationEventKafkaListenerContainerFactoryBuilder;

@Configuration(proxyBeanMethods = false)
@Profile("!traffic-benchmark")
public class PaymentOperationKafkaConsumerConfiguration {

	public static final String CONTAINER_FACTORY =
		"paymentOperationKafkaListenerContainerFactory";

	@Bean(name = CONTAINER_FACTORY)
	public ConcurrentKafkaListenerContainerFactory<Object, Object>
		paymentOperationKafkaListenerContainerFactory(
			IntegrationEventKafkaListenerContainerFactoryBuilder factoryBuilder
		) {
		return factoryBuilder.build(PaymentOperationExecutionRequestedV1.TOPIC);
	}
}
