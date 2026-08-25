package kr.kro.airbob.domain.payment.monitoring;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionAction;

public record PaymentOperationHealthSnapshot(
	Instant observedAt,
	long manualReviewCount,
	Optional<Instant> oldestManualReviewAt,
	long reconciliationPendingCount,
	Optional<Instant> oldestReconciliationPendingAt,
	long staleQueuedCount,
	Optional<Instant> oldestStaleQueuedAt,
	long expiredExecutingLeaseCount,
	Map<PaymentOperationResolutionAction, Long> resolutionCounts
) {
	public PaymentOperationHealthSnapshot {
		Objects.requireNonNull(observedAt, "observedAt must not be null");
		oldestManualReviewAt = requireQueueSnapshot(
			manualReviewCount, oldestManualReviewAt, "manualReview");
		oldestReconciliationPendingAt = requireQueueSnapshot(
			reconciliationPendingCount, oldestReconciliationPendingAt, "reconciliationPending");
		oldestStaleQueuedAt = requireQueueSnapshot(
			staleQueuedCount, oldestStaleQueuedAt, "staleQueued");
		if (expiredExecutingLeaseCount < 0) {
			throw new IllegalArgumentException("expiredExecutingLeaseCount must not be negative");
		}
		resolutionCounts = Map.copyOf(
			Objects.requireNonNull(resolutionCounts, "resolutionCounts must not be null"));
		resolutionCounts.forEach((action, count) -> {
			Objects.requireNonNull(action, "resolution action must not be null");
			if (count == null || count < 0) {
				throw new IllegalArgumentException("resolution count must not be negative");
			}
		});
	}

	public static PaymentOperationHealthSnapshot empty(Instant observedAt) {
		return new PaymentOperationHealthSnapshot(
			observedAt,
			0,
			Optional.empty(),
			0,
			Optional.empty(),
			0,
			Optional.empty(),
			0,
			Map.of()
		);
	}

	private static Optional<Instant> requireQueueSnapshot(
		long count,
		Optional<Instant> oldestAt,
		String name
	) {
		Objects.requireNonNull(oldestAt, name + " oldest timestamp must not be null");
		if (count < 0) {
			throw new IllegalArgumentException(name + " count must not be negative");
		}
		if ((count == 0) != oldestAt.isEmpty()) {
			throw new IllegalArgumentException(name + " count and oldest timestamp must agree");
		}
		return oldestAt;
	}
}
