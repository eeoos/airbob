package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.EnableScheduling;

import kr.kro.airbob.AirbobApplication;

@DisplayName("대량 쓰기 벤치마크 scheduling 격리 테스트")
class BulkWriteBenchmarkSchedulingTest {

	private static final String SCHEDULED_PROCESSOR_BEAN_NAME =
		"org.springframework.context.annotation.internalScheduledAnnotationProcessor";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(SchedulingConfig.class);

	@Test
	@DisplayName("일반 profile에서는 scheduling processor를 등록한다")
	void defaultProfileEnablesScheduling() {
		contextRunner.run(context -> {
			assertThat(context).hasSingleBean(SchedulingConfig.class);
			assertThat(context).hasBean(SCHEDULED_PROCESSOR_BEAN_NAME);
		});
	}

	@Test
	@DisplayName("bulk-write-benchmark profile에서는 scheduling processor를 등록하지 않는다")
	void bulkWriteBenchmarkProfileDisablesScheduling() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.run(context -> {
				assertThat(context).doesNotHaveBean(SchedulingConfig.class);
				assertThat(context).doesNotHaveBean(SCHEDULED_PROCESSOR_BEAN_NAME);
			});
	}

	@Test
	@DisplayName("애플리케이션 entry point는 scheduling을 전역 활성화하지 않는다")
	void applicationEntryPointDoesNotEnableScheduling() {
		assertThat(AirbobApplication.class.isAnnotationPresent(EnableScheduling.class)).isFalse();
	}
}
