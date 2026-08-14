package kr.kro.airbob.domain.accommodation.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

@ConfigurationProperties(prefix = "accommodation.detail-cache")
public record AccommodationDetailCacheProperties(
	boolean enabled,
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
