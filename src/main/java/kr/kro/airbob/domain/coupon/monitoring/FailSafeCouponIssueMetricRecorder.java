package kr.kro.airbob.domain.coupon.monitoring;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관측 코드의 실패가 쿠폰 발급 결과나 락·재고 상태를 바꾸지 않도록 하는 경계다.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class FailSafeCouponIssueMetricRecorder implements CouponIssueMetricRecorder {

	private final MicrometerCouponIssueMetricRecorder delegate;

	@Override
	public void recordIssue(Strategy strategy, IssueResult result, long durationNanos) {
		try {
			delegate.recordIssue(strategy, result, durationNanos);
		} catch (RuntimeException exception) {
			logFailure(ISSUE_METRIC, exception);
		}
	}

	@Override
	public void recordDatabase(Strategy strategy, DatabaseResult result, long durationNanos) {
		try {
			delegate.recordDatabase(strategy, result, durationNanos);
		} catch (RuntimeException exception) {
			logFailure(DATABASE_METRIC, exception);
		}
	}

	@Override
	public void recordLockWait(LockResult result, long durationNanos) {
		try {
			delegate.recordLockWait(result, durationNanos);
		} catch (RuntimeException exception) {
			logFailure(LOCK_METRIC, exception);
		}
	}

	@Override
	public void recordLua(LuaOperation operation, LuaResult result, long durationNanos) {
		try {
			delegate.recordLua(operation, result, durationNanos);
		} catch (RuntimeException exception) {
			logFailure(LUA_METRIC, exception);
		}
	}

	@Override
	public void recordCompensation(CompensationResult result) {
		try {
			delegate.recordCompensation(result);
		} catch (RuntimeException exception) {
			logFailure(COMPENSATION_METRIC, exception);
		}
	}

	private void logFailure(String metric, RuntimeException exception) {
		log.warn("쿠폰 발급 메트릭 기록 실패. metric={}", metric, exception);
	}

	private static final String ISSUE_METRIC = "issue";
	private static final String DATABASE_METRIC = "database";
	private static final String LOCK_METRIC = "lock";
	private static final String LUA_METRIC = "lua";
	private static final String COMPENSATION_METRIC = "compensation";
}
