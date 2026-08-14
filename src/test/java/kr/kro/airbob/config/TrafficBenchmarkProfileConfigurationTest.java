package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

@DisplayName("읽기 트래픽 벤치마크 프로필 설정 테스트")
class TrafficBenchmarkProfileConfigurationTest {

	@Test
	@DisplayName("traffic-benchmark 프로필은 performance-lab을 함께 활성화한다")
	void includesPerformanceLabProfile() throws IOException {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
			"application.yaml",
			new ClassPathResource("application.yaml")
		);

		assertThat(sources)
			.extracting(source -> source.getProperty("spring.profiles.group.traffic-benchmark[0]"))
			.contains("performance-lab");
	}

	@Test
	@DisplayName("traffic-benchmark 프로필은 Kafka listener를 자동 시작하지 않는다")
	void disablesKafkaListenerAutoStartup() throws IOException {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
			"application-traffic-benchmark.yaml",
			new ClassPathResource("application-traffic-benchmark.yaml")
		);

		assertThat(sources)
			.extracting(source -> source.getProperty("spring.kafka.listener.auto-startup"))
			.contains(false);
	}
}
