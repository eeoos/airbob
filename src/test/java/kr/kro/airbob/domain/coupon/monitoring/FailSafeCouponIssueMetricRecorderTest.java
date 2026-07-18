package kr.kro.airbob.domain.coupon.monitoring;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FailSafeCouponIssueMetricRecorderTest {

	@Test
	@DisplayName("모든 계측 예외를 흡수해 쿠폰 발급 흐름에 전파하지 않는다")
	void swallowsEveryMetricFailure() {
		MicrometerCouponIssueMetricRecorder delegate = mock(MicrometerCouponIssueMetricRecorder.class);
		FailSafeCouponIssueMetricRecorder recorder = new FailSafeCouponIssueMetricRecorder(delegate);

		doThrow(new IllegalStateException("issue metric failure"))
			.when(delegate).recordIssue(
				CouponIssueMetricRecorder.Strategy.LUA,
				CouponIssueMetricRecorder.IssueResult.SUCCESS,
				1L);
		doThrow(new IllegalStateException("database metric failure"))
			.when(delegate).recordDatabase(
				CouponIssueMetricRecorder.Strategy.LUA,
				CouponIssueMetricRecorder.DatabaseResult.SUCCESS,
				1L);
		doThrow(new IllegalStateException("lock metric failure"))
			.when(delegate).recordLockWait(CouponIssueMetricRecorder.LockResult.ACQUIRED, 1L);
		doThrow(new IllegalStateException("lua metric failure"))
			.when(delegate).recordLua(
				CouponIssueMetricRecorder.LuaOperation.ISSUE,
				CouponIssueMetricRecorder.LuaResult.APPROVED,
				1L);
		doThrow(new IllegalStateException("compensation metric failure"))
			.when(delegate).recordCompensation(
				CouponIssueMetricRecorder.CompensationResult.COMPENSATED);

		assertThatCode(() -> recorder.recordIssue(
			CouponIssueMetricRecorder.Strategy.LUA,
			CouponIssueMetricRecorder.IssueResult.SUCCESS,
			1L)).doesNotThrowAnyException();
		assertThatCode(() -> recorder.recordDatabase(
			CouponIssueMetricRecorder.Strategy.LUA,
			CouponIssueMetricRecorder.DatabaseResult.SUCCESS,
			1L)).doesNotThrowAnyException();
		assertThatCode(() -> recorder.recordLockWait(
			CouponIssueMetricRecorder.LockResult.ACQUIRED,
			1L)).doesNotThrowAnyException();
		assertThatCode(() -> recorder.recordLua(
			CouponIssueMetricRecorder.LuaOperation.ISSUE,
			CouponIssueMetricRecorder.LuaResult.APPROVED,
			1L)).doesNotThrowAnyException();
		assertThatCode(() -> recorder.recordCompensation(
			CouponIssueMetricRecorder.CompensationResult.COMPENSATED))
			.doesNotThrowAnyException();
	}
}
