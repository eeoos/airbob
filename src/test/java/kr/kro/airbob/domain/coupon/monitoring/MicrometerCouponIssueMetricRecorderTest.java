package kr.kro.airbob.domain.coupon.monitoring;
import static org.assertj.core.api.Assertions.*;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.*;
class MicrometerCouponIssueMetricRecorderTest {
	@Test void recordsClosedStrategyOutcomesAndRetryReasonsWithoutIdentifiers() {
		var registry = new SimpleMeterRegistry();
		var recorder = new MicrometerCouponIssueMetricRecorder(registry);
		recorder.recordIssue(IssueResult.SUCCESS,120_000_000);
		recorder.recordDatabase(DatabaseResult.RETRY,80_000_000);
		recorder.recordRetry(RetryReason.DEADLOCK);
		assertThat(registry.get(MicrometerCouponIssueMetricRecorder.ISSUE_DURATION)
			.tags("strategy","skip_surrogate_rr","result","success").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(120);
		assertThat(registry.get(MicrometerCouponIssueMetricRecorder.DATABASE_DURATION)
			.tags("strategy","skip_surrogate_rr","result","retry").timer().count()).isEqualTo(1);
		assertThat(registry.get(MicrometerCouponIssueMetricRecorder.RETRY_TOTAL)
			.tags("strategy","skip_surrogate_rr","reason","deadlock").counter().count()).isEqualTo(1);
		assertThat(registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream()).map(t -> t.getKey()).toList())
			.doesNotContainAnyElementsOf(Set.of("coupon_id","couponId","member_id","memberId"));
		assertThat(registry.find("coupon.lua.duration").timer()).isNull();
	}
}
