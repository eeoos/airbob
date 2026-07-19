package kr.kro.airbob.domain.recentlyViewed.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
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

	@Test
	@DisplayName("N+1 측정 프로필은 재현 API를 열고 batch fetch를 비활성화한다")
	void nplus1BenchmarkProfileIsolatesTheAddressNPlusOneBaseline() throws IOException {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
			"nplus1-benchmark",
			new ClassPathResource("application-nplus1-benchmark.yaml")
		);
		MutablePropertySources propertySources = new MutablePropertySources();
		sources.forEach(propertySources::addLast);
		PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(propertySources);

		assertThat(resolver.getProperty("benchmark.nplus1.enabled", Boolean.class)).isTrue();
		assertThat(resolver.getProperty(
			"spring.jpa.properties.hibernate.default_batch_fetch_size",
			Integer.class
		)).isZero();
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
