package kr.kro.airbob.messaging.alert.infrastructure.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.messaging.infrastructure.kafka.IntegrationEventKafkaListenerContainerFactoryBuilder;

@Configuration(proxyBeanMethods = false)
@Profile("!traffic-benchmark")
public class OperatorAlertKafkaConsumerConfiguration {

	public static final String CONTAINER_FACTORY =
		"operatorAlertKafkaListenerContainerFactory";

	@Bean(name = CONTAINER_FACTORY)
	public ConcurrentKafkaListenerContainerFactory<Object, Object>
		operatorAlertKafkaListenerContainerFactory(
			IntegrationEventKafkaListenerContainerFactoryBuilder factoryBuilder
		) {
		return factoryBuilder.build(OperatorAlertRequestedV1.TOPIC);
	}
}
