package kr.kro.airbob.domain.accommodation.cache;

import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.LoadResult.ERROR;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.LoadResult.FOUND;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.LoadResult.NOT_FOUND;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.LockResult.ACQUIRED;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.LockResult.INTERRUPTED;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.LockResult.TIMEOUT;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.OperationResult.SUCCESS;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RedisOperation.DELETE;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RedisOperation.GET;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RedisOperation.PUT;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.COALESCED;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.HIT;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.HIT_AFTER_WAIT;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.LOADED;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.NEGATIVE_COALESCED;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.NEGATIVE_HIT;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.NEGATIVE_LOADED;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class AccommodationDetailCache {

	private static final String CACHE_KEY_PREFIX = "airbob:cache:accommodation-detail:v1:";
	private static final String LOAD_PERMIT_KEY_PREFIX = "airbob:cache:accommodation-detail:load-permit:";
	private static final String LOCK_KEY_PREFIX = "airbob:lock:accommodation-detail:";
	private static final DefaultRedisScript<Long> WRITE_IF_PERMITTED_SCRIPT = new DefaultRedisScript<>("""
		local current = redis.call('GET', KEYS[1])
		if current ~= ARGV[1] then
			return 0
		end
		redis.call('PSETEX', KEYS[2], ARGV[2], ARGV[3])
		redis.call('DEL', KEYS[1])
		return 1
		""", Long.class);
	private static final DefaultRedisScript<Long> INVALIDATE_SCRIPT = new DefaultRedisScript<>("""
		redis.call('DEL', KEYS[1])
		redis.call('DEL', KEYS[2])
		return 1
		""", Long.class);
	private static final DefaultRedisScript<Long> ACQUIRE_LOAD_PERMIT_SCRIPT = new DefaultRedisScript<>("""
		redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[1])
		return 1
		""", Long.class);
	private static final DefaultRedisScript<Long> RELEASE_LOAD_PERMIT_SCRIPT = new DefaultRedisScript<>("""
		if redis.call('GET', KEYS[1]) ~= ARGV[1] then
			return 0
		end
		return redis.call('DEL', KEYS[1])
		""", Long.class);

	private final AccommodationDetailRedisClient redisClient;
	private final RedissonClient redissonClient;
	private final ObjectMapper objectMapper;
	private final AccommodationDetailCacheMetricRecorder metricRecorder;
	private final AccommodationDetailCacheJitter jitter;
	private final AccommodationDetailCacheProperties properties;
	private final ConcurrentHashMap<Long, CompletableFuture<AccommodationDetailSnapshot>> localLoads =
		new ConcurrentHashMap<>();

	public AccommodationDetailCache(
		AccommodationDetailRedisClient redisClient,
		@Qualifier("accommodationDetailRedissonClient") RedissonClient redissonClient,
		ObjectMapper objectMapper,
		AccommodationDetailCacheMetricRecorder metricRecorder,
		AccommodationDetailCacheJitter jitter,
		AccommodationDetailCacheProperties properties
	) {
		this.redisClient = redisClient;
		this.redissonClient = redissonClient;
		this.objectMapper = objectMapper;
		this.metricRecorder = metricRecorder;
		this.jitter = jitter;
		this.properties = properties;
	}

	public AccommodationDetailSnapshot getOrLoad(
		Long accommodationId,
		Supplier<AccommodationDetailSnapshot> loader
	) {
		if (!properties.enabled()) {
			return timedUncachedLoad(loader);
		}
		CompletableFuture<AccommodationDetailSnapshot> localLoad = localLoads.get(accommodationId);
		if (localLoad != null) {
			return awaitLocalLoad(accommodationId, localLoad, loader);
		}

		CacheLookup firstLookup = read(accommodationId);
		if (firstLookup.failed()) {
			return loadWithoutCache(accommodationId, loader);
		}
		if (firstLookup.value() != null) {
			return resolveHit(firstLookup.value(), HIT);
		}

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
			return loadWithoutCache(accommodationId, loader);
		}

		try {
			CacheLookup secondLookup = read(accommodationId);
			if (secondLookup.failed()) {
				return loadWithoutCache(accommodationId, loader);
			}
			if (secondLookup.value() != null) {
				return resolveHit(secondLookup.value(), HIT_AFTER_WAIT);
			}

			String loadPermit = acquireLoadPermit(accommodationId);
			if (loadPermit == null) {
				return loadWithoutCache(accommodationId, loader);
			}
			return loadAndCache(accommodationId, loadPermit, loader);
		} finally {
			release(lock, accommodationId);
		}
	}

	public void evict(
		Long accommodationId,
		AccommodationDetailCacheInvalidationReason reason
	) {
		if (!properties.enabled()) {
			return;
		}
		try {
			evictInternal(accommodationId, reason);
		} catch (RuntimeException exception) {
			log.warn("숙소 상세 캐시 무효화 실패. accommodationId={}, reason={}",
				accommodationId, reason, exception);
		}
	}

	private void evictInternal(
		Long accommodationId,
		AccommodationDetailCacheInvalidationReason reason
	) {
		CompletableFuture<AccommodationDetailSnapshot> localLoad = localLoads.remove(accommodationId);
		if (localLoad != null) {
			localLoad.completeExceptionally(new LocalLoadInvalidatedException());
		}
		try {
			Long invalidated = redisClient.execute(
				INVALIDATE_SCRIPT,
				List.of(loadPermitKey(accommodationId), cacheKey(accommodationId))
			);
			if (invalidated == null) {
				throw new IllegalStateException("캐시 무효화 스크립트가 결과를 반환하지 않음");
			}
			metricRecorder.recordRedis(DELETE, SUCCESS);
			metricRecorder.recordEviction(reason, SUCCESS);
		} catch (RuntimeException exception) {
			metricRecorder.recordRedis(DELETE, AccommodationDetailCacheMetricRecorder.OperationResult.ERROR);
			metricRecorder.recordEviction(reason, AccommodationDetailCacheMetricRecorder.OperationResult.ERROR);
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
			write(accommodationId, loadPermit, AccommodationDetailCacheValue.found(snapshot), ttlWithJitter());
			metricRecorder.recordRequest(LOADED);
			return snapshot;
		} catch (AccommodationNotFoundException exception) {
			write(accommodationId, loadPermit, AccommodationDetailCacheValue.notFound(), negativeTtlWithJitter());
			metricRecorder.recordRequest(NEGATIVE_LOADED);
			throw exception;
		} finally {
			releaseLoadPermit(accommodationId, loadPermit);
		}
	}

	private AccommodationDetailSnapshot loadWithoutCache(
		Long accommodationId,
		Supplier<AccommodationDetailSnapshot> loader
	) {
		CompletableFuture<AccommodationDetailSnapshot> newLoad = new CompletableFuture<>();
		CompletableFuture<AccommodationDetailSnapshot> existing = localLoads.putIfAbsent(
			accommodationId, newLoad);
		if (existing != null) {
			return awaitLocalLoad(accommodationId, existing, loader);
		}

		try {
			AccommodationDetailSnapshot snapshot = timedLoad(loader);
			newLoad.complete(snapshot);
			metricRecorder.recordRequest(LOADED);
			return snapshot;
		} catch (AccommodationNotFoundException exception) {
			newLoad.completeExceptionally(exception);
			metricRecorder.recordRequest(NEGATIVE_LOADED);
			throw exception;
		} catch (RuntimeException exception) {
			newLoad.completeExceptionally(exception);
			throw exception;
		} finally {
			localLoads.remove(accommodationId, newLoad);
		}
	}

	private AccommodationDetailSnapshot awaitLocalLoad(
		Long accommodationId,
		CompletableFuture<AccommodationDetailSnapshot> load,
		Supplier<AccommodationDetailSnapshot> loader
	) {
		try {
			AccommodationDetailSnapshot snapshot = load.get(
				properties.localLoadWait().toMillis(), TimeUnit.MILLISECONDS);
			metricRecorder.recordRequest(COALESCED);
			return snapshot;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("숙소 상세 단일 조회 대기 중 인터럽트됨", exception);
		} catch (TimeoutException exception) {
			if (localLoads.remove(accommodationId, load)) {
				return timedUncachedLoad(loader);
			}
			return getOrLoad(accommodationId, loader);
		} catch (ExecutionException exception) {
			if (exception.getCause() instanceof LocalLoadInvalidatedException) {
				return getOrLoad(accommodationId, loader);
			}
			if (exception.getCause() instanceof AccommodationNotFoundException notFoundException) {
				metricRecorder.recordRequest(NEGATIVE_COALESCED);
				throw notFoundException;
			}
			if (exception.getCause() instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("숙소 상세 단일 조회가 실패함", exception.getCause());
		}
	}

	private AccommodationDetailSnapshot timedUncachedLoad(
		Supplier<AccommodationDetailSnapshot> loader
	) {
		try {
			AccommodationDetailSnapshot snapshot = timedLoad(loader);
			metricRecorder.recordRequest(LOADED);
			return snapshot;
		} catch (AccommodationNotFoundException exception) {
			metricRecorder.recordRequest(NEGATIVE_LOADED);
			throw exception;
		}
	}

	private AccommodationDetailSnapshot timedLoad(Supplier<AccommodationDetailSnapshot> loader) {
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

	private AccommodationDetailSnapshot resolveHit(
		AccommodationDetailCacheValue cacheValue,
		AccommodationDetailCacheMetricRecorder.RequestResult positiveResult
	) {
		if (cacheValue.status() == AccommodationDetailCacheValue.Status.NOT_FOUND) {
			metricRecorder.recordRequest(NEGATIVE_HIT);
			throw new AccommodationNotFoundException();
		}
		metricRecorder.recordRequest(positiveResult);
		return cacheValue.snapshot();
	}

	private CacheLookup read(Long accommodationId) {
		try {
			String json = redisClient.get(cacheKey(accommodationId));
			if (json == null) {
				metricRecorder.recordRedis(GET, SUCCESS);
				return CacheLookup.miss();
			}
			AccommodationDetailCacheValue value = objectMapper.readValue(
				json, AccommodationDetailCacheValue.class);
			if (value.status() == null
				|| value.status() == AccommodationDetailCacheValue.Status.FOUND && value.snapshot() == null
				|| value.status() == AccommodationDetailCacheValue.Status.NOT_FOUND && value.snapshot() != null) {
				throw new JsonProcessingException("invalid accommodation detail cache state") { };
			}
			metricRecorder.recordRedis(GET, SUCCESS);
			return CacheLookup.hit(value);
		} catch (JsonProcessingException exception) {
			metricRecorder.recordRedis(GET, AccommodationDetailCacheMetricRecorder.OperationResult.ERROR);
			log.warn("숙소 상세 캐시 역직렬화 실패. accommodationId={}", accommodationId, exception);
			deleteCorruptEntry(accommodationId);
			return CacheLookup.miss();
		} catch (RuntimeException exception) {
			metricRecorder.recordRedis(GET, AccommodationDetailCacheMetricRecorder.OperationResult.ERROR);
			log.warn("숙소 상세 캐시 조회 실패. accommodationId={}", accommodationId, exception);
			return CacheLookup.failure();
		}
	}

	private String acquireLoadPermit(Long accommodationId) {
		try {
			String permit = UUID.randomUUID().toString();
			Long acquired = redisClient.execute(
				ACQUIRE_LOAD_PERMIT_SCRIPT,
				List.of(loadPermitKey(accommodationId)),
				permit,
				Long.toString(properties.loadPermitTtl().toMillis())
			);
			return acquired != null && acquired == 1L ? permit : null;
		} catch (RuntimeException exception) {
			log.warn("숙소 상세 캐시 쓰기 허가 발급 실패. accommodationId={}", accommodationId, exception);
			return null;
		}
	}

	private void releaseLoadPermit(Long accommodationId, String permit) {
		try {
			redisClient.execute(
				RELEASE_LOAD_PERMIT_SCRIPT,
				List.of(loadPermitKey(accommodationId)),
				permit
			);
		} catch (RuntimeException exception) {
			log.warn("숙소 상세 캐시 쓰기 허가 정리 실패. accommodationId={}", accommodationId, exception);
		}
	}

	private void write(
		Long accommodationId,
		String loadPermit,
		AccommodationDetailCacheValue value,
		Duration ttl
	) {
		try {
			String json = objectMapper.writeValueAsString(value);
			Long written = redisClient.execute(
				WRITE_IF_PERMITTED_SCRIPT,
				List.of(loadPermitKey(accommodationId), cacheKey(accommodationId)),
				loadPermit,
				Long.toString(ttl.toMillis()),
				json
			);
			if (written == null) {
				throw new IllegalStateException("캐시 저장 스크립트가 결과를 반환하지 않음");
			}
			if (written == 1L) {
				metricRecorder.recordRedis(PUT, SUCCESS);
			}
		} catch (JsonProcessingException | RuntimeException exception) {
			metricRecorder.recordRedis(PUT, AccommodationDetailCacheMetricRecorder.OperationResult.ERROR);
			log.warn("숙소 상세 캐시 저장 실패. accommodationId={}", accommodationId, exception);
		}
	}

	private void deleteCorruptEntry(Long accommodationId) {
		try {
			redisClient.delete(cacheKey(accommodationId));
		} catch (RuntimeException exception) {
			log.warn("손상된 숙소 상세 캐시 삭제 실패. accommodationId={}", accommodationId, exception);
		}
	}

	private void release(RLock lock, Long accommodationId) {
		if (lock == null) {
			return;
		}
		try {
			if (lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		} catch (RuntimeException exception) {
			log.warn("숙소 상세 캐시 락 해제 실패. accommodationId={}", accommodationId, exception);
		}
	}

	private Duration ttlWithJitter() {
		return withJitter(properties.ttl(), properties.ttlJitter());
	}

	private Duration negativeTtlWithJitter() {
		return withJitter(properties.negativeTtl(), properties.negativeTtlJitter());
	}

	private Duration withJitter(Duration base, Duration jitter) {
		long jitterMillis = this.jitter.nextMillis(jitter.toMillis() + 1);
		return base.plusMillis(jitterMillis);
	}

	private String cacheKey(Long accommodationId) {
		return CACHE_KEY_PREFIX + "{" + accommodationId + "}";
	}

	private String loadPermitKey(Long accommodationId) {
		return LOAD_PERMIT_KEY_PREFIX + "{" + accommodationId + "}";
	}

	private String lockKey(Long accommodationId) {
		return LOCK_KEY_PREFIX + "{" + accommodationId + "}";
	}

	private record CacheLookup(AccommodationDetailCacheValue value, boolean failed) {
		private static CacheLookup hit(AccommodationDetailCacheValue value) {
			return new CacheLookup(value, false);
		}

		private static CacheLookup miss() {
			return new CacheLookup(null, false);
		}

		private static CacheLookup failure() {
			return new CacheLookup(null, true);
		}
	}

	private static final class LocalLoadInvalidatedException extends RuntimeException {
	}
}
