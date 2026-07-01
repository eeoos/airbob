package kr.kro.airbob.domain.auth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;
import kr.kro.airbob.domain.auth.exception.InvalidPasswordException;
import kr.kro.airbob.domain.auth.repository.SessionRedisRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private SessionRedisRepository sessionRedisRepository;

    @Test
    void loginAcceptsBCryptPasswordHash() {
        AuthService authService = new AuthService(memberRepository, sessionRedisRepository);
        Member member = activeMember(1L, "guest@airbob.test", BCrypt.hashpw("password1", BCrypt.gensalt()));
        given(memberRepository.findByEmailAndStatus("guest@airbob.test", MemberStatus.ACTIVE))
            .willReturn(Optional.of(member));

        String sessionId = authService.login("guest@airbob.test", "password1");

        assertFalse(sessionId.isBlank());
        then(sessionRedisRepository).should().saveSession(sessionId, 1L);
    }

    @Test
    void loginRejectsPlaintextStoredPasswordWithoutFallback() {
        AuthService authService = new AuthService(memberRepository, sessionRedisRepository);
        Member member = activeMember(1L, "guest@airbob.test", "password1");
        given(memberRepository.findByEmailAndStatus("guest@airbob.test", MemberStatus.ACTIVE))
            .willReturn(Optional.of(member));

        assertThrows(InvalidPasswordException.class, () -> authService.login("guest@airbob.test", "password1"));
        then(sessionRedisRepository).shouldHaveNoInteractions();
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
