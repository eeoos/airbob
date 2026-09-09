package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import kr.kro.airbob.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.messaging.infrastructure.kafka.MessagingKafkaConfiguration;
import kr.kro.airbob.search.messaging.kafka.AccommodationSearchKafkaConsumerConfiguration;

@DisplayName("스케줄러 설정 테스트")
class SchedulingConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
		.withUserConfiguration(SchedulingConfig.class);
	private final ApplicationContextRunner profileContextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(SchedulingConfig.class);
	private final ApplicationContextRunner integratedSmokeContextRunner =
		new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withPropertyValues(
				"spring.profiles.active=aws,performance-lab,test",
				"spring.kafka.listener.auto-startup=true",
				"operator-alert.kafka.auto-startup=true",
				"accommodation.indexing.kafka.auto-startup=true",
				"accommodation.detail-cache.invalidation.kafka.auto-startup=true"
			)
			.withUserConfiguration(
				SchedulingConfig.class,
				MessagingKafkaConfiguration.class,
				AccommodationSearchKafkaConsumerConfiguration.class
			)
			.withBean(KafkaProperties.class, KafkaProperties::new)
			.withBean(
				ConcurrentKafkaListenerContainerFactoryConfigurer.class,
				() -> mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class)
			)
			.withBean(IntegrationEventCodec.class, () -> mock(IntegrationEventCodec.class));

	@Test
	@DisplayName("test 프로필에서는 스케줄러를 활성화하지 않는다")
	void doesNotEnableSchedulingInTestProfile() {
		contextRunner.run(context -> assertThat(context).doesNotHaveBean(SchedulingConfig.class));
	}

	@Test
	@DisplayName("traffic-benchmark 프로필에서는 스케줄러를 활성화하지 않는다")
	void doesNotEnableSchedulingInTrafficBenchmarkProfile() {
		profileContextRunner
			.withPropertyValues("spring.profiles.active=traffic-benchmark")
			.run(context -> assertThat(context).doesNotHaveBean(SchedulingConfig.class));
	}

	@Test
	@DisplayName("integrated-smoke 안전 프로필은 스케줄러만 제외하고 Kafka 구성을 유지한다")
	void integratedSmokeSafetyProfileDisablesSchedulingButRetainsKafka() {
		integratedSmokeContextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getEnvironment().getActiveProfiles())
				.containsExactly("aws", "performance-lab", "test");
			assertThat(context.getEnvironment().getProperty(
				"spring.kafka.listener.auto-startup", Boolean.class)).isTrue();
			assertThat(context.getEnvironment().getProperty(
				"operator-alert.kafka.auto-startup", Boolean.class)).isTrue();
			assertThat(context.getEnvironment().getProperty(
				"accommodation.indexing.kafka.auto-startup", Boolean.class)).isTrue();
			assertThat(context.getEnvironment().getProperty(
				"accommodation.detail-cache.invalidation.kafka.auto-startup", Boolean.class))
				.isTrue();
			assertThat(context).doesNotHaveBean(SchedulingConfig.class);
			assertThat(context).hasSingleBean(MessagingKafkaConfiguration.class);
			assertThat(context)
				.hasSingleBean(AccommodationSearchKafkaConsumerConfiguration.class);
			assertThat(context).hasBean(
				AccommodationSearchKafkaConsumerConfiguration.CONTAINER_FACTORY);
		});
	}

	@Test
	@DisplayName("performance-lab 프로필만 활성화하면 스케줄러를 유지한다")
	void enablesSchedulingInPerformanceLabProfile() {
		profileContextRunner
			.withPropertyValues("spring.profiles.active=performance-lab")
			.run(context -> {
				assertThat(context).hasSingleBean(SchedulingConfig.class);
				assertThat(context).hasBean(SchedulingConfig.DEFAULT_TASK_SCHEDULER);
				assertThat(context).hasBean(SchedulingConfig.RESERVATION_CLEANUP_TASK_SCHEDULER);
				assertThat(context).hasBean(SchedulingConfig.RESERVATION_QUOTE_CLEANUP_TASK_SCHEDULER);
				ThreadPoolTaskScheduler defaultScheduler = context.getBean(
					SchedulingConfig.DEFAULT_TASK_SCHEDULER,
					ThreadPoolTaskScheduler.class
				);
				ThreadPoolTaskScheduler scheduler = context.getBean(
					SchedulingConfig.RESERVATION_CLEANUP_TASK_SCHEDULER,
					ThreadPoolTaskScheduler.class
				);
				ThreadPoolTaskScheduler quoteScheduler = context.getBean(
					SchedulingConfig.RESERVATION_QUOTE_CLEANUP_TASK_SCHEDULER,
					ThreadPoolTaskScheduler.class
				);
				assertThat(defaultScheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
					.isEqualTo(4);
				assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
				assertThat(quoteScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
			});
	}
}
