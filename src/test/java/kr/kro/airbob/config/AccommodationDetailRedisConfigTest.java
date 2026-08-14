package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheConfiguration;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheProperties;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailRedisClient;

class AccommodationDetailRedisConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(
			AccommodationDetailRedisConfig.class,
			AccommodationDetailCacheConfiguration.class)
		.withPropertyValues(
			"accommodation.detail-cache.ttl=10m",
			"accommodation.detail-cache.ttl-jitter=2m",
			"accommodation.detail-cache.negative-ttl=45s",
			"accommodation.detail-cache.negative-ttl-jitter=15s",
			"accommodation.detail-cache.lock-wait=2s",
			"accommodation.detail-cache.local-load-wait=5s",
			"accommodation.detail-cache.load-permit-ttl=30s",
			"accommodation.detail-cache.redis-connect-timeout=1s",
			"accommodation.detail-cache.redis-command-timeout=1s",
			"accommodation.detail-cache.redis.host=127.0.0.1",
			"accommodation.detail-cache.redis.port=1",
			"accommodation.detail-cache.redis.database=0");

	@Test
	void unavailableCacheRedisDoesNotPreventContextStartup() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			var redisClient = context.getBean(AccommodationDetailRedisClient.class);
			var connectionFactory = (LettuceConnectionFactory)ReflectionTestUtils.getField(
				redisClient, "connectionFactory");
			var redissonClient = context.getBean(
				"accommodationDetailRedissonClient", RedissonClient.class);

			assertThat(connectionFactory).isNotNull();
			assertThat(connectionFactory.getHostName()).isEqualTo("127.0.0.1");
			assertThat(connectionFactory.getPort()).isEqualTo(1);
			assertThat(redissonClient.getConfig().isLazyInitialization()).isTrue();
			assertThat(redissonClient.getConfig().toYAML())
				.contains("redis://127.0.0.1:1");
		});
	}

	@Test
	void cacheLockClientUsesTheDedicatedEndpointAndLazyConnection() {
		AccommodationDetailRedisConfig redisConfig = new AccommodationDetailRedisConfig();
		var endpoint = new AccommodationDetailRedisProperties(
			"cache.internal", 6380, 3, "cache-user", "cache-password");

		var config = redisConfig.accommodationDetailRedissonConfig(endpoint, cacheProperties());
		var server = redisConfig.configureSingleServer(new Config(), endpoint, cacheProperties());

		assertThat(config.isLazyInitialization()).isTrue();
		assertThat(server.getAddress().toString()).isEqualTo("redis://cache.internal:6380");
		assertThat(server.getDatabase()).isEqualTo(3);
		assertThat(server.getUsername()).isEqualTo("cache-user");
		assertThat(server.getPassword()).isEqualTo("cache-password");
		assertThat(server.getConnectTimeout()).isEqualTo(1_000);
		assertThat(server.getTimeout()).isEqualTo(1_000);
		assertThat(server.getRetryAttempts()).isZero();
	}

	@Test
	void cacheDataClientUsesTheDedicatedEndpointAndTimeouts() {
		AccommodationDetailRedisConfig redisConfig = new AccommodationDetailRedisConfig();
		var endpoint = new AccommodationDetailRedisProperties(
			"cache.internal", 6380, 3, "cache-user", "cache-password");

		var standalone = redisConfig.redisStandaloneConfiguration(endpoint);
		var client = redisConfig.lettuceClientConfiguration(cacheProperties());

		assertThat(standalone.getHostName()).isEqualTo("cache.internal");
		assertThat(standalone.getPort()).isEqualTo(6380);
		assertThat(standalone.getDatabase()).isEqualTo(3);
		assertThat(standalone.getUsername()).isEqualTo("cache-user");
		assertThat(standalone.getPassword().isPresent()).isTrue();
		assertThat(client.getCommandTimeout()).isEqualTo(Duration.ofSeconds(1));
		assertThat(client.getClientOptions().orElseThrow().getSocketOptions().getConnectTimeout())
			.isEqualTo(Duration.ofSeconds(1));
	}

	@Test
	void dedicatedRedisPropertiesRejectInvalidEndpoints() {
		assertThatThrownBy(() ->
			new AccommodationDetailRedisProperties(" ", 6379, 0, "", ""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() ->
			new AccommodationDetailRedisProperties("redis-cache", 0, 0, "", ""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() ->
			new AccommodationDetailRedisProperties("redis-cache", 6379, -1, "", ""))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private AccommodationDetailCacheProperties cacheProperties() {
		return new AccommodationDetailCacheProperties(
			Duration.ofMinutes(10), Duration.ofMinutes(2),
			Duration.ofSeconds(45), Duration.ofSeconds(15),
			Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(30),
			Duration.ofSeconds(1), Duration.ofSeconds(1));
	}
}
