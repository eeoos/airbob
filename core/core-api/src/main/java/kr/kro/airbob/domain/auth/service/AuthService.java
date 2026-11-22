package kr.kro.airbob.domain.auth.service;

import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.auth.exception.InvalidPasswordException;
import kr.kro.airbob.domain.auth.repository.SessionRedisRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.service.MemberReader;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberReader memberReader;
    private final SessionRedisRepository sessionRedisRepository;
    private final SessionIssuanceService sessionIssuanceService;

    public String login(String email, String password) {
        Member member = memberReader.readActiveByEmail(email);

        if (!member.matchesPassword(password)) {
            throw new InvalidPasswordException();
        }

        return sessionIssuanceService.issue(member.getId(), member.getPassword());
    }

    public void logout(String sessionId) {
        sessionRedisRepository.deleteSession(sessionId);
    }

}
