package kr.kro.airbob.search.messaging.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;

import kr.kro.airbob.messaging.infrastructure.kafka.SanitizingRetryKafkaTemplateFactory;
import kr.kro.airbob.search.messaging.event.AccommodationSearchRefreshRequestedV1;

@Configuration(proxyBeanMethods = false)
@Profile("!traffic-benchmark")
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
