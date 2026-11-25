package kr.kro.airbob.core.domain.auth;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.domain.member.Member;
import kr.kro.airbob.core.domain.member.MemberReader;
import lombok.RequiredArgsConstructor;

/**
 * 비밀번호를 확인한 회원에게 세션을 발급한다. 로그인은 비밀번호 비교를 트랜잭션 밖에서 하므로,
 * 발급은 자기 트랜잭션에서 회원을 잠그고 그 사이 탈퇴하거나 비밀번호가 바뀌지 않았는지 다시 확인한다.
 */
@Component
@RequiredArgsConstructor
public class SessionIssuer {
	private final MemberReader memberReader;
	private final SessionWriter sessionWriter;

	@Transactional
	public String issue(Long memberId, String verifiedPasswordHash) {
		Member member = memberReader.readActiveForUpdate(memberId);

		if (!member.hasPasswordHash(verifiedPasswordHash)) {
			throw new InvalidPasswordException();
		}

		return sessionWriter.create(memberId);
	}
}
