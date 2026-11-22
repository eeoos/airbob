package kr.kro.airbob.core.domain.auth;

import org.springframework.stereotype.Service;

import kr.kro.airbob.core.domain.member.Member;
import kr.kro.airbob.core.domain.member.MemberReader;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberReader memberReader;
    private final SessionIssuer sessionIssuer;
    private final SessionWriter sessionWriter;

    public String login(String email, String password) {
        Member member = memberReader.readActiveByEmail(email);

        if (!member.matchesPassword(password)) {
            throw new InvalidPasswordException();
        }

        return sessionIssuer.issue(member.getId(), member.getPassword());
    }

    public void logout(String sessionId) {
        sessionWriter.delete(sessionId);
    }

}
