package kr.kro.airbob.domain.payment.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentOperationHealthRefreshScheduler {

	private final PaymentOperationHealthSnapshotRepository repository;
	private final PaymentOperationHealthMetrics metrics;
	private final Clock clock;
	private final Duration staleQueuedAfter;

	public PaymentOperationHealthRefreshScheduler(
		PaymentOperationHealthSnapshotRepository repository,
		PaymentOperationHealthMetrics metrics,
		Clock clock,
		@Value("${payment.operation.monitoring.stale-queued-after:30s}")
		Duration staleQueuedAfter
	) {
		if (staleQueuedAfter == null || staleQueuedAfter.isZero() || staleQueuedAfter.isNegative()) {
			throw new IllegalArgumentException("staleQueuedAfter must be positive");
		}
		this.repository = repository;
		this.metrics = metrics;
		this.clock = clock;
		this.staleQueuedAfter = staleQueuedAfter;
	}

	@Scheduled(fixedDelayString = "${payment.operation.monitoring.fixed-delay:30s}")
	public void refreshHealthSnapshot() {
		Instant observedAt = clock.instant();
		try {
			metrics.recordSuccess(repository.readSnapshot(observedAt, staleQueuedAfter));
		} catch (RuntimeException exception) {
			metrics.recordFailure();
			throw exception;
		}
	}
}
