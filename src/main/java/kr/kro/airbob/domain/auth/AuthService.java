package kr.kro.airbob.domain.auth;

import java.util.UUID;

import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.auth.exception.InvalidPasswordException;
import kr.kro.airbob.domain.auth.exception.NotEqualHostException;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;
    private final SessionRedisRepository sessionRedisRepository;

    public String login(String email, String password) {
        Member member = memberRepository.findByEmailAndStatus(email, MemberStatus.ACTIVE)
            .orElseThrow(MemberNotFoundException::new);

        if (!BCrypt.checkpw(password, member.getPassword())) {
            throw new InvalidPasswordException();
        }

        String sessionId = UUID.randomUUID().toString();
        sessionRedisRepository.saveSession(sessionId, member.getId());

        return sessionId;
    }

    public void logout(String sessionId) {
        sessionRedisRepository.deleteSession(sessionId);
    }


    public void validateHost(String sessionId, Long hostId) {
        Long memberId = sessionRedisRepository.getMemberIdBySession(sessionId).orElse(null);

        if (!hostId.equals(memberId)) {
            throw new NotEqualHostException();
        }
    }
}
