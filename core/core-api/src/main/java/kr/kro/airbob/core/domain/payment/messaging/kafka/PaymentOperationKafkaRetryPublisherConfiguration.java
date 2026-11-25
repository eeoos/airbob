package kr.kro.airbob.core.domain.payment.messaging.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import kr.kro.airbob.core.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.core.support.messaging.kafka.SanitizingRetryKafkaTemplateFactory;

@Configuration(proxyBeanMethods = false)
public class PaymentOperationKafkaRetryPublisherConfiguration {

	@Bean
	public KafkaTemplate<String, String> paymentOperationRetryKafkaTemplate(
		SanitizingRetryKafkaTemplateFactory templateFactory) {
		return templateFactory.create(
			PaymentOperationExecutionRequestedV1.DESCRIPTOR,
			PaymentOperationExecutionRequestedV1.class,
			PaymentOperationExecutionRequestedV1::partitionKey);
	}
}
