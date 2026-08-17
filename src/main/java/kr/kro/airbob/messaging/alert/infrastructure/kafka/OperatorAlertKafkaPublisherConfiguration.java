package kr.kro.airbob.messaging.alert.infrastructure.kafka;

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

import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;

@Configuration(proxyBeanMethods = false)
public class OperatorAlertKafkaPublisherConfiguration {

	static final String SANITIZED_POISON = "{\"event_type\":\"UNKNOWN\",\"payload\":{}}";

	@Bean
	public KafkaTemplate<String, String> operatorAlertRetryKafkaTemplate(
		@Qualifier("deadLetterProducerFactory") ProducerFactory<String, String> producerFactory,
		IntegrationEventCodec codec
	) {
		KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
		template.setProducerInterceptor(new SanitizingInterceptor(codec));
		return template;
	}

	private static final class SanitizingInterceptor
		implements ProducerInterceptor<String, String> {

		private final IntegrationEventCodec codec;

		private SanitizingInterceptor(IntegrationEventCodec codec) {
			this.codec = codec;
		}

		@Override
		public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
			SanitizedPayload sanitized = sanitize(record.value());
			Headers headers = OperatorAlertKafkaHeaders.copyValidatedFrameworkOwned(
				record.headers(), record.topic(), record.partition());
			return new ProducerRecord<>(
				record.topic(),
				record.partition(),
				record.timestamp(),
				sanitized.key(),
				sanitized.value(),
				headers
			);
		}

		private SanitizedPayload sanitize(String value) {
			try {
				EventEnvelope<OperatorAlertRequestedV1> envelope = codec.decode(
					value,
					OperatorAlertRequestedV1.DESCRIPTOR,
					OperatorAlertRequestedV1.class
				);
				return new SanitizedPayload(
					envelope.payload().partitionKey(), codec.encode(envelope));
			} catch (RuntimeException invalidEvent) {
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

	private record SanitizedPayload(String key, String value) {
		private static SanitizedPayload poison() {
			return new SanitizedPayload(null, SANITIZED_POISON);
		}
	}
}
