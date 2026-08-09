package kr.kro.airbob.domain.auth.service;

import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.auth.exception.InvalidPasswordException;
import kr.kro.airbob.domain.auth.repository.SessionRedisRepository;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;
    private final SessionRedisRepository sessionRedisRepository;
    private final SessionIssuanceService sessionIssuanceService;

    public String login(String email, String password) {
        Member member = memberRepository.findByEmailAndStatus(email, MemberStatus.ACTIVE)
            .orElseThrow(MemberNotFoundException::new);

        if (!matchesPassword(password, member.getPassword())) {
            throw new InvalidPasswordException();
        }

        return sessionIssuanceService.issue(member.getId(), member.getPassword());
    }

    private boolean matchesPassword(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, storedPassword);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public void logout(String sessionId) {
        sessionRedisRepository.deleteSession(sessionId);
    }

    @Transactional(readOnly = true)
    public MemberResponse.MeInfo getMemberInfo(Long memberId) {
        Member member = memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE)
            .orElseThrow(MemberNotFoundException::new);

        return new MemberResponse.MeInfo(
            member.getId(),
            member.getEmail(),
            member.getNickname(),
            member.getThumbnailImageUrl()
        );
    }
}
