package kr.kro.airbob.domain.accommodation.cache;

import java.util.Locale;

/**
 * 캐시 요청, 락, DB 조회, Redis 연산을 고정된 enum 태그로 기록하는 관측 경계
 * 숙소 ID처럼 값의 종류가 계속 늘어나는 정보는 태그로 사용하지 않음
 */
public interface AccommodationDetailCacheMetricRecorder {

	void recordRequest(RequestResult result);

	void recordLock(LockResult result, long durationNanos);

	void recordLoad(LoadResult result, long durationNanos);

	void recordRedis(RedisOperation operation, OperationResult result);

	void recordEviction(
		EvictionSource source,
		AccommodationDetailCacheInvalidationReason reason,
		OperationResult result
	);

	interface TaggedValue {
		default String tagValue() {
			return ((Enum<?>)this).name().toLowerCase(Locale.ROOT);
		}
	}

	/** 요청 하나가 최종적으로 데이터를 얻은 경로를 나타냄 */
	enum RequestResult implements TaggedValue {
		// 첫 Redis 조회에서 정상 캐시를 사용
		HIT,
		// 분산 락을 기다린 뒤 Redis에서 값을 사용
		HIT_AFTER_WAIT,
		// 같은 JVM에서 진행 중인 선행 DB 조회 결과를 공유
		COALESCED,
		// Redis에 저장된 NOT_FOUND를 사용
		NEGATIVE_HIT,
		// 같은 JVM의 선행 NOT_FOUND 결과를 공유
		NEGATIVE_COALESCED,
		// DB에서 숙소 상세를 조회
		LOADED,
		// DB에서 NOT_FOUND를 확인
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

	enum EvictionSource implements TaggedValue {
		// 원본 변경 커밋 직후 요청 스레드에서 실행하는 빠른 삭제
		AFTER_COMMIT,
		// outbox 이벤트를 소비해 재시도 가능한 경로에서 실행하는 멱등 재삭제
		OUTBOX
	}

	enum OperationResult implements TaggedValue {
		SUCCESS,
		ERROR
	}
}
