package kr.kro.airbob.domain.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.when;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("세션 Redis 저장소 단위 테스트")
class SessionRedisRepositoryTest {

	private static final Duration SESSION_TTL = Duration.ofHours(1);

	@Mock
	private RedisTemplate<String, Object> redisTemplate;

	@Mock
	private ValueOperations<String, Object> valueOperations;

	@Mock
	private ZSetOperations<String, Object> zSetOperations;

	@InjectMocks
	private SessionRedisRepository sessionRedisRepository;

	@Test
	@DisplayName("세션 저장 시 만료 시각을 가진 회원별 세션 인덱스와 TTL도 함께 저장한다")
	void saveSessionStoresMemberSessionIndex() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		ArgumentCaptor<Double> cleanupThreshold = ArgumentCaptor.forClass(Double.class);
		ArgumentCaptor<Double> expiresAt = ArgumentCaptor.forClass(Double.class);

		sessionRedisRepository.saveSession("session-1", 10L);

		then(valueOperations).should()
			.set("SESSION:session-1", 10L, SESSION_TTL);
		then(valueOperations).should()
			.set("MEMBER_SESSION_ACTIVE:10", true, SESSION_TTL);
		then(zSetOperations).should()
			.removeRangeByScore(eq("MEMBER_SESSIONS:10"), eq(0D), cleanupThreshold.capture());
		then(zSetOperations).should()
			.add(eq("MEMBER_SESSIONS:10"), eq("session-1"), expiresAt.capture());
		assertThat(expiresAt.getValue() - cleanupThreshold.getValue())
			.isEqualTo(SESSION_TTL.toMillis());
		then(redisTemplate).should()
			.expire("MEMBER_SESSIONS:10", SESSION_TTL);
	}

	@Test
	@DisplayName("단일 로그아웃 시 정방향 세션과 회원별 세션 인덱스를 함께 제거한다")
	void deleteSessionRemovesBothSessionIndexes() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get("SESSION:session-1")).thenReturn(10L);
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

		sessionRedisRepository.deleteSession("session-1");

		then(redisTemplate).should().delete("SESSION:session-1");
		then(zSetOperations).should()
			.remove("MEMBER_SESSIONS:10", "session-1");
	}

	@Test
	@DisplayName("이미 만료된 세션 로그아웃도 오류 없이 처리한다")
	void deleteExpiredSessionIsIdempotent() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get("SESSION:expired-session")).thenReturn(null);

		sessionRedisRepository.deleteSession("expired-session");

		then(redisTemplate).should().delete("SESSION:expired-session");
		then(zSetOperations).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("회원의 모든 정방향 세션과 회원별 세션 인덱스를 제거한다")
	void deleteAllSessionsRemovesEverySessionForMember() {
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		when(zSetOperations.range("MEMBER_SESSIONS:10", 0, -1))
			.thenReturn(Set.of("session-1", "session-2"));

		sessionRedisRepository.deleteAllSessions(10L);

		then(redisTemplate).should().delete(Set.of(
			"SESSION:session-1",
			"SESSION:session-2"
		));
		then(redisTemplate).should().delete("MEMBER_SESSIONS:10");
	}

	@Test
	@DisplayName("역인덱스가 축출돼도 회원별 세션 활성 상태를 먼저 제거한다")
	void deleteAllSessionsRevokesMemberWhenReverseIndexIsMissing() {
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		when(zSetOperations.range("MEMBER_SESSIONS:10", 0, -1))
			.thenReturn(Set.of());

		sessionRedisRepository.deleteAllSessions(10L);

		then(redisTemplate).should().delete("MEMBER_SESSION_ACTIVE:10");
		then(redisTemplate).should().delete("MEMBER_SESSIONS:10");
	}
}
