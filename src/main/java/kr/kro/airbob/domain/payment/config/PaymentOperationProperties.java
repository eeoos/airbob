package kr.kro.airbob.domain.payment.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.operation")
public record PaymentOperationProperties(
	Duration leaseDuration,
	Duration schedulerDelay,
	int batchSize,
	int maxAttempts,
	Duration retryInitialDelay,
	Duration retryMaxDelay
) {
	public PaymentOperationProperties {
		requirePositive(leaseDuration, "leaseDuration");
		requirePositive(schedulerDelay, "schedulerDelay");
		requirePositive(batchSize, "batchSize");
		requirePositive(maxAttempts, "maxAttempts");
		requirePositive(retryInitialDelay, "retryInitialDelay");
		requirePositive(retryMaxDelay, "retryMaxDelay");
		if (retryInitialDelay.compareTo(retryMaxDelay) > 0) {
			throw new IllegalArgumentException("retryInitialDelay must not exceed retryMaxDelay");
		}
	}

	private static void requirePositive(Duration value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}

	private static void requirePositive(int value, String name) {
		if (value <= 0) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}
}
