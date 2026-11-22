package kr.kro.airbob.core.domain.member;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.domain.member.port.SessionInvalidator;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberReader memberReader;
    private final MemberWriter memberWriter;
    private final SessionInvalidator sessionInvalidator;

    @Transactional
    public void createMember(NewMember newMember) {
        memberWriter.create(newMember);
    }

    @Transactional
    public void deleteMember(Long memberId, String reason) {
        Member member = memberReader.readForUpdate(memberId);
        memberWriter.withdraw(member, reason);
        sessionInvalidator.invalidateAll(memberId);
    }

    /** 관리자 경로 인가용: 활성 상태의 ADMIN 회원인지. 세션에는 회원 ID만 있으므로 역할은 매번 DB에서 확인한다. */
    @Transactional(readOnly = true)
    public boolean isActiveAdmin(Long memberId) {
        return memberReader.isActiveAdmin(memberId);
    }

    @Transactional(readOnly = true)
    public MemberProfile getMemberInfo(Long memberId) {
        return memberReader.readActiveProfile(memberId);
    }
}
