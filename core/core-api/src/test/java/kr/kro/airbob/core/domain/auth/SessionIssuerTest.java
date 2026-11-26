package kr.kro.airbob.core.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.core.domain.auth.repository.SessionRedisRepository;
import kr.kro.airbob.core.domain.member.Member;
import kr.kro.airbob.core.domain.member.MemberNotFoundException;
import kr.kro.airbob.core.domain.member.MemberReader;
import kr.kro.airbob.core.domain.member.MemberStatus;
import kr.kro.airbob.core.domain.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class SessionIssuerTest {
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private SessionRedisRepository sessionRedisRepository;

	@Test
	void issueCreatesSessionAfterLockingActiveMember() {
		SessionIssuer issuer = new SessionIssuer(new MemberReader(memberRepository),
			new SessionWriter(sessionRedisRepository));
		Member member = activeMember(1L, "current-hash");
		given(memberRepository.findByIdAndStatusForUpdate(1L, MemberStatus.ACTIVE))
			.willReturn(Optional.of(member));

		String sessionId = issuer.issue(1L, "current-hash");

		assertThat(sessionId).isNotBlank();
		then(sessionRedisRepository).should().saveSession(sessionId, 1L);
	}

	@Test
	void issueRejectsMemberThatBecameInactive() {
		SessionIssuer issuer = new SessionIssuer(new MemberReader(memberRepository),
			new SessionWriter(sessionRedisRepository));
		given(memberRepository.findByIdAndStatusForUpdate(1L, MemberStatus.ACTIVE))
			.willReturn(Optional.empty());

		assertThatThrownBy(() -> issuer.issue(1L, "current-hash")).isInstanceOf(MemberNotFoundException.class);

		then(sessionRedisRepository).shouldHaveNoInteractions();
	}

	@Test
	void issueRejectsPasswordChangedAfterCredentialCheck() {
		SessionIssuer issuer = new SessionIssuer(new MemberReader(memberRepository),
			new SessionWriter(sessionRedisRepository));
		Member member = activeMember(1L, "new-hash");
		given(memberRepository.findByIdAndStatusForUpdate(1L, MemberStatus.ACTIVE))
			.willReturn(Optional.of(member));

		assertThatThrownBy(() -> issuer.issue(1L, "old-hash")).isInstanceOf(InvalidPasswordException.class);

		then(sessionRedisRepository).shouldHaveNoInteractions();
	}

	private Member activeMember(Long id, String password) {
		return Member.builder()
			.id(id)
			.password(password)
			.status(MemberStatus.ACTIVE)
			.build();
	}
}
