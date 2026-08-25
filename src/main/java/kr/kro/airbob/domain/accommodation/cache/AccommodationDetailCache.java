package kr.kro.airbob.domain.accommodation.cache;

import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.EvictionSource.AFTER_COMMIT;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.EvictionSource.OUTBOX;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.LoadResult.ERROR;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.LoadResult.FOUND;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.LoadResult.NOT_FOUND;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.LockResult.ACQUIRED;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.LockResult.INTERRUPTED;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.LockResult.TIMEOUT;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.OperationResult.SUCCESS;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.HIT;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.HIT_AFTER_WAIT;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.LOADED;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.NEGATIVE_HIT;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.NEGATIVE_LOADED;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.accommodation.cache.config.AccommodationDetailCacheJitter;
import kr.kro.airbob.domain.accommodation.cache.config.AccommodationDetailCacheProperties;
import kr.kro.airbob.domain.accommodation.cache.redis.AccommodationDetailRedisClient;
import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import lombok.extern.slf4j.Slf4j;

/**
 * 숙소 상세 cache-aside 흐름을 조정하는 공개 진입점
 * Redis 저장 규칙과 JVM single-flight 상태는 위임
 */
@Slf4j
@Component
public class AccommodationDetailCache {

	private static final String LOCK_KEY_PREFIX = "airbob:lock:accommodation-detail:";

	private final AccommodationDetailRedisStore redisStore;
	private final AccommodationDetailLocalLoadCoordinator localLoadCoordinator;
	private final RedissonClient redissonClient;
	private final AccommodationDetailCacheMetricRecorder metricRecorder;
	private final AccommodationDetailCacheProperties properties;

	public AccommodationDetailCache(
		AccommodationDetailRedisClient redisClient,
		@Qualifier("accommodationDetailRedissonClient") RedissonClient redissonClient,
		ObjectMapper objectMapper,
		AccommodationDetailCacheMetricRecorder metricRecorder,
		AccommodationDetailCacheJitter jitter,
		AccommodationDetailCacheProperties properties
	) {
		this.redisStore = new AccommodationDetailRedisStore(
			redisClient, objectMapper, metricRecorder, jitter, properties);
		this.localLoadCoordinator = new AccommodationDetailLocalLoadCoordinator(
			metricRecorder, properties.localLoadWait());
		this.redissonClient = redissonClient;
		this.metricRecorder = metricRecorder;
		this.properties = properties;
	}

	public AccommodationDetailSnapshot getOrLoad(
		Long accommodationId,
		Supplier<AccommodationDetailSnapshot> loader
	) {
		if (!properties.enabled()) {
			return localLoadCoordinator.loadDirect(() -> timedLoad(loader));
		}

		// 캐시가 정상이면 락이나 DB를 사용하지 않음
		CacheLookup<AccommodationDetailSnapshot> firstLookup = redisStore.read(accommodationId);
		switch (firstLookup) {
			case CacheLookup.Hit<AccommodationDetailSnapshot>(var snapshot) -> {
				return resolvePositiveHit(snapshot, HIT);
			}
			case CacheLookup.NegativeHit<AccommodationDetailSnapshot>() -> {
				return resolveNegativeHit();
			}
			case CacheLookup.Failure<AccommodationDetailSnapshot>() -> {
				return loadWithoutCache(accommodationId, loader);
			}
			case CacheLookup.Miss<AccommodationDetailSnapshot>() -> { // MISS인 경우 분산 락 진입
			}
		}

		// Redis miss라면 이미 진행 중인 fallback DB 조회에 합류해 추가 락과 조회를 피함
		Optional<AccommodationDetailSnapshot> coalescedSnapshot = localLoadCoordinator.joinIfRunning(
			accommodationId, () -> timedLoad(loader));
		if (coalescedSnapshot.isPresent()) {
			return coalescedSnapshot.get();
		}

		// Cache miss일 때 여러 서버 중 한 요청만 DB를 읽도록 숙소별 분산 락 사용
		// leaseTime을 지정하지 않아 긴 조회 중에는 Redisson Watchdog이 락 갱신
		RLock lock = null;
		boolean acquired = false;
		long waitStartedAt = System.nanoTime();
		AccommodationDetailCacheMetricRecorder.LockResult lockResult =
			AccommodationDetailCacheMetricRecorder.LockResult.ERROR;
		try {
			lock = redissonClient.getLock(lockKey(accommodationId));
			acquired = lock.tryLock(properties.lockWait().toMillis(), TimeUnit.MILLISECONDS);
			lockResult = acquired ? ACQUIRED : TIMEOUT;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			lockResult = INTERRUPTED;
		} catch (RuntimeException exception) {
			log.warn("숙소 상세 캐시 락 획득 실패. accommodationId={}", accommodationId, exception);
		} finally {
			metricRecorder.recordLock(lockResult, System.nanoTime() - waitStartedAt);
		}

		if (!acquired) {
			// timeout 직전에 다른 요청이 캐시를 채웠을 수 있어 DB로 우회하기 전에 한 번 더 확인
			// Redis 장애 가능성이 있는 락 오류나 인터럽트에서는 추가 조회 없이 바로 우회
			if (lockResult == TIMEOUT) {
				CacheLookup<AccommodationDetailSnapshot> timeoutLookup = redisStore.read(accommodationId);
				// hit만 즉시 반환하고, miss와 Redis 조회 실패(Failure)는 아래의 로컬 single-flight로 합류
				switch (timeoutLookup) {
					case CacheLookup.Hit<AccommodationDetailSnapshot>(var snapshot) -> {
						return resolvePositiveHit(snapshot, HIT_AFTER_WAIT);
					}
					case CacheLookup.NegativeHit<AccommodationDetailSnapshot>() -> {
						return resolveNegativeHit();
					}
					case CacheLookup.Failure<AccommodationDetailSnapshot>() -> {
					}
					case CacheLookup.Miss<AccommodationDetailSnapshot>() -> {
					}
				}
			}
			return loadWithoutCache(accommodationId, loader);
		}

		try {
			// 락을 기다리는 동안 앞선 요청이 값을 채웠을 수 있으므로 DB 조회 전에 다시 확인
			CacheLookup<AccommodationDetailSnapshot> secondLookup = redisStore.read(accommodationId);
			switch (secondLookup) {
				case CacheLookup.Hit<AccommodationDetailSnapshot>(var snapshot) -> {
					return resolvePositiveHit(snapshot, HIT_AFTER_WAIT);
				}
				case CacheLookup.NegativeHit<AccommodationDetailSnapshot>() -> {
					return resolveNegativeHit();
				}
				case CacheLookup.Failure<AccommodationDetailSnapshot>() -> {
					// Redis를 사용할 수 없으므로 락을 먼저 해제한 뒤 DB로 우회
				}
				case CacheLookup.Miss<AccommodationDetailSnapshot>() -> {
					// 조회 중 데이터가 변경되면 무효화가 이 토큰을 지워 오래된 결과의 저장을 거부
					String loadPermit = redisStore.acquireLoadPermit(accommodationId);
					if (loadPermit != null) {
						return loadAndCache(accommodationId, loadPermit, loader);
					}
					// 쓰기 허가가 없으면 캐시를 채울 수 없으므로 락 밖에서 DB로 우회
				}
			}
		} finally {
			release(lock, accommodationId);
		}

		// 캐시에 저장하지 않는 DB 조회는 분산 락의 보호 대상이 아님
		return loadWithoutCache(accommodationId, loader);
	}

	public void evict(
		Long accommodationId,
		AccommodationDetailCacheInvalidationReason reason
	) {
		if (!properties.enabled()) {
			return;
		}

		// DB 변경은 이미 커밋됐으므로 캐시 장애가 정상 요청을 실패시키지 않게 best-effort로 처리
		try {
			evictInternal(accommodationId, reason, AFTER_COMMIT);
		} catch (RuntimeException exception) {
			log.warn("숙소 상세 캐시 무효화 실패. accommodationId={}, reason={}",
				accommodationId, reason, exception);
		}
	}

	/**
	 * outbox 소비자가 삭제 실패를 감지해 Kafka retry/DLT로 넘길 수 있도록 예외를 전파
	 */
	public void evictOrThrow(
		Long accommodationId,
		AccommodationDetailCacheInvalidationReason reason
	) {
		if (!properties.enabled()) {
			return;
		}

		evictInternal(accommodationId, reason, OUTBOX);
	}

	private void evictInternal(
		Long accommodationId,
		AccommodationDetailCacheInvalidationReason reason,
		AccommodationDetailCacheMetricRecorder.EvictionSource source
	) {
		// Redis 삭제가 지연돼도 새 요청이 변경 전 fallback 조회에 합류하지 않게 먼저 분리
		localLoadCoordinator.detachCurrent(accommodationId);
		try {
			redisStore.invalidate(accommodationId);
			metricRecorder.recordEviction(source, reason, SUCCESS);
		} catch (RuntimeException exception) {
			metricRecorder.recordEviction(
				source, reason, AccommodationDetailCacheMetricRecorder.OperationResult.ERROR);
			throw exception;
		}
	}

	private AccommodationDetailSnapshot loadAndCache(
		Long accommodationId,
		String loadPermit,
		Supplier<AccommodationDetailSnapshot> loader
	) {
		try {
			AccommodationDetailSnapshot snapshot = timedLoad(loader);
			redisStore.writeFound(accommodationId, loadPermit, snapshot);
			metricRecorder.recordRequest(LOADED);
			return snapshot;
		} catch (AccommodationNotFoundException exception) {
			// 존재하지 않는 ID도 짧게 캐시해 반복적인 404 조회가 DB까지 도달하지 않게 함
			redisStore.writeNotFound(accommodationId, loadPermit);
			metricRecorder.recordRequest(NEGATIVE_LOADED);
			throw exception;
		} finally {
			redisStore.releaseLoadPermit(accommodationId, loadPermit);
		}
	}

	private AccommodationDetailSnapshot loadWithoutCache(
		Long accommodationId,
		Supplier<AccommodationDetailSnapshot> loader
	) {
		// 분산 락을 얻지 못한 경로에서는 stale write를 피하기 위해 캐시에 저장하지 않음
		// 대신 같은 JVM에서 진행 중인 우회 DB 조회만 Future로 공유
		return localLoadCoordinator.loadOrJoin(
			accommodationId,
			() -> timedLoad(loader)
		);
	}

	private AccommodationDetailSnapshot timedLoad(Supplier<AccommodationDetailSnapshot> loader) {
		// 캐시 miss 이후 실제 DB 조회 시간과 결과를 별도로 관측
		long startedAt = System.nanoTime();
		AccommodationDetailCacheMetricRecorder.LoadResult result = ERROR;
		try {
			AccommodationDetailSnapshot snapshot = loader.get();
			result = FOUND;
			return snapshot;
		} catch (AccommodationNotFoundException exception) {
			result = NOT_FOUND;
			throw exception;
		} finally {
			metricRecorder.recordLoad(result, System.nanoTime() - startedAt);
		}
	}

	private AccommodationDetailSnapshot resolvePositiveHit(
		AccommodationDetailSnapshot snapshot,
		AccommodationDetailCacheMetricRecorder.RequestResult positiveResult
	) {
		metricRecorder.recordRequest(positiveResult);
		return snapshot;
	}

	private AccommodationDetailSnapshot resolveNegativeHit() {
		metricRecorder.recordRequest(NEGATIVE_HIT);
		throw new AccommodationNotFoundException();
	}

	private void release(RLock lock, Long accommodationId) {
		if (lock == null) {
			return;
		}
		try {
			// Redisson unlock은 owner token을 원자적으로 확인하므로 다른 소유자의 락을 지우지 않음
			// 별도의 소유권 조회가 실패해 watchdog 갱신만 남는 상황을 피하려고 바로 해제를 시도
			lock.unlock();
		} catch (RuntimeException exception) {
			log.warn("숙소 상세 캐시 락 해제 실패. accommodationId={}", accommodationId, exception);
		}
	}

	private String lockKey(Long accommodationId) {
		return LOCK_KEY_PREFIX + "{" + accommodationId + "}";
	}
}
