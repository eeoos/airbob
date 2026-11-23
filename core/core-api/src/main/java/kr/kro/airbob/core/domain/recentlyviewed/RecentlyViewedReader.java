package kr.kro.airbob.core.domain.recentlyviewed;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** 최근 본 숙소 기록 조회 구현 컴포넌트. */
@Component
@RequiredArgsConstructor
public class RecentlyViewedReader {

	private final RedisTemplate<String, String> redisTemplate;

	/** 회원의 기록을 최근에 본 순서로 읽는다. 기록이 없으면 빈 목록이다. */
	public List<RecentlyViewedEntry> readRecent(Long memberId) {
		Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
			.reverseRangeWithScores(RecentlyViewedKeys.of(memberId), 0, -1);
		if (tuples == null || tuples.isEmpty()) {
			return List.of();
		}
		return tuples.stream()
			.map(tuple -> new RecentlyViewedEntry(
				Long.parseLong(tuple.getValue()),
				Instant.ofEpochMilli(tuple.getScore().longValue())))
			.toList();
	}
}
