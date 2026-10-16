package kr.kro.airbob.domain.coupon.monitoring;

import java.util.Locale;

public interface CouponIssueMetricRecorder {
	void recordIssue(IssueResult result, long durationNanos);
	void recordDatabase(DatabaseResult result, long durationNanos);
	void recordRetry(RetryReason reason);

	interface TaggedValue {
		default String tagValue() { return ((Enum<?>)this).name().toLowerCase(Locale.ROOT); }
	}
	enum IssueResult implements TaggedValue {
		SUCCESS, SOLD_OUT, DUPLICATE, NOT_ISSUABLE, UNPREPARED, CONTENDED, ERROR
	}
	enum DatabaseResult implements TaggedValue { SUCCESS, REJECTED, RETRY, ERROR }
	enum RetryReason implements TaggedValue { EMPTY_UNIT, DEADLOCK, LOCK_TIMEOUT, POLICY_LOCK_BUSY }
}
