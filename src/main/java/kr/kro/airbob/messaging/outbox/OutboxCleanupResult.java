package kr.kro.airbob.messaging.outbox;

import java.time.Instant;
import java.util.Objects;

public record OutboxCleanupResult(
	int deletedCount,
	OutboxBacklogSnapshot backlog,
	Instant observedAt
) {

	public OutboxCleanupResult {
		if (deletedCount < 0) {
			throw new IllegalArgumentException("deletedCount must not be negative");
		}
		Objects.requireNonNull(backlog, "backlog must not be null");
		Objects.requireNonNull(observedAt, "observedAt must not be null");
	}
}
