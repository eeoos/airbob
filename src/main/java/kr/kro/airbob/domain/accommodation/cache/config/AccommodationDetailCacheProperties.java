package kr.kro.airbob.domain.accommodation.cache.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * 숙소 상세 캐시의 만료, 대기, 장애 감지 시간을 정의
 *
 * @param ttl 정상 캐시의 기본 유지 시간
 * @param ttlJitter 정상 캐시 만료 시점에 추가할 최대 분산 범위
 * @param negativeTtl 404 캐시의 기본 유지 시간
 * @param negativeTtlJitter 404 캐시 만료 시점에 추가할 최대 분산 범위
 * @param lockWait 분산 락 획득을 기다리는 최대 시간
 * @param localLoadWait 같은 JVM의 선행 DB 조회를 기다리는 최대 시간
 * @param loadPermitTtl 캐시 쓰기 허가 토큰의 최대 유지 시간
 * @param redisConnectTimeout 캐시 Redis 연결 제한 시간
 * @param redisCommandTimeout 캐시 Redis 명령 제한 시간
 */
@ConfigurationProperties(prefix = "accommodation.detail-cache")
public record AccommodationDetailCacheProperties(
	Duration ttl,
	Duration ttlJitter,
	Duration negativeTtl,
	Duration negativeTtlJitter,
	Duration lockWait,
	Duration localLoadWait,
	Duration loadPermitTtl,
	Duration redisConnectTimeout,
	Duration redisCommandTimeout
) {
	public AccommodationDetailCacheProperties {
		Assert.notNull(ttl, "accommodation.detail-cache.ttl must not be null");
		Assert.notNull(ttlJitter, "accommodation.detail-cache.ttl-jitter must not be null");
		Assert.notNull(negativeTtl, "accommodation.detail-cache.negative-ttl must not be null");
		Assert.notNull(negativeTtlJitter, "accommodation.detail-cache.negative-ttl-jitter must not be null");
		Assert.notNull(lockWait, "accommodation.detail-cache.lock-wait must not be null");
		Assert.notNull(localLoadWait, "accommodation.detail-cache.local-load-wait must not be null");
		Assert.notNull(loadPermitTtl, "accommodation.detail-cache.load-permit-ttl must not be null");
		Assert.notNull(redisConnectTimeout,
			"accommodation.detail-cache.redis-connect-timeout must not be null");
		Assert.notNull(redisCommandTimeout,
			"accommodation.detail-cache.redis-command-timeout must not be null");
		Assert.isTrue(!ttl.isNegative() && !ttl.isZero(),
			"accommodation.detail-cache.ttl must be positive");
		Assert.isTrue(!negativeTtl.isNegative() && !negativeTtl.isZero(),
			"accommodation.detail-cache.negative-ttl must be positive");
		Assert.isTrue(!ttlJitter.isNegative() && !negativeTtlJitter.isNegative(),
			"accommodation.detail-cache jitter must not be negative");
		Assert.isTrue(!lockWait.isNegative() && !lockWait.isZero(),
			"accommodation.detail-cache.lock-wait must be positive");
		Assert.isTrue(!localLoadWait.isNegative() && !localLoadWait.isZero(),
			"accommodation.detail-cache.local-load-wait must be positive");
		Assert.isTrue(!loadPermitTtl.isNegative() && !loadPermitTtl.isZero(),
			"accommodation.detail-cache.load-permit-ttl must be positive");
		Assert.isTrue(!redisConnectTimeout.isNegative() && !redisConnectTimeout.isZero(),
			"accommodation.detail-cache.redis-connect-timeout must be positive");
		Assert.isTrue(!redisCommandTimeout.isNegative() && !redisCommandTimeout.isZero(),
			"accommodation.detail-cache.redis-command-timeout must be positive");
	}
}
