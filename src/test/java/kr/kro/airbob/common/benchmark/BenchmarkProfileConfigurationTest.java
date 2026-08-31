package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import kr.kro.airbob.config.SchedulingConfig;

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

	@Test
	@DisplayName("AWS isolated-read는 read-model API만 추가하고 writer lifecycle은 모두 끈다")
	void isolatedReadRuntimeIsReadOnly() throws IOException {
		assertProperty("application-traffic-benchmark.yaml", "spring.kafka.listener.auto-startup", false);
		assertProperty("application-performance-lab.yaml", "reservation.inventory.startup.enabled", false);
		assertProperty("application-performance-lab.yaml", "reservation.inventory.seed.enabled", false);
		assertProperty("application-performance-lab.yaml", "reservation.inventory.retention.enabled", false);
		assertProperty("application-performance-lab.yaml", "payment.toss.enabled", false);
		assertProperty("application-performance-lab.yaml", "operator-alert.slack.enabled", false);
		assertProperty("application-performance-lab.yaml", "cloud.aws.s3.write-enabled", false);

		new ApplicationContextRunner()
			.withUserConfiguration(SchedulingConfig.class)
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("traffic-benchmark"))
			.run(context -> assertThat(context).doesNotHaveBean(SchedulingConfig.class));

		String startScript = Files.readString(Path.of("infra/aws/lab/templates/start-app.sh.tftpl"));
		assertThat(startScript)
			.contains("SPRING_PROFILES_INCLUDE=read-model-benchmark")
			.contains("BENCHMARK_READ_MODEL_TOKEN=")
			.contains("chmod 600 /etc/airbob/app.env")
			.contains("grep -Ec '^SPRING_PROFILES_INCLUDE=read-model-benchmark$'")
			.contains("grep -Ec '^BENCHMARK_READ_MODEL_TOKEN=[0-9a-f]{64}$'")
			.contains("ACCOMMODATION_INDEX_BOOTSTRAP_ENABLED=false")
			.contains("ACCOMMODATION_INDEXING_AUTO_STARTUP=false")
			.contains("ACCOMMODATION_DETAIL_CACHE_INVALIDATION_AUTO_STARTUP=false")
			.contains("AIRBOB_RUNTIME_REVISION=")
			.contains("AIRBOB_APP_INSTANCE_ID=")
			.contains("rm -f /run/airbob/read-model-benchmark-token")
			.doesNotContain("SPRING_PROFILES_INCLUDE=read-model-benchmark,performance-lab");
		assertThat(startScript.indexOf("verify-app-runtime-env.sh"))
			.isLessThan(startScript.indexOf("SPRING_PROFILES_INCLUDE=read-model-benchmark"));
		assertThat(startScript.indexOf("SPRING_PROFILES_INCLUDE=read-model-benchmark"))
			.isLessThan(startScript.indexOf("grep -Ec '^SPRING_PROFILES_INCLUDE=read-model-benchmark$'"));
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

	private void assertProperty(String resourceName, String property, Object expected) throws IOException {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
			resourceName,
			new ClassPathResource(resourceName)
		);
		assertThat(sources)
			.extracting(source -> source.getProperty(property))
			.contains(expected);
	}

	@Configuration(proxyBeanMethods = false)
	@Import(BenchmarkAccessGuard.class)
	static class GuardConfiguration {
	}
}
