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
	public static final String LOCK_WAIT_DURATION = "coupon.lock.wait.duration";
	public static final String LOCK_TIMEOUT_TOTAL = "coupon.lock.timeout";
	public static final String LUA_DURATION = "coupon.lua.duration";
	public static final String COMPENSATION_TOTAL = "coupon.compensation";

	private static final Duration[] ISSUE_SLOS = durations(10, 50, 100, 250, 500, 1_000, 2_500, 5_000);
	private static final Duration[] LUA_SLOS = durations(1, 2, 5, 10, 25, 50, 100);

	private final MeterRegistry meterRegistry;

	@Override
	public void recordIssue(Strategy strategy, IssueResult result, long durationNanos) {
		timer(ISSUE_DURATION, "End-to-end synchronous coupon issue duration", ISSUE_SLOS,
			"strategy", strategy.tagValue(), "result", result.tagValue())
			.record(durationNanos, TimeUnit.NANOSECONDS);
	}

	@Override
	public void recordDatabase(Strategy strategy, DatabaseResult result, long durationNanos) {
		timer(DATABASE_DURATION, "Coupon issue database transaction duration", ISSUE_SLOS,
			"strategy", strategy.tagValue(), "result", result.tagValue())
			.record(durationNanos, TimeUnit.NANOSECONDS);
	}

	@Override
	public void recordLockWait(LockResult result, long durationNanos) {
		timer(LOCK_WAIT_DURATION, "Redisson coupon lock acquisition wait duration", ISSUE_SLOS,
			"result", result.tagValue())
			.record(durationNanos, TimeUnit.NANOSECONDS);
		if (result == LockResult.TIMEOUT) {
			Counter.builder(LOCK_TIMEOUT_TOTAL)
				.description("Number of Redisson coupon lock acquisition timeouts")
				.register(meterRegistry)
				.increment();
		}
	}

	@Override
	public void recordLua(LuaOperation operation, LuaResult result, long durationNanos) {
		timer(LUA_DURATION, "Redis coupon Lua execution duration", LUA_SLOS,
			"operation", operation.tagValue(), "result", result.tagValue())
			.record(durationNanos, TimeUnit.NANOSECONDS);
	}

	@Override
	public void recordCompensation(CompensationResult result) {
		Counter.builder(COMPENSATION_TOTAL)
			.description("Coupon Redis compensation outcomes after database failure")
			.tag("result", result.tagValue())
			.register(meterRegistry)
			.increment();
	}

	private Timer timer(String name, String description, Duration[] objectives, String... tags) {
		return Timer.builder(name)
			.description(description)
			.tags(tags)
			.publishPercentileHistogram()
			.serviceLevelObjectives(objectives)
			.register(meterRegistry);
	}

	private static Duration[] durations(long... milliseconds) {
		Duration[] durations = new Duration[milliseconds.length];
		for (int i = 0; i < milliseconds.length; i++) {
			durations[i] = Duration.ofMillis(milliseconds[i]);
		}
		return durations;
	}
}
