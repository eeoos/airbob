package kr.kro.airbob.core.support.messaging.outbox.application;

import java.time.Instant;

public interface OutboxCleanupRepository {

	int deleteOldestBefore(Instant cutoffExclusive, int batchSize);
}
