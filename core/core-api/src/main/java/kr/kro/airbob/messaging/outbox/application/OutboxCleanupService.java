package kr.kro.airbob.messaging.outbox.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public class OutboxCleanupService {

	private final OutboxCleanupBatchDeleter batchDeleter;
	private final Duration retention;
	private final int batchSize;
	private final Clock clock;

	public OutboxCleanupService(
		OutboxCleanupBatchDeleter batchDeleter,
		Duration retention,
		int batchSize,
		Clock clock
	) {
		this.batchDeleter = Objects.requireNonNull(batchDeleter, "batchDeleter must not be null");
		this.retention = Objects.requireNonNull(retention, "retention must not be null");
		if (batchSize <= 0) {
			throw new IllegalArgumentException("batchSize must be positive");
		}
		this.batchSize = batchSize;
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	public OutboxCleanupResult cleanupOneBatch() {
		Instant startedAt = clock.instant();
		Instant cutoffExclusive = startedAt.minus(retention);
		int deletedCount = batchDeleter.deleteOneBatch(cutoffExclusive, batchSize);
		return new OutboxCleanupResult(deletedCount, clock.instant());
	}
}
