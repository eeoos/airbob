package kr.kro.airbob.domain.member.service;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;

import kr.kro.airbob.common.history.HistoryConstants;
import kr.kro.airbob.domain.auth.repository.SessionRedisRepository;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.dto.MemberRequest;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberHistory;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
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
    @Mock
    private SessionRedisRepository sessionRedisRepository;
    @Captor
    private ArgumentCaptor<Member> memberCaptor;

    @Test
    void createMemberStoresBCryptPasswordHash() {
        MemberService memberService = new MemberService(memberRepository, historyRepository, sessionRedisRepository);
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

    @Test
    void deleteMemberRevokesAllSessions() {
        MemberService memberService = new MemberService(memberRepository, historyRepository, sessionRedisRepository);
        Member member = Member.builder()
            .id(10L)
            .email("guest@airbob.test")
            .password("hashed-password")
            .nickname("guest")
            .role(MemberRole.MEMBER)
            .status(MemberStatus.ACTIVE)
            .build();
        given(memberRepository.findByIdForUpdate(10L)).willReturn(Optional.of(member));
        given(historyRepository.findByMemberIdAndValidTo(10L, HistoryConstants.FOREVER))
            .willReturn(Optional.empty());

        memberService.deleteMember(10L, "사용자 탈퇴");

        then(sessionRedisRepository).should().deleteAllSessions(10L);
    }

    @Test
    void getMemberInfoReturnsActiveMemberProfile() {
        MemberService service = new MemberService(memberRepository, historyRepository, sessionRedisRepository);
        Member member = Member.builder()
            .id(10L)
            .email("guest@airbob.test")
            .nickname("guest")
            .thumbnailImageUrl("https://img.example/guest.png")
            .status(MemberStatus.ACTIVE)
            .build();
        given(memberRepository.findByIdAndStatus(10L, MemberStatus.ACTIVE))
            .willReturn(Optional.of(member));

        MemberResponse.MeInfo result = service.getMemberInfo(10L);

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.email()).isEqualTo("guest@airbob.test");
        assertThat(result.nickname()).isEqualTo("guest");
        assertThat(result.thumbnailImageUrl()).isEqualTo("https://img.example/guest.png");
    }

    @Test
    void getMemberInfoRejectsMissingOrInactiveMember() {
        MemberService service = new MemberService(memberRepository, historyRepository, sessionRedisRepository);
        given(memberRepository.findByIdAndStatus(10L, MemberStatus.ACTIVE))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMemberInfo(10L))
            .isInstanceOf(MemberNotFoundException.class);
    }
}
