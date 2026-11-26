package kr.kro.airbob.core.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import kr.kro.airbob.AirbobApplication;
import kr.kro.airbob.core.domain.search.messaging.kafka.AccommodationSearchKafkaConsumerConfiguration;
import kr.kro.airbob.core.support.messaging.event.IntegrationEventCodec;
import kr.kro.airbob.core.support.messaging.kafka.MessagingKafkaConfiguration;

@DisplayName("스케줄러 설정 테스트")
class SchedulingConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
		.withUserConfiguration(SchedulingConfiguration.class);
	private final ApplicationContextRunner profileContextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(SchedulingConfiguration.class);
	private final ApplicationContextRunner integratedSmokeContextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withPropertyValues(
			"spring.profiles.active=aws,test",
			"spring.kafka.listener.auto-startup=true",
			"operator-alert.kafka.auto-startup=true",
			"accommodation.indexing.kafka.auto-startup=true",
			"accommodation.detail-cache.invalidation.kafka.auto-startup=true")
		.withUserConfiguration(
			SchedulingConfiguration.class,
			MessagingKafkaConfiguration.class,
			AccommodationSearchKafkaConsumerConfiguration.class)
		.withBean(KafkaProperties.class, KafkaProperties::new)
		.withBean(
			ConcurrentKafkaListenerContainerFactoryConfigurer.class,
			() -> mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class))
		.withBean(IntegrationEventCodec.class, () -> mock(IntegrationEventCodec.class));

	@Test
	@DisplayName("일반 프로필에서는 스케줄러를 활성화한다")
	void defaultProfileEnablesScheduling() {
		new ApplicationContextRunner()
			.withUserConfiguration(SchedulingConfiguration.class)
			.run(context -> {
				assertThat(context).hasSingleBean(SchedulingConfiguration.class);
				assertThat(context)
					.hasBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor");
			});
	}

	@Test
	@DisplayName("애플리케이션 진입점은 스케줄러를 전역 활성화하지 않는다")
	void applicationEntryPointDoesNotEnableScheduling() {
		assertThat(AirbobApplication.class.isAnnotationPresent(EnableScheduling.class)).isFalse();
	}

	@Test
	@DisplayName("test 프로필에서는 스케줄러를 활성화하지 않는다")
	void doesNotEnableSchedulingInTestProfile() {
		contextRunner.run(context -> assertThat(context).doesNotHaveBean(SchedulingConfiguration.class));
	}

	@Test
	@DisplayName("integrated-smoke 안전 프로필은 스케줄러만 제외하고 Kafka 구성을 유지한다")
	void integratedSmokeSafetyProfileDisablesSchedulingButRetainsKafka() {
		integratedSmokeContextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getEnvironment().getActiveProfiles())
				.containsExactly("aws", "test");
			assertThat(context.getEnvironment().getProperty(
				"spring.kafka.listener.auto-startup", Boolean.class)).isTrue();
			assertThat(context.getEnvironment().getProperty(
				"operator-alert.kafka.auto-startup", Boolean.class)).isTrue();
			assertThat(context.getEnvironment().getProperty(
				"accommodation.indexing.kafka.auto-startup", Boolean.class)).isTrue();
			assertThat(context.getEnvironment().getProperty(
				"accommodation.detail-cache.invalidation.kafka.auto-startup", Boolean.class))
				.isTrue();
			assertThat(context).doesNotHaveBean(SchedulingConfiguration.class);
			assertThat(context).hasSingleBean(MessagingKafkaConfiguration.class);
			assertThat(context)
				.hasSingleBean(AccommodationSearchKafkaConsumerConfiguration.class);
			assertThat(context).hasBean(
				AccommodationSearchKafkaConsumerConfiguration.CONTAINER_FACTORY);
		});
	}

	@Test
	@DisplayName("운영 프로필에서는 스케줄러와 작업별 풀을 유지한다")
	void enablesSchedulingInProductionProfile() {
		profileContextRunner
			.withPropertyValues("spring.profiles.active=aws")
			.run(context -> {
				assertThat(context).hasSingleBean(SchedulingConfiguration.class);
				assertThat(context).hasBean(SchedulingConfiguration.DEFAULT_TASK_SCHEDULER);
				assertThat(context).hasBean(SchedulingConfiguration.RESERVATION_CLEANUP_TASK_SCHEDULER);
				assertThat(context).hasBean(SchedulingConfiguration.RESERVATION_QUOTE_CLEANUP_TASK_SCHEDULER);
				ThreadPoolTaskScheduler defaultScheduler = context.getBean(
					SchedulingConfiguration.DEFAULT_TASK_SCHEDULER,
					ThreadPoolTaskScheduler.class);
				ThreadPoolTaskScheduler scheduler = context.getBean(
					SchedulingConfiguration.RESERVATION_CLEANUP_TASK_SCHEDULER,
					ThreadPoolTaskScheduler.class);
				ThreadPoolTaskScheduler quoteScheduler = context.getBean(
					SchedulingConfiguration.RESERVATION_QUOTE_CLEANUP_TASK_SCHEDULER,
					ThreadPoolTaskScheduler.class);
				assertThat(defaultScheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
					.isEqualTo(4);
				assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
				assertThat(quoteScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
			});
	}
}
