package kr.kro.airbob.domain.auth.service;

import java.util.UUID;

import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.auth.repository.SessionRedisRepository;
import kr.kro.airbob.domain.auth.exception.InvalidPasswordException;
import kr.kro.airbob.domain.auth.exception.NotEqualHostException;
import kr.kro.airbob.domain.member.dto.MemberResponse;
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

        // todo: etl 작업으로 넣은 데이터와 BCrypt가 일치하지 않아 주석처리
        // todo: oauth 2.0으로 변환 필요
        // if (!BCrypt.checkpw(password, member.getPassword())) {
        //     throw new InvalidPasswordException();
        // }

        if (!password.equals(member.getPassword())) {
            throw new InvalidPasswordException();
        }

        String sessionId = UUID.randomUUID().toString();
        sessionRedisRepository.saveSession(sessionId, member.getId());

        return sessionId;

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
