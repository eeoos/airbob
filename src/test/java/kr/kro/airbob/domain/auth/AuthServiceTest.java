package kr.kro.airbob.domain.auth;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import kr.kro.airbob.domain.auth.exception.InvalidPasswordException;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @InjectMocks
    private AuthService authService;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private SessionRedisRepository sessionRedisRepository;

    @Test
    @DisplayName("로그인 성공시 세션ID를 반환한다")
    void successLogin() {
        // given
        String email = "test@example.com";
        String rawPassword = "password123";
        String hashedPassword = BCrypt.hashpw(rawPassword, BCrypt.gensalt());

        Member member = Member.builder()
                .id(1L)
                .email(email)
                .password(hashedPassword)
                .build();

        given(memberRepository.findByEmail(email)).willReturn(Optional.of(member));

        // when
        String sessionId = authService.login(email, rawPassword);

        // then
        assertThat(sessionId).isNotNull();
        verify(sessionRedisRepository).saveSession(eq(sessionId), eq(member.getId()));
    }

    @Test
    @DisplayName("존재하지 않는 이메일이면 예외가 발생한다")
    void notExistedEmail() {
        // given
        given(memberRepository.findByEmail("notfound@example.com")).willReturn(Optional.empty());

        // expect
        assertThatThrownBy(() -> authService.login("notfound@example.com", "pwd"))
                .isInstanceOf(MemberNotFoundException.class);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 예외가 발생한다")
    void invalidPassword() {
        // given
        String email = "user@example.com";
        String wrongPassword = "wrong";
        String correctPassword = BCrypt.hashpw("correct", BCrypt.gensalt());

        Member member = Member.builder()
                .id(1L)
                .email(email)
                .password(correctPassword)
                .build();

        given(memberRepository.findByEmail(email)).willReturn(Optional.of(member));

        // expect
        assertThatThrownBy(() -> authService.login(email, wrongPassword))
                .isInstanceOf(InvalidPasswordException.class);
    }
}
