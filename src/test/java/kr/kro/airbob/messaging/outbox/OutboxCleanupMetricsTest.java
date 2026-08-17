package kr.kro.airbob.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutboxCleanupMetricsTest {

	@Test
	@DisplayName("payload나 key 태그 없이 backlog, oldest age, 마지막 성공 시각을 기록한다")
	void exposesSafeHealthSignalsWithoutMessageData() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		OutboxCleanupMetrics metrics = new OutboxCleanupMetrics(registry);
		Instant observedAt = Instant.parse("2026-08-17T00:00:00Z");
		metrics.recordSuccess(new OutboxCleanupResult(
			25,
			new OutboxBacklogSnapshot(
				75, Optional.of(Instant.parse("2026-08-16T23:58:30Z"))),
			observedAt
		));

		assertGauge(registry, OutboxCleanupMetrics.BACKLOG_COUNT, 75);
		assertGauge(registry, OutboxCleanupMetrics.OLDEST_AGE_SECONDS, 90);
		assertGauge(registry, OutboxCleanupMetrics.LAST_SUCCESS_EPOCH_SECONDS, observedAt.getEpochSecond());
		assertGauge(registry, OutboxCleanupMetrics.LAST_DELETED_COUNT, 25);
		assertThat(registry.getMeters())
			.extracting(Meter::getId)
			.allSatisfy(id -> assertThat(id.getTags()).isEmpty());
	}

	@Test
	@DisplayName("빈 backlog의 oldest age는 0이고 실패해도 마지막 성공 시각은 유지한다")
	void handlesEmptyBacklogAndPreservesLastSuccessOnFailure() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		OutboxCleanupMetrics metrics = new OutboxCleanupMetrics(registry);
		Instant observedAt = Instant.parse("2026-08-17T00:00:00Z");
		metrics.recordSuccess(new OutboxCleanupResult(
			0, new OutboxBacklogSnapshot(0, Optional.empty()), observedAt));

		metrics.recordFailure();

		assertGauge(registry, OutboxCleanupMetrics.OLDEST_AGE_SECONDS, 0);
		assertGauge(registry, OutboxCleanupMetrics.LAST_SUCCESS_EPOCH_SECONDS, observedAt.getEpochSecond());
		assertThat(registry.get(OutboxCleanupMetrics.FAILURE_COUNT).counter().count()).isEqualTo(1);
	}

	private void assertGauge(SimpleMeterRegistry registry, String name, double expected) {
		Gauge gauge = registry.get(name).gauge();
		assertThat(gauge.value()).isEqualTo(expected);
	}
}
