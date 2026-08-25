package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@DisplayName("스케줄러 설정 테스트")
class SchedulingConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
		.withUserConfiguration(SchedulingConfig.class);
	private final ApplicationContextRunner profileContextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(SchedulingConfig.class);

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
