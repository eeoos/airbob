package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@DisplayName("Kafka DLQ 설정 테스트")
class KafkaConfigTest {

	@Test
	@DisplayName("DLQ 원문 JSON 문자열을 다시 JSON 문자열로 감싸지 않는다")
	void usesStringSerializerForDlqValues() {
		KafkaConfig config = new KafkaConfig();

		DefaultKafkaProducerFactory<String, String> producerFactory =
			(DefaultKafkaProducerFactory<String, String>)config.deadLetterProducerFactory(
				new KafkaProperties());

		assertThat(producerFactory.getConfigurationProperties()
			.get(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG))
			.isEqualTo(StringSerializer.class);
	}

	@Test
	@DisplayName("테스트 프로필에서도 비차단 재시도용 스케줄러를 제공한다")
	void providesDedicatedRetryTopicScheduler() {
		KafkaConfig config = new KafkaConfig();

		RetryTopicSchedulerWrapper schedulerWrapper = config.retryTopicScheduler();

		assertThat(schedulerWrapper.getScheduler())
			.isInstanceOf(ThreadPoolTaskScheduler.class);
		assertThat(((ThreadPoolTaskScheduler)schedulerWrapper.getScheduler()).getThreadNamePrefix())
			.isEqualTo("kafka-retry-");
	}
}
