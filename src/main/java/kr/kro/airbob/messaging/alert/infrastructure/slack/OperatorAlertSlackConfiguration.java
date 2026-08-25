package kr.kro.airbob.messaging.alert.infrastructure.slack;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperatorAlertSlackProperties.class)
public class OperatorAlertSlackConfiguration {

	private final OperatorAlertSlackProperties properties;

	public OperatorAlertSlackConfiguration(OperatorAlertSlackProperties properties) {
		this.properties = properties;
	}

	@Bean(name = "operatorAlertRestClient")
	public RestClient operatorAlertRestClient() {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory =
			new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());

		return RestClient.builder()
			.requestFactory(requestFactory)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.build();
	}
}
