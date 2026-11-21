package kr.kro.airbob.messaging.outbox.monitoring;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import kr.kro.airbob.messaging.outbox.application.OutboxCleanupResult;

public class OutboxCleanupMetrics {

	public static final String LAST_SUCCESS_EPOCH_SECONDS =
		"airbob.messaging.outbox.cleanup.last.success.epoch.seconds";
	public static final String LAST_DELETED_COUNT =
		"airbob.messaging.outbox.cleanup.last.deleted.count";
	public static final String FAILURE_COUNT = "airbob.messaging.outbox.cleanup.failures";

	private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
	private final AtomicLong lastDeletedCount = new AtomicLong();
	private final Counter failureCount;

	public OutboxCleanupMetrics(MeterRegistry meterRegistry) {
		Gauge.builder(LAST_SUCCESS_EPOCH_SECONDS, lastSuccessEpochSeconds, AtomicLong::get)
			.description("Epoch second of the last successful outbox cleanup tick")
			.register(meterRegistry);
		Gauge.builder(LAST_DELETED_COUNT, lastDeletedCount, AtomicLong::get)
			.description("Number of outbox messages deleted by the last successful tick")
			.register(meterRegistry);
		failureCount = Counter.builder(FAILURE_COUNT)
			.description("Number of failed outbox cleanup ticks")
			.register(meterRegistry);
	}

	public void recordSuccess(OutboxCleanupResult result) {
		lastDeletedCount.set(result.deletedCount());
		lastSuccessEpochSeconds.set(result.observedAt().getEpochSecond());
	}

	public void recordFailure() {
		failureCount.increment();
	}
}
