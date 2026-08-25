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
import java.util.Objects;
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

import kr.kro.airbob.domain.accommodation.cache.config.AccommodationDetailCacheJitter;
import kr.kro.airbob.domain.accommodation.cache.config.AccommodationDetailCacheProperties;
import kr.kro.airbob.domain.accommodation.cache.redis.AccommodationDetailRedisClient;
import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class AccommodationDetailCache {

	// v1은 직렬화 payload 버전
	// 호환되지 않는 구조 변경 시 key namespace를 올림
	private static final String CACHE_KEY_PREFIX = "airbob:cache:accommodation-detail:v1:";
	private static final String LOAD_PERMIT_KEY_PREFIX = "airbob:cache:accommodation-detail:load-permit:";
	private static final String LOCK_KEY_PREFIX = "airbob:lock:accommodation-detail:";
	// DB 조회를 시작할 때 발급한 토큰이 그대로인 경우에만 저장
	// 조회 도중 무효화가 토큰을 삭제했다면 오래된 조회 결과는 캐시에 쓰지 않음
	private static final DefaultRedisScript<Long> WRITE_IF_PERMITTED_SCRIPT = new DefaultRedisScript<>("""
		local current = redis.call('GET', KEYS[1])
		if current ~= ARGV[1] then
			return 0
		end
		redis.call('PSETEX', KEYS[2], ARGV[2], ARGV[3])
		redis.call('DEL', KEYS[1])
		return 1
		""", Long.class);

	// 캐시 값과 진행 중인 쓰기 허가를 함께 지워 무효화와 캐시 저장 사이의 경쟁을 차단
	private static final DefaultRedisScript<Long> INVALIDATE_SCRIPT = new DefaultRedisScript<>("""
		redis.call('DEL', KEYS[1])
		redis.call('DEL', KEYS[2])
		return 1
		""", Long.class);

	// 분산 락 안에서만 호출되므로 토큰은 덮어써 발급
	// TTL이 먼저 끝나면 후속 write가 거부되어 오래된 결과를 안전하게 버림
	private static final DefaultRedisScript<Long> ACQUIRE_LOAD_PERMIT_SCRIPT = new DefaultRedisScript<>("""
		redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[1])
		return 1
		""", Long.class);

	// 자신이 발급받은 토큰일 때만 삭제해 이후 요청의 토큰을 잘못 지우지 않음
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

	// Redis 장애나 락 timeout으로 DB를 직접 볼 때 같은 JVM의 중복 조회를 하나로 합침
	// 이 경로는 Redis에 값을 쓰지 않으며, 무효화와 정확히 겹친 응답의 최신성은 best-effort로 둔다.
	// 캐시에 오래된 값이 다시 저장되는 문제는 아래의 Redis 쓰기 허가 토큰이 차단한다.
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

		// 캐시가 정상이면 락이나 DB를 사용하지 않음
		CacheLookup<AccommodationDetailSnapshot> firstLookup = read(accommodationId);
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
		CompletableFuture<AccommodationDetailSnapshot> localLoad = localLoads.get(accommodationId);
		if (localLoad != null) {
			return awaitLocalLoad(accommodationId, localLoad, loader);
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
				CacheLookup<AccommodationDetailSnapshot> timeoutLookup = read(accommodationId);
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
			CacheLookup<AccommodationDetailSnapshot> secondLookup = read(accommodationId);
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
					String loadPermit = acquireLoadPermit(accommodationId);
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
		CompletableFuture<AccommodationDetailSnapshot> localLoad = localLoads.get(accommodationId);
		try {
			// Lua로 쓰기 허가와 캐시 값을 원자적으로 삭제해 stale refill을 막음
			Long invalidated = redisClient.execute(
				INVALIDATE_SCRIPT,
				List.of(loadPermitKey(accommodationId), cacheKey(accommodationId))
			);
			if (invalidated == null) {
				throw new IllegalStateException("캐시 무효화 스크립트가 결과를 반환하지 않음");
			}
			metricRecorder.recordRedis(DELETE, SUCCESS);
			metricRecorder.recordEviction(source, reason, SUCCESS);
		} catch (RuntimeException exception) {
			metricRecorder.recordRedis(DELETE, AccommodationDetailCacheMetricRecorder.OperationResult.ERROR);
			metricRecorder.recordEviction(
				source, reason, AccommodationDetailCacheMetricRecorder.OperationResult.ERROR);
			throw exception;
		} finally {
			// 기존 요청은 진행 중인 조회로 끝내고, 이후 요청만 이전 조회에 합류하지 않게 연결을 끊는다.
			if (localLoad != null) {
				localLoads.remove(accommodationId, localLoad);
			}
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
			// 존재하지 않는 ID도 짧게 캐시해 반복적인 404 조회가 DB까지 도달하지 않게 함
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
		// 분산 락을 얻지 못한 경로에서는 stale write를 피하기 위해 캐시에 저장하지 않음
		// 대신 같은 JVM에서 진행 중인 우회 DB 조회만 Future로 공유
		CompletableFuture<AccommodationDetailSnapshot> newLoad = new CompletableFuture<>();
		CompletableFuture<AccommodationDetailSnapshot> existing =
			localLoads.putIfAbsent(accommodationId, newLoad);
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
			// 느린 leader를 더 기다리지 않고 이 요청은 독립적으로 DB를 조회
			localLoads.remove(accommodationId, load);
			return timedUncachedLoad(loader);
		} catch (ExecutionException exception) {
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

	private CacheLookup<AccommodationDetailSnapshot> read(Long accommodationId) {
		try {
			String json = redisClient.get(cacheKey(accommodationId));
			if (json == null) {
				metricRecorder.recordRedis(GET, SUCCESS);
				return CacheLookup.miss();
			}
			AccommodationDetailCacheValue value = objectMapper.readValue(
				json, AccommodationDetailCacheValue.class);
			// 역직렬화는 성공했더라도 status와 snapshot 조합이 맞지 않으면 손상된 값으로 취급
			if (value.status() == null
				|| value.status() == AccommodationDetailCacheValue.Status.FOUND && value.snapshot() == null
				|| value.status() == AccommodationDetailCacheValue.Status.NOT_FOUND && value.snapshot() != null) {
				throw new JsonProcessingException("invalid accommodation detail cache state") { };
			}
			metricRecorder.recordRedis(GET, SUCCESS);
			return switch (value.status()) {
				case FOUND -> CacheLookup.hit(value.snapshot());
				case NOT_FOUND -> CacheLookup.negativeHit();
			};
		} catch (JsonProcessingException exception) {
			// 손상된 엔트리는 삭제한 뒤 정상적인 cache miss와 동일하게 복구
			metricRecorder.recordRedis(GET, AccommodationDetailCacheMetricRecorder.OperationResult.ERROR);
			log.warn("숙소 상세 캐시 역직렬화 실패. accommodationId={}", accommodationId, exception);
			deleteCorruptEntry(accommodationId);
			return CacheLookup.miss();
		} catch (RuntimeException exception) {
			// Redis 장애는 miss와 구분해 이후 분산 락 시도까지 건너뛰고 DB로 우회
			metricRecorder.recordRedis(GET, AccommodationDetailCacheMetricRecorder.OperationResult.ERROR);
			log.warn("숙소 상세 캐시 조회 실패. accommodationId={}", accommodationId, exception);
			return CacheLookup.failure();
		}
	}

	private String acquireLoadPermit(Long accommodationId) {
		try {
			// UUID가 조회 세대를 식별해 무효화 이전 loader와 이후 loader를 구분
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
		// 캐시는 보조 저장소이므로 직렬화나 Redis 쓰기 실패가 DB에서 읽은 원본 응답을 실패시키지 않음
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
			// 0은 무효화로 토큰이 사라져 오래된 결과를 버린 정상적인 fencing 결과
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
			// Redisson unlock은 owner token을 원자적으로 확인하므로 다른 소유자의 락을 지우지 않는다.
			// 별도의 소유권 조회가 실패해 watchdog 갱신만 남는 상황을 피하려고 바로 해제를 시도한다.
			lock.unlock();
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
		// 만료 시점을 분산해 많은 키가 동시에 DB로 몰리는 cache avalanche를 완화
		long jitterMillis = this.jitter.nextMillis(jitter.toMillis() + 1);
		return base.plusMillis(jitterMillis);
	}

	// cache와 load-permit에 같은 {id} hash tag를 사용해 Redis Cluster에서도 Lua multi-key 연산을 보장
	// lock도 같은 규칙을 사용해 숙소별 Redis 키의 위치와 이름을 일관되게 유지
	private String cacheKey(Long accommodationId) {
		return CACHE_KEY_PREFIX + "{" + accommodationId + "}";
	}

	private String loadPermitKey(Long accommodationId) {
		return LOAD_PERMIT_KEY_PREFIX + "{" + accommodationId + "}";
	}

	private String lockKey(Long accommodationId) {
		return LOCK_KEY_PREFIX + "{" + accommodationId + "}";
	}

	/**
	 * Redis 조회 결과를 값과 boolean 조합 대신 서로 배타적인 상태로 표현
	 * Miss는 정상적인 빈 캐시라 락을 거쳐 값을 채우고, Failure는 Redis 장애라 캐시를 우회
	 * NegativeHit는 실제 Miss와 구분해 존재하지 않는 숙소의 반복 DB 조회를 막음
	 */
	private sealed interface CacheLookup<T> {
		record Hit<T>(T value) implements CacheLookup<T> {
			public Hit {
				Objects.requireNonNull(value);
			}
		}

		record NegativeHit<T>() implements CacheLookup<T> {
		}

		record Miss<T>() implements CacheLookup<T> {
		}

		record Failure<T>() implements CacheLookup<T> {
		}

		private static <T> CacheLookup<T> hit(T value) {
			return new Hit<>(value);
		}

		private static <T> CacheLookup<T> negativeHit() {
			return new NegativeHit<>();
		}

		private static <T> CacheLookup<T> miss() {
			return new Miss<>();
		}

		private static <T> CacheLookup<T> failure() {
			return new Failure<>();
		}
	}
}
