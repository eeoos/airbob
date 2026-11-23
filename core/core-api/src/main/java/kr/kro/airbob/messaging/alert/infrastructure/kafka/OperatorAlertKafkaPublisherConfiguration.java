package kr.kro.airbob.messaging.alert.infrastructure.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import kr.kro.airbob.core.support.messaging.kafka.SanitizingRetryKafkaTemplateFactory;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;

@Configuration(proxyBeanMethods = false)
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
