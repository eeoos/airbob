package kr.kro.airbob.domain.coupon.monitoring;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MicrometerCouponIssueMetricRecorder implements CouponIssueMetricRecorder {
	public static final String ISSUE_DURATION = "coupon.issue.duration";
	public static final String DATABASE_DURATION = "coupon.database.issue.duration";
	public static final String RETRY_TOTAL = "coupon.issue.retryable";
	private static final Duration[] SLOS = {Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(100),
		Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2500), Duration.ofSeconds(5)};
	private final MeterRegistry meterRegistry;

	@Override
	public void recordIssue(IssueResult result, long durationNanos) {
		timer(ISSUE_DURATION, result.tagValue()).record(durationNanos, TimeUnit.NANOSECONDS);
	}
	@Override
	public void recordDatabase(DatabaseResult result, long durationNanos) {
		timer(DATABASE_DURATION, result.tagValue()).record(durationNanos, TimeUnit.NANOSECONDS);
	}
	@Override
	public void recordRetry(RetryReason reason) {
		Counter.builder(RETRY_TOTAL).tags("strategy", "skip_surrogate_rr", "reason", reason.tagValue())
			.register(meterRegistry).increment();
	}
	private Timer timer(String name, String result) {
		return Timer.builder(name).tags("strategy", "skip_surrogate_rr", "result", result)
			.publishPercentileHistogram().serviceLevelObjectives(SLOS).register(meterRegistry);
	}
}
