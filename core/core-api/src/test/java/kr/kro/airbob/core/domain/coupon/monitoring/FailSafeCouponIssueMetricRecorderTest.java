package kr.kro.airbob.core.domain.coupon.monitoring;

import static org.assertj.core.api.Assertions.*;
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
		doThrow(new IllegalStateException()).when(delegate).recordIssue(IssueResult.SUCCESS, 1);
		doThrow(new IllegalStateException()).when(delegate).recordDatabase(DatabaseResult.SUCCESS, 1);
		doThrow(new IllegalStateException()).when(delegate).recordRetry(RetryReason.DEADLOCK);
		assertThatCode(() -> recorder.recordIssue(IssueResult.SUCCESS, 1)).doesNotThrowAnyException();
		assertThatCode(() -> recorder.recordDatabase(DatabaseResult.SUCCESS, 1)).doesNotThrowAnyException();
		assertThatCode(() -> recorder.recordRetry(RetryReason.DEADLOCK)).doesNotThrowAnyException();
	}
}
