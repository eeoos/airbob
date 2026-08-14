package kr.kro.airbob.domain.accommodation.cache;

import java.util.List;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 숙소 상세 캐시 전용 Redis 연결을 감싼다.
 * 전용 StringRedisTemplate을 다른 Redis 기능에 노출하지 않고 연결 생명주기도 함께 관리한다.
 */
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
