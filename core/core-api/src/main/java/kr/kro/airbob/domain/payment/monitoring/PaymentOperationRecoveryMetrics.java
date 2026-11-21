package kr.kro.airbob.domain.payment.monitoring;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class PaymentOperationRecoveryMetrics {

	public static final String LAST_SUCCESS_EPOCH_SECONDS =
		"airbob.payment.operation.recovery.scheduler.last.success.epoch.seconds";
	public static final String FAILURE_COUNT =
		"airbob.payment.operation.recovery.scheduler.failures";

	private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
	private final Counter failures;

	public PaymentOperationRecoveryMetrics(MeterRegistry meterRegistry) {
		Gauge.builder(LAST_SUCCESS_EPOCH_SECONDS, lastSuccessEpochSeconds, AtomicLong::get)
			.description("Epoch second of the last successfully completed payment recovery tick")
			.register(meterRegistry);
		failures = Counter.builder(FAILURE_COUNT)
			.description("Number of failed payment operation recovery ticks")
			.register(meterRegistry);
	}

	public void recordSuccess(Instant completedAt) {
		lastSuccessEpochSeconds.set(completedAt.getEpochSecond());
	}

	public void recordFailure() {
		failures.increment();
	}
}
