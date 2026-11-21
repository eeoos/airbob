package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import kr.kro.airbob.domain.payment.config.TossPaymentClientProperties;

class RestClientConfigTest {

	@Test
	void applicationDefaultsBoundTossDeadlines() throws Exception {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader()
			.load("application", new ClassPathResource("application.yaml"));
		MockEnvironment environment = new MockEnvironment();
		sources.forEach(environment.getPropertySources()::addLast);

		assertThat(DurationStyle.detectAndParse(environment.getProperty("payment.toss.connect-timeout")))
			.isEqualTo(Duration.ofSeconds(2));
		assertThat(DurationStyle.detectAndParse(environment.getProperty("payment.toss.read-timeout")))
			.isEqualTo(Duration.ofSeconds(10));
	}

	@Test
	void tossRestClientUsesConfiguredConnectAndReadDeadlines() {
		TossPaymentClientProperties properties = new TossPaymentClientProperties(
			"test-secret",
			"https://api.example.com",
			Duration.ofSeconds(2),
			Duration.ofSeconds(10)
		);
		RestClient restClient = new RestClientConfig(properties).tossPaymentRestClient();

		Object requestFactory = ReflectionTestUtils.getField(restClient, "clientRequestFactory");
		assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);

		HttpClient httpClient = (HttpClient)ReflectionTestUtils.getField(requestFactory, "httpClient");
		Duration readTimeout = (Duration)ReflectionTestUtils.getField(requestFactory, "readTimeout");
		assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(2));
		assertThat(readTimeout).isEqualTo(Duration.ofSeconds(10));
	}
}
