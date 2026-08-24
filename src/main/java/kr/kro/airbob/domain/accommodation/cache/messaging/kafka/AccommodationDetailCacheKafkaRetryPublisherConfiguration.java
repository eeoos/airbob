package kr.kro.airbob.domain.accommodation.cache.messaging.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;

import kr.kro.airbob.domain.accommodation.cache.messaging.event.AccommodationDetailCacheInvalidationRequestedV1;
import kr.kro.airbob.messaging.infrastructure.kafka.SanitizingRetryKafkaTemplateFactory;

@Configuration(proxyBeanMethods = false)
@Profile("!traffic-benchmark")
public class AccommodationDetailCacheKafkaRetryPublisherConfiguration {

	@Bean
	public KafkaTemplate<String, String> accommodationDetailCacheRetryKafkaTemplate(
		SanitizingRetryKafkaTemplateFactory templateFactory
	) {
		return templateFactory.create(
			AccommodationDetailCacheInvalidationRequestedV1.DESCRIPTOR,
			AccommodationDetailCacheInvalidationRequestedV1.class,
			AccommodationDetailCacheInvalidationRequestedV1::partitionKey);
	}
}
