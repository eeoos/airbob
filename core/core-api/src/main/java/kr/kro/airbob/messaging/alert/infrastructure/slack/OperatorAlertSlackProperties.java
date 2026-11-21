package kr.kro.airbob.messaging.alert.infrastructure.slack;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "operator-alert.slack")
public record OperatorAlertSlackProperties(
	boolean enabled,
	String webhookUrl,
	Duration connectTimeout,
	Duration readTimeout
) {
	public OperatorAlertSlackProperties {
		webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
		requirePositive(connectTimeout, "connectTimeout");
		requirePositive(readTimeout, "readTimeout");
	}

	private static void requirePositive(Duration value, String fieldName) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(fieldName + " must be positive");
		}
	}

	public boolean deliveryConfigured() {
		return enabled && !webhookUrl.isBlank();
	}
}
