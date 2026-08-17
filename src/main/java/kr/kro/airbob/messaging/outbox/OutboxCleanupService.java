package kr.kro.airbob.messaging.outbox;

import java.time.Clock;
import java.time.Instant;

public class OutboxCleanupService {

	private final OutboxCleanupBatchDeleter batchDeleter;
	private final OutboxCleanupRepository repository;
	private final OutboxCleanupProperties properties;
	private final Clock clock;

	public OutboxCleanupService(
		OutboxCleanupBatchDeleter batchDeleter,
		OutboxCleanupRepository repository,
		OutboxCleanupProperties properties,
		Clock clock
	) {
		this.batchDeleter = batchDeleter;
		this.repository = repository;
		this.properties = properties;
		this.clock = clock;
	}

	public OutboxCleanupResult cleanupOneBatch() {
		Instant observedAt = clock.instant();
		Instant cutoffExclusive = observedAt.minus(properties.retention());
		int deletedCount = batchDeleter.deleteOneBatch(
			cutoffExclusive, properties.batchSize());
		OutboxBacklogSnapshot backlog = repository.readBacklogSnapshot();
		return new OutboxCleanupResult(deletedCount, backlog, observedAt);
	}
}
