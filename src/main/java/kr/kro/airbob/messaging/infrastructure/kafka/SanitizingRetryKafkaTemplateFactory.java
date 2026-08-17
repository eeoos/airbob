package kr.kro.airbob.messaging.infrastructure.kafka;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import kr.kro.airbob.messaging.event.EventDescriptor;
import kr.kro.airbob.messaging.event.EventEnvelope;
import kr.kro.airbob.messaging.event.IntegrationEvent;
import kr.kro.airbob.messaging.event.IntegrationEventCodec;

public class SanitizingRetryKafkaTemplateFactory {

	public static final String SANITIZED_POISON = "{\"event_type\":\"UNKNOWN\",\"payload\":{}}";

	private final ProducerFactory<String, String> producerFactory;
	private final IntegrationEventCodec codec;

	public SanitizingRetryKafkaTemplateFactory(
		ProducerFactory<String, String> producerFactory,
		IntegrationEventCodec codec
	) {
		this.producerFactory = producerFactory;
		this.codec = codec;
	}

	public <T extends IntegrationEvent> KafkaTemplate<String, String> create(
		EventDescriptor descriptor,
		Class<T> eventType,
		Function<T, String> keyExtractor
	) {
		KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
		template.setProducerInterceptor(new SanitizingInterceptor<>(
			codec,
			Objects.requireNonNull(descriptor),
			Objects.requireNonNull(eventType),
			Objects.requireNonNull(keyExtractor)));
		return template;
	}

	private static final class SanitizingInterceptor<T extends IntegrationEvent>
		implements ProducerInterceptor<String, String> {

		private final IntegrationEventCodec codec;
		private final EventDescriptor descriptor;
		private final Class<T> eventType;
		private final Function<T, String> keyExtractor;

		private SanitizingInterceptor(
			IntegrationEventCodec codec,
			EventDescriptor descriptor,
			Class<T> eventType,
			Function<T, String> keyExtractor
		) {
			this.codec = codec;
			this.descriptor = descriptor;
			this.eventType = eventType;
			this.keyExtractor = keyExtractor;
		}

		@Override
		public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
			SanitizedPayload sanitized = sanitize(record.value());
			Headers headers = KafkaRetryHeaders.copyValidatedFrameworkOwned(
				record.headers(), record.topic(), record.partition());
			return new ProducerRecord<>(
				record.topic(),
				record.partition(),
				record.timestamp(),
				sanitized.key(),
				sanitized.value(),
				headers);
		}

		private SanitizedPayload sanitize(String value) {
			try {
				EventEnvelope<T> envelope = codec.decode(value, descriptor, eventType);
				String key = keyExtractor.apply(envelope.payload());
				if (key == null || key.isBlank()) {
					return SanitizedPayload.poison();
				}
				return new SanitizedPayload(key, codec.encode(envelope));
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
