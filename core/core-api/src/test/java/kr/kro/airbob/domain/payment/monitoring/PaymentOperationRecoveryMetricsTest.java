package kr.kro.airbob.domain.payment.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PaymentOperationRecoveryMetricsTest {

	@Test
	void recordsLastSuccessfulCompletionAndFailuresWithoutTags() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		PaymentOperationRecoveryMetrics metrics = new PaymentOperationRecoveryMetrics(registry);
		Instant completedAt = Instant.parse("2026-08-17T00:00:00Z");

		metrics.recordSuccess(completedAt);
		metrics.recordFailure();

		assertThat(registry.get(PaymentOperationRecoveryMetrics.LAST_SUCCESS_EPOCH_SECONDS)
			.gauge().value()).isEqualTo(completedAt.getEpochSecond());
		assertThat(registry.get(PaymentOperationRecoveryMetrics.FAILURE_COUNT)
			.counter().count()).isEqualTo(1);
		assertThat(registry.getMeters())
			.extracting(Meter::getId)
			.allSatisfy(id -> assertThat(id.getTags()).isEmpty());
	}
}
