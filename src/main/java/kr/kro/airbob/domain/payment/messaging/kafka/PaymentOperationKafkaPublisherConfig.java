package kr.kro.airbob.domain.payment.messaging.kafka;

import java.util.Map;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import kr.kro.airbob.domain.payment.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;

@Configuration(proxyBeanMethods = false)
public class PaymentOperationKafkaPublisherConfig {

	private static final String SANITIZED_POISON = "{\"event_type\":\"UNKNOWN\",\"payload\":{}}";

	@Bean
	public KafkaTemplate<String, String> paymentOperationRetryKafkaTemplate(
		@Qualifier("deadLetterProducerFactory") ProducerFactory<String, String> producerFactory,
		IntegrationEventCodec codec
	) {
		KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>(producerFactory);
		kafkaTemplate.setProducerInterceptor(new SanitizingInterceptor(codec));
		return kafkaTemplate;
	}

	private static final class SanitizingInterceptor implements ProducerInterceptor<String, String> {
		private final IntegrationEventCodec codec;

		private SanitizingInterceptor(IntegrationEventCodec codec) {
			this.codec = codec;
		}

		@Override
		public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
			SanitizedPayload sanitized = sanitize(record.value());
			Headers sanitizedHeaders = PaymentOperationKafkaHeaders.copyValidatedFrameworkOwned(
				record.headers(), record.topic(), record.partition());
			return new ProducerRecord<>(
				record.topic(),
				record.partition(),
				record.timestamp(),
				sanitized.key(),
				sanitized.value(),
				sanitizedHeaders
			);
		}

		private SanitizedPayload sanitize(String value) {
			try {
				EventEnvelope<PaymentOperationExecutionRequestedV1> envelope = codec.decode(
					value,
					PaymentOperationExecutionRequestedV1.DESCRIPTOR,
					PaymentOperationExecutionRequestedV1.class
				);
				return new SanitizedPayload(
					codec.encode(envelope), envelope.payload().partitionKey());
			} catch (Exception ignored) {
				return SanitizedPayload.poison();
			}
		}

		@Override
		public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
		}

		@Override
		public void close() {
		}

		@Override
		public void configure(Map<String, ?> configs) {
		}
	}

	private record SanitizedPayload(String value, String key) {
		private static SanitizedPayload poison() {
			return new SanitizedPayload(SANITIZED_POISON, null);
		}
	}
}
