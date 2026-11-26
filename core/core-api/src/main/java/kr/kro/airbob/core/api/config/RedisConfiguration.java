package kr.kro.airbob.core.api.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfiguration {

	@Bean
	public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
		RedisTemplate<String, Object> template = new RedisTemplate<>();
		template.setConnectionFactory(factory);
		template.setKeySerializer(new StringRedisSerializer());
		template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
		return template;
	}

	@Bean
	@Primary
	public RedissonClient redissonClient(
		@Value("${spring.data.redis.host}")
		String redisHost,
		@Value("${spring.data.redis.port}")
		int redisPort) {
		return Redisson.create(redissonConfig(redisHost, redisPort));
	}

	Config redissonConfig(String redisHost, int redisPort) {
		Config config = new Config();
		config.setUseScriptCache(true);
		String redisAddress = "redis://" + redisHost + ":" + redisPort;
		config.useSingleServer()
			.setAddress(redisAddress);
		return config;
	}
}
