package kr.kro.airbob.messaging.outbox.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import kr.kro.airbob.messaging.outbox.application.OutboxCleanupResult;

class OutboxCleanupMetricsTest {

	@Test
	@DisplayName("payload나 key 태그 없이 마지막 삭제 수와 성공 시각만 기록한다")
	void exposesOnlySafeCleanupTickSignals() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		OutboxCleanupMetrics metrics = new OutboxCleanupMetrics(registry);
		Instant observedAt = Instant.parse("2026-08-17T00:00:00Z");
		metrics.recordSuccess(new OutboxCleanupResult(25, observedAt));

		assertGauge(registry, OutboxCleanupMetrics.LAST_SUCCESS_EPOCH_SECONDS, observedAt.getEpochSecond());
		assertGauge(registry, OutboxCleanupMetrics.LAST_DELETED_COUNT, 25);
		assertThat(registry.find("airbob.messaging.outbox.backlog.count").meter()).isNull();
		assertThat(registry.find("airbob.messaging.outbox.oldest.age.seconds").meter()).isNull();
		assertThat(registry.getMeters())
			.extracting(Meter::getId)
			.allSatisfy(id -> assertThat(id.getTags()).isEmpty());
	}

	@Test
	@DisplayName("cleanup 실패는 마지막 성공 시각과 삭제 수를 유지하고 실패 횟수만 올린다")
	void preservesLastSuccessOnFailure() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		OutboxCleanupMetrics metrics = new OutboxCleanupMetrics(registry);
		Instant observedAt = Instant.parse("2026-08-17T00:00:00Z");
		metrics.recordSuccess(new OutboxCleanupResult(0, observedAt));

		metrics.recordFailure();

		assertGauge(registry, OutboxCleanupMetrics.LAST_SUCCESS_EPOCH_SECONDS, observedAt.getEpochSecond());
		assertGauge(registry, OutboxCleanupMetrics.LAST_DELETED_COUNT, 0);
		assertThat(registry.get(OutboxCleanupMetrics.FAILURE_COUNT).counter().count()).isEqualTo(1);
	}

	private void assertGauge(SimpleMeterRegistry registry, String name, double expected) {
		Gauge gauge = registry.get(name).gauge();
		assertThat(gauge.value()).isEqualTo(expected);
	}
}
