package kr.kro.airbob.config;

import java.time.Duration;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheProperties;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailRedisClient;

/**
 * 숙소 상세 캐시 전용 Redis 클라이언트를 구성한다.
 * Lettuce는 값과 Lua 연산에, Redisson은 분산 락에 사용하며 둘 다 짧은 timeout으로 fail-fast한다.
 */
@Configuration
@EnableConfigurationProperties(AccommodationDetailRedisProperties.class)
public class AccommodationDetailRedisConfig {

	private static final int CACHE_LOCK_CONNECTION_POOL_SIZE = 8;
	private static final int CACHE_LOCK_CONNECTION_MINIMUM_IDLE_SIZE = 1;
	private static final int CACHE_LOCK_THREADS = 2;

	@Bean(destroyMethod = "destroy")
	AccommodationDetailRedisClient accommodationDetailRedisClient(
		AccommodationDetailRedisProperties redisProperties,
		AccommodationDetailCacheProperties properties
	) {
		LettuceClientConfiguration clientConfiguration = lettuceClientConfiguration(properties);
		RedisStandaloneConfiguration standalone = redisStandaloneConfiguration(redisProperties);
		LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, clientConfiguration);
		factory.afterPropertiesSet();

		StringRedisTemplate template = new StringRedisTemplate(factory);
		template.afterPropertiesSet();
		return new AccommodationDetailRedisClient(template, factory);
	}

	@Bean(name = "accommodationDetailRedissonClient", destroyMethod = "shutdown")
	RedissonClient accommodationDetailRedissonClient(
		AccommodationDetailRedisProperties redisProperties,
		AccommodationDetailCacheProperties properties
	) {
		return Redisson.create(accommodationDetailRedissonConfig(redisProperties, properties));
	}

	LettuceClientConfiguration lettuceClientConfiguration(
		AccommodationDetailCacheProperties properties
	) {
		SocketOptions socketOptions = SocketOptions.builder()
			.connectTimeout(properties.redisConnectTimeout())
			.build();
		ClientOptions clientOptions = ClientOptions.builder()
			.socketOptions(socketOptions)
			.build();
		return LettuceClientConfiguration.builder()
			.clientOptions(clientOptions)
			.commandTimeout(properties.redisCommandTimeout())
			.shutdownQuietPeriod(Duration.ZERO)
			.shutdownTimeout(Duration.ZERO)
			.build();
	}

	RedisStandaloneConfiguration redisStandaloneConfiguration(
		AccommodationDetailRedisProperties redisProperties
	) {
		RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
			redisProperties.host(), redisProperties.port());
		standalone.setDatabase(redisProperties.database());
		if (redisProperties.username() != null && !redisProperties.username().isBlank()) {
			standalone.setUsername(redisProperties.username());
		}
		if (redisProperties.password() != null && !redisProperties.password().isBlank()) {
			standalone.setPassword(redisProperties.password());
		}
		return standalone;
	}

	Config accommodationDetailRedissonConfig(
		AccommodationDetailRedisProperties redisProperties,
		AccommodationDetailCacheProperties properties
	) {
		Config config = new Config();
		config.setUseScriptCache(true);
		// 캐시 Redis 장애가 애플리케이션 시작 자체를 막지 않도록 최초 사용 시 연결한다.
		config.setLazyInitialization(true);
		config.setThreads(CACHE_LOCK_THREADS);
		config.setNettyThreads(CACHE_LOCK_THREADS);
		configureSingleServer(config, redisProperties, properties);
		return config;
	}

	SingleServerConfig configureSingleServer(
		Config config,
		AccommodationDetailRedisProperties redisProperties,
		AccommodationDetailCacheProperties properties
	) {
		var server = config.useSingleServer()
			.setAddress("redis://" + redisProperties.host() + ":" + redisProperties.port())
			.setDatabase(redisProperties.database())
			.setConnectTimeout(toIntMillis(properties.redisConnectTimeout()))
			.setTimeout(toIntMillis(properties.redisCommandTimeout()))
			.setRetryAttempts(0)
			.setConnectionMinimumIdleSize(CACHE_LOCK_CONNECTION_MINIMUM_IDLE_SIZE)
			.setConnectionPoolSize(CACHE_LOCK_CONNECTION_POOL_SIZE);
		if (redisProperties.username() != null && !redisProperties.username().isBlank()) {
			server.setUsername(redisProperties.username());
		}
		if (redisProperties.password() != null && !redisProperties.password().isBlank()) {
			server.setPassword(redisProperties.password());
		}
		return server;
	}

	private int toIntMillis(Duration duration) {
		return Math.toIntExact(duration.toMillis());
	}
}
