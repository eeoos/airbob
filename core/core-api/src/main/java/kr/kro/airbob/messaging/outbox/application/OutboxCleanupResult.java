package kr.kro.airbob.messaging.outbox.application;

import java.time.Instant;
import java.util.Objects;

public record OutboxCleanupResult(
	int deletedCount,
	Instant observedAt
) {

	public OutboxCleanupResult {
		if (deletedCount < 0) {
			throw new IllegalArgumentException("deletedCount must not be negative");
		}
		Objects.requireNonNull(observedAt, "observedAt must not be null");
	}
}
