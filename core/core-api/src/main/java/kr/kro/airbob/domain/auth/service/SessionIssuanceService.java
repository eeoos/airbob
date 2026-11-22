package kr.kro.airbob.domain.auth.service;

import java.util.UUID;

import kr.kro.airbob.core.domain.member.Member;
import kr.kro.airbob.core.domain.member.MemberReader;
import kr.kro.airbob.domain.auth.exception.InvalidPasswordException;
import kr.kro.airbob.domain.auth.repository.SessionRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SessionIssuanceService {
    private final MemberReader memberReader;
    private final SessionRedisRepository sessionRedisRepository;

    @Transactional
    public String issue(Long memberId, String verifiedPasswordHash) {
        Member member = memberReader.readActiveForUpdate(memberId);

        if (!member.hasPasswordHash(verifiedPasswordHash)) {
            throw new InvalidPasswordException();
        }

        String sessionId = UUID.randomUUID().toString();
        sessionRedisRepository.saveSession(sessionId, memberId);
        return sessionId;
    }
}
