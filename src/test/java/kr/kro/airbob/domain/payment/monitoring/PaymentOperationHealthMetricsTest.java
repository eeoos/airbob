package kr.kro.airbob.domain.payment.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionAction;

class PaymentOperationHealthMetricsTest {

	private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

	@Test
	void publishesAggregateAgesAndClosedResolutionCountersOnly() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		PaymentOperationHealthMetrics metrics = new PaymentOperationHealthMetrics(registry);
		PaymentOperationHealthSnapshot snapshot = new PaymentOperationHealthSnapshot(
			NOW,
			2,
			Optional.of(NOW.minusSeconds(120)),
			1,
			Optional.of(NOW.minusSeconds(90)),
			3,
			Optional.of(NOW.minusSeconds(45)),
			4,
			Map.of(
				PaymentOperationResolutionAction.RECONCILIATION_REQUESTED, 7L,
				PaymentOperationResolutionAction.MARKED_NOT_PAID, 2L
			)
		);

		metrics.recordSuccess(snapshot);

		assertGauge(registry, PaymentOperationHealthMetrics.MANUAL_REVIEW_COUNT, 2);
		assertGauge(registry, PaymentOperationHealthMetrics.MANUAL_REVIEW_OLDEST_AGE_SECONDS, 120);
		assertGauge(registry, PaymentOperationHealthMetrics.RECONCILIATION_PENDING_COUNT, 1);
		assertGauge(registry, PaymentOperationHealthMetrics.RECONCILIATION_PENDING_OLDEST_AGE_SECONDS, 90);
		assertGauge(registry, PaymentOperationHealthMetrics.STALE_QUEUED_COUNT, 3);
		assertGauge(registry, PaymentOperationHealthMetrics.STALE_QUEUED_OLDEST_AGE_SECONDS, 45);
		assertGauge(registry, PaymentOperationHealthMetrics.EXPIRED_EXECUTING_LEASE_COUNT, 4);
		assertGauge(registry, PaymentOperationHealthMetrics.HEALTH_LAST_SUCCESS_EPOCH_SECONDS,
			NOW.getEpochSecond());

		for (PaymentOperationResolutionAction action : PaymentOperationResolutionAction.values()) {
			double expected = snapshot.resolutionCounts().getOrDefault(action, 0L);
			assertThat(registry.get(PaymentOperationHealthMetrics.RESOLUTION_COUNT)
				.tag(PaymentOperationHealthMetrics.RESOLUTION_ACTION_TAG, action.name())
				.functionCounter().count()).isEqualTo(expected);
		}
		Set<String> closedActions = Arrays.stream(PaymentOperationResolutionAction.values())
			.map(Enum::name)
			.collect(Collectors.toUnmodifiableSet());
		assertThat(registry.getMeters())
			.filteredOn(meter -> meter.getId().getName()
				.equals(PaymentOperationHealthMetrics.RESOLUTION_COUNT))
			.extracting(Meter::getId)
			.allSatisfy(id -> {
				assertThat(id.getTags()).hasSize(1);
				assertThat(closedActions)
					.contains(id.getTag(PaymentOperationHealthMetrics.RESOLUTION_ACTION_TAG));
			});
	}

	@Test
	void emptyQueuesHaveZeroAgeAndRefreshFailurePreservesTheLastSnapshot() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		PaymentOperationHealthMetrics metrics = new PaymentOperationHealthMetrics(registry);
		metrics.recordSuccess(PaymentOperationHealthSnapshot.empty(NOW));

		metrics.recordFailure();

		assertGauge(registry, PaymentOperationHealthMetrics.MANUAL_REVIEW_OLDEST_AGE_SECONDS, 0);
		assertGauge(registry, PaymentOperationHealthMetrics.RECONCILIATION_PENDING_OLDEST_AGE_SECONDS, 0);
		assertGauge(registry, PaymentOperationHealthMetrics.STALE_QUEUED_OLDEST_AGE_SECONDS, 0);
		assertGauge(registry, PaymentOperationHealthMetrics.HEALTH_LAST_SUCCESS_EPOCH_SECONDS,
			NOW.getEpochSecond());
		assertThat(registry.get(PaymentOperationHealthMetrics.HEALTH_REFRESH_FAILURE_COUNT)
			.counter().count()).isEqualTo(1);
	}

	private void assertGauge(SimpleMeterRegistry registry, String name, double expected) {
		assertThat(registry.get(name).gauge().value()).isEqualTo(expected);
	}
}
