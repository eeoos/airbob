package kr.kro.airbob.messaging.outbox.configuration;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "messaging.outbox.cleanup")
public record OutboxCleanupProperties(
	@DefaultValue("P30D") Duration retention,
	@DefaultValue("PT1H") Duration fixedDelay,
	@DefaultValue("1000") int batchSize
) {

	static final Duration MINIMUM_RETENTION = Duration.ofDays(1);
	static final Duration MINIMUM_FIXED_DELAY = Duration.ofMinutes(1);
	static final int MAXIMUM_BATCH_SIZE = 10_000;

	public OutboxCleanupProperties {
		Objects.requireNonNull(retention, "retention must not be null");
		Objects.requireNonNull(fixedDelay, "fixedDelay must not be null");
		if (retention.compareTo(MINIMUM_RETENTION) < 0) {
			throw new IllegalArgumentException("retention must be at least one day");
		}
		if (fixedDelay.compareTo(MINIMUM_FIXED_DELAY) < 0) {
			throw new IllegalArgumentException("fixedDelay must be at least one minute");
		}
		if (batchSize <= 0 || batchSize > MAXIMUM_BATCH_SIZE) {
			throw new IllegalArgumentException("batchSize must be between 1 and 10000");
		}
	}
}
