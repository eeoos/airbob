package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("스케줄러 설정 테스트")
class SchedulingConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
		.withUserConfiguration(SchedulingConfig.class);

	@Test
	@DisplayName("test 프로필에서는 스케줄러를 활성화하지 않는다")
	void doesNotEnableSchedulingInTestProfile() {
		contextRunner.run(context -> assertThat(context).doesNotHaveBean(SchedulingConfig.class));
	}
}
