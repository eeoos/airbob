package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("개발 프로필 모니터링 설정 테스트")
class DevMetricsConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withPropertyValues("spring.profiles.active=dev");

	@Test
	@DisplayName("개발 프로필은 HTTP 서버 요청의 Prometheus percentile histogram을 활성화한다")
	void devProfileEnablesHttpServerRequestHistogram() {
		contextRunner.run(context -> assertThat(context.getEnvironment().getProperty(
			"management.metrics.distribution.percentiles-histogram.http.server.requests",
			Boolean.class
		)).isTrue());
	}
}
