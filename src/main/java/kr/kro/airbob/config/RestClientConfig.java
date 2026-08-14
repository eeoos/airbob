package kr.kro.airbob.config;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import kr.kro.airbob.domain.payment.config.TossPaymentClientProperties;

@Configuration
@EnableConfigurationProperties(TossPaymentClientProperties.class)
public class RestClientConfig {

	public static final String BASIC_DELIMITER = ":";
	public static final String AUTH_HEADER_PREFIX = "Basic ";

	private final TossPaymentClientProperties tossProperties;

	public RestClientConfig(TossPaymentClientProperties tossProperties) {
		this.tossProperties = tossProperties;
	}

	@Bean(name = "tossPaymentRestClient")
	public RestClient tossPaymentRestClient() {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(tossProperties.connectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(tossProperties.readTimeout());

		return RestClient.builder()
			.requestFactory(requestFactory)
			.baseUrl(tossProperties.baseUrl())
			.defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(tossProperties.secretKey()))
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.build();
	}

	private String basicAuth(String secretKey) {
		String encodedAuth = Base64.getEncoder()
			.encodeToString((secretKey + BASIC_DELIMITER).getBytes(StandardCharsets.UTF_8));
		return AUTH_HEADER_PREFIX + encodedAuth;
	}

	@Bean(name = "generalRestClient")
	public RestClient generalRestClient() {
		return RestClient.create();
	}
}
