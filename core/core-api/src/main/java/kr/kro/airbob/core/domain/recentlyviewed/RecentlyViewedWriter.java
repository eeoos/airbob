package kr.kro.airbob.core.domain.recentlyviewed;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** 최근 본 숙소 기록 쓰기 구현 컴포넌트. 회원별 최대 100개, 마지막 기록 뒤 7일 동안 보관한다. */
@Component
@RequiredArgsConstructor
public class RecentlyViewedWriter {

	private static final int MAX_COUNT = 100;
	private static final long TTL_DAYS = 7;
	private static final DefaultRedisScript<Long> ADD_HISTORY = new DefaultRedisScript<>("""
		redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
		local size = redis.call('ZCARD', KEYS[1])
		local maxCount = tonumber(ARGV[3])
		if size > maxCount then
			redis.call('ZREMRANGEBYRANK', KEYS[1], 0, size - maxCount - 1)
		end
		redis.call('EXPIRE', KEYS[1], ARGV[4])
		return 1
		""", Long.class);

	private final RedisTemplate<String, String> redisTemplate;
	private final Clock clock;

	public void add(Long accommodationId, Long memberId) {
		long timestamp = clock.millis();
		// 기록·개수 제한·만료 갱신 사이에 다른 요청이 끼어 과도하게 삭제되지 않도록 함께 실행한다.
		redisTemplate.execute(ADD_HISTORY, List.of(RecentlyViewedKeys.of(memberId)), accommodationId.toString(),
			Long.toString(timestamp), Integer.toString(MAX_COUNT),
			Long.toString(Duration.ofDays(TTL_DAYS).toSeconds()));
	}

	public void remove(Long accommodationId, Long memberId) {
		redisTemplate.opsForZSet().remove(RecentlyViewedKeys.of(memberId), accommodationId.toString());
	}

	public void removeAll(Collection<Long> accommodationIds, Long memberId) {
		Object[] members = accommodationIds.stream().map(String::valueOf).toArray();
		redisTemplate.opsForZSet().remove(RecentlyViewedKeys.of(memberId), members);
	}
}
