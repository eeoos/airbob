package kr.kro.airbob.domain.coupon.monitoring;

import java.util.Locale;

public interface CouponIssueMetricRecorder {

	void recordIssue(Strategy strategy, IssueResult result, long durationNanos);

	void recordDatabase(Strategy strategy, DatabaseResult result, long durationNanos);

	void recordLockWait(LockResult result, long durationNanos);

	void recordLua(LuaOperation operation, LuaResult result, long durationNanos);

	void recordCompensation(CompensationResult result);

	interface TaggedValue {
		default String tagValue() {
			return ((Enum<?>)this).name().toLowerCase(Locale.ROOT);
		}
	}

	enum Strategy implements TaggedValue {
		LOCK,
		LUA
	}

	enum IssueResult implements TaggedValue {
		SUCCESS,
		SOLD_OUT,
		DUPLICATE,
		NOT_ISSUABLE,
		TIMEOUT,
		UNPREPARED,
		ERROR
	}

	enum DatabaseResult implements TaggedValue {
		SUCCESS,
		REJECTED,
		ERROR
	}

	enum LockResult implements TaggedValue {
		ACQUIRED,
		TIMEOUT,
		INTERRUPTED,
		ERROR
	}

	enum LuaOperation implements TaggedValue {
		PREPARE,
		ISSUE,
		COMPENSATE
	}

	enum LuaResult implements TaggedValue {
		PREPARED,
		ALREADY_PREPARED,
		APPROVED,
		SOLD_OUT,
		DUPLICATE,
		NOT_STARTED,
		ENDED,
		UNPREPARED,
		INACTIVE,
		COMPENSATED,
		NO_OP,
		META_MISSING,
		ERROR
	}

	enum CompensationResult implements TaggedValue {
		COMPENSATED,
		NO_OP,
		META_MISSING,
		ERROR
	}
}
