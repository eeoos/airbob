package kr.kro.airbob.messaging.outbox.monitoring;

import java.time.Clock;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxHealthRefreshScheduler {

	private final OutboxHealthSnapshotRepository repository;
	private final OutboxHealthMetrics metrics;
	private final Clock clock;

	public OutboxHealthRefreshScheduler(
		OutboxHealthSnapshotRepository repository,
		OutboxHealthMetrics metrics,
		Clock clock
	) {
		this.repository = repository;
		this.metrics = metrics;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${messaging.outbox.health.fixed-delay:30s}")
	public void refreshHealthSnapshot() {
		Instant observedAt = clock.instant();
		try {
			metrics.recordSuccess(repository.readSnapshot(observedAt));
		} catch (RuntimeException exception) {
			metrics.recordFailure();
			throw exception;
		}
	}
}
