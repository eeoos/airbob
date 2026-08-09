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
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionIssuanceServiceTest {
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private SessionRedisRepository sessionRedisRepository;

    @Test
    void issueCreatesSessionAfterLockingActiveMember() {
        SessionIssuanceService service = new SessionIssuanceService(memberRepository, sessionRedisRepository);
        Member member = activeMember(1L, "current-hash");
        given(memberRepository.findByIdAndStatusForUpdate(1L, MemberStatus.ACTIVE))
            .willReturn(Optional.of(member));

        String sessionId = service.issue(1L, "current-hash");

        assertFalse(sessionId.isBlank());
        then(sessionRedisRepository).should().saveSession(sessionId, 1L);
    }

    @Test
    void issueRejectsMemberThatBecameInactive() {
        SessionIssuanceService service = new SessionIssuanceService(memberRepository, sessionRedisRepository);
        given(memberRepository.findByIdAndStatusForUpdate(1L, MemberStatus.ACTIVE))
            .willReturn(Optional.empty());

        assertThrows(MemberNotFoundException.class, () -> service.issue(1L, "current-hash"));

        then(sessionRedisRepository).shouldHaveNoInteractions();
    }

    @Test
    void issueRejectsPasswordChangedAfterCredentialCheck() {
        SessionIssuanceService service = new SessionIssuanceService(memberRepository, sessionRedisRepository);
        Member member = activeMember(1L, "new-hash");
        given(memberRepository.findByIdAndStatusForUpdate(1L, MemberStatus.ACTIVE))
            .willReturn(Optional.of(member));

        assertThrows(InvalidPasswordException.class, () -> service.issue(1L, "old-hash"));

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
