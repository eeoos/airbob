package kr.kro.airbob.core.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.core.domain.member.Member;
import kr.kro.airbob.core.domain.member.MemberReader;
import kr.kro.airbob.core.domain.member.MemberStatus;
import kr.kro.airbob.core.domain.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private SessionIssuer sessionIssuer;
	@Mock
	private SessionWriter sessionWriter;

	@Test
	void loginAcceptsBCryptPasswordHash() {
		AuthService authService = new AuthService(new MemberReader(memberRepository), sessionIssuer, sessionWriter);
		Member member = activeMember(1L, "guest@airbob.test", BCrypt.hashpw("password1", BCrypt.gensalt()));
		given(memberRepository.findByEmailAndStatus("guest@airbob.test", MemberStatus.ACTIVE))
			.willReturn(Optional.of(member));
		given(sessionIssuer.issue(1L, member.getPassword())).willReturn("session-1");

		String sessionId = authService.login("guest@airbob.test", "password1");

		assertThat(sessionId).isNotBlank();
		assertThat(sessionId).isEqualTo("session-1");
		then(sessionIssuer).should().issue(1L, member.getPassword());
	}

	@Test
	void loginRejectsPlaintextStoredPasswordWithoutFallback() {
		AuthService authService = new AuthService(new MemberReader(memberRepository), sessionIssuer, sessionWriter);
		Member member = activeMember(1L, "guest@airbob.test", "password1");
		given(memberRepository.findByEmailAndStatus("guest@airbob.test", MemberStatus.ACTIVE))
			.willReturn(Optional.of(member));

		assertThatThrownBy(() -> authService.login("guest@airbob.test", "password1"))
			.isInstanceOf(InvalidPasswordException.class);
		then(sessionIssuer).shouldHaveNoInteractions();
	}

	private Member activeMember(Long id, String email, String password) {
		return Member.builder()
			.id(id)
			.email(email)
			.password(password)
			.nickname("guest")
			.status(MemberStatus.ACTIVE)
			.build();
	}
}
