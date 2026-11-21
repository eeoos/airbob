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
	public void recordIssue(IssueResult result, long durationNanos) {
		record("issue", () -> delegate.recordIssue(result, durationNanos));
	}
	@Override
	public void recordDatabase(DatabaseResult result, long durationNanos) {
		record("database", () -> delegate.recordDatabase(result, durationNanos));
	}
	@Override
	public void recordRetry(RetryReason reason) {
		record("retryable", () -> delegate.recordRetry(reason));
	}
	private void record(String metric, Runnable operation) {
		try { operation.run(); }
		catch (RuntimeException exception) {
			log.warn("쿠폰 발급 메트릭 기록 실패. metric={}, type={}", metric, exception.getClass().getSimpleName());
		}
	}
}
