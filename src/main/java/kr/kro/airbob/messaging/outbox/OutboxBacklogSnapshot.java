package kr.kro.airbob.messaging.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record OutboxBacklogSnapshot(
	long messageCount,
	Optional<Instant> oldestOccurredAt
) {

	public OutboxBacklogSnapshot {
		if (messageCount < 0) {
			throw new IllegalArgumentException("messageCount must not be negative");
		}
		Objects.requireNonNull(oldestOccurredAt, "oldestOccurredAt must not be null");
		if (messageCount == 0 && oldestOccurredAt.isPresent()) {
			throw new IllegalArgumentException("an empty backlog must not have an oldest event");
		}
		if (messageCount > 0 && oldestOccurredAt.isEmpty()) {
			throw new IllegalArgumentException("a non-empty backlog must have an oldest event");
		}
	}
}
