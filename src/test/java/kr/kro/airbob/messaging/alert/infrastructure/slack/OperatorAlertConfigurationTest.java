package kr.kro.airbob.messaging.alert.infrastructure.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;

class OperatorAlertConfigurationTest {

	@Test
	void usesDedicatedPropertiesAndRequiresPositiveTimeouts() {
		ConfigurationProperties annotation = OperatorAlertSlackProperties.class
			.getAnnotation(ConfigurationProperties.class);

		assertThat(annotation.prefix()).isEqualTo("operator-alert.slack");
		assertThatThrownBy(() -> new OperatorAlertSlackProperties(
			true, "https://hooks.slack.test", Duration.ZERO, Duration.ofSeconds(1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OperatorAlertSlackProperties(
			true, "https://hooks.slack.test", Duration.ofSeconds(1), Duration.ofSeconds(-1)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void productionIsWiredByDefaultWhileTestsDisableDelivery() throws Exception {
		String mainYaml;
		try (var stream = getClass().getResourceAsStream("/application.yaml")) {
			assertThat(stream).isNotNull();
			mainYaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		String testYaml = java.nio.file.Files.readString(
			java.nio.file.Path.of("src/test/resources/application-test.yaml"));

		assertThat(mainYaml)
			.contains(
				"auto-startup: ${OPERATOR_ALERT_KAFKA_AUTO_STARTUP:true}",
				"enabled: ${OPERATOR_ALERT_SLACK_ENABLED:true}",
				"webhook-url: ${OPERATOR_ALERT_SLACK_WEBHOOK_URL:${SLACK_WEBHOOK_URL:}}",
				"connect-timeout: ${OPERATOR_ALERT_SLACK_CONNECT_TIMEOUT:2s}",
				"read-timeout: ${OPERATOR_ALERT_SLACK_READ_TIMEOUT:10s}")
			.doesNotContain("OPERATOR_ALERT_SLACK_WEBHOOK_URL:http");
		assertThat(testYaml)
			.contains("operator-alert:", "auto-startup: false", "enabled: false");
	}
}
