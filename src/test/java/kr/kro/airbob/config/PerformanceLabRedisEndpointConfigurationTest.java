package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheConfiguration;

class PerformanceLabRedisEndpointConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(
			PerformanceLabRedisEndpointConfiguration.class,
			AccommodationDetailRedisConfig.class,
			AccommodationDetailCacheConfiguration.class)
		.withPropertyValues(
			"spring.profiles.active=performance-lab",
			"accommodation.detail-cache.ttl=10m",
			"accommodation.detail-cache.ttl-jitter=2m",
			"accommodation.detail-cache.negative-ttl=45s",
			"accommodation.detail-cache.negative-ttl-jitter=15s",
			"accommodation.detail-cache.lock-wait=2s",
			"accommodation.detail-cache.local-load-wait=5s",
			"accommodation.detail-cache.load-permit-ttl=30s",
			"accommodation.detail-cache.redis-connect-timeout=1s",
			"accommodation.detail-cache.redis-command-timeout=1s",
			"accommodation.detail-cache.redis.database=0");

	@Test
	void rejectsTheSameGeneralAndCacheEndpoint() {
		runner
			.withPropertyValues(
				"spring.data.redis.host=redis.internal",
				"spring.data.redis.port=6379",
				"accommodation.detail-cache.redis.host=redis.internal",
				"accommodation.detail-cache.redis.port=6379")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void acceptsTwoPortsOnTheSameRedisHost() {
		runner
			.withPropertyValues(
				"spring.data.redis.host=redis.internal",
				"spring.data.redis.port=6379",
				"accommodation.detail-cache.redis.host=redis.internal",
				"accommodation.detail-cache.redis.port=6380")
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void rejectsEndpointsWhoseHostsDifferOnlyByCaseAndWhitespace() {
		runner
			.withPropertyValues(
				"spring.data.redis.host= Redis.Internal ",
				"spring.data.redis.port=6379",
				"accommodation.detail-cache.redis.host=redis.internal",
				"accommodation.detail-cache.redis.port=6379")
			.run(context -> assertThat(context).hasFailed());
	}
}
