package kr.kro.airbob.config;

import java.time.Duration;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
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

@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class AccommodationDetailRedisConfig {

	private static final int CACHE_LOCK_CONNECTION_POOL_SIZE = 8;
	private static final int CACHE_LOCK_CONNECTION_MINIMUM_IDLE_SIZE = 1;
	private static final int CACHE_LOCK_THREADS = 2;

	@Bean(destroyMethod = "destroy")
	AccommodationDetailRedisClient accommodationDetailRedisClient(
		RedisProperties redisProperties,
		AccommodationDetailCacheProperties properties
	) {
		SocketOptions socketOptions = SocketOptions.builder()
			.connectTimeout(properties.redisConnectTimeout())
			.build();
		ClientOptions clientOptions = ClientOptions.builder()
			.socketOptions(socketOptions)
			.build();
		LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
			.clientOptions(clientOptions)
			.commandTimeout(properties.redisCommandTimeout())
			.shutdownQuietPeriod(Duration.ZERO)
			.shutdownTimeout(Duration.ZERO)
			.build();
		RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
			redisProperties.getHost(), redisProperties.getPort());
		standalone.setDatabase(redisProperties.getDatabase());
		if (redisProperties.getUsername() != null && !redisProperties.getUsername().isBlank()) {
			standalone.setUsername(redisProperties.getUsername());
		}
		if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
			standalone.setPassword(redisProperties.getPassword());
		}
		LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, clientConfiguration);
		factory.afterPropertiesSet();

		StringRedisTemplate template = new StringRedisTemplate(factory);
		template.afterPropertiesSet();
		return new AccommodationDetailRedisClient(template, factory);
	}

	@Bean(name = "accommodationDetailRedissonClient", destroyMethod = "shutdown")
	RedissonClient accommodationDetailRedissonClient(
		RedisProperties redisProperties,
		AccommodationDetailCacheProperties properties
	) {
		Config config = new Config();
		config.setUseScriptCache(true);
		config.setThreads(CACHE_LOCK_THREADS);
		config.setNettyThreads(CACHE_LOCK_THREADS);
		var server = config.useSingleServer()
			.setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
			.setDatabase(redisProperties.getDatabase())
			.setConnectTimeout(toIntMillis(properties.redisConnectTimeout()))
			.setTimeout(toIntMillis(properties.redisCommandTimeout()))
			.setRetryAttempts(0)
			.setConnectionMinimumIdleSize(CACHE_LOCK_CONNECTION_MINIMUM_IDLE_SIZE)
			.setConnectionPoolSize(CACHE_LOCK_CONNECTION_POOL_SIZE);
		if (redisProperties.getUsername() != null && !redisProperties.getUsername().isBlank()) {
			server.setUsername(redisProperties.getUsername());
		}
		if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
			server.setPassword(redisProperties.getPassword());
		}
		return Redisson.create(config);
	}

	private int toIntMillis(Duration duration) {
		return Math.toIntExact(duration.toMillis());
	}
}
