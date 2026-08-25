package kr.kro.airbob.domain.accommodation.cache.messaging.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

import kr.kro.airbob.domain.accommodation.cache.messaging.event.AccommodationDetailCacheInvalidationRequestedV1;
import kr.kro.airbob.messaging.infrastructure.kafka.IntegrationEventKafkaListenerContainerFactoryBuilder;

@Configuration(proxyBeanMethods = false)
@Profile("!traffic-benchmark")
public class AccommodationDetailCacheKafkaConsumerConfiguration {

	public static final String CONTAINER_FACTORY =
		"accommodationDetailCacheKafkaListenerContainerFactory";

	@Bean(name = CONTAINER_FACTORY)
	public ConcurrentKafkaListenerContainerFactory<Object, Object>
		accommodationDetailCacheKafkaListenerContainerFactory(
			IntegrationEventKafkaListenerContainerFactoryBuilder factoryBuilder
		) {
		return factoryBuilder.build(AccommodationDetailCacheInvalidationRequestedV1.TOPIC);
	}
}
