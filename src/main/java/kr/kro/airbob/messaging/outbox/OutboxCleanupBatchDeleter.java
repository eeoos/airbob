package kr.kro.airbob.messaging.outbox;

import java.time.Instant;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class OutboxCleanupBatchDeleter {

	private final OutboxCleanupRepository repository;

	public OutboxCleanupBatchDeleter(OutboxCleanupRepository repository) {
		this.repository = repository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int deleteOneBatch(Instant cutoffExclusive, int batchSize) {
		return repository.deleteOldestBefore(cutoffExclusive, batchSize);
	}
}
