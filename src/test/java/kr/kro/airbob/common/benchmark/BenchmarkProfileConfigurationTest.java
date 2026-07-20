package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

@DisplayName("벤치마크 프로필 공통 접근 설정 테스트")
class BenchmarkProfileConfigurationTest {

	@Test
	@DisplayName("N+1과 read-model 프로필은 동일한 read-model 활성화 및 토큰 설정을 사용한다")
	void benchmarkProfilesUseTheSameReadModelSettings() throws IOException {
		assertReadModelSettings("application-nplus1-benchmark.yaml");
		assertReadModelSettings("application-read-model-benchmark.yaml");
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
}
