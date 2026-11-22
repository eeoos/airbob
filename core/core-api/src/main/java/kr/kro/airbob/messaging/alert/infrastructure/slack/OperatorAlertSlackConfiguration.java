package kr.kro.airbob.messaging.alert.infrastructure.slack;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kr.kro.airbob.client.slack.SlackWebhookClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperatorAlertSlackProperties.class)
public class OperatorAlertSlackConfiguration {

	private final OperatorAlertSlackProperties properties;

	public OperatorAlertSlackConfiguration(OperatorAlertSlackProperties properties) {
		this.properties = properties;
	}

	@Bean
	public SlackWebhookClient operatorAlertSlackWebhookClient() {
		return SlackWebhookClient.create(properties.connectTimeout(), properties.readTimeout());
	}
}
