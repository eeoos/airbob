package kr.kro.airbob.domain.accommodation.cache;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 메트릭 시스템의 실패가 캐시 처리와 숙소 상세 응답에 영향을 주지 않도록 예외를 격리
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class FailSafeAccommodationDetailCacheMetricRecorder
	implements AccommodationDetailCacheMetricRecorder {

	private final MicrometerAccommodationDetailCacheMetricRecorder delegate;

	@Override
	public void recordRequest(RequestResult result) {
		record("request", () -> delegate.recordRequest(result));
	}

	@Override
	public void recordLock(LockResult result, long durationNanos) {
		record("lock", () -> delegate.recordLock(result, durationNanos));
	}

	@Override
	public void recordLoad(LoadResult result, long durationNanos) {
		record("load", () -> delegate.recordLoad(result, durationNanos));
	}

	@Override
	public void recordRedis(RedisOperation operation, OperationResult result) {
		record("redis", () -> delegate.recordRedis(operation, result));
	}

	@Override
	public void recordEviction(AccommodationDetailCacheInvalidationReason reason, OperationResult result) {
		record("eviction", () -> delegate.recordEviction(reason, result));
	}

	private void record(String metric, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException exception) {
			log.warn("숙소 상세 캐시 메트릭 기록 실패. metric={}", metric, exception);
		}
	}
}
