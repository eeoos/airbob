package kr.kro.airbob.messaging.outbox.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutboxHealthMetricsTest {

	@Test
	void recordsRetentionFootprintWithoutMessageTags() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		OutboxHealthMetrics metrics = new OutboxHealthMetrics(registry);
		Instant observedAt = Instant.parse("2026-08-17T00:00:00Z");

		metrics.recordSuccess(new OutboxHealthSnapshot(
			observedAt, 75, Optional.of(observedAt.minusSeconds(90))));

		assertGauge(registry, OutboxHealthMetrics.RETAINED_ROW_COUNT, 75);
		assertGauge(registry, OutboxHealthMetrics.OLDEST_AGE_SECONDS, 90);
		assertGauge(registry, OutboxHealthMetrics.LAST_SUCCESS_EPOCH_SECONDS,
			observedAt.getEpochSecond());
		assertThat(registry.getMeters())
			.extracting(Meter::getId)
			.allSatisfy(id -> assertThat(id.getTags()).isEmpty());
	}

	@Test
	void emptyRetentionFootprintHasZeroAgeAndFailurePreservesTheLastSuccess() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		OutboxHealthMetrics metrics = new OutboxHealthMetrics(registry);
		Instant observedAt = Instant.parse("2026-08-17T00:00:00Z");
		metrics.recordSuccess(new OutboxHealthSnapshot(observedAt, 0, Optional.empty()));

		metrics.recordFailure();

		assertGauge(registry, OutboxHealthMetrics.OLDEST_AGE_SECONDS, 0);
		assertGauge(registry, OutboxHealthMetrics.LAST_SUCCESS_EPOCH_SECONDS,
			observedAt.getEpochSecond());
		assertThat(registry.get(OutboxHealthMetrics.FAILURE_COUNT).counter().count()).isEqualTo(1);
	}

	private void assertGauge(SimpleMeterRegistry registry, String name, double expected) {
		assertThat(registry.get(name).gauge().value()).isEqualTo(expected);
	}
}
