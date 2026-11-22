package kr.kro.airbob.core.domain.auth;

import java.util.Optional;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** 요청의 세션 ID로 로그인한 회원을 확인한다. */
@Service
@RequiredArgsConstructor
public class SessionAuthenticationService {

	private final SessionReader sessionReader;

	/** 세션의 회원 ID. 세션이 없거나 읽을 수 없거나, 회원의 세션이 모두 무효화되었으면 비어 있다. */
	public Optional<Long> authenticate(String sessionId) {
		return sessionReader.readActiveMemberId(sessionId);
	}
}
