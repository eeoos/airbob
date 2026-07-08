package kr.kro.airbob.domain.member.service;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.any;

import kr.kro.airbob.domain.member.dto.MemberRequest;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberHistory;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberHistoryRepository;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private MemberHistoryRepository historyRepository;
    @Captor
    private ArgumentCaptor<Member> memberCaptor;

    @Test
    void createMemberStoresBCryptPasswordHash() {
        MemberService memberService = new MemberService(memberRepository, historyRepository);
        MemberRequest.Signup request = MemberRequest.Signup.builder()
            .email("guest@airbob.test")
            .nickname("guest")
            .password("password1")
            .thumbnailImageUrl("https://img.example/guest.png")
            .build();
        given(memberRepository.existsByEmailAndStatus(request.getEmail(), MemberStatus.ACTIVE)).willReturn(false);

        memberService.createMember(request);

        then(memberRepository).should().save(memberCaptor.capture());
        Member savedMember = memberCaptor.getValue();
        assertNotEquals("password1", savedMember.getPassword());
        assertTrue(BCrypt.checkpw("password1", savedMember.getPassword()));
        then(historyRepository).should().save(any(MemberHistory.class));
    }
}
