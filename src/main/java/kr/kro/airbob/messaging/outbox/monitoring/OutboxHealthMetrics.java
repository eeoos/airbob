package kr.kro.airbob.messaging.outbox.monitoring;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class OutboxHealthMetrics {

	public static final String RETAINED_ROW_COUNT = "airbob.messaging.outbox.retained.rows";
	public static final String OLDEST_AGE_SECONDS =
		"airbob.messaging.outbox.oldest.retained.age.seconds";
	public static final String LAST_SUCCESS_EPOCH_SECONDS =
		"airbob.messaging.outbox.health.refresh.last.success.epoch.seconds";
	public static final String FAILURE_COUNT =
		"airbob.messaging.outbox.health.refresh.failures";

	private final AtomicLong retainedRowCount = new AtomicLong();
	private final AtomicLong oldestAgeSeconds = new AtomicLong();
	private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
	private final Counter failures;

	public OutboxHealthMetrics(MeterRegistry meterRegistry) {
		Gauge.builder(RETAINED_ROW_COUNT, retainedRowCount, AtomicLong::get)
			.description("Retention footprint: rows currently retained in the transactional outbox")
			.register(meterRegistry);
		Gauge.builder(OLDEST_AGE_SECONDS, oldestAgeSeconds, AtomicLong::get)
			.description("Retention footprint: age in seconds of the oldest retained outbox row")
			.register(meterRegistry);
		Gauge.builder(LAST_SUCCESS_EPOCH_SECONDS, lastSuccessEpochSeconds, AtomicLong::get)
			.description("Epoch second of the last successful outbox health refresh")
			.register(meterRegistry);
		failures = Counter.builder(FAILURE_COUNT)
			.description("Number of failed outbox health refreshes")
			.register(meterRegistry);
	}

	public void recordSuccess(OutboxHealthSnapshot snapshot) {
		retainedRowCount.set(snapshot.retainedRowCount());
		long oldestAge = snapshot.oldestOccurredAt()
			.map(oldest -> Math.max(
				0L, Duration.between(oldest, snapshot.observedAt()).getSeconds()))
			.orElse(0L);
		oldestAgeSeconds.set(oldestAge);
		lastSuccessEpochSeconds.set(snapshot.observedAt().getEpochSecond());
	}

	public void recordFailure() {
		failures.increment();
	}
}
