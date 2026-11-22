package kr.kro.airbob.domain.auth.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.auth.repository.SessionRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 요청의 세션 ID로 로그인한 회원을 확인한다. 세션 저장 형식은 SessionRedisRepository만 안다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionAuthenticationService {

	private final SessionRedisRepository sessionRedisRepository;

	/**
	 * 세션의 회원 ID. 세션이 없거나 읽을 수 없거나, 회원의 세션이 모두 무효화되었으면 비어 있다.
	 * 세션 값을 읽다 실패하면 비인증으로 처리한다. 회원별 활성 키를 확인하다 난 오류는 그대로 던진다.
	 */
	public Optional<Long> authenticate(String sessionId) {
		Optional<Long> memberId;
		try {
			memberId = sessionRedisRepository.getMemberIdBySession(sessionId);
		} catch (RuntimeException exception) {
			log.warn("유효하지 않은 세션값 (무시): SESSION:{}", sessionId, exception);
			return Optional.empty();
		}

		if (memberId.isPresent() && !sessionRedisRepository.isMemberSessionActive(memberId.get())) {
			log.warn("회원별 세션 활성 키 누락 (무시): memberId={}", memberId.get());
			return Optional.empty();
		}
		return memberId;
	}
}
