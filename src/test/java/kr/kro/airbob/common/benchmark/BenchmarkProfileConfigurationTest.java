package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

@DisplayName("벤치마크 프로필 공통 접근 설정 테스트")
class BenchmarkProfileConfigurationTest {

	private final ApplicationContextRunner guardContextRunner = new ApplicationContextRunner()
		.withUserConfiguration(GuardConfiguration.class);

	@Test
	@DisplayName("N+1과 read-model 프로필은 동일한 read-model 활성화 및 토큰 설정을 사용한다")
	void benchmarkProfilesUseTheSameReadModelSettings() throws IOException {
		assertReadModelSettings("application-nplus1-benchmark.yaml");
		assertReadModelSettings("application-read-model-benchmark.yaml");
		assertReadModelSettings("application-coupon-benchmark.yaml");
	}

	@Test
	@DisplayName("공통 토큰 가드는 coupon benchmark 프로필에서도 생성된다")
	void couponBenchmarkProfileCreatesSharedGuard() {
		guardContextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("coupon-benchmark"))
			.withPropertyValues("benchmark.read-model.token=test-token")
			.run(context -> assertThat(context).hasSingleBean(BenchmarkAccessGuard.class));
	}

	private void assertReadModelSettings(String resourceName) throws IOException {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
			resourceName,
			new ClassPathResource(resourceName)
		);

		assertThat(sources)
			.extracting(source -> source.getProperty("benchmark.read-model.enabled"))
			.contains(true);
		assertThat(sources)
			.extracting(source -> source.getProperty("benchmark.read-model.token"))
			.contains("${BENCHMARK_READ_MODEL_TOKEN}");
	}

	@Configuration(proxyBeanMethods = false)
	@Import(BenchmarkAccessGuard.class)
	static class GuardConfiguration {
	}
}
