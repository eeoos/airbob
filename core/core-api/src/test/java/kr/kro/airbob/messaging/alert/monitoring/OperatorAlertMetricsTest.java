package kr.kro.airbob.messaging.alert.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OperatorAlertMetricsTest {

	@Test
	void recordsThreeIdentifierFreeCountersWithoutTags() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		OperatorAlertMetrics metrics = new OperatorAlertMetrics(registry);

		metrics.delivered();
		metrics.failed();
		metrics.dlt();

		assertCounter(registry, OperatorAlertMetrics.DELIVERED_COUNT);
		assertCounter(registry, OperatorAlertMetrics.FAILURE_COUNT);
		assertCounter(registry, OperatorAlertMetrics.DLT_COUNT);
	}

	private void assertCounter(SimpleMeterRegistry registry, String name) {
		var counter = registry.get(name).counter();
		assertThat(counter.count()).isEqualTo(1);
		assertThat(counter.getId().getTags()).isEmpty();
	}
}
