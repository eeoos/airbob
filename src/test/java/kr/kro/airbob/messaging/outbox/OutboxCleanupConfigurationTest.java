package kr.kro.airbob.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutboxCleanupConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(OutboxCleanupConfiguration.class)
		.withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
		.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
		.withBean(Clock.class, Clock::systemUTC);

	@Test
	@DisplayName("cleanup은 명시적으로 활성화하지 않으면 스케줄러를 만들지 않는다")
	void cleanupIsDisabledByDefault() {
		contextRunner.run(context -> {
			assertThat(context).doesNotHaveBean(OutboxCleanupScheduler.class);
			assertThat(context).doesNotHaveBean(OutboxCleanupService.class);
		});
	}

	@Test
	@DisplayName("cleanup을 활성화하면 보수적인 기본값으로 구성한다")
	void enabledCleanupUsesSafeDefaults() {
		contextRunner
			.withPropertyValues("messaging.outbox.cleanup.enabled=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(OutboxCleanupScheduler.class);

				OutboxCleanupProperties properties = context.getBean(OutboxCleanupProperties.class);
				assertThat(properties.retention()).isEqualTo(Duration.ofDays(30));
				assertThat(properties.fixedDelay()).isEqualTo(Duration.ofHours(1));
				assertThat(properties.batchSize()).isEqualTo(1_000);
			});
	}

	@Test
	@DisplayName("활성화된 cleanup은 위험한 보관 기간을 거부한다")
	void enabledCleanupRejectsUnsafeRetention() {
		contextRunner
			.withPropertyValues(
				"messaging.outbox.cleanup.enabled=true",
				"messaging.outbox.cleanup.retention=PT23H"
			)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("활성화된 cleanup은 과도한 배치 크기를 거부한다")
	void enabledCleanupRejectsUnboundedBatch() {
		contextRunner
			.withPropertyValues(
				"messaging.outbox.cleanup.enabled=true",
				"messaging.outbox.cleanup.batch-size=10001"
			)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("활성화된 cleanup은 지나치게 짧은 실행 간격을 거부한다")
	void enabledCleanupRejectsUnsafeFixedDelay() {
		contextRunner
			.withPropertyValues(
				"messaging.outbox.cleanup.enabled=true",
				"messaging.outbox.cleanup.fixed-delay=PT59S"
			)
			.run(context -> assertThat(context).hasFailed());
	}
}
