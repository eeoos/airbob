package kr.kro.airbob.messaging.alert.infrastructure.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;

import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.messaging.infrastructure.kafka.SanitizingRetryKafkaTemplateFactory;

@Configuration(proxyBeanMethods = false)
@Profile("!traffic-benchmark")
public class OperatorAlertKafkaPublisherConfiguration {

	@Bean
	public KafkaTemplate<String, String> operatorAlertRetryKafkaTemplate(
		SanitizingRetryKafkaTemplateFactory templateFactory
	) {
		return templateFactory.create(
			OperatorAlertRequestedV1.DESCRIPTOR,
			OperatorAlertRequestedV1.class,
			OperatorAlertRequestedV1::partitionKey);
	}
}
