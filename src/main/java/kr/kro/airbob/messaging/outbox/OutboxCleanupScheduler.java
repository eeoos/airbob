package kr.kro.airbob.messaging.outbox;

import org.springframework.scheduling.annotation.Scheduled;

public class OutboxCleanupScheduler {

	private final OutboxCleanupService service;
	private final OutboxCleanupMetrics metrics;

	public OutboxCleanupScheduler(
		OutboxCleanupService service,
		OutboxCleanupMetrics metrics
	) {
		this.service = service;
		this.metrics = metrics;
	}

	@Scheduled(fixedDelayString = "${messaging.outbox.cleanup.fixed-delay:PT1H}")
	public void cleanupExpiredMessages() {
		try {
			metrics.recordSuccess(service.cleanupOneBatch());
		} catch (RuntimeException exception) {
			metrics.recordFailure();
			throw exception;
		}
	}
}
