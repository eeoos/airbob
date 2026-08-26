package kr.kro.airbob.domain.accommodation.cache;

import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.OperationResult.ERROR;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.OperationResult.SUCCESS;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RedisOperation.DELETE;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RedisOperation.GET;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RedisOperation.PUT;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.accommodation.cache.config.AccommodationDetailCacheJitter;
import kr.kro.airbob.domain.accommodation.cache.config.AccommodationDetailCacheProperties;
import kr.kro.airbob.domain.accommodation.cache.redis.AccommodationDetailRedisClient;
import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;
import lombok.extern.slf4j.Slf4j;

/**
 * 숙소 상세 캐시의 Redis 저장 형식과 원자 연산을 담당함
 */
@Slf4j
final class AccommodationDetailRedisStore {

	// v1은 직렬화 payload 버전
	// 호환되지 않는 구조 변경 시 key namespace를 올림
	private static final String CACHE_KEY_PREFIX = "airbob:cache:accommodation-detail:v1:";
	private static final String LOAD_PERMIT_KEY_PREFIX = "airbob:cache:accommodation-detail:load-permit:";

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
		return redis.call('DEL', KEYS[1], KEYS[2])
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
	private final ObjectMapper objectMapper;
	private final AccommodationDetailCacheMetricRecorder metricRecorder;
	private final AccommodationDetailCacheJitter jitter;
	private final AccommodationDetailCacheProperties properties;

	AccommodationDetailRedisStore(
		AccommodationDetailRedisClient redisClient,
		ObjectMapper objectMapper,
		AccommodationDetailCacheMetricRecorder metricRecorder,
		AccommodationDetailCacheJitter jitter,
		AccommodationDetailCacheProperties properties
	) {
		this.redisClient = redisClient;
		this.objectMapper = objectMapper;
		this.metricRecorder = metricRecorder;
		this.jitter = jitter;
		this.properties = properties;
	}

	CacheLookup<AccommodationDetailSnapshot> read(Long accommodationId) {
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
			metricRecorder.recordRedis(GET, ERROR);
			log.warn("숙소 상세 캐시 역직렬화 실패. accommodationId={}", accommodationId, exception);
			deleteCorruptEntry(accommodationId);
			return CacheLookup.miss();
		} catch (RuntimeException exception) {
			// Redis 장애는 miss와 구분해 이후 분산 락 시도까지 건너뛰고 DB로 우회
			metricRecorder.recordRedis(GET, ERROR);
			log.warn("숙소 상세 캐시 조회 실패. accommodationId={}", accommodationId, exception);
			return CacheLookup.failure();
		}
	}

	String acquireLoadPermit(Long accommodationId) {
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

	void writeFound(
		Long accommodationId,
		String loadPermit,
		AccommodationDetailSnapshot snapshot
	) {
		write(accommodationId, loadPermit, AccommodationDetailCacheValue.found(snapshot), ttlWithJitter());
	}

	void writeNotFound(Long accommodationId, String loadPermit) {
		write(accommodationId, loadPermit, AccommodationDetailCacheValue.notFound(), negativeTtlWithJitter());
	}

	void releaseLoadPermit(Long accommodationId, String permit) {
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

	void invalidate(Long accommodationId) {
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
		} catch (RuntimeException exception) {
			metricRecorder.recordRedis(DELETE, ERROR);
			throw exception;
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
			metricRecorder.recordRedis(PUT, ERROR);
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
	private String cacheKey(Long accommodationId) {
		return CACHE_KEY_PREFIX + "{" + accommodationId + "}";
	}

	private String loadPermitKey(Long accommodationId) {
		return LOAD_PERMIT_KEY_PREFIX + "{" + accommodationId + "}";
	}
}

/**
 * Redis 조회 결과를 값과 boolean 조합 대신 서로 배타적인 상태로 표현
 * Miss는 정상적인 빈 캐시라 락을 거쳐 값을 채우고, Failure는 Redis 장애라 캐시를 우회
 * NegativeHit는 실제 Miss와 구분해 존재하지 않는 숙소의 반복 DB 조회를 막음
 */
sealed interface CacheLookup<T> {
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

	static <T> CacheLookup<T> hit(T value) {
		return new Hit<>(value);
	}

	static <T> CacheLookup<T> negativeHit() {
		return new NegativeHit<>();
	}

	static <T> CacheLookup<T> miss() {
		return new Miss<>();
	}

	static <T> CacheLookup<T> failure() {
		return new Failure<>();
	}
}
