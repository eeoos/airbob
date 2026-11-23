package kr.kro.airbob.core.domain.search.messaging.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import kr.kro.airbob.core.domain.search.messaging.event.AccommodationSearchRefreshRequestedV1;
import kr.kro.airbob.messaging.infrastructure.kafka.SanitizingRetryKafkaTemplateFactory;

@Configuration(proxyBeanMethods = false)
public class AccommodationSearchKafkaRetryPublisherConfiguration {

	@Bean
	public KafkaTemplate<String, String> accommodationSearchRetryKafkaTemplate(
		SanitizingRetryKafkaTemplateFactory templateFactory
	) {
		return templateFactory.create(
			AccommodationSearchRefreshRequestedV1.DESCRIPTOR,
			AccommodationSearchRefreshRequestedV1.class,
			AccommodationSearchRefreshRequestedV1::partitionKey);
	}
}
