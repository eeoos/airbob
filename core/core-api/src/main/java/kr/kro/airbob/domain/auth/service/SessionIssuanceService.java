package kr.kro.airbob.domain.auth.service;

import java.util.Objects;
import java.util.UUID;

import kr.kro.airbob.domain.auth.exception.InvalidPasswordException;
import kr.kro.airbob.domain.auth.repository.SessionRedisRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SessionIssuanceService {
    private final MemberRepository memberRepository;
    private final SessionRedisRepository sessionRedisRepository;

    @Transactional
    public String issue(Long memberId, String verifiedPasswordHash) {
        Member member = memberRepository.findByIdAndStatusForUpdate(memberId, MemberStatus.ACTIVE)
            .orElseThrow(MemberNotFoundException::new);

        if (!Objects.equals(member.getPassword(), verifiedPasswordHash)) {
            throw new InvalidPasswordException();
        }

        String sessionId = UUID.randomUUID().toString();
        sessionRedisRepository.saveSession(sessionId, memberId);
        return sessionId;
    }
}
