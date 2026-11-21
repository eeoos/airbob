package kr.kro.airbob.domain.auth.repository;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import kr.kro.airbob.domain.member.port.SessionInvalidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Slf4j
@RequiredArgsConstructor
public class SessionRedisRepository implements SessionInvalidator {
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

    public Optional<Long> getMemberIdBySession(String sessionId) {
        Object value = redisTemplate.opsForValue().get(SESSION + sessionId);
        log.info("value: {}, type: {}", value, value == null ? null : value.getClass());

        if (value instanceof Number number) {
            return Optional.of(number.longValue());
        } else {
            return Optional.empty(); // 또는 예외
        }
    }

    public void deleteSession(String sessionId) {
        Optional<Long> memberId = getMemberIdBySession(sessionId);
        redisTemplate.delete(SESSION + sessionId);
        memberId.ifPresent(id -> redisTemplate.opsForZSet().remove(MEMBER_SESSIONS + id, sessionId));
    }

    @Override
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
