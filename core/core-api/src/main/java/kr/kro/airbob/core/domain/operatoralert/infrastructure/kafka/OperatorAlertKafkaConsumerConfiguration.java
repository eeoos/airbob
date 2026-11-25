package kr.kro.airbob.core.domain.operatoralert.infrastructure.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

import kr.kro.airbob.core.domain.operatoralert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.core.support.messaging.kafka.IntegrationEventKafkaListenerContainerFactoryBuilder;

@Configuration(proxyBeanMethods = false)
public class OperatorAlertKafkaConsumerConfiguration {

	public static final String CONTAINER_FACTORY = "operatorAlertKafkaListenerContainerFactory";

	@Bean(name = CONTAINER_FACTORY)
	public ConcurrentKafkaListenerContainerFactory<Object, Object> operatorAlertKafkaListenerContainerFactory(
		IntegrationEventKafkaListenerContainerFactoryBuilder factoryBuilder) {
		return factoryBuilder.build(OperatorAlertRequestedV1.TOPIC);
	}
}
