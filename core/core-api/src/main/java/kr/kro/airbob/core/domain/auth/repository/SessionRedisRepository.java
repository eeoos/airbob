package kr.kro.airbob.core.domain.auth.repository;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Repository
@Slf4j
@RequiredArgsConstructor
public class SessionRedisRepository {
	private static final Duration TTL = Duration.ofHours(1);
	private static final String SESSION = "SESSION:";
	private static final String MEMBER_SESSIONS = "MEMBER_SESSIONS:";
	private static final String MEMBER_SESSION_ACTIVE = "MEMBER_SESSION_ACTIVE:";
	private final RedisTemplate<String, Object> redisTemplate;

	public void saveSession(String sessionId, Long memberId) {
		redisTemplate.opsForValue().set(SESSION + sessionId, memberId, TTL);
		redisTemplate.opsForValue().set(MEMBER_SESSION_ACTIVE + memberId, true, TTL);
		String memberSessionsKey = MEMBER_SESSIONS + memberId;
		long now = System.currentTimeMillis();
		redisTemplate.opsForZSet().removeRangeByScore(memberSessionsKey, 0, now);
		redisTemplate.opsForZSet().add(memberSessionsKey, sessionId, now + TTL.toMillis());
		redisTemplate.expire(memberSessionsKey, TTL);
	}

	/** 세션에 저장된 회원 ID. 세션이 없거나 값의 형식이 잘못되었으면 비어 있다. */
	public Optional<Long> getMemberIdBySession(String sessionId) {
		Object value = redisTemplate.opsForValue().get(SESSION + sessionId);
		if (value instanceof Number number) {
			return Optional.of(number.longValue());
		}
		if (value != null) {
			log.warn("세션 값 타입 오류: SESSION:{} 타입 = {}", sessionId, value.getClass().getName());
		}
		return Optional.empty();
	}

	/** 회원별 세션 활성 키. 회원의 모든 세션을 무효화하면 사라져, 남은 세션 키가 있어도 인증되지 않는다. */
	public boolean isMemberSessionActive(Long memberId) {
		return Boolean.TRUE.equals(redisTemplate.hasKey(MEMBER_SESSION_ACTIVE + memberId));
	}

	public void deleteSession(String sessionId) {
		Optional<Long> memberId = getMemberIdBySession(sessionId);
		redisTemplate.delete(SESSION + sessionId);
		memberId.ifPresent(id -> redisTemplate.opsForZSet().remove(MEMBER_SESSIONS + id, sessionId));
	}

	public void invalidateAll(Long memberId) {
		String memberSessionsKey = MEMBER_SESSIONS + memberId;
		redisTemplate.delete(MEMBER_SESSION_ACTIVE + memberId);
		Set<Object> sessionIds = redisTemplate.opsForZSet().range(memberSessionsKey, 0, -1);

		if (sessionIds != null && !sessionIds.isEmpty()) {
			Set<String> sessionKeys = sessionIds.stream()
				.map(String::valueOf)
				.map(sessionId -> SESSION + sessionId)
				.collect(Collectors.toSet());
			redisTemplate.delete(sessionKeys);
		}

		redisTemplate.delete(memberSessionsKey);
	}
}
