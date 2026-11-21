package kr.kro.airbob.domain.payment.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionAction;

@Component
public class PaymentOperationHealthMetrics {

	public static final String MANUAL_REVIEW_COUNT =
		"airbob.payment.operation.manual.review.count";
	public static final String MANUAL_REVIEW_OLDEST_AGE_SECONDS =
		"airbob.payment.operation.manual.review.oldest.age.seconds";
	public static final String RECONCILIATION_PENDING_COUNT =
		"airbob.payment.operation.reconciliation.pending.count";
	public static final String RECONCILIATION_PENDING_OLDEST_AGE_SECONDS =
		"airbob.payment.operation.reconciliation.pending.oldest.age.seconds";
	public static final String STALE_QUEUED_COUNT =
		"airbob.payment.operation.queued.stale.count";
	public static final String STALE_QUEUED_OLDEST_AGE_SECONDS =
		"airbob.payment.operation.queued.stale.oldest.age.seconds";
	public static final String EXPIRED_EXECUTING_LEASE_COUNT =
		"airbob.payment.operation.executing.lease.expired.count";
	public static final String HEALTH_LAST_SUCCESS_EPOCH_SECONDS =
		"airbob.payment.operation.health.refresh.last.success.epoch.seconds";
	public static final String HEALTH_REFRESH_FAILURE_COUNT =
		"airbob.payment.operation.health.refresh.failures";
	public static final String RESOLUTION_COUNT =
		"airbob.payment.operation.resolution.count";
	public static final String RESOLUTION_ACTION_TAG = "action";

	private final AtomicLong manualReviewCount = new AtomicLong();
	private final AtomicLong manualReviewOldestAgeSeconds = new AtomicLong();
	private final AtomicLong reconciliationPendingCount = new AtomicLong();
	private final AtomicLong reconciliationPendingOldestAgeSeconds = new AtomicLong();
	private final AtomicLong staleQueuedCount = new AtomicLong();
	private final AtomicLong staleQueuedOldestAgeSeconds = new AtomicLong();
	private final AtomicLong expiredExecutingLeaseCount = new AtomicLong();
	private final AtomicLong healthLastSuccessEpochSeconds = new AtomicLong();
	private final Map<PaymentOperationResolutionAction, AtomicLong> resolutionCounts =
		new EnumMap<>(PaymentOperationResolutionAction.class);
	private final Counter healthRefreshFailures;

	public PaymentOperationHealthMetrics(MeterRegistry meterRegistry) {
		registerGauge(meterRegistry, MANUAL_REVIEW_COUNT, manualReviewCount,
			"Number of payment operations paused for manual review");
		registerGauge(meterRegistry, MANUAL_REVIEW_OLDEST_AGE_SECONDS,
			manualReviewOldestAgeSeconds,
			"Age in seconds of the oldest payment operation paused for manual review");
		registerGauge(meterRegistry, RECONCILIATION_PENDING_COUNT, reconciliationPendingCount,
			"Number of payment operations awaiting an admin-requested provider inquiry");
		registerGauge(meterRegistry, RECONCILIATION_PENDING_OLDEST_AGE_SECONDS,
			reconciliationPendingOldestAgeSeconds,
			"Age in seconds of the oldest pending admin-requested provider inquiry");
		registerGauge(meterRegistry, STALE_QUEUED_COUNT, staleQueuedCount,
			"Number of queued payment operations older than the monitoring threshold");
		registerGauge(meterRegistry, STALE_QUEUED_OLDEST_AGE_SECONDS,
			staleQueuedOldestAgeSeconds,
			"Age in seconds of the oldest queued payment operation beyond the threshold");
		registerGauge(meterRegistry, EXPIRED_EXECUTING_LEASE_COUNT, expiredExecutingLeaseCount,
			"Number of executing payment operations with an expired lease");
		registerGauge(meterRegistry, HEALTH_LAST_SUCCESS_EPOCH_SECONDS,
			healthLastSuccessEpochSeconds,
			"Epoch second of the last successful payment operation health refresh");

		for (PaymentOperationResolutionAction action : PaymentOperationResolutionAction.values()) {
			AtomicLong count = new AtomicLong();
			resolutionCounts.put(action, count);
			FunctionCounter.builder(RESOLUTION_COUNT, count, AtomicLong::doubleValue)
				.description("Durable payment operation resolutions by closed action")
				.tag(RESOLUTION_ACTION_TAG, action.name())
				.register(meterRegistry);
		}

		healthRefreshFailures = Counter.builder(HEALTH_REFRESH_FAILURE_COUNT)
			.description("Number of failed payment operation health refreshes")
			.register(meterRegistry);
	}

	public void recordSuccess(PaymentOperationHealthSnapshot snapshot) {
		manualReviewCount.set(snapshot.manualReviewCount());
		manualReviewOldestAgeSeconds.set(ageSeconds(
			snapshot.oldestManualReviewAt().orElse(null), snapshot.observedAt()));
		reconciliationPendingCount.set(snapshot.reconciliationPendingCount());
		reconciliationPendingOldestAgeSeconds.set(ageSeconds(
			snapshot.oldestReconciliationPendingAt().orElse(null), snapshot.observedAt()));
		staleQueuedCount.set(snapshot.staleQueuedCount());
		staleQueuedOldestAgeSeconds.set(ageSeconds(
			snapshot.oldestStaleQueuedAt().orElse(null), snapshot.observedAt()));
		expiredExecutingLeaseCount.set(snapshot.expiredExecutingLeaseCount());
		for (PaymentOperationResolutionAction action : PaymentOperationResolutionAction.values()) {
			resolutionCounts.get(action).set(snapshot.resolutionCounts().getOrDefault(action, 0L));
		}
		healthLastSuccessEpochSeconds.set(snapshot.observedAt().getEpochSecond());
	}

	public void recordFailure() {
		healthRefreshFailures.increment();
	}

	private static void registerGauge(
		MeterRegistry registry,
		String name,
		AtomicLong value,
		String description
	) {
		Gauge.builder(name, value, AtomicLong::get)
			.description(description)
			.register(registry);
	}

	private static long ageSeconds(Instant oldestAt, Instant observedAt) {
		return oldestAt == null
			? 0L
			: Math.max(0L, Duration.between(oldestAt, observedAt).getSeconds());
	}
}
