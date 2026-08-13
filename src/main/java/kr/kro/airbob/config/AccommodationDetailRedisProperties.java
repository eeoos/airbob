package kr.kro.airbob.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

@ConfigurationProperties(prefix = "accommodation.detail-cache.redis")
public record AccommodationDetailRedisProperties(
	String host,
	int port,
	int database,
	String username,
	String password
) {
	public AccommodationDetailRedisProperties {
		Assert.hasText(host, "accommodation.detail-cache.redis.host must not be blank");
		Assert.isTrue(port > 0 && port <= 65_535,
			"accommodation.detail-cache.redis.port must be between 1 and 65535");
		Assert.isTrue(database >= 0,
			"accommodation.detail-cache.redis.database must not be negative");
	}
}
