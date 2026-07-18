package kr.kro.airbob.domain.coupon.monitoring;

import static kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.CompensationResult.COMPENSATED;
import static kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.DatabaseResult.ERROR;
import static kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.IssueResult.SUCCESS;
import static kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.LockResult.TIMEOUT;
import static kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.LuaOperation.ISSUE;
import static kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.LuaResult.SOLD_OUT;
import static kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.Strategy.LOCK;
import static kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.Strategy.LUA;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MicrometerCouponIssueMetricRecorderTest {

	private SimpleMeterRegistry meterRegistry;
	private MicrometerCouponIssueMetricRecorder recorder;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		recorder = new MicrometerCouponIssueMetricRecorder(meterRegistry);
	}

	@Test
	@DisplayName("발급 전체 지연과 DB 지연을 전략·결과별 Timer로 기록한다")
	void recordsIssueAndDatabaseDurations() {
		recorder.recordIssue(LOCK, SUCCESS, Duration.ofMillis(120).toNanos());
		recorder.recordDatabase(LUA, ERROR, Duration.ofMillis(80).toNanos());

		Timer issueTimer = meterRegistry.find(MicrometerCouponIssueMetricRecorder.ISSUE_DURATION)
			.tags("strategy", "lock", "result", "success")
			.timer();
		Timer databaseTimer = meterRegistry.find(MicrometerCouponIssueMetricRecorder.DATABASE_DURATION)
			.tags("strategy", "lua", "result", "error")
			.timer();

		assertThat(issueTimer).isNotNull();
		assertThat(issueTimer.count()).isOne();
		assertThat(issueTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(120.0);
		assertThat(databaseTimer).isNotNull();
		assertThat(databaseTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(80.0);
	}

	@Test
	@DisplayName("락 대기·타임아웃과 Lua 실행 결과를 별도 지표로 기록한다")
	void recordsLockAndLuaMetrics() {
		recorder.recordLockWait(TIMEOUT, Duration.ofSeconds(5).toNanos());
		recorder.recordLua(ISSUE, SOLD_OUT, Duration.ofMillis(2).toNanos());

		Timer lockTimer = meterRegistry.find(MicrometerCouponIssueMetricRecorder.LOCK_WAIT_DURATION)
			.tag("result", "timeout")
			.timer();
		Counter timeoutCounter = meterRegistry.find(MicrometerCouponIssueMetricRecorder.LOCK_TIMEOUT_TOTAL)
			.counter();
		Timer luaTimer = meterRegistry.find(MicrometerCouponIssueMetricRecorder.LUA_DURATION)
			.tags("operation", "issue", "result", "sold_out")
			.timer();

		assertThat(lockTimer).isNotNull();
		assertThat(lockTimer.totalTime(TimeUnit.SECONDS)).isEqualTo(5.0);
		assertThat(timeoutCounter).isNotNull();
		assertThat(timeoutCounter.count()).isOne();
		assertThat(luaTimer).isNotNull();
		assertThat(luaTimer.count()).isOne();
	}

	@Test
	@DisplayName("Redis 보상 결과를 카운터로 기록한다")
	void recordsCompensationOutcome() {
		recorder.recordCompensation(COMPENSATED);

		Counter counter = meterRegistry.find(MicrometerCouponIssueMetricRecorder.COMPENSATION_TOTAL)
			.tag("result", "compensated")
			.counter();
		assertThat(counter).isNotNull();
		assertThat(counter.count()).isOne();
	}

	@Test
	@DisplayName("쿠폰 ID와 회원 ID는 어떤 쿠폰 발급 메트릭 태그에도 포함하지 않는다")
	void neverUsesHighCardinalityIdentifierTags() {
		recorder.recordIssue(LOCK, SUCCESS, 1L);
		recorder.recordDatabase(LUA, ERROR, 1L);
		recorder.recordLockWait(TIMEOUT, 1L);
		recorder.recordLua(ISSUE, SOLD_OUT, 1L);
		recorder.recordCompensation(COMPENSATED);

		Set<String> forbiddenTags = Set.of("coupon_id", "couponId", "member_id", "memberId");
		assertThat(meterRegistry.getMeters().stream()
			.flatMap(meter -> meter.getId().getTags().stream())
			.map(tag -> tag.getKey())
			.toList())
			.doesNotContainAnyElementsOf(forbiddenTags);
	}
}
