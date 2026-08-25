package kr.kro.airbob.messaging.alert.monitoring;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class OperatorAlertMetrics {

	public static final String DELIVERED_COUNT = "airbob.operator.alert.delivered";
	public static final String FAILURE_COUNT = "airbob.operator.alert.failure";
	public static final String DLT_COUNT = "airbob.operator.alert.dlt";

	private final Counter delivered;
	private final Counter failed;
	private final Counter dlt;

	public OperatorAlertMetrics(MeterRegistry meterRegistry) {
		delivered = Counter.builder(DELIVERED_COUNT)
			.description("Operator alerts delivered to the configured gateway")
			.register(meterRegistry);
		failed = Counter.builder(FAILURE_COUNT)
			.description("Operator alert gateway delivery failures")
			.register(meterRegistry);
		dlt = Counter.builder(DLT_COUNT)
			.description("Operator alerts retained in the dedicated DLT")
			.register(meterRegistry);
	}

	public void delivered() {
		delivered.increment();
	}

	public void failed() {
		failed.increment();
	}

	public void dlt() {
		dlt.increment();
	}
}
