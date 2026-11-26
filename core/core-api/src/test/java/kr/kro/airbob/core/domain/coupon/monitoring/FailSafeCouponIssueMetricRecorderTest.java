package kr.kro.airbob.core.domain.coupon.monitoring;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import kr.kro.airbob.core.domain.coupon.monitoring.CouponIssueMetricRecorder.DatabaseResult;
import kr.kro.airbob.core.domain.coupon.monitoring.CouponIssueMetricRecorder.IssueResult;
import kr.kro.airbob.core.domain.coupon.monitoring.CouponIssueMetricRecorder.RetryReason;

class FailSafeCouponIssueMetricRecorderTest {
	@Test
	void swallowsEveryMetricFailure() {
		var delegate = mock(MicrometerCouponIssueMetricRecorder.class);
		var recorder = new FailSafeCouponIssueMetricRecorder(delegate);
		willThrow(new IllegalStateException()).given(delegate).recordIssue(IssueResult.SUCCESS, 1);
		willThrow(new IllegalStateException()).given(delegate).recordDatabase(DatabaseResult.SUCCESS, 1);
		willThrow(new IllegalStateException()).given(delegate).recordRetry(RetryReason.DEADLOCK);
		assertThatCode(() -> recorder.recordIssue(IssueResult.SUCCESS, 1)).doesNotThrowAnyException();
		assertThatCode(() -> recorder.recordDatabase(DatabaseResult.SUCCESS, 1)).doesNotThrowAnyException();
		assertThatCode(() -> recorder.recordRetry(RetryReason.DEADLOCK)).doesNotThrowAnyException();
	}
}
