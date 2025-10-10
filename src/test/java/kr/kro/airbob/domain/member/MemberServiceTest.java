package kr.kro.airbob.domain.member;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import kr.kro.airbob.domain.member.dto.MemberRequestDto.SignupMemberRequestDto;
import kr.kro.airbob.domain.member.exception.DuplicatedEmailException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.service.MemberService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberService memberService;

    @Test
    @DisplayName("회원가입시 이메일이 중복되면 예외가 발생한다.")
    void signupDuplicatedEmail() {
        // given
        SignupMemberRequestDto request = SignupMemberRequestDto
                .builder()
                .email("test@a.com")
                .password("password123")
                .nickname("test")
                .build();

        when(memberRepository.existsByEmail(request.getEmail())).thenReturn(true);

        // when & then
        DuplicatedEmailException exception = assertThrows(DuplicatedEmailException.class, () -> {
            memberService.createMember(request);
        });

        assertNotNull(exception);
        verify(memberRepository, never()).save(any());
    }
}
