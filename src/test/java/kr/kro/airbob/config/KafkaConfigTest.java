package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@DisplayName("Kafka DLQ 설정 테스트")
class KafkaConfigTest {

	private final ApplicationContextRunner profileContextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withBean(KafkaProperties.class, KafkaProperties::new)
		.withUserConfiguration(KafkaConfig.class);

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

	@Test
	@DisplayName("traffic-benchmark 프로필에서는 Kafka 설정을 만들지 않는다")
	void doesNotCreateKafkaConfigInTrafficBenchmarkProfile() {
		profileContextRunner
			.withPropertyValues("spring.profiles.active=traffic-benchmark")
			.run(context -> assertThat(context).doesNotHaveBean(KafkaConfig.class));
	}

	@Test
	@DisplayName("performance-lab 프로필만 활성화하면 Kafka 설정을 유지한다")
	void createsKafkaConfigInPerformanceLabProfile() {
		profileContextRunner
			.withPropertyValues("spring.profiles.active=performance-lab")
			.run(context -> assertThat(context).hasSingleBean(KafkaConfig.class));
	}
}
