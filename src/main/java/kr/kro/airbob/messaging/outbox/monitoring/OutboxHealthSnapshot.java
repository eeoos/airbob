package kr.kro.airbob.messaging.outbox.monitoring;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record OutboxHealthSnapshot(
	Instant observedAt,
	long retainedRowCount,
	Optional<Instant> oldestOccurredAt
) {
	public OutboxHealthSnapshot {
		Objects.requireNonNull(observedAt, "observedAt must not be null");
		Objects.requireNonNull(oldestOccurredAt, "oldestOccurredAt must not be null");
		if (retainedRowCount < 0) {
			throw new IllegalArgumentException("retainedRowCount must not be negative");
		}
		if ((retainedRowCount == 0) != oldestOccurredAt.isEmpty()) {
			throw new IllegalArgumentException("retained row count and oldest timestamp must agree");
		}
	}
}
