package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class PerformanceLabProfileConfigurationTest {

	@Test
	void performanceLabDisablesExternalIntegrationsWithoutProductionEndpointsOrPlaceholders() throws IOException {
		EnumerablePropertySource<?> properties = loadPerformanceLabProperties();

		assertThat(properties.getProperty("payment.toss.enabled")).isEqualTo(false);
		assertThat(properties.getProperty("google.api.enabled")).isEqualTo(false);
		assertThat(properties.getProperty("slack.notification.enabled")).isEqualTo(false);
		assertThat(properties.getProperty("cloud.aws.s3.write-enabled")).isEqualTo(false);
		assertThat(propertyValues(properties))
			.noneMatch(value -> value.contains("tosspayments.com")
				|| value.contains("googleapis.com")
				|| value.contains("slack.com")
				|| value.contains("${"));
	}

	private EnumerablePropertySource<?> loadPerformanceLabProperties() throws IOException {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
			"application-performance-lab.yaml",
			new ClassPathResource("application-performance-lab.yaml"));
		return (EnumerablePropertySource<?>) sources.getFirst();
	}

	private List<String> propertyValues(EnumerablePropertySource<?> properties) {
		return Arrays.stream(properties.getPropertyNames())
			.map(properties::getProperty)
			.map(String::valueOf)
			.toList();
	}
}
