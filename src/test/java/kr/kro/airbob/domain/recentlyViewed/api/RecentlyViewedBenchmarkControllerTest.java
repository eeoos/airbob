package kr.kro.airbob.domain.recentlyViewed.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import kr.kro.airbob.domain.recentlyViewed.service.RecentlyViewedService;

@DisplayName("최근 본 숙소 N+1 벤치마크 API 설정 테스트")
class RecentlyViewedBenchmarkControllerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	@DisplayName("기본 설정에서는 벤치마크 API를 노출하지 않는다")
	void benchmarkApiIsDisabledByDefault() {
		contextRunner.run(context -> assertThat(context)
			.doesNotHaveBean(RecentlyViewedBenchmarkController.class));
	}

	@Test
	@DisplayName("벤치마크 설정을 활성화하면 재현 API를 노출한다")
	void benchmarkApiCanBeEnabledExplicitly() {
		contextRunner
			.withPropertyValues("benchmark.nplus1.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(RecentlyViewedBenchmarkController.class));
	}

	@Configuration(proxyBeanMethods = false)
	@Import(RecentlyViewedBenchmarkController.class)
	static class TestConfiguration {

		@Bean
		RecentlyViewedService recentlyViewedService() {
			return mock(RecentlyViewedService.class);
		}
	}
}
