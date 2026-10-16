package kr.kro.airbob.domain.coupon.monitoring;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class FailSafeCouponIssueMetricRecorder implements CouponIssueMetricRecorder {
	private final MicrometerCouponIssueMetricRecorder delegate;

	@Override
	public void recordIssue(Strategy strategy, IssueResult result, long durationNanos) {
		record("issue", () -> delegate.recordIssue(strategy, result, durationNanos));
	}
	@Override
	public void recordDatabase(Strategy strategy, DatabaseResult result, long durationNanos) {
		record("database", () -> delegate.recordDatabase(strategy, result, durationNanos));
	}
	@Override
	public void recordRetry(Strategy strategy, RetryReason reason) {
		record("retryable", () -> delegate.recordRetry(strategy, reason));
	}
	private void record(String metric, Runnable operation) {
		try { operation.run(); }
		catch (RuntimeException exception) {
			log.warn("쿠폰 발급 메트릭 기록 실패. metric={}, type={}", metric, exception.getClass().getSimpleName());
		}
	}
}
