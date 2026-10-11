package kr.kro.airbob.domain.coupon.monitoring;

import java.util.Locale;

public interface CouponIssueMetricRecorder {
	void recordIssue(Strategy strategy, IssueResult result, long durationNanos);
	void recordDatabase(Strategy strategy, DatabaseResult result, long durationNanos);
	void recordRetry(Strategy strategy, RetryReason reason);

	interface TaggedValue {
		default String tagValue() { return ((Enum<?>)this).name().toLowerCase(Locale.ROOT); }
	}
	enum Strategy implements TaggedValue {
		DB, SKIP_SURROGATE_RR, SKIP_SURROGATE_RC, SKIP_COMPOSITE_RR, SKIP_COMPOSITE_RC
	}
	enum IssueResult implements TaggedValue {
		SUCCESS, SOLD_OUT, DUPLICATE, NOT_ISSUABLE, UNPREPARED, CONTENDED, ERROR
	}
	enum DatabaseResult implements TaggedValue { SUCCESS, REJECTED, RETRY, ERROR }
	enum RetryReason implements TaggedValue { EMPTY_UNIT, DEADLOCK, LOCK_TIMEOUT, POLICY_LOCK_BUSY }
}
