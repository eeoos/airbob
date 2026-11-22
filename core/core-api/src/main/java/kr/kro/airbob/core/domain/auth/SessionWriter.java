package kr.kro.airbob.core.domain.auth;

import java.util.UUID;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.auth.repository.SessionRedisRepository;
import kr.kro.airbob.core.domain.member.port.SessionInvalidator;
import lombok.RequiredArgsConstructor;

/** 세션 변경 구현 컴포넌트. 회원 도메인은 SessionInvalidator 포트로 회원의 모든 세션을 무효화한다. */
@Component
@RequiredArgsConstructor
public class SessionWriter implements SessionInvalidator {

	private final SessionRedisRepository sessionRedisRepository;

	/** 회원의 새 세션을 저장하고 세션 ID를 돌려준다. */
	public String create(Long memberId) {
		String sessionId = UUID.randomUUID().toString();
		sessionRedisRepository.saveSession(sessionId, memberId);
		return sessionId;
	}

	public void delete(String sessionId) {
		sessionRedisRepository.deleteSession(sessionId);
	}

	@Override
	public void invalidateAll(Long memberId) {
		sessionRedisRepository.invalidateAll(memberId);
	}
}
