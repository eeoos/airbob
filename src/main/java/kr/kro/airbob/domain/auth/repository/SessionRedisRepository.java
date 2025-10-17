package kr.kro.airbob.domain.auth.repository;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Slf4j
@RequiredArgsConstructor
public class SessionRedisRepository {
    private static final Duration TTL = Duration.ofHours(1);
    private static final String SESSION = "SESSION:";
    private final RedisTemplate<String, Object> redisTemplate;

    public void saveSession(String sessionId, Long memberId) {
        redisTemplate.opsForValue().set(SESSION + sessionId, memberId, TTL);
    }

    public Optional<Long> getMemberIdBySession(String sessionId) {
        Object value = redisTemplate.opsForValue().get(SESSION + sessionId);
        log.info("value: {}, type: {}", value, value.getClass());

        if (value instanceof Number number) {
            return Optional.of(number.longValue());
        } else {
            return Optional.empty(); // 또는 예외
        }
    }

    public void deleteSession(String sessionId) {
        redisTemplate.delete(SESSION + sessionId);
    }
}
