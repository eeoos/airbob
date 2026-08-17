package kr.kro.airbob.domain.accommodation.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.config.AccommodationDetailRedisConfig;
import kr.kro.airbob.config.PerformanceLabRedisEndpointConfiguration;
import kr.kro.airbob.config.RedisConfig;

@Testcontainers
class PerformanceLabRedisEndpointIsolationIntegrationTest {

	@Container
	private static final GenericContainer<?> generalRedis = new GenericContainer<>("redis:7.2-alpine")
		.withExposedPorts(6379);

	@Container
	private static final GenericContainer<?> cacheRedis = new GenericContainer<>("redis:7.2-alpine")
		.withExposedPorts(6379);

	@Test
	void routesGeneralAndAccommodationDetailCacheKeysToSeparateRedisContainers() {
		runner().run(context -> {
			assertThat(context).hasNotFailed();
			StringRedisTemplate generalRedisTemplate = context.getBean(StringRedisTemplate.class);
			StringRedisTemplate cacheRedisTemplate = cacheRedisTemplate(context.getBean(
				AccommodationDetailRedisClient.class));

			generalRedisTemplate.opsForValue().set("SESSION:lab-member", "general");
			cacheRedisTemplate.opsForValue().set(
				"airbob:cache:accommodation-detail:v1:1", "cache");

			assertThat(generalRedisTemplate.hasKey("SESSION:lab-member")).isTrue();
			assertThat(cacheRedisTemplate.hasKey("SESSION:lab-member")).isFalse();
			assertThat(cacheRedisTemplate.hasKey(
				"airbob:cache:accommodation-detail:v1:1")).isTrue();
			assertThat(generalRedisTemplate.hasKey(
				"airbob:cache:accommodation-detail:v1:1")).isFalse();

			cacheRedisTemplate.getConnectionFactory().getConnection()
				.serverCommands().flushDb();

			assertThat(generalRedisTemplate.hasKey("SESSION:lab-member")).isTrue();
		});
	}

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
			.withUserConfiguration(
				RedisConfig.class,
				AccommodationDetailRedisConfig.class,
				AccommodationDetailCacheConfiguration.class,
				PerformanceLabRedisEndpointConfiguration.class)
			.withPropertyValues(
				"spring.profiles.active=performance-lab",
				"spring.data.redis.host=" + generalRedis.getHost(),
				"spring.data.redis.port=" + generalRedis.getMappedPort(6379),
				"accommodation.detail-cache.redis.host=" + cacheRedis.getHost(),
				"accommodation.detail-cache.redis.port=" + cacheRedis.getMappedPort(6379),
				"accommodation.detail-cache.redis.database=0",
				"accommodation.detail-cache.ttl=10m",
				"accommodation.detail-cache.ttl-jitter=2m",
				"accommodation.detail-cache.negative-ttl=45s",
				"accommodation.detail-cache.negative-ttl-jitter=15s",
				"accommodation.detail-cache.lock-wait=2s",
				"accommodation.detail-cache.local-load-wait=5s",
				"accommodation.detail-cache.load-permit-ttl=30s",
				"accommodation.detail-cache.redis-connect-timeout=1s",
				"accommodation.detail-cache.redis-command-timeout=1s");
	}

	private StringRedisTemplate cacheRedisTemplate(AccommodationDetailRedisClient redisClient) {
		return (StringRedisTemplate)ReflectionTestUtils.getField(redisClient, "redisTemplate");
	}
}
