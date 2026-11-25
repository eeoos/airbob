package kr.kro.airbob.core.domain.search.messaging.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

import kr.kro.airbob.core.domain.search.messaging.event.AccommodationSearchRefreshRequestedV1;
import kr.kro.airbob.core.support.messaging.kafka.IntegrationEventKafkaListenerContainerFactoryBuilder;

@Configuration(proxyBeanMethods = false)
public class AccommodationSearchKafkaConsumerConfiguration {

	public static final String CONTAINER_FACTORY = "accommodationSearchKafkaListenerContainerFactory";

	@Bean(name = CONTAINER_FACTORY)
	public ConcurrentKafkaListenerContainerFactory<Object, Object> accommodationSearchKafkaListenerContainerFactory(
		IntegrationEventKafkaListenerContainerFactoryBuilder factoryBuilder) {
		return factoryBuilder.build(AccommodationSearchRefreshRequestedV1.TOPIC);
	}
}
