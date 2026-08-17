package kr.kro.airbob.messaging.outbox;

import java.time.Instant;

public interface OutboxCleanupRepository {

	int deleteOldestBefore(Instant cutoffExclusive, int batchSize);

	OutboxBacklogSnapshot readBacklogSnapshot();
}
