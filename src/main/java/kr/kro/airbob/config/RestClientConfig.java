package kr.kro.airbob.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

	public static final String BASIC_DELIMITER = ":";
	public static final String AUTH_HEADER_PREFIX = "Basic ";

	@Value("${payment.toss.secret-key}")
	private String tossSecretKey;

	@Value("${payment.toss.base-url}")
	private String baseUrl;

	@Bean
	public RestClient tossPaymentRestClient() {
		String encodedAuth = Base64.getEncoder()
			.encodeToString((tossSecretKey + BASIC_DELIMITER).getBytes(StandardCharsets.UTF_8));

		return RestClient.builder()
			.baseUrl(baseUrl)
			.defaultHeader(HttpHeaders.AUTHORIZATION, AUTH_HEADER_PREFIX + encodedAuth)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.build();
	}
}
