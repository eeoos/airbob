package kr.kro.airbob.domain.member.service;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import kr.kro.airbob.common.history.HistoryConstants;
import kr.kro.airbob.domain.member.port.SessionInvalidator;
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
    private static final Instant NOW = Instant.parse("2026-08-12T05:30:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private MemberHistoryRepository historyRepository;
    @Mock
    private SessionInvalidator sessionInvalidator;
    @Captor
    private ArgumentCaptor<Member> memberCaptor;

    @Test
    void createMemberStoresBCryptPasswordHash() {
        MemberService memberService = new MemberService(
            memberRepository, historyRepository, sessionInvalidator, FIXED_CLOCK);
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
        MemberService memberService = new MemberService(
            memberRepository, historyRepository, sessionInvalidator, FIXED_CLOCK);
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

        then(sessionInvalidator).should().invalidateAll(10L);
    }

    @Test
    void deleteMemberClosesAndOpensHistoryAtSameUtcTime() {
        MemberService memberService = new MemberService(
            memberRepository, historyRepository, sessionInvalidator, FIXED_CLOCK);
        Member member = Member.builder()
            .id(10L)
            .status(MemberStatus.ACTIVE)
            .build();
        MemberHistory currentHistory = MemberHistory.builder()
            .memberId(10L)
            .status(MemberStatus.ACTIVE)
            .changeType(kr.kro.airbob.common.history.ChangeType.CREATE)
            .validFrom(LocalDateTime.of(2026, 8, 1, 0, 0))
            .validTo(HistoryConstants.FOREVER)
            .build();
        given(memberRepository.findByIdForUpdate(10L)).willReturn(Optional.of(member));
        given(historyRepository.findByMemberIdAndValidTo(10L, HistoryConstants.FOREVER))
            .willReturn(Optional.of(currentHistory));
        ArgumentCaptor<MemberHistory> historyCaptor = ArgumentCaptor.forClass(MemberHistory.class);

        memberService.deleteMember(10L, "사용자 탈퇴");

        LocalDateTime expected = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        assertThat(currentHistory.getValidTo()).isEqualTo(expected);
        then(historyRepository).should().save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getValidFrom()).isEqualTo(expected);
    }

    @Test
    void getMemberInfoReturnsActiveMemberProfile() {
        MemberService service = new MemberService(
            memberRepository, historyRepository, sessionInvalidator, FIXED_CLOCK);
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
        MemberService service = new MemberService(
            memberRepository, historyRepository, sessionInvalidator, FIXED_CLOCK);
        given(memberRepository.findByIdAndStatus(10L, MemberStatus.ACTIVE))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMemberInfo(10L))
            .isInstanceOf(MemberNotFoundException.class);
    }
}
