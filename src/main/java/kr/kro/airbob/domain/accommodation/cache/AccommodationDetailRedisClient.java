package kr.kro.airbob.domain.accommodation.cache;

import java.util.List;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

public final class AccommodationDetailRedisClient {

	private final StringRedisTemplate redisTemplate;
	private final LettuceConnectionFactory connectionFactory;

	public AccommodationDetailRedisClient(
		StringRedisTemplate redisTemplate,
		LettuceConnectionFactory connectionFactory
	) {
		this.redisTemplate = redisTemplate;
		this.connectionFactory = connectionFactory;
	}

	String get(String key) {
		return redisTemplate.opsForValue().get(key);
	}

	Long execute(RedisScript<Long> script, List<String> keys, Object... args) {
		return redisTemplate.execute(script, keys, args);
	}

	Boolean delete(String key) {
		return redisTemplate.delete(key);
	}

	void destroy() {
		connectionFactory.destroy();
	}
}
