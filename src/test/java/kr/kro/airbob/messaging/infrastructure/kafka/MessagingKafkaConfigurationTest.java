package kr.kro.airbob.messaging.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import kr.kro.airbob.messaging.event.IntegrationEventCodec;

@DisplayName("공통 integration-event Kafka 설정")
class MessagingKafkaConfigurationTest {

	private final MessagingKafkaConfiguration configuration =
		new MessagingKafkaConfiguration();
	private final ApplicationContextRunner profileContextRunner = new ApplicationContextRunner()
		.withUserConfiguration(MessagingKafkaConfiguration.class)
		.withBean(KafkaProperties.class, KafkaProperties::new)
		.withBean(
			ConcurrentKafkaListenerContainerFactoryConfigurer.class,
			() -> mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class))
		.withBean(IntegrationEventCodec.class, () -> mock(IntegrationEventCodec.class));

	@Test
	void usesStringSerializersForCanonicalEventJson() {
		DefaultKafkaProducerFactory<String, String> producerFactory =
			(DefaultKafkaProducerFactory<String, String>)configuration
				.integrationEventProducerFactory(new KafkaProperties());

		assertThat(producerFactory.getConfigurationProperties())
			.containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
			.containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
	}

	@Test
	void providesDedicatedRetryTopicScheduler() {
		RetryTopicSchedulerWrapper schedulerWrapper = configuration.kafkaRetryTopicScheduler();

		assertThat(schedulerWrapper.getScheduler()).isInstanceOf(ThreadPoolTaskScheduler.class);
		assertThat(((ThreadPoolTaskScheduler)schedulerWrapper.getScheduler()).getThreadNamePrefix())
			.isEqualTo("kafka-retry-");
	}

	@Test
	void excludesSharedKafkaInfrastructureFromTrafficBenchmark() {
		profileContextRunner
			.withInitializer(context -> context.getEnvironment()
				.setActiveProfiles("traffic-benchmark"))
			.run(context -> assertThat(context)
				.doesNotHaveBean(MessagingKafkaConfiguration.class));
	}

	@Test
	void retainsSharedKafkaInfrastructureInPerformanceLab() {
		profileContextRunner
			.withInitializer(context -> context.getEnvironment()
				.setActiveProfiles("performance-lab"))
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(MessagingKafkaConfiguration.class);
			});
	}
}
