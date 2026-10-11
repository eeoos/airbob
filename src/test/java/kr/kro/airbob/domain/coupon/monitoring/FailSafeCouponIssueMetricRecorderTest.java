package kr.kro.airbob.domain.coupon.monitoring;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.*;
class FailSafeCouponIssueMetricRecorderTest {
	@Test void swallowsEveryMetricFailure() {
		var delegate = mock(MicrometerCouponIssueMetricRecorder.class);
		var recorder = new FailSafeCouponIssueMetricRecorder(delegate);
		doThrow(new IllegalStateException()).when(delegate).recordIssue(Strategy.SKIP_SURROGATE_RR,IssueResult.SUCCESS,1);
		doThrow(new IllegalStateException()).when(delegate).recordDatabase(Strategy.SKIP_SURROGATE_RR,DatabaseResult.SUCCESS,1);
		doThrow(new IllegalStateException()).when(delegate).recordRetry(Strategy.SKIP_SURROGATE_RR,RetryReason.DEADLOCK);
		assertThatCode(() -> recorder.recordIssue(Strategy.SKIP_SURROGATE_RR,IssueResult.SUCCESS,1)).doesNotThrowAnyException();
		assertThatCode(() -> recorder.recordDatabase(Strategy.SKIP_SURROGATE_RR,DatabaseResult.SUCCESS,1)).doesNotThrowAnyException();
		assertThatCode(() -> recorder.recordRetry(Strategy.SKIP_SURROGATE_RR,RetryReason.DEADLOCK)).doesNotThrowAnyException();
	}
}
