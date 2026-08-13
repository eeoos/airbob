package kr.kro.airbob.domain.accommodation.cache;

import java.util.Locale;

public interface AccommodationDetailCacheMetricRecorder {

	void recordRequest(RequestResult result);

	void recordLock(LockResult result, long durationNanos);

	void recordLoad(LoadResult result, long durationNanos);

	void recordRedis(RedisOperation operation, OperationResult result);

	void recordEviction(AccommodationDetailCacheInvalidationReason reason, OperationResult result);

	interface TaggedValue {
		default String tagValue() {
			return ((Enum<?>)this).name().toLowerCase(Locale.ROOT);
		}
	}

	enum RequestResult implements TaggedValue {
		HIT,
		HIT_AFTER_WAIT,
		COALESCED,
		NEGATIVE_HIT,
		NEGATIVE_COALESCED,
		LOADED,
		NEGATIVE_LOADED
	}

	enum LockResult implements TaggedValue {
		ACQUIRED,
		TIMEOUT,
		INTERRUPTED,
		ERROR
	}

	enum LoadResult implements TaggedValue {
		FOUND,
		NOT_FOUND,
		ERROR
	}

	enum RedisOperation implements TaggedValue {
		GET,
		PUT,
		DELETE
	}

	enum OperationResult implements TaggedValue {
		SUCCESS,
		ERROR
	}
}
